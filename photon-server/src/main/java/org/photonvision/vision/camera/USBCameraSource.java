/*
 * Copyright (C) 2020 Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.vision.camera;

import edu.wpi.cscore.CvSink;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoException;
import edu.wpi.cscore.VideoMode;
import edu.wpi.first.cameraserver.CameraServer;
import java.util.*;
import java.util.stream.Collectors;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.configuration.ConfigManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.vision.frame.FrameProvider;
import org.photonvision.vision.frame.provider.USBFrameProvider;
import org.photonvision.vision.processes.VisionSource;
import org.photonvision.vision.processes.VisionSourceSettables;

public class USBCameraSource implements VisionSource {
    private final Logger logger;
    private final UsbCamera camera;
    private final USBCameraSettables usbCameraSettables;
    private final USBFrameProvider usbFrameProvider;
    public final CameraConfiguration configuration;
    private final CvSink cvSink;

    public final QuirkyCamera cameraQuirks;

    public USBCameraSource(CameraConfiguration config) {
        logger = new Logger(USBCameraSource.class, config.nickname, LogGroup.Camera);
        configuration = config;
        camera = new UsbCamera(config.nickname, config.path);
        cvSink = CameraServer.getInstance().getVideo(this.camera);

        cameraQuirks =
                QuirkyCamera.getQuirkyCamera(
                        camera.getInfo().productId, camera.getInfo().vendorId, config.baseName);

        if (cameraQuirks.hasQuirks()) {
            logger.info("Quirky camera detected: " + cameraQuirks.baseName);
        }

        usbCameraSettables = new USBCameraSettables(config);
        usbFrameProvider = new USBFrameProvider(cvSink, usbCameraSettables);
    }

    @Override
    public FrameProvider getFrameProvider() {
        return usbFrameProvider;
    }

    @Override
    public VisionSourceSettables getSettables() {
        return this.usbCameraSettables;
    }

    public class USBCameraSettables extends VisionSourceSettables {
        protected USBCameraSettables(CameraConfiguration configuration) {
            super(configuration);
            getAllVideoModes();
            setVideoMode(videoModes.get(0));
            calculateFrameStaticProps();
        }

        @Override
        public void setExposure(int exposure) {
            try {
                if (cameraQuirks.hasQuirk(CameraQuirk.PiCam)) {
                    camera.getProperty("auto_exposure").set(1); // auto exposure off
                    camera
                            .getProperty("exposure_time_absolute")
                            .set(MathUtils.safeDivide(10000, exposure)); // exposure time is a range, 0-10000
                } else {
                    camera.setExposureManual(exposure);
                    camera.setExposureManual(exposure);
                }
            } catch (VideoException e) {
                logger.error("Failed to set camera exposure!", e);
            }
        }

        @Override
        public void setBrightness(int brightness) {
            try {
                camera.setBrightness(brightness);
                camera.setBrightness(brightness);
            } catch (VideoException e) {
                logger.error("Failed to set camera brightness!", e);
            }
        }

        @Override
        public void setGain(int gain) {
            try {
                if (cameraQuirks.hasQuirk(CameraQuirk.Gain)) {
                    camera.getProperty("gain_automatic").set(0);
                    camera.getProperty("gain").set(gain);
                }
            } catch (VideoException e) {
                logger.error("Failed to set camera gain!", e);
            }
        }

        @Override
        public VideoMode getCurrentVideoMode() {
            return camera.isConnected() ? camera.getVideoMode() : null;
        }

        @Override
        public void setVideoModeInternal(VideoMode videoMode) {
            try {
                if (videoMode == null) {
                    logger.error("Got a null video mode! Doing nothing...");
                    return;
                }
                camera.setVideoMode(videoMode);
            } catch (Exception e) {
                logger.error("Failed to set video mode!", e);
            }
        }

        @Override
        public HashMap<Integer, VideoMode> getAllVideoModes() {
            if (videoModes == null) {
                videoModes = new HashMap<>();
                List<VideoMode> videoModesList = new ArrayList<>();
                try {
                    var modes = camera.enumerateVideoModes();
                    for (int i = 0; i < modes.length; i++) {
                        var videoMode = modes[i];

                        // Filter grey modes
                        if (videoMode.pixelFormat == VideoMode.PixelFormat.kGray
                                || videoMode.pixelFormat == VideoMode.PixelFormat.kUnknown) {
                            continue;
                        }

                        // On picam, filter non-bgr modes for performance
                        if (cameraQuirks.hasQuirk(CameraQuirk.PiCam)) {
                            if (videoMode.pixelFormat != VideoMode.PixelFormat.kBGR) {
                                continue;
                            }
                        }

                        videoModesList.add(videoMode);

                        // We look for modes with the same height/width/pixelformat as this mode
                        // and remove all the ones that are slower. This is sorted low to high.
                        // So we remove the last element (the fastest FPS) from the duplicate list,
                        // and remove all remaining elements from the final list
                        var duplicateModes =
                                videoModesList.stream()
                                        .filter(
                                                it ->
                                                        it.height == videoMode.height
                                                                && it.width == videoMode.width
                                                                && it.pixelFormat == videoMode.pixelFormat)
                                        .sorted(Comparator.comparingDouble(it -> it.fps))
                                        .collect(Collectors.toList());
                        duplicateModes.remove(duplicateModes.size() - 1);
                        videoModesList.removeAll(duplicateModes);
                    }
                } catch (Exception e) {
                    logger.error("Exception while enumerating video modes!", e);
                    videoModesList = List.of();
                }

                // Sort by resolution
                var sortedList =
                        videoModesList.stream()
                                .sorted(((a, b) -> (b.width + b.height) - (a.width + a.height)))
                                .collect(Collectors.toList());
                Collections.reverse(sortedList);

                // On vendor cameras, respect blacklisted indices
                var indexBlacklist =
                        ConfigManager.getInstance().getConfig().getHardwareConfig().blacklistedResIndices;
                for (int badIdx : indexBlacklist) {
                    sortedList.remove(badIdx);
                }

                // Filter bogus modes on picam
                if (cameraQuirks.hasQuirk(CameraQuirk.PiCam)) {
                    sortedList.removeIf(
                            it ->
                                    (it.width == 1296
                                                    && it.height == 730
                                                    && it.pixelFormat == VideoMode.PixelFormat.kBGR)
                                            || (it.width == 1296
                                                    && it.height == 972
                                                    && it.pixelFormat == VideoMode.PixelFormat.kBGR)
                                            || (it.width == 2592
                                                    && it.height == 1944
                                                    && it.pixelFormat == VideoMode.PixelFormat.kBGR)
                                            || (it.width == 160
                                                    && it.height == 120
                                                    && it.pixelFormat == VideoMode.PixelFormat.kBGR));
                }

                for (VideoMode videoMode : sortedList) {
                    videoModes.put(sortedList.indexOf(videoMode), videoMode);
                }
            }
            return videoModes;
        }
    }

    // TODO improve robustness of this detection
    @Override
    public boolean isVendorCamera() {
        return ConfigManager.getInstance().getConfig().getHardwareConfig().hasPresetFOV()
                && cameraQuirks.hasQuirk(CameraQuirk.PiCam);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        USBCameraSource that = (USBCameraSource) o;
        return cameraQuirks.equals(that.cameraQuirks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                camera, usbCameraSettables, usbFrameProvider, configuration, cvSink, cameraQuirks);
    }
}
