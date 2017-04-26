package com.google.charliehotel.calibrationrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class Camera {
    private static final String TAG = "CalibrationRecorder";

    /**
     * Max preview width/height that is guaranteed by Camera2 API
     */
    private static final int CAMERA2_MAX_PREVIEW_WIDTH = 1920;
    private static final int CAMERA2_MAX_PREVIEW_HEIGHT = 1080;

    private static final int CAMERA_TIMESTAMP_OFFSET_NS = 0;

    private static final int MAX_IMAGE_READER_IMAGES = 4;

    private static final float SCALE_FACTOR = 1.0f;

    Camera(@NonNull Context context, @NonNull String cameraId) {
        mContext = context;
        mCameraId = cameraId;
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    void setImageDir(@NonNull File imageDir) {
        mImageDir = imageDir;
    }

    void setMetadataWriter(@NonNull FileWriter metadataWriter) {
        mMetadataWriter = metadataWriter;
    }

    void open() {
        Log.i(TAG, "openCamera " + mCameraId);

        ensurePermission(mContext, Manifest.permission.CAMERA);

        try {
            CameraCharacteristics camera_characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            int timestamp_source = camera_characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                Log.i(TAG, "Camera " + mCameraId + " time source is realtime");
            } else if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN) {
                Log.e(TAG, "Camera " + mCameraId + " time source is unknown; not good");
            }

            if (!mCameraOpenCloseLock.tryAcquire(5000, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            Log.i(TAG, "Opening camera");
            startBackgroundThread();
            setUpOutputs();
            mCameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "open/CameraAccessException: " + e);
        } catch (SecurityException e) {
            Log.e(TAG, "open/SecurityException: " + e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void setUpOutputs() {
        Log.i(TAG, "setUpCameraOutputs " + mCameraId);
        try {
            CameraCharacteristics characteristics
                    = mCameraManager.getCameraCharacteristics(mCameraId);

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            //Log.i(TAG, "scaler map: " + map)i

            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());
            Log.i(TAG, "Largest output is " + largest);
            int width = largest.getWidth();
            int height = largest.getHeight();
            width = Math.round(width * SCALE_FACTOR);
            height = Math.round(height * SCALE_FACTOR);
            Log.i(TAG, "using " + width + " x " + height);
            mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                    ImageFormat.JPEG, /*maxImages*/ MAX_IMAGE_READER_IMAGES);
            assert mImageReader != null;
            mImageReader.setOnImageAvailableListener(
                    mOnImageAvailableListener, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "setUpOutputs: CamearAccessException");
        }
    }

    void close() {
        Log.i(TAG, "closeCamera " + mCameraId);
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();//
                mImageReader = null;
            }
        }  catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            stopBackgroundThread();
            mCameraOpenCloseLock.release();
        }
    }

    private void createPreviewCameraSession() {
        try {
            CaptureRequest.Builder captureRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureRequest = captureRequestBuilder.build();
            Log.i(TAG, "creating capture sesion");
            mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.i(TAG, "capture session configured");
                            if (mCameraDevice == null) {
                                Log.v(TAG, "onConfigured " + mCameraId + ", but camera is already closed");
                                return;
                            }
                            mCaptureSession = cameraCaptureSession;
                            try {
                                Log.i(TAG, "Initiating capture");
                                mCaptureSession.setRepeatingRequest(mCaptureRequest, mCaptureCallback, mCameraBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "CameraAccessException: " + e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "onConfiguredFailed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException: " + e);
        }
    }

    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "camera device " + mCameraId + " opened");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createPreviewCameraSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.w(TAG, "camera device " + mCameraId + " disconnected");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.e(TAG, "camera device " + mCameraId + " error, error=" + error);
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            Log.v(TAG, "onCaptureProgressed");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            Log.i(TAG, "onCaputureCompleted " + mCameraId);
            Log.v(TAG, "frame number: " + result.getFrameNumber());

            try {
                mMetadataWriter.write(formatCaptureResult(result));
            } catch (IOException e) {
                Log.e(TAG, "I/O Exception on mMetaDataWriter");
            }
        }

        String formatCaptureResult(TotalCaptureResult capture_result) {
            long adjusted_timestamp_ns = capture_result.get(TotalCaptureResult.SENSOR_TIMESTAMP) - CAMERA_TIMESTAMP_OFFSET_NS;
            return String.format("%d %05d %d %d\n",
                    adjusted_timestamp_ns,
                    capture_result.getFrameNumber(),
                    capture_result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME),
                    capture_result.get(TotalCaptureResult.SENSOR_ROLLING_SHUTTER_SKEW));
        }
    };

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.v(TAG, "onImageAvailable " + mCameraId);
            Image image = reader.acquireNextImage();
            int image_index = mImageIndexCounter.getAndIncrement();
            Log.i(TAG, "image " + image_index + " timestamp: " + image.getTimestamp());
            String basename = String.format("%05d.jpg", image_index);
            File file = new File(mImageDir, basename);
            mCameraBackgroundHandler.post(new CameraUtils.ImageSaver(image, file));
        }
    };

    private void startBackgroundThread() {
        Log.i(TAG, "starting background thread for camera " + mCameraId);
        mCameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
        mCameraBackgroundThread.start();
        mCameraBackgroundHandler = new Handler(mCameraBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        Log.i(TAG, "stopping background thread for camera " + mCameraId);
        mCameraBackgroundThread.quitSafely();
        try {
            mCameraBackgroundThread.join();
            mCameraBackgroundThread = null;
            mCameraBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "stopBackgroundThread: " + e);
        }
        Log.i(TAG, "background thread for camera " + mCameraId + " stopped");
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void ensurePermission(final Context context, final String permission) {
        final int cookie = 42;
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context, new String[]{permission}, cookie);
        }
    }

    private Context mContext;

    private final String mCameraId;
    private File mImageDir;
    private FileWriter mMetadataWriter;

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;

    private AtomicInteger mImageIndexCounter = new AtomicInteger(0);
    private ImageReader mImageReader;
    private CaptureRequest mCaptureRequest;

    private HandlerThread mCameraBackgroundThread;
    private Handler mCameraBackgroundHandler;

}
