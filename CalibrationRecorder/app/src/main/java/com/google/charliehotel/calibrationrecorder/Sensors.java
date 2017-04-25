package com.google.charliehotel.calibrationrecorder;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;

class Sensors {
    private static final String TAG = "CalibrationRecorder";

    private static final int ACCEL_LPF_TIMESTAMP_OFFSET_NS = 0;  // BMI160: 1370833;
    private static final int GYRO_LPF_TIMESTAMP_OFFSET_NS = 0;  // BMI160: 1370833;

    private static final int ACCEL_200HZ_PERIOD_US = 5000;
    private static final int GYRO_200HZ_PERIOD_US = 5000;

    public Sensors(@NonNull Context context) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        assert mAccelSensor != null;
        mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (mGyroSensor == null) {
            Log.w(TAG, "Falling back to calibrated gyro.  Will use, but do not want.");
            mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        assert mGyroSensor != null;
    }

    public void setAccelWriter(@NonNull FileWriter writer) {
        mAccelWriter = writer;
    }

    public void setGyroWriter(@NonNull FileWriter writer) {
        mGyroWriter = writer;
    }

    public void open() {
        Log.i(TAG, "Setting sensor callbacks");
        mSensorManager.registerListener(mAccelSensorEventListener, mAccelSensor, ACCEL_200HZ_PERIOD_US);
        mSensorManager.registerListener(mGyroSensorEventListener, mGyroSensor, GYRO_200HZ_PERIOD_US);
    }

    public void close() {
        mSensorManager.unregisterListener(mAccelSensorEventListener, mAccelSensor);
        mSensorManager.unregisterListener(mGyroSensorEventListener, mGyroSensor);
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

    private SensorManager mSensorManager;
    private Sensor mAccelSensor;
    private Sensor mGyroSensor;

    private FileWriter mAccelWriter;
    private FileWriter mGyroWriter;
}
