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
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Camera {
    private static final String TAG = "CalibrationRecorder";

    private static final int CAMERA_TIMESTAMP_OFFSET_NS = 0;

    public Camera(@NonNull Context context, String cameraId) {
        mContext = context;
        mCameraId = cameraId;
        mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setImageDir(File imageDir) {
        mImageDir = imageDir;
    }

    public void setMetadataWriter(FileWriter metadataWriter) {
        mMetadataWriter = metadataWriter;
    }

    public void open(int width, int height) {
        Log.i(TAG, "openCamera " + mCameraId + " " + width + " " + height);

        ensurePermission(mContext, Manifest.permission.CAMERA);

        setUpOutputs(width, height);
        try {
            CameraCharacteristics camera_characteristics = mCameraManager.getCameraCharacteristics(mCameraId);
            int timestamp_source = camera_characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                Log.i(TAG, "Camera " + mCameraId + " time source is realtime");
            } else if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN) {
                Log.e(TAG, "Camera " + mCameraId + " time source is unknown; not good");
            }

            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    public void setUpOutputs(int width, int height) {
        Log.i(TAG, "setUpCameraOutputs " + mCameraId + " " + width + " " + height);
        try {
            CameraCharacteristics characteristics
                    = mCameraManager.getCameraCharacteristics(mCameraId);

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            //Log.i(TAG, "scaler map: " + map);

            // For still image captures, we use the largest available size.
            Size largest = Collections.max(
                    Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                    new CompareSizesByArea());
            Log.i(TAG, "Largest output is " + largest);
            mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                    ImageFormat.JPEG, /*maxImages*/2);
            mImageReader.setOnImageAvailableListener(
                    mOnImageAvailableListener, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void close() {
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
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void createPreviewSession() {
        try {
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                Log.v(TAG, "onConfigured " + mCameraId + ", but camera is already closed");
                                return;
                            }

                            mCaptureSession = cameraCaptureSession;
                            try {
                                Log.i(TAG, "setting repeating request");
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mCameraBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
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
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice cameraDevice) {
            Log.i(TAG, "camera device " + mCameraId + " opened");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.w(TAG, "camera device " + mCameraId + " disconnected");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
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
                e.printStackTrace();
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
            mCameraBackgroundHandler.post(new ImageSaver(image, mImageDir));
        }
    };

    public void startBackgroundThread() {
        mCameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
        mCameraBackgroundThread.start();
        mCameraBackgroundHandler = new Handler(mCameraBackgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        mCameraBackgroundThread.quitSafely();
        try {
            mCameraBackgroundThread.join();
            mCameraBackgroundThread = null;
            mCameraBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private static class ImageSaver implements Runnable {
        private static AtomicInteger mIdCounter = new AtomicInteger(0);

        private final int mId;
        private final Image mImage;
        private final File mFile;


        public ImageSaver(Image image, File dir) {
            mId = mIdCounter.getAndIncrement();
            mImage = image;
            mFile = new File(dir, String.format("%05d.jpg", mId));
        }

        @Override
        public void run() {
            Log.v(TAG, "ImageSaver " + mId);
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            mImage.close();
            Log.v(TAG, "ImageSaver wrote " + mFile);
        }
    }

    void ensurePermission(final Context context, final String permission) {
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
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;

    private HandlerThread mCameraBackgroundThread;
    private Handler mCameraBackgroundHandler;

}

