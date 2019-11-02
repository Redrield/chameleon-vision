package com.chameleonvision.vision.camera;

import com.chameleonvision.settings.GeneralSettings;
import com.chameleonvision.util.FileHelper;
import com.chameleonvision.settings.SettingsManager;
import com.chameleonvision.vision.Pipeline;
import com.chameleonvision.vision.process.VisionProcess;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.UsbCameraInfo;
import org.opencv.videoio.VideoCapture;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class CameraManager {

	private static final Path CamConfigPath = Paths.get(SettingsManager.SettingsPath.toString(), "cameras");

	private static LinkedHashMap<String, Camera> AllCamerasByName = new LinkedHashMap<>();
	private static HashMap<String, VisionProcess> AllVisionProcessesByName = new HashMap<>();

	static HashMap<String, UsbCameraInfo> AllUsbCameraInfosByName = new HashMap<>() {{
		var suffix = 0;
		for (var info : UsbCamera.enumerateUsbCameras()) {
			var cap = new VideoCapture(info.dev);
			if (cap.isOpened()) {
				cap.release();
				var name = info.name;
				while (this.containsKey(name)) {
					suffix++;
					name = String.format("%s(%s)", info.name, suffix);
				}
				put(name, info);
			}
		}
	}};

	public static HashMap<String, Camera> getAllCamerasByName() {
		return AllCamerasByName;
	}
	public static List<String> getAllCameraByNickname(){
		var cameras = getAllCamerasByName();
		return cameras.values().stream().map(Camera::getNickname).collect(Collectors.toList());
	}

	public static boolean initializeCameras() {
		if (AllUsbCameraInfosByName.size() == 0) return false;
		FileHelper.CheckPath(CamConfigPath);
		AllUsbCameraInfosByName.forEach((key, value) -> {
			var camPath = Paths.get(CamConfigPath.toString(), String.format("%s.json", key));
			File camJsonFile = new File(camPath.toString());
			if (camJsonFile.exists() && camJsonFile.length() != 0) {
				try {
					Gson gson = new GsonBuilder().registerTypeAdapter(Camera.class, new CameraDeserializer()).create();
					var camJsonFileReader = new FileReader(camPath.toString());
					var gsonRead = gson.fromJson(camJsonFileReader, Camera.class);
					AllCamerasByName.put(key, gsonRead);
				} catch (FileNotFoundException ex) {
					ex.printStackTrace();
				}
			} else {
				if (!addCamera(new Camera(key), key)) {
					System.err.println("Failed to add camera! Already exists!");
				}
			}
		});
		return true;
	}

	public static void initializeThreads(){
		AllCamerasByName.forEach((key, value) -> {
			VisionProcess visionProcess = new VisionProcess(value);
			AllVisionProcessesByName.put(key, visionProcess);
			new Thread(visionProcess).start();
		});
	}

	private static boolean addCamera(Camera camera, String cameraName) {
		if (AllCamerasByName.containsKey(cameraName)) return false;
		camera.addPipeline();
		AllCamerasByName.put(cameraName, camera);
		return true;
	}

	private static Camera getCamera(String cameraName) {
		return AllCamerasByName.get(cameraName);
	}

	public static Camera getCameraByIndex(int index) {
		return AllCamerasByName.get( (AllCamerasByName.keySet().toArray())[ index ] );
	}

	public static Camera getCurrentCamera() {
		if (AllCamerasByName.size() == 0) return null;
		return AllCamerasByName.get(SettingsManager.GeneralSettings.currentCamera);
	}
	public static Integer getCurrentCameraIndex() throws CameraException {
		if (AllCamerasByName.size() == 0) throw new CameraException(CameraException.CameraExceptionType.NO_CAMERA);
		List<String> arr = new ArrayList<>(AllCamerasByName.keySet());
		for (var i = 0; i < AllCamerasByName.size(); i++){
			if (SettingsManager.GeneralSettings.currentCamera.equals(arr.get(i))){
				return i;
			}
		}
		return null;
	}

	public static void setCurrentCamera(String cameraName) throws CameraException {
		if (!AllCamerasByName.containsKey(cameraName))
			throw new CameraException(CameraException.CameraExceptionType.BAD_CAMERA);
		SettingsManager.GeneralSettings.currentCamera = cameraName;
		SettingsManager.updateCameraSetting(cameraName, getCurrentCamera().getCurrentPipelineIndex());
	}
	public static void setCurrentCamera(int cameraIndex) throws CameraException {
		List<String> s =   new ArrayList<String>(AllCamerasByName.keySet());
		setCurrentCamera(s.get(cameraIndex));
	}

	public static Pipeline getCurrentPipeline() throws CameraException {
		return getCurrentCamera().getCurrentPipeline();
	}

	public static void setCurrentPipeline(int pipelineNumber) throws CameraException {
		if (pipelineNumber >= getCurrentCamera().getPipelines().size()){
			throw new CameraException(CameraException.CameraExceptionType.BAD_PIPELINE);
		}
		getCurrentCamera().setCurrentPipelineIndex(pipelineNumber);
		SettingsManager.updatePipelineSetting(pipelineNumber);
	}

	public static VisionProcess getCurrentCameraProcess() throws CameraException {
		if (!SettingsManager.GeneralSettings.currentCamera.equals("")){
			return AllVisionProcessesByName.get(SettingsManager.GeneralSettings.currentCamera);
		}
		throw new CameraException(CameraException.CameraExceptionType.NO_CAMERA);
	}

	public static void saveCameras() {
		for (var entry : AllCamerasByName.entrySet()) {
			try {
				Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Camera.class, new CameraSerializer()).create();
				FileWriter writer = new FileWriter(Paths.get(CamConfigPath.toString(), String.format("%s.json", entry.getKey())).toString());
				gson.toJson(entry.getValue(), writer);
				writer.flush();
				writer.close();
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}
}
