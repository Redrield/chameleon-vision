package com.chameleonvision.vision;

import com.chameleonvision.Debug;
import com.chameleonvision.config.CameraCalibrationConfig;
import com.chameleonvision.config.CameraConfig;
import com.chameleonvision.config.ConfigManager;
import com.chameleonvision.scripting.ScriptEventType;
import com.chameleonvision.scripting.ScriptManager;
import com.chameleonvision.config.FullCameraConfiguration;
import com.chameleonvision.util.LoopingRunnable;
import com.chameleonvision.util.MathHandler;
import com.chameleonvision.vision.camera.CameraStreamer;
import com.chameleonvision.vision.camera.USBCameraCapture;
import com.chameleonvision.vision.pipeline.*;
import com.chameleonvision.vision.pipeline.impl.StandardCVPipeline;
import com.chameleonvision.vision.pipeline.impl.DriverVisionPipeline;
import com.chameleonvision.vision.pipeline.impl.StandardCVPipelineSettings;
import com.chameleonvision.web.SocketHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.wpi.cscore.VideoMode;
import edu.wpi.first.networktables.*;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpiutil.CircularBuffer;
import org.apache.commons.lang3.tuple.Pair;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;


public class VisionProcess {

    private final USBCameraCapture cameraCapture;
    private final CameraStreamerRunnable streamRunnable;
    private final VisionProcessRunnable visionRunnable;
    private final CameraConfig fileConfig;
    public final CameraStreamer cameraStreamer;
    public PipelineManager pipelineManager;

    private volatile CVPipelineResult lastPipelineResult;

    private BlockingQueue<Mat> streamFrameQueue = new LinkedBlockingDeque<>(1);

    // network table stuff
    private final NetworkTable defaultTable;
    private NetworkTableInstance tableInstance;
    private NetworkTableEntry ntPipelineEntry;
    public NetworkTableEntry ntDriverModeEntry;
    private int ntDriveModeListenerID;
    private int ntPipelineListenerID;
    private NetworkTableEntry ntYawEntry;
    private NetworkTableEntry ntPitchEntry;
    private NetworkTableEntry ntAuxListEntry;
    private NetworkTableEntry ntAreaEntry;
    private NetworkTableEntry ntLatencyEntry;
    private NetworkTableEntry ntValidEntry;
    private NetworkTableEntry ntPoseEntry;
    private ObjectMapper objectMapper = new ObjectMapper();

    private long lastUIUpdateMs = 0;

    VisionProcess(USBCameraCapture cameraCapture, FullCameraConfiguration config) {
        this.cameraCapture = cameraCapture;

        fileConfig = config.fileConfig;

        pipelineManager = new PipelineManager(this, config.pipelines);

        // Thread to put frames on the dashboard
        this.cameraStreamer = new CameraStreamer(cameraCapture, config.cameraConfig.name, pipelineManager.getCurrentPipeline().settings.streamDivisor);
        this.streamRunnable = new CameraStreamerRunnable(30, cameraStreamer);

        // Thread to process vision data
        this.visionRunnable = new VisionProcessRunnable();

        // network table
        defaultTable = NetworkTableInstance.getDefault().getTable("/chameleon-vision/" + cameraCapture.getProperties().getNickname());
    }

    public void start() {
        System.out.println("Starting NetworkTables.");
        initNT(defaultTable);

        System.out.println("Starting vision thread.");
        var visionThread = new Thread(visionRunnable);
        visionThread.setName(getCamera().getProperties().name + " - Vision Thread");
        visionThread.start();

//        System.out.println("Starting stream thread.");
//        var streamThread = new Thread(streamRunnable);
//        streamThread.setName(getCamera().getProperties().name + " - Stream Thread");
//        streamThread.start();
    }

    /**
     * Removes the old value change listeners
     * calls {@link #initNT}
     *
     * @param newTable passed to {@link #initNT}
     */
    public void resetNT(NetworkTable newTable) {
        ntDriverModeEntry.removeListener(ntDriveModeListenerID);
        ntPipelineEntry.removeListener(ntPipelineListenerID);
        initNT(newTable);
    }

    public void setCameraNickname(String newName) {
        getCamera().getProperties().setNickname(newName);
        var newTable = NetworkTableInstance.getDefault().getTable("/chameleon-vision/" + newName);
        resetNT(newTable);
    }

    private void initNT(NetworkTable newTable) {
        tableInstance = newTable.getInstance();
        ntPipelineEntry = newTable.getEntry("pipeline");
        ntDriverModeEntry = newTable.getEntry("driver_mode");
        ntPitchEntry = newTable.getEntry("pitch");
        ntYawEntry = newTable.getEntry("yaw");
        ntAreaEntry = newTable.getEntry("area");
        ntLatencyEntry = newTable.getEntry("latency");
        ntValidEntry = newTable.getEntry("is_valid");
        ntAuxListEntry = newTable.getEntry("aux_targets");
        ntPoseEntry = newTable.getEntry("poseList");
        ntDriveModeListenerID = ntDriverModeEntry.addListener(this::setDriverMode, EntryListenerFlags.kUpdate);
        ntPipelineListenerID = ntPipelineEntry.addListener(this::setPipeline, EntryListenerFlags.kUpdate);
        ntDriverModeEntry.setBoolean(false);
        ntPipelineEntry.setNumber(pipelineManager.getCurrentPipelineIndex());
        pipelineManager.ntIndexEntry = ntPipelineEntry;
    }

