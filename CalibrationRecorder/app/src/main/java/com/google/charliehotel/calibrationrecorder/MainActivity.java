package com.google.charliehotel.calibrationrecorder;

// pm grant com.google.charliehotel.calibrationrecorder android.permission.CAMERA
// am start -n "com.google.charliehotel.calibrationrecorder/com.google.charliehotel.calibrationrecorder.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER


import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final String TAG = "CalibrationRecorder";

    private static boolean ENABLE_CAMERAS = true;
    private static boolean ENABLE_SENSORS = true;
    private static boolean FINISH_UPON_PAUSING = true;

    private static final String LEFT_CAMERA_ID = "0";
    private static final String RIGHT_CAMERA_ID = "1";

    /**
     * Max preview width/height that is guaranteed by Camera2 API
     */
    private static final int CAMERA2_MAX_PREVIEW_WIDTH = 1920;
    private static final int CAMERA2_MAX_PREVIEW_HEIGHT = 1080;

    private static final int PREVIEW_WIDTH = 3016; //CAMERA2_MAX_PREVIEW_WIDTH;  // 3036
    private static final int PREVIEW_HEIGHT = 3016; //CAMERA2_MAX_PREVIEW_HEIGHT;  // 3036

    private static final int ACCEL_LPF_TIMESTAMP_OFFSET_NS = 1370833;
    private static final int GYRO_LPF_TIMESTAMP_OFFSET_NS = 1370833;
    private static final int ACCEL_200HZ_PERIOD_US = 5000;
    private static final int GYRO_200HZ_PERIOD_US = 5000;

    private static final int CAMERA_TIMESTAMP_OFFSET_NS = 0;

    private static final String ACCEL_DATA_FILENAME = "accel.txt";
    private static final String GYRO_DATA_FILENAME = "gyro.txt";
    private static final String CAMERA_METADATA_FILENAME = "image_metadata.txt";
    private static final String IMAGE_DIRNAME = "images";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG + "WakeLock");
        mWakeLock.acquire();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        assert mAccelSensor != null;
        mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (mGyroSensor == null) {
            Log.w(TAG, "Falling back to calibrated gyro.  Will use, but do not want.");
            mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        assert mGyroSensor != null;

        File external_dir = getExternalFilesDir(null);
        DateFormat date_format = new SimpleDateFormat("YYYYMMDDHHMMSS");
        date_format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String run_name = date_format.format(new Date());
        mDir = new File(external_dir, run_name);
        Log.i(TAG, "mDir=" + mDir);

        Log.i(TAG, "onCreate done");
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.i(TAG, "onStart");
        Log.i(TAG, "onStart done");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        setupFiles();
        if (ENABLE_SENSORS) {
            openSensors();
        }
        if (ENABLE_CAMERAS) {
            startCameraBackgroundThread();
            openCamera(LEFT_CAMERA_ID, PREVIEW_WIDTH, PREVIEW_HEIGHT);
        }
        Log.i(TAG, "onResume done");
    }

    @Override
    public void onPause() {
        Log.i(TAG, "onPause");
        if (ENABLE_CAMERAS) {
            closeCamera();
            stopCameraBackgroundThread();
        }
        if (ENABLE_SENSORS) {
            closeSensors();
        }
        cleanupFiles();

        if (FINISH_UPON_PAUSING) {
            finish();
        }
        Log.i(TAG, "onPause done");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.i(TAG, "onStop");
        mWakeLock.release();
        Log.i(TAG, "onStop done");
        super.onStop();
    }

    void setupFiles() {
        try {
            if (!mDir.mkdir()) {
                showToast("Could not mkdir " + mDir);
            }
            mImageDir = new File(mDir, IMAGE_DIRNAME);
            if (!mImageDir.mkdir()) {
                showToast("Could not mkdir " + mImageDir);
            }
            mAccelWriter = new FileWriter(new File(mDir, ACCEL_DATA_FILENAME));
            mGyroWriter = new FileWriter(new File(mDir, GYRO_DATA_FILENAME));
            mCameraMetadataWriter = new FileWriter(new File(mDir, CAMERA_METADATA_FILENAME));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void cleanupFiles() {
        try {
            if (mAccelWriter != null) {
                mAccelWriter.close();
                mAccelWriter = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (mGyroWriter != null) {
                mGyroWriter.close();
                mGyroWriter = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (mCameraMetadataWriter != null) {
                mCameraMetadataWriter.close();
                mCameraMetadataWriter = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    void openSensors() {
        Log.i(TAG, "Setting sensor callbacks");
        mSensorManager.registerListener(mAccelSensorEventListener, mAccelSensor, ACCEL_200HZ_PERIOD_US);
        mSensorManager.registerListener(mGyroSensorEventListener, mGyroSensor, GYRO_200HZ_PERIOD_US);
    }

    void closeSensors() {
        mSensorManager.unregisterListener(mAccelSensorEventListener, mAccelSensor);
        mSensorManager.unregisterListener(mGyroSensorEventListener, mGyroSensor);
    }

    private void openCamera(@NonNull String cameraId, int width, int height) {
        Log.i(TAG, "openCamera " + cameraId + " " + width + " " + height);

        ensurePermission(this, Manifest.permission.CAMERA);

        setUpCameraOutputs(cameraId, width, height);
        try {
            CameraCharacteristics camera_characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            int timestamp_source = camera_characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                Log.i(TAG, "Camera time source is realtime");
            } else if (timestamp_source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN) {
                Log.e(TAG, "Camera time source is unknown");
            }

            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(cameraId, mCameraDeviceStateCallback, mCameraBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void setUpCameraOutputs(@NonNull String cameraId, int width, int height) {
        Log.i(TAG, "setUpCameraOutputs " + cameraId + " " + width + " " + height);
        try {
            CameraCharacteristics characteristics
                    = mCameraManager.getCameraCharacteristics(cameraId);

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

    private void closeCamera() {
        Log.i(TAG, "closeCamera");
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

    private void createCameraPreviewSession() {
        try {
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                Log.v(TAG, "onConfigured, but camera is already closed");
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
                            showToast("Failed");
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
            Log.i(TAG, "camera device opened");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            Log.w(TAG, "camera device disconnected");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            Log.e(TAG, "camera device error, error=" + error);
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
            Log.i(TAG, "onCaptureProgressed");
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            Log.i(TAG, "onCaputureCompleted");
            Log.v(TAG, "frame number: " + result.getFrameNumber());

            try {
                mCameraMetadataWriter.write(formatCaptureResult(result));
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

    final static AtomicInteger mImageIndexCounter = new AtomicInteger(0);

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.v(TAG, "onImageAvailable");
            Image image = reader.acquireNextImage();
            int image_index = mImageIndexCounter.getAndIncrement();
            Log.i(TAG, "image " + image_index + " timestamp: " +image.getTimestamp());
            mCameraBackgroundHandler.post(new ImageSaver(image, mImageDir));
        }
    };

    private void startCameraBackgroundThread() {
        mCameraBackgroundThread = new HandlerThread("CameraBackgroundThread");
        mCameraBackgroundThread.start();
        mCameraBackgroundHandler = new Handler(mCameraBackgroundThread.getLooper());
    }

    private void stopCameraBackgroundThread() {
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
            Log.v(TAG, "ImageSaver " + mId + " running");
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
            Log.v(TAG, "ImageSaver wrote " + mFile);
        }
    }

    void ensurePermission(final Context context, final String permission) {
        final int cookie = 42;
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity) context, new String[]{permission}, cookie);
        }
    }

    private void showToast(final String text) {
        final Activity activity = this;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private final SensorEventListener mAccelSensorEventListener = new SensorEventListener() {
        String formatAccelEvent(SensorEvent sensorEvent) {
            long adjusted_timestamp_ns = sensorEvent.timestamp - ACCEL_LPF_TIMESTAMP_OFFSET_NS;
            return String.format("%d %a %a %a\n", adjusted_timestamp_ns,
                    sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]);
        }

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            try {
                mAccelWriter.write(formatAccelEvent(sensorEvent));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
            Log.i(TAG, "Accel accuracy changed to " + i);
        }
    };

    private final SensorEventListener mGyroSensorEventListener = new SensorEventListener() {
        String formatGryoEvent(SensorEvent sensorEvent) {
            long adjusted_timestamp_ns = sensorEvent.timestamp - GYRO_LPF_TIMESTAMP_OFFSET_NS;
            return String.format("%d %a %a %a\n", adjusted_timestamp_ns,
                    sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]);
        }

        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            try {
                mGyroWriter.write(formatGryoEvent(sensorEvent));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {
            Log.i(TAG, "Gryo accuracy changed to " + i);
        }
    };

    private PowerManager mPowerManager;
    private WakeLock mWakeLock;

    private SensorManager mSensorManager;
    private Sensor mAccelSensor;
    private Sensor mGyroSensor;

    private CameraManager mCameraManager;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;
    private HandlerThread mCameraBackgroundThread;
    private Handler mCameraBackgroundHandler;

    private File mDir;
    private File mImageDir;
    private FileWriter mAccelWriter;
    private FileWriter mGyroWriter;
    private FileWriter mCameraMetadataWriter;
}
