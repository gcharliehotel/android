package com.google.charliehotel.calibrationrecorder;

// pm grant com.google.charliehotel.calibrationrecorder android.permission.CAMERA android.permission.WAKE_LOCK
// am start -n com.google.charliehotel.calibrationrecorder/.MainActivity
// am start -n "com.google.charliehotel.calibrationrecorder/com.google.charliehotel.calibrationrecorder.MainActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class MainActivity extends Activity {
    private static final String TAG = "CalibrationRecorder";

    private static final boolean ENABLE_LEFT_CAMERA = true;
    private static final boolean ENABLE_RIGHT_CAMERA = false;
    private static final boolean ENABLE_SENSORS = true;

    private static final boolean FINISH_UPON_PAUSING = true;

    private static final String LEFT_CAMERA_ID = "0";
    private static final String RIGHT_CAMERA_ID = "1";

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

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + "WakeLock");
        mWakeLock.acquire();

        if (ENABLE_SENSORS) {
            mSensors = new Sensors(this);
        }

        if (ENABLE_LEFT_CAMERA) {
            mLeftCamera = new Camera(this, LEFT_CAMERA_ID);
        }
        if (ENABLE_RIGHT_CAMERA) {
            mRightCamera = new Camera(this, RIGHT_CAMERA_ID);
        }

        mDir = getRunDir(getExternalFilesDir(null));
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

        if (mSensors != null) {
            mSensors.setAccelWriter(mAccelWriter);
            mSensors.setGyroWriter(mGyroWriter);
            mSensors.open();
        }

        if (mLeftCamera != null) {
            mLeftCamera.setImageDir(mLeftImageDir);
            mLeftCamera.setMetadataWriter(mLeftCameraMetadataWriter);
            mLeftCamera.open();
        }

        if (mRightCamera != null) {
            mRightCamera.setImageDir(mRightImageDir);
            mRightCamera.setMetadataWriter(mRightCameraMetadataWriter);
            mRightCamera.open();
        }

        Log.i(TAG, "onResume done");
    }

    @Override
    public void onPause() {
        Log.i(TAG, "onPause");

        if (mSensors != null) {
            mSensors.close();
            mSensors = null;
        }

        if (mLeftCamera != null) {
            mLeftCamera.close();
            mLeftCamera = null;
        }
        if (mRightCamera != null) {
            mRightCamera.close();
            mRightCamera = null;
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

    private static void noisyMkdir(File path) {
        if (!path.mkdir()) {
            Log.wtf(TAG, "Could not mkdir " + path);
        }
    }

    private void setupFiles() {
        try {
            noisyMkdir(mDir);

            if (mSensors != null) {
                mAccelWriter = new FileWriter(new File(mDir, ACCEL_DATA_FILENAME));
                mGyroWriter = new FileWriter(new File(mDir, GYRO_DATA_FILENAME));
            }

            if (mLeftCamera != null) {
                mLeftImageDir = new File(mDir, LEFT_IMAGE_DIRNAME);
                noisyMkdir(mLeftImageDir);
                mLeftCameraMetadataWriter = new FileWriter(new File(mDir, CAMERA_LEFT_METADATA_FILENAME));
            }
            if (mRightCamera != null) {
                mRightImageDir = new File(mDir, RIGHT_IMAGE_DIRNAME);
                noisyMkdir(mRightImageDir);
                mRightCameraMetadataWriter = new FileWriter(new File(mDir, CAMERA_RIGHT_METADATA_FILENAME));
            }
        } catch (IOException e) {
            Log.wtf(TAG, "Failed to setup files: " + e);
        }
    }

    private static void closeQuietly(Writer writer) {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not close writer");
        }
    }

    private void cleanupFiles() {
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

    private static File getRunDir(File external_dir) {
        DateFormat date_format = new SimpleDateFormat("YYYYMMDDHHMMSS", Locale.US);
        date_format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String run_name = date_format.format(new Date());
        return new File(external_dir, run_name);
    }

    private WakeLock mWakeLock;

    private Sensors mSensors;

    private Camera mLeftCamera;
    private Camera mRightCamera;

    private File mDir;

    private FileWriter mAccelWriter;
    private FileWriter mGyroWriter;

    private File mLeftImageDir;
    private File mRightImageDir;
    private FileWriter mLeftCameraMetadataWriter;
    private FileWriter mRightCameraMetadataWriter;
}