    private void setDriverMode(EntryNotification driverModeEntryNotification) {
        setDriverMode(driverModeEntryNotification.value.getBoolean());
    }

    public void setDriverMode(boolean driverMode) {
        pipelineManager.setDriverMode(driverMode);
        ScriptManager.queueEvent(driverMode ? ScriptEventType.kEnterDriverMode : ScriptEventType.kExitDriverMode);
        SocketHandler.sendFullSettings();
    }

    /**
     * Method called by the nt entry listener to update the next pipeline.
     *
     * @param notification the notification
     */
    private void setPipeline(EntryNotification notification) {
        var wantedPipelineIndex = (int) notification.value.getDouble();
        pipelineManager.setCurrentPipeline(wantedPipelineIndex);
    }

    public void setDriverModeEntry(boolean isDriverMode) {

        // if it's null, we haven't even started the program yet, so just return
        // otherwise, set it.
        if (ntDriverModeEntry != null) {
            ntDriverModeEntry.setBoolean(isDriverMode);
        }
    }

    private void updateUI(CVPipelineResult data) {
        // 30 "FPS" update rate
        long currentMillis = System.currentTimeMillis();
        if (currentMillis - lastUIUpdateMs > 1000 / 30) {
            lastUIUpdateMs = currentMillis;


            if (cameraCapture.getProperties().name.equals(ConfigManager.settings.currentCamera)) {
                HashMap<String, Object> WebSend = new HashMap<>();
                HashMap<String, Object> point = new HashMap<>();
                HashMap<String, Object> pointMap = new HashMap<>();
                ArrayList<Object> webTargets = new ArrayList<Object>();
                List<Double> center = new ArrayList<>();



                if (data.hasTarget) {
                    if (data instanceof StandardCVPipeline.StandardCVPipelineResult) {
                        StandardCVPipeline.StandardCVPipelineResult result = (StandardCVPipeline.StandardCVPipelineResult) data;
                        StandardCVPipeline.TrackedTarget bestTarget = result.targets.get(0);
                        if (((StandardCVPipelineSettings) pipelineManager.getCurrentPipeline().settings).multiple) {
                            for (var target : result.targets) {
                                pointMap = new HashMap<>();
                                pointMap.put("pitch", target.pitch);
                                pointMap.put("yaw", target.yaw);
                                pointMap.put("area", target.area);
                                pointMap.put("pose", target.cameraRelativePose);
                                webTargets.add(pointMap);
                            }
                        } else {
                            pointMap.put("pitch", bestTarget.pitch);
                            pointMap.put("yaw", bestTarget.yaw);
                            pointMap.put("area", bestTarget.area);
                            pointMap.put("pose", bestTarget.cameraRelativePose);
                            webTargets.add(pointMap);
                        }
                        center.add(bestTarget.minAreaRect.center.x);
                        center.add(bestTarget.minAreaRect.center.y);

                    }
                } else {
                    pointMap.put("pitch", null);
                    pointMap.put("yaw", null);
                    pointMap.put("area", null);
                    pointMap.put("pose", new Pose2d());
                    webTargets.add(pointMap);
                    center.add(null);
                    center.add(null);
                }

                point.put("fps", visionRunnable.fps);
                point.put("targets", webTargets);
                point.put("rawPoint", center);
                WebSend.put("point", point);
                SocketHandler.broadcastMessage(WebSend);
            }
        }
    }

