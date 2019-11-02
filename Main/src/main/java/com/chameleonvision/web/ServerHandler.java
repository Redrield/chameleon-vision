package com.chameleonvision.web;

import com.chameleonvision.Main;
import com.chameleonvision.settings.GeneralSettings;
import com.chameleonvision.vision.*;
import com.chameleonvision.vision.camera.CameraException;
import com.chameleonvision.settings.SettingsManager;
import com.chameleonvision.vision.camera.CameraManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.*;

import org.apache.commons.lang3.ArrayUtils;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.springframework.beans.BeanUtils;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;


public class ServerHandler {

    private static List<WsContext> users;
    private static ObjectMapper objectMapper;

    ServerHandler() {
        users = new ArrayList<>();
        objectMapper = new ObjectMapper(new MessagePackFactory());
    }

    void onConnect(WsConnectContext context) {
        users.add(context);
        sendFullSettings();
    }

    public void onClose(WsCloseContext context) {
        users.remove(context);
    }

    void onBinaryMessage(WsBinaryMessageContext context) throws Exception {
        Map<String, Object> deserialized = objectMapper.readValue(ArrayUtils.toPrimitive(context.data()), new TypeReference<Map<String, Object>>() {
        });
        for (Map.Entry<String, Object> entry : deserialized.entrySet()) {
            try {
                switch (entry.getKey()) {
                    case "generalSettings": {
                        for (HashMap.Entry<String, Object> e : ((HashMap<String, Object>) entry.getValue()).entrySet()) {
                            setField(SettingsManager.GeneralSettings, e.getKey(), e.getValue());
                        }
                        SettingsManager.saveSettings();
                        break;
                    }
                    case "cameraSettings": {
                        HashMap camSettings = (HashMap) entry.getValue();
                        CameraManager.getCurrentCamera().setFOV((Number) camSettings.get("fov"));
                        CameraManager.getCurrentCamera().setStreamDivisor((Integer) camSettings.get("streamDivisor"));
                        CameraManager.getCurrentCamera().setCamVideoMode((Integer) camSettings.get("resolution"), true);
                        SettingsManager.saveSettings();
                        break;
                    }
                    case "changeCameraName": {
                        CameraManager.getCurrentCamera().setNickname((String) entry.getValue());
                        sendFullSettings();
                        break;
                    }
                    case "changePipelineName": {
                        CameraManager.getCurrentPipeline().nickname = (String) entry.getValue();
                        sendFullSettings();
                        break;
                    }
                    case "duplicatePipeline": {
                        HashMap pipelineVals = (HashMap) entry.getValue();
                        int pipelineIndex = (int) pipelineVals.get("pipeline");
                        int cameraIndex = (int) pipelineVals.get("camera");

                        Pipeline origPipeline = CameraManager.getCurrentCamera().getPipelineByIndex(pipelineIndex);

                        if (cameraIndex != -1) {
                            CameraManager.getCameraByIndex(cameraIndex).addPipeline(origPipeline);
                        } else {
                            CameraManager.getCurrentCamera().addPipeline(origPipeline);
                        }
                        break;
                    }
                    case "command": {
                        var cam = CameraManager.getCurrentCamera();
                        switch ((String) entry.getValue()) {
                            case "addNewPipeline":
                                cam.addPipeline();
                                sendFullSettings();
                                break;
                            case "deleteCurrentPipeline":
                                int currentIndex = cam.getCurrentPipelineIndex();
                                int nextIndex;
                                if (currentIndex == cam.getPipelines().size() - 1){
                                    nextIndex = currentIndex - 1;
                                } else {
                                    nextIndex = currentIndex;
                                }
                                cam.deletePipeline();
                                cam.setCurrentPipelineIndex(nextIndex);
                                sendFullSettings();
                                break;
                            case "retryCameras":
                                Main.initCameras();
                                break;
                        }
                        // used to define all incoming commands
                        break;
                    }
                    case "currentCamera": {
                        CameraManager.setCurrentCamera((Integer) entry.getValue());
                        sendFullSettings();
                        break;
                    }
                    case "currentPipeline": {
                        var cam = CameraManager.getCurrentCamera();
                        cam.setCurrentPipelineIndex((Integer) entry.getValue());
                        HashMap<String, Object> tmp = new HashMap<>();
                        tmp.put("pipeline", getOrdinalPipeline());
                        broadcastMessage(tmp);
                        try {
                            cam.setBrightness(cam.getCurrentPipeline().brightness);
                            cam.setExposure(cam.getCurrentPipeline().exposure);
                        }catch (Exception e){
                            continue;
                        }
                        break;
                    }
                    default: {
                        setField(CameraManager.getCurrentCamera().getCurrentPipeline(), entry.getKey(), entry.getValue());
                        switch (entry.getKey()) {
                            case "exposure": {
                                try {
                                    CameraManager.getCurrentCamera().setExposure((Integer) entry.getValue());
                                } catch (Exception e) {
                                    System.err.println("Camera Does not support exposure change");
                                }
                            }
                            case "brightness": {
                                CameraManager.getCurrentCamera().setBrightness((Integer) entry.getValue());
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println(e.getMessage());
            }
            broadcastMessage(deserialized, context);
        }
    }

    private void setField(Object obj, String fieldName, Object value) {
        try {
            Field field = obj.getClass().getField(fieldName);
            if (BeanUtils.isSimpleValueType(field.getType())) {
                if (field.getType().isEnum()) {
                    field.set(obj, field.getType().getEnumConstants()[(Integer) value]);
                } else {
                    field.set(obj, value);
                }
            } else if (field.getType() == List.class) {
//                if(((ParameterizedType)field.getGenericType()).getActualTypeArguments()[0] == Double.class){
                field.set(obj, value);
            }
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            ex.printStackTrace();
        }
    }

    private static void broadcastMessage(Object obj, WsContext userToSkip) {
        if (users != null)
            for (var user : users) {
                if (userToSkip != null && user.getSessionId().equals(userToSkip.getSessionId())) {
                    continue;
                }
                try {
                    ByteBuffer b = ByteBuffer.wrap(objectMapper.writeValueAsBytes(obj));
                    user.send(b);
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            }
    }

    public static void broadcastMessage(Object obj) {
        broadcastMessage(obj, null);//Broadcasts the message to every user
    }

    private static HashMap<String, Object> getOrdinalPipeline() throws CameraException, IllegalAccessException {
        HashMap<String, Object> tmp = new HashMap<>();
        for (Field f : Pipeline.class.getFields()) {
            if (!f.getType().isEnum()) {
                tmp.put(f.getName(), f.get(CameraManager.getCurrentCamera().getCurrentPipeline()));
            } else {
                var i = (Enum) f.get(CameraManager.getCurrentCamera().getCurrentPipeline());
                tmp.put(f.getName(), i.ordinal());
            }
        }
        return tmp;
    }

    private static HashMap<String, Object> getOrdinalSettings() {
        HashMap<String, Object> tmp = new HashMap<>();

        GeneralSettings genSettings = new GeneralSettings();
        if (SettingsManager.GeneralSettings != null) {
            genSettings = SettingsManager.GeneralSettings;
        }

        tmp.put("teamNumber", genSettings.teamNumber);
        tmp.put("connectionType", genSettings.connectionType.ordinal());
        tmp.put("ip", genSettings.ip);
        tmp.put("gateway", genSettings.gateway);
        tmp.put("netmask", genSettings.netmask);
        tmp.put("hostname", genSettings.hostname);
        return tmp;
    }

    private static HashMap<String, Object> getOrdinalCameraSettings() {
        HashMap<String, Object> tmp = new HashMap<>();
        var currentCamera = CameraManager.getCurrentCamera();
        if (currentCamera == null) return new HashMap<>();
        tmp.put("fov", currentCamera.getFOV());
        tmp.put("streamDivisor", currentCamera.getStreamDivisor().ordinal());
        tmp.put("resolution", currentCamera.getVideoModeIndex());

        return tmp;
    }

    public static void sendFullSettings() {
        //General settings
        Map<String, Object> fullSettings = new HashMap<>();
        boolean hasCamera = CameraManager.getCurrentCamera() != null;

        fullSettings.put("settings", getOrdinalSettings());

        if (hasCamera) {
            try {
                fullSettings.put("cameraSettings", getOrdinalCameraSettings());
                fullSettings.put("cameraList", CameraManager.getAllCameraByNickname());
                var currentCamera = CameraManager.getCurrentCamera();
                fullSettings.put("pipeline", getOrdinalPipeline());
                fullSettings.put("pipelineList", currentCamera.getPipelinesNickname());
                fullSettings.put("resolutionList", currentCamera.getResolutionList());
                fullSettings.put("port", currentCamera.getStreamPort());
                fullSettings.put("currentPipelineIndex",CameraManager.getCurrentCamera().getCurrentPipelineIndex());
                fullSettings.put("currentCameraIndex", CameraManager.getCurrentCameraIndex());
            } catch (CameraException | IllegalAccessException ignored) {
                System.out.println("No cameras detected, notifying user via UI.");
            }
        } else {
            fullSettings.put("cameraList", new ArrayList<String>());
        }

        broadcastMessage(fullSettings);
    }
}
