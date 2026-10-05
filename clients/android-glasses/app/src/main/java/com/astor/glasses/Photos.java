package com.astor.glasses;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import com.astor.glasses.core.Assist;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;

/**
 * One photo from the phone's own camera, prepared the way the iPhone client prepares a photo from the
 * glasses: longest side at most 1280, JPEG, within the backend's size limit.
 *
 * This is a stand-in. The camera of the glasses is reachable only through the vendor SDK, see
 * {@link com.astor.glasses.core.GlassesDevice}. The photo lives in the private cache and is deleted
 * as soon as it is read; it never reaches the gallery.
 */
final class Photos {
    static final int REQUEST = 41;
    private static final int MAX_SIDE = 1280;

    private File file;

    /** False when the phone has no camera application to ask. */
    boolean capture(Activity activity) {
        File dir = new File(activity.getCacheDir(), "photos");
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        file = new File(dir, UUID.randomUUID() + ".jpg");
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".photos", file);
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST);
            return true;
        } catch (Exception e) {
            discard();
            return false;
        }
    }

    /** The prepared JPEG, or null when the capture was cancelled or cannot be used. */
    byte[] result(int resultCode) {
        File taken = file;
        file = null;
        if (taken == null) return null;
        try {
            if (resultCode != Activity.RESULT_OK || taken.length() == 0 || taken.length() > 12L * 1024 * 1024) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(taken.getPath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            for (options.inSampleSize = 1; Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= MAX_SIDE; ) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(taken.getPath(), options);
            if (bitmap == null) return null;
            float ratio = Math.min(1f, (float) MAX_SIDE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Matrix matrix = new Matrix();
            matrix.postScale(ratio, ratio);
            // The camera stores "which way is up" beside the pixels; the server sees pixels only.
            matrix.postRotate(degrees(new ExifInterface(taken.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)));
            Bitmap small = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 75, jpeg);
            return jpeg.size() > 0 && jpeg.size() <= Assist.MAX_MEDIA_BYTES ? jpeg.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            taken.delete();
        }
    }

    void discard() {
        if (file != null) file.delete();
        file = null;
    }

    private static int degrees(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }
}