    private void updateNetworkTableData(CVPipelineResult data) {
        ntValidEntry.setBoolean(data.hasTarget);
        if (data.hasTarget && !(data instanceof DriverVisionPipeline.DriverPipelineResult)) {
            if (data instanceof StandardCVPipeline.StandardCVPipelineResult) {

                //noinspection unchecked
                List<StandardCVPipeline.TrackedTarget> targets = (List<StandardCVPipeline.TrackedTarget>) data.targets;
                ntLatencyEntry.setDouble(MathHandler.roundTo(data.processTime * 1e-6, 3));
                ntPitchEntry.setDouble(targets.get(0).pitch);
                ntYawEntry.setDouble(targets.get(0).yaw);
                ntAreaEntry.setDouble(targets.get(0).area);
                try {
                    ntAuxListEntry.setString(objectMapper.writeValueAsString(targets.stream()
                            .map(it -> List.of(it.pitch, it.yaw, it.area, it.cameraRelativePose))
                            .collect(Collectors.toList())));

                    // TODO: (2.1) 3d stuff...
                    ntPoseEntry.setString(objectMapper.writeValueAsString(targets.stream().map(target -> target.cameraRelativePose).collect(Collectors.toList())));
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
            } else {
                ntPitchEntry.setDouble(0.0);
                ntYawEntry.setDouble(0.0);
                ntAreaEntry.setDouble(0.0);
                ntLatencyEntry.setDouble(0.0);
                ntAuxListEntry.setString("");
            }
        }
        tableInstance.flush();

    }

    public void setVideoMode(VideoMode newMode) {
        cameraCapture.setVideoMode(newMode);
        cameraStreamer.setNewVideoMode(newMode);
    }

    public VideoMode getCurrentVideoMode() {
        return cameraCapture.getCurrentVideoMode();
    }

    public List<VideoMode> getPossibleVideoModes() {
        return cameraCapture.getProperties().videoModes;
    }

    public USBCameraCapture getCamera() {
        return cameraCapture;
    }

    public CVPipelineSettings getDriverModeSettings() {
        return pipelineManager.driverModePipeline.settings;
    }

    public void addCalibration(CameraCalibrationConfig cal) {
        cameraCapture.addCalibrationData(cal);
        System.out.println("saving to file");
        fileConfig.saveCalibration(cameraCapture.getConfig());
    }

    public void setIs3d(Boolean value) {
        var settings = pipelineManager.getCurrentPipeline().settings;
        if (settings instanceof StandardCVPipelineSettings) {
            ((StandardCVPipelineSettings) settings).is3D = value;
        }
    }

    public boolean getIs3d() {
        var settings = pipelineManager.getCurrentPipeline().settings;
        if (settings instanceof StandardCVPipelineSettings) {
            return ((StandardCVPipelineSettings) settings).is3D;
        }
        return false;
    }

    /**
     * VisionProcessRunnable will process images as quickly as possible
     */
    private class VisionProcessRunnable implements Runnable {

        volatile Double fps = 0.0;
        private CircularBuffer fpsAveragingBuffer = new CircularBuffer(7);

        @Override
        public void run() {
            var lastUpdateTimeNanos = System.nanoTime();
            var lastStreamTimeMs = System.currentTimeMillis();
            while (!Thread.interrupted()) {

                // blocking call, will block until camera has a new frame.
                Pair<Mat, Long> camData = cameraCapture.getFrame();

                Mat camFrame = camData.getLeft();
                if (camFrame.cols() > 0 && camFrame.rows() > 0) {
                    CVPipelineResult result = null;
                    try {
                        result = pipelineManager.getCurrentPipeline().runPipeline(camFrame);
                    } catch (Exception e) {
                        System.err.println("Exception in vision process " + getCamera().getProperties().getNickname() + "!");
                        e.printStackTrace();
                    }

                    camFrame.release();

                    if (result != null) {
                        result.setTimestamp(camData.getRight());
                        lastPipelineResult = result;
                        updateNetworkTableData(lastPipelineResult);
                        updateUI(lastPipelineResult);
                    }
                }

                try {
//                    streamFrameQueue.clear();
//                    streamFrameQueue.add(lastPipelineResult.outputMat);
                    var currentTime = System.currentTimeMillis();
                    if((currentTime - lastStreamTimeMs)/1000d > 1.0 / 30.0) {
                        cameraStreamer.runStream(lastPipelineResult.outputMat);
//                        System.out.println("Ran stream in " + (System.currentTimeMillis() - currentTime) + "ms!");
                        lastStreamTimeMs = currentTime;
                        lastPipelineResult.outputMat.release();
                    }

                } catch (Exception e) {
                    Debug.printInfo("Vision running faster than stream.");
                }

                var deltaTimeNanos = System.nanoTime() - lastUpdateTimeNanos;
                fpsAveragingBuffer.addFirst(1.0 / (deltaTimeNanos * 1E-09));
                lastUpdateTimeNanos = System.nanoTime();
                fps = getAverageFPS();
            }
        }

        double getAverageFPS() {
            var temp = 0.0;
            for (int i = 0; i < 7; i++) {
                temp += fpsAveragingBuffer.get(i);
            }
            temp /= 7.0;
            return temp;
        }

    }

    private class CameraStreamerRunnable extends LoopingRunnable {

        final CameraStreamer streamer;
        private Mat bufferMat = new Mat();

        private CameraStreamerRunnable(int cameraFPS, CameraStreamer streamer) {
            // add 2 FPS to allow for a bit of overhead
            super(1000L / (cameraFPS + 2));
            this.streamer = streamer;
        }

        @Override
        protected void process() {
            if (!streamFrameQueue.isEmpty()) {
                try {

                    bufferMat = streamFrameQueue.take();

                    try {
                        streamer.runStream(bufferMat);
                        bufferMat.release();
                    } catch (Exception e) {
                        // do nothing
                    }

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
