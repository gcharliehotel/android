package com.google.charliehotel.calibrationrecorder;

// pm grant com.google.charliehotel.calibrationrecorder android.permission.CAMERA android.permission.WAKE_LOCK
// am start -n com.google.charliehotel.calibrationrecorder/.MainActivity
// am start -n "com.google.charliehotel.calibrationrecorder/com.google.charliehotel.calibrationrecorder.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER


import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeoutException;


public class MainActivity extends Activity {
    private static final String TAG = "CalibrationRecorder";

    private static boolean ENABLE_LEFT_CAMERA = true;
    private static boolean ENABLE_RIGHT_CAMERA = false;
    private static boolean ENABLE_SENSORS = true;
    private static boolean FINISH_UPON_PAUSING = false;

    private static final String LEFT_CAMERA_ID = "0";
    private static final String RIGHT_CAMERA_ID = "1";

    private static final int ACCEL_LPF_TIMESTAMP_OFFSET_NS = 1370833;
    private static final int GYRO_LPF_TIMESTAMP_OFFSET_NS = 1370833;
    private static final int ACCEL_200HZ_PERIOD_US = 5000;
    private static final int GYRO_200HZ_PERIOD_US = 5000;

    private static final String ACCEL_DATA_FILENAME = "accel.txt";
    private static final String GYRO_DATA_FILENAME = "gyro.txt";
    private static final String CAMERA_LEFT_METADATA_FILENAME = "left_image_metadata.txt";
    private static final String CAMERA_RIGHT_METADATA_FILENAME = "right_image_metadata.txt";
    private static final String LEFT_IMAGE_DIRNAME = "left_images";
    private static final String RIGHT_IMAGE_DIRNAME = "right_images";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        mHandler = new Handler();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + "WakeLock");
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

        if (ENABLE_LEFT_CAMERA) {
            mLeftCamera = new Camera(this, LEFT_CAMERA_ID);
        }
        if (ENABLE_RIGHT_CAMERA) {
            mRightCamera = new Camera(this, RIGHT_CAMERA_ID);
        }

        mDir = makeRunDir(getExternalFilesDir(null));
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
        if (ENABLE_LEFT_CAMERA) {
            mLeftCamera.setImageDir(mLeftImageDir);
            mLeftCamera.setMetadataWriter(mLeftCameraMetadataWriter);
            mLeftCamera.open();
        }

        if (ENABLE_RIGHT_CAMERA) {
            mRightCamera.setImageDir(mRightImageDir);
            mRightCamera.setMetadataWriter(mRightCameraMetadataWriter);
            mRightCamera.open();
        }

        Log.i(TAG, "onResume done");
    }

    @Override
    public void onPause() {
        Log.i(TAG, "onPause");
        if (mLeftCamera != null) {
            mLeftCamera.close();
            mLeftCamera = null;
        }
        if (mRightCamera != null) {
            mRightCamera.close();
            mRightCamera = null;
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

            mAccelWriter = new FileWriter(new File(mDir, ACCEL_DATA_FILENAME));
            mGyroWriter = new FileWriter(new File(mDir, GYRO_DATA_FILENAME));

            if (mLeftCamera != null) {
                mLeftImageDir = new File(mDir, LEFT_IMAGE_DIRNAME);
                if (!mLeftImageDir.mkdir()) {
                    showToast("Could not mkdir " + mLeftImageDir);
                }
                mLeftCameraMetadataWriter = new FileWriter(new File(mDir, CAMERA_LEFT_METADATA_FILENAME));
            }
            if (mRightCamera != null) {
                mRightImageDir = new File(mDir, RIGHT_IMAGE_DIRNAME);
                if (!mRightImageDir.mkdir()) {
                    showToast("Could not mkdir " + mRightImageDir);
                }
                mRightCameraMetadataWriter = new FileWriter(new File(mDir, CAMERA_RIGHT_METADATA_FILENAME));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void closeQuietly(Writer writer) {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not close writer");
            e.printStackTrace();
        }
    }

    void cleanupFiles() {
        closeQuietly(mAccelWriter);
        mAccelWriter = null;
        closeQuietly(mGyroWriter);
        mGyroWriter = null;
        closeQuietly(mLeftCameraMetadataWriter);
        mLeftCameraMetadataWriter = null;
        closeQuietly(mRightCameraMetadataWriter);
        mRightCameraMetadataWriter = null;
        Log.i(TAG, "file cleanup complete");
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

    private static File makeRunDir(File external_dir) {
        DateFormat date_format = new SimpleDateFormat("YYYYMMDDHHMMSS");
        date_format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String run_name = date_format.format(new Date());
        return new File(external_dir, run_name);
    }

    private PowerManager mPowerManager;
    private WakeLock mWakeLock;

    private SensorManager mSensorManager;
    private Sensor mAccelSensor;
    private Sensor mGyroSensor;

    private Camera mLeftCamera;
    private Camera mRightCamera;

    private File mDir;
    private FileWriter mAccelWriter;
    private FileWriter mGyroWriter;

    private File mLeftImageDir;
    private File mRightImageDir;
    private FileWriter mLeftCameraMetadataWriter;
    private FileWriter mRightCameraMetadataWriter;

    private Handler mHandler;
}
