package com.chameleonvision.web;

import com.chameleonvision.Exceptions.DuplicatedKeyException;
import com.chameleonvision.config.ConfigManager;
import com.chameleonvision.network.NetworkIPMode;
import com.chameleonvision.vision.VisionManager;
import com.chameleonvision.vision.VisionProcess;
import com.chameleonvision.vision.camera.USBCameraCapture;
import com.chameleonvision.vision.pipeline.CVPipelineSettings;
import com.chameleonvision.vision.pipeline.PipelineManager;
import com.chameleonvision.vision.pipeline.impl.Calibrate3dPipeline;
import com.chameleonvision.vision.pipeline.impl.StandardCVPipeline;
import com.chameleonvision.vision.pipeline.impl.StandardCVPipelineSettings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.wpi.cscore.VideoMode;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RequestHandler {

    private static final ObjectMapper kObjectMapper = new ObjectMapper();

    public static void onGeneralSettings(Context ctx) {
        ObjectMapper objectMapper = kObjectMapper;
        try {
            Map map = objectMapper.readValue(ctx.body(), Map.class);

            // TODO: change to function, to restart NetworkTables
            ConfigManager.settings.teamNumber = (int) map.get("teamNumber");

            ConfigManager.settings.connectionType = NetworkIPMode.values()[(int) map.get("connectionType")];
            ConfigManager.settings.ip = (String) map.get("ip");
            ConfigManager.settings.netmask = (String) map.get("netmask");
            ConfigManager.settings.gateway = (String) map.get("gateway");
            ConfigManager.settings.hostname = (String) map.get("hostname");
            ConfigManager.saveGeneralSettings();
            SocketHandler.sendFullSettings();
            ctx.status(200);
        } catch (JsonProcessingException e) {
            ctx.status(500);
        }
    }

    public static void onDuplicatePipeline(Context ctx) {
        ObjectMapper objectMapper = kObjectMapper;
        try {
            Map data = objectMapper.readValue(ctx.body(), Map.class);

            int cameraIndex = (Integer) data.getOrDefault("camera", -1);

            var pipelineIndex = (Integer) data.get("pipeline");
            StandardCVPipelineSettings origPipeline = (StandardCVPipelineSettings) VisionManager.getCurrentUIVisionProcess().pipelineManager.getPipeline(pipelineIndex).settings;
            String tmp = objectMapper.writeValueAsString(origPipeline);
            StandardCVPipelineSettings newPipeline = objectMapper.readValue(tmp, StandardCVPipelineSettings.class);

            if (cameraIndex == -1) { // same camera

                VisionManager.getCurrentUIVisionProcess().pipelineManager.duplicatePipeline(newPipeline);

            } else { // another camera
                var cam = VisionManager.getVisionProcessByIndex(cameraIndex);
                if (cam != null) {
                    if (cam.getCamera().getProperties().videoModes.size() < newPipeline.videoModeIndex) {
                        newPipeline.videoModeIndex = cam.getCamera().getProperties().videoModes.size() - 1;
                    }
                    if (newPipeline.is3D){
                        var calibration = cam.getCamera().getCalibration(cam.getCamera().getProperties().getVideoMode(newPipeline.videoModeIndex));
                        if (calibration == null){
                            newPipeline.is3D = false;
                        }
                    }
                    VisionManager.getCurrentUIVisionProcess().pipelineManager.duplicatePipeline(newPipeline, cam);
                    ctx.status(200);
                } else {
                    ctx.status(500);
                }
            }
        } catch (JsonProcessingException | DuplicatedKeyException ex) {
            ctx.status(500);
        }
    }


    public static void onCameraSettings(Context ctx) {
        ObjectMapper objectMapper = kObjectMapper;
        try {
            Map camSettings = objectMapper.readValue(ctx.body(), Map.class);

            VisionProcess currentVisionProcess = VisionManager.getCurrentUIVisionProcess();
            USBCameraCapture currentCamera = currentVisionProcess.getCamera();

            double newFOV, tilt;
            try {
                newFOV = (Double) camSettings.get("fov");
            } catch (Exception ignored) {
                newFOV = (Integer) camSettings.get("fov");
            }
            try {
                tilt = (Double) camSettings.get("tilt");
            } catch (Exception ignored) {
                tilt = (Integer) camSettings.get("tilt");
            }
            currentCamera.getProperties().setFOV(newFOV);
            currentCamera.getProperties().setTilt(Rotation2d.fromDegrees(tilt));
            VisionManager.saveCurrentCameraSettings();
            SocketHandler.sendFullSettings();
            ctx.status(200);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            ctx.status(500);
        }
    }

    public static void onCalibrationStart(Context ctx) throws JsonProcessingException {
        PipelineManager pipeManager = VisionManager.getCurrentUIVisionProcess().pipelineManager;
        ObjectMapper objectMapper = kObjectMapper;
        var data = objectMapper.readValue(ctx.body(), Map.class);
        int resolutionIndex = (Integer) data.get("resolution");
        double squareSize;
        try {
            squareSize = (Double) data.get("squareSize");
        } catch (Exception e) {
            squareSize = (Integer) data.get("squareSize");
        }
        // convert from mm to meters
        pipeManager.calib3dPipe.setSquareSize(squareSize);
        VisionManager.getCurrentUIVisionProcess().pipelineManager.calib3dPipe.settings.videoModeIndex = resolutionIndex;
        VisionManager.getCurrentUIVisionProcess().pipelineManager.setCalibrationMode(true);
        VisionManager.getCurrentUIVisionProcess().getCamera().setVideoMode(resolutionIndex);
    }

    public static void onSnapshot(Context ctx) {
        Calibrate3dPipeline calPipe = VisionManager.getCurrentUIVisionProcess().pipelineManager.calib3dPipe;

        calPipe.takeSnapshot();

        HashMap<String, Object> toSend = new HashMap<>();
        toSend.put("snapshotCount", calPipe.getSnapshotCount());
        toSend.put("hasEnough", calPipe.hasEnoughSnapshots());

        ctx.json(toSend);
        ctx.status(200);
    }

    public static void onCalibrationEnding(Context ctx) throws JsonProcessingException {
        PipelineManager pipeManager = VisionManager.getCurrentUIVisionProcess().pipelineManager;

        var data = kObjectMapper.readValue(ctx.body(), Map.class);
        double squareSize;
        try {
            squareSize = (Double) data.get("squareSize");
        } catch (Exception e) {
            squareSize = (Integer) data.get("squareSize");
        }
        pipeManager.calib3dPipe.setSquareSize(squareSize);

        System.out.println("Finishing Cal");
        if (pipeManager.calib3dPipe.hasEnoughSnapshots()) {
            if (pipeManager.calib3dPipe.tryCalibration()) {
                ctx.status(200);
            } else {
                System.err.println("CALFAIL");
                ctx.status(500);
            }
        }
        pipeManager.setCalibrationMode(false);
        ctx.status(200);
    }

    public static void onPnpModel(Context ctx) throws JsonProcessingException {
        System.out.println(ctx.body());
        ObjectMapper objectMapper = kObjectMapper;
        List points = objectMapper.readValue(ctx.body(), List.class);
        System.out.println(points);
    }
}
