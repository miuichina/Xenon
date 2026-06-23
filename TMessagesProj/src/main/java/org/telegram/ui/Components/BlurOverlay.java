package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

import java.util.List;

public class BlurOverlay {

    public static void capture(Bitmap[] outBitmap, BitmapShader[] outShader, Paint[] outPaint, Matrix[] outMatrix) {
        capture(outBitmap, outShader, outPaint, outMatrix, null, null);
    }

    public static void capture(Bitmap[] outBitmap, BitmapShader[] outShader, Paint[] outPaint, Matrix[] outMatrix, View forView, List<View> exclude) {
        AndroidUtilities.makeGlobalBlurBitmap(bitmap -> {
            if (bitmap == null) return;
            outBitmap[0] = bitmap;
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            outShader[0] = shader;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(shader);
            outPaint[0] = paint;
            Matrix matrix = new Matrix();
            matrix.postScale(8f, 8f);
            outMatrix[0] = matrix;
            shader.setLocalMatrix(matrix);
        }, 8);
    }

    public static void updateMatrix(Matrix matrix, BitmapShader shader, int[] location) {
        if (matrix == null || shader == null) return;
        matrix.reset();
        matrix.postScale(8f, 8f);
        matrix.postTranslate(-location[0], -location[1]);
        shader.setLocalMatrix(matrix);
    }

    public static void drawBlur(Canvas canvas, Paint paint, float alpha, android.graphics.RectF rect, float r) {
        if (paint == null) return;
        paint.setAlpha((int) (0xFF * alpha));
        canvas.drawRoundRect(rect, r, r, paint);
    }

    public static void drawDim(Canvas canvas, Paint dimPaint, float alpha, android.graphics.RectF rect, float r, int dimColor) {
        if (dimPaint == null) return;
        dimPaint.setColor(dimColor);
        dimPaint.setAlpha((int) (0xFF * alpha * 0.3f));
        canvas.drawRoundRect(rect, r, r, dimPaint);
    }

    public static void recycle(Bitmap bitmap, BitmapShader shader, Paint paint) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
