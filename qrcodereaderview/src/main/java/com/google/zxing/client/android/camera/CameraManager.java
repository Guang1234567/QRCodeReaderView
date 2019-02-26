/*
 * Copyright (C) 2008 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.client.android.camera;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;

import com.dlazaro66.qrcodereaderview.SimpleLog;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.client.android.camera.open.OpenCamera;
import com.google.zxing.client.android.camera.open.OpenCameraInterface;

import java.io.IOException;

/**
 * This object wraps the Camera service object and expects to be the only one talking to it. The
 * implementation encapsulates the steps needed to take preview-sized images, which are used for
 * both preview and decoding.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class CameraManager {

    private static final String TAG = CameraManager.class.getSimpleName();

    private final Context context;
    private final CameraConfigurationManager configManager;
    private OpenCamera openCamera;
    private AutoFocusManager autoFocusManager;
    private boolean initialized;
    private boolean previewing;
    private Camera.PreviewCallback previewCallback;
    private int displayOrientation = 0;

    // PreviewCallback references are also removed from original ZXING authors work,
    // since we're using our own interface.
    // FramingRects references are also removed from original ZXING authors work,
    // since We're using all view size while detecting QR-Codes.
    private int requestedCameraId = OpenCameraInterface.NO_REQUESTED_CAMERA;
    private long autofocusIntervalInMs = AutoFocusManager.DEFAULT_AUTO_FOCUS_INTERVAL_MS;


    public CameraManager(Context context) {
        this.context = context;
        this.configManager = new CameraConfigurationManager(context);
    }

    public void setPreviewCallback(Camera.PreviewCallback previewCallback) {
        this.previewCallback = previewCallback;

        if (isOpen()) {
            openCamera.getCamera().setPreviewCallback(previewCallback);
        }
    }

    public void setDisplayOrientation(int degrees) {
        this.displayOrientation = degrees;

        if (isOpen()) {
            openCamera.getCamera().setDisplayOrientation(degrees);
        }
    }

    public void setAutofocusInterval(long autofocusIntervalInMs) {
        this.autofocusIntervalInMs = autofocusIntervalInMs;
        if (autoFocusManager != null) {
            autoFocusManager.setAutofocusInterval(autofocusIntervalInMs);
        }
    }

    public void forceAutoFocus() {
        if (autoFocusManager != null) {
            autoFocusManager.start();
        }
    }

    public Point getPreviewSize() {
        return configManager.getCameraResolution();
    }

    /**
     * Opens the camera driver and initializes the hardware parameters.
     *
     * @param holder The surface object which the camera will draw preview frames into.
     * @param height @throws IOException Indicates the camera driver failed to open.
     */
    public synchronized void openDriver(SurfaceHolder holder, int width, int height)
            throws IOException {
        OpenCamera theCamera = openCamera;
        if (!isOpen()) {
            theCamera = OpenCameraInterface.open(requestedCameraId);
            if (theCamera == null || theCamera.getCamera() == null) {
                throw new IOException("Camera.open() failed to return object from driver");
            }
            openCamera = theCamera;
        }
        theCamera.getCamera().setPreviewDisplay(holder);
        theCamera.getCamera().setPreviewCallback(previewCallback);
        theCamera.getCamera().setDisplayOrientation(displayOrientation);

        if (!initialized) {
            initialized = true;
            configManager.initFromCameraParameters(theCamera, width, height);
        }

        Camera cameraObject = theCamera.getCamera();
        Camera.Parameters parameters = cameraObject.getParameters();
        String parametersFlattened =
                parameters == null ? null : parameters.flatten(); // Save these, temporarily
        try {
            configManager.setDesiredCameraParameters(theCamera, false);
        } catch (RuntimeException re) {
            // Driver failed
            SimpleLog.w(TAG, "Camera rejected parameters. Setting only minimal safe-mode parameters");
            SimpleLog.i(TAG, "Resetting to saved camera params: " + parametersFlattened);
            // Reset:
            if (parametersFlattened != null) {
                parameters = cameraObject.getParameters();
                parameters.unflatten(parametersFlattened);
                try {
                    cameraObject.setParameters(parameters);
                    configManager.setDesiredCameraParameters(theCamera, true);
                } catch (RuntimeException re2) {
                    // Well, darn. Give up
                    SimpleLog.w(TAG, "Camera rejected even safe-mode parameters! No configuration");
                }
            }
        }
        cameraObject.setPreviewDisplay(holder);
    }

    /**
     * Allows third party apps to specify the camera ID, rather than determine
     * it automatically based on available cameras and their orientation.
     *
     * @param cameraId camera ID of the camera to use. A negative value means "no preference".
     */
    public synchronized void setPreviewCameraId(int cameraId) {
        requestedCameraId = cameraId;
    }

    public int getPreviewCameraId() {
        return requestedCameraId;
    }

    /**
     * @param enabled if {@code true}, light should be turned on if currently off. And vice versa.
     */
    public synchronized void setTorchEnabled(boolean enabled) {
        OpenCamera theCamera = openCamera;
        if (theCamera != null && enabled != configManager.getTorchState(theCamera.getCamera())) {
            boolean wasAutoFocusManager = autoFocusManager != null;
            if (wasAutoFocusManager) {
                autoFocusManager.stop();
                autoFocusManager = null;
            }
            configManager.setTorchEnabled(theCamera.getCamera(), enabled);
            if (wasAutoFocusManager) {
                autoFocusManager = new AutoFocusManager(theCamera.getCamera());
                autoFocusManager.start();
            }
        }
    }

    public synchronized boolean isOpen() {
        return openCamera != null && openCamera.getCamera() != null;
    }

    /**
     * Closes the camera driver if still in use.
     */
    public synchronized void closeDriver() {
        if (isOpen()) {
            openCamera.getCamera().release();
            openCamera = null;
            // Make sure to clear these each time we close the camera, so that any scanning rect
            // requested by intent is forgotten.
            // framingRect = null;
            // framingRectInPreview = null;
        }
    }

    /**
     * Asks the camera hardware to begin drawing preview frames to the screen.
     */
    public synchronized void startPreview() {
        OpenCamera theCamera = openCamera;
        if (theCamera != null && !previewing) {
            theCamera.getCamera().startPreview();
            previewing = true;
            autoFocusManager = new AutoFocusManager(theCamera.getCamera());
            autoFocusManager.setAutofocusInterval(autofocusIntervalInMs);
        }
    }

    /**
     * Tells the camera to stop drawing preview frames.
     */
    public synchronized void stopPreview() {
        if (autoFocusManager != null) {
            autoFocusManager.stop();
            autoFocusManager = null;
        }
        if (openCamera != null && previewing) {
            openCamera.getCamera().stopPreview();
            previewing = false;
        }
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     *
     * @param data   A preview frame.
     * @param width  The width of the image.
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    /*public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height) {
        return new PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false);
    }*/



    private static final int MIN_FRAME_WIDTH = 240;
    private static final int MIN_FRAME_HEIGHT = 240;
    private static final int MAX_FRAME_WIDTH = 1200; // = 5/8 * 1920
    private static final int MAX_FRAME_HEIGHT = 675; // = 5/8 * 1080

    private Rect framingRect;
    private Rect framingRectInPreview;

    public synchronized Rect getFramingRect(Point viewSize) {
        if (framingRect == null) {
            if (openCamera == null) {
                return null;
            }
            Point screenResolution = viewSize /*configManager.getScreenResolution()*/;
            if (screenResolution == null) {
                // Called early, before init even finished
                return null;
            }

            //这个长宽是用来计算识别区域的大小，如需修改识别区域大小，可从这个地方下手。
            int width = findDesiredDimensionInRange(screenResolution.x, MIN_FRAME_WIDTH, MAX_FRAME_WIDTH);
//            int height = findDesiredDimensionInRange(screenResolution.y, MIN_FRAME_HEIGHT, MAX_FRAME_HEIGHT);
            int height = width; //改成正方形

            int leftOffset = (screenResolution.x - width) / 2;
            int topOffset = (screenResolution.y - height) / 2;
            framingRect = new Rect(leftOffset, topOffset, leftOffset + width, topOffset + height);
            Log.d(TAG, "Calculated framing rect: " + framingRect);
        }
        return framingRect;
    }

    private static int findDesiredDimensionInRange(int resolution, int hardMin, int hardMax) {
        int dim = 5 * resolution / 8; // Target 5/8 of each dimension
        if (dim < hardMin) {
            return hardMin;
        }
        if (dim > hardMax) {
            return hardMax;
        }
        return dim;
    }

    /**
     * Like {@link #getFramingRect} but coordinates are in terms of the preview frame,
     * not UI / screen.
     *
     * @return {@link Rect} expressing barcode scan area in terms of the preview size
     */
    public synchronized Rect getFramingRectInPreview(Point viewSize) {
        if (framingRectInPreview == null) {
            Rect framingRect = getFramingRect(viewSize);
            if (framingRect == null) {
                return null;
            }
            Rect rect = new Rect(framingRect);
            Point cameraResolution = configManager.getCameraResolution();
            Point screenResolution = configManager.getScreenResolution();
            if (cameraResolution == null || screenResolution == null) {
                // Called early, before init even finished
                return null;
            }
            if (context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                rect.left = rect.left * cameraResolution.x / screenResolution.x;
                rect.right = rect.right * cameraResolution.x / screenResolution.x;
                rect.top = rect.top * cameraResolution.y / screenResolution.y;
                rect.bottom = rect.bottom * cameraResolution.y / screenResolution.y;
            } else {
                rect.left = rect.left * cameraResolution.y / screenResolution.x;
                rect.right = rect.right * cameraResolution.y / screenResolution.x;
                rect.top = rect.top * cameraResolution.x / screenResolution.y;
                rect.bottom = rect.bottom * cameraResolution.x / screenResolution.y;
            }
            framingRectInPreview = rect;
        }
        return framingRectInPreview;
    }

    /**
     * A factory method to build the appropriate LuminanceSource object based on the format
     * of the preview buffers, as described by Camera.Parameters.
     *
     * @param data   A preview frame.
     * @param width  The width of the image.a
     * @param height The height of the image.
     * @return A PlanarYUVLuminanceSource instance.
     */
    public PlanarYUVLuminanceSource buildLuminanceSource(byte[] data, int width, int height, Point viewSize) {
        Rect rect = getFramingRectInPreview(viewSize);
        if (rect == null) {
            return null;
        }
        // Go ahead and assume it's YUV rather than die.
        return new PlanarYUVLuminanceSource(data, width, height, rect.left, rect.top,
                rect.width(), rect.height(), false);
    }
}
