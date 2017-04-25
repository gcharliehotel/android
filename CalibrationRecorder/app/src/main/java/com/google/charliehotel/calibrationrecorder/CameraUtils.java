package com.google.charliehotel.calibrationrecorder;

import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

class CameraUtils {
    final static String TAG = "CameraUtils";

    static void writeImage(Image image, File file) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.getChannel().write(buffer);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write image to " + file);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close image file " + file);
                }
            }
        }
    }
}
