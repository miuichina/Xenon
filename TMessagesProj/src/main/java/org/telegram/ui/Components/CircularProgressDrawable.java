package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.telegram.messenger.AndroidUtilities;

import zxc.iconic.xenon.NekoConfig;

public class CircularProgressDrawable extends Drawable {

    public float size = AndroidUtilities.dp(18);
    public float thickness = AndroidUtilities.dp(2.25f);

    public CircularProgressDrawable() {
        this(0xffffffff);
    }
    public CircularProgressDrawable(int color) {
        setColor(color);
    }
    public CircularProgressDrawable(float size, float thickness, int color) {
        this.size = size;
        this.thickness = thickness;
        setColor(color);
    }

    private long start = -1;
    public static final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
    private float[] segment = new float[2];
    private void updateSegment() {
        final long now = SystemClock.elapsedRealtime();
        final long t = (now - start) % 5400;
        getSegments(t, segment);
    }

    private long lastWavyUpdate;
    private float wavePhaseAngle;
    private float wavyAmplitudeSmooth = 1f;
    private float wavyLastAmplitudeSmooth = 1f;
    private float bgThicknessScale;
    private final Path wavyProgressPath = new Path();
    private final PathMeasure wavyProgressPathMeasure = new PathMeasure();
    private final Path wavySegmentPath = new Path();
    private RectF wavyLastOval = new RectF();
    private int wavyLastGeneration;

    public static void getSegments(float t, float[] segments) {
        segments[0] = Math.max(0, 1520 * t / 5400f - 20);
        segments[1] = 1520 * t / 5400f;
        for (int i = 0; i < 4; ++i) {
            segments[1] += interpolator.getInterpolation((t - i * 1350) / 667f) * 250;
            segments[0] += interpolator.getInterpolation((t - (667 + i * 1350)) / 667f) * 250;
        }
    }

    private final Paint paint = new Paint(); {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private float angleOffset;
    private final RectF bounds = new RectF();

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (start < 0) {
            start = SystemClock.elapsedRealtime();
        }
        updateSegment();

        long now = SystemClock.elapsedRealtime();
        if (lastWavyUpdate != 0) {
            long dt = now - lastWavyUpdate;
            if (dt > 50) dt = 50;
            wavePhaseAngle += (dt * NekoConfig.wavySpeed) / 1000f;
            wavePhaseAngle %= 360f;
            wavyAmplitudeSmooth += (1f - wavyAmplitudeSmooth) * Math.min(1f, dt / 80f);
        }
        lastWavyUpdate = now;

        float inset = AndroidUtilities.dp(2.5f);
        RectF insetOval = new RectF(bounds);
        insetOval.inset(inset, inset);
        float sweep = segment[1] - segment[0];
        float absSweep = Math.abs(sweep);
        if (absSweep < 360) {
            int alpha = paint.getAlpha();
            paint.setAlpha(alpha * 40 / 100);
            float saveWidth = paint.getStrokeWidth();
            paint.setStrokeWidth(saveWidth * bgThicknessScale);
            float gap = 16;
            float dir = sweep >= 0 ? 1 : -1;
            float bgSweep = 360 - absSweep - 2 * gap;
            if (bgSweep > 0) {
                canvas.drawArc(insetOval, angleOffset + segment[1] + dir * gap, dir * bgSweep, false, paint);
            }
            paint.setStrokeWidth(saveWidth);
            paint.setAlpha(alpha);
        }
        drawWavyArc(canvas, insetOval, angleOffset + segment[0], sweep, paint);
        invalidateSelf();
    }

    private void drawWavyArc(Canvas canvas, RectF oval, float startAngle, float sweepAngle, Paint paint) {
        if (!oval.equals(wavyLastOval) || wavyLastGeneration != NekoConfig.wavyGeneration || wavyLastAmplitudeSmooth != wavyAmplitudeSmooth) {
            wavyLastOval.set(oval);
            wavyProgressPath.rewind();

            float cx = oval.centerX();
            float cy = oval.centerY();
            float baseRadius = Math.min(oval.width(), oval.height()) / 2f;

            float amplitude = baseRadius * NekoConfig.wavyAmplitudeFactor * wavyAmplitudeSmooth;
            int waves = NekoConfig.wavyWaves;
            int steps = 180;

            for (int i = 0; i <= steps; i++) {
                float angle = (i * 360f) / steps;
                float rad = (float) Math.toRadians(angle);
                float r = baseRadius + amplitude * (float) Math.sin(waves * rad);
                float x = cx + r * (float) Math.cos(rad);
                float y = cy + r * (float) Math.sin(rad);

                if (i == 0) {
                    wavyProgressPath.moveTo(x, y);
                } else {
                    wavyProgressPath.lineTo(x, y);
                }
            }
            wavyProgressPath.close();
            wavyProgressPathMeasure.setPath(wavyProgressPath, false);
            wavyLastGeneration = NekoConfig.wavyGeneration;
            wavyLastAmplitudeSmooth = wavyAmplitudeSmooth;
        }

        float length = wavyProgressPathMeasure.getLength();
        float sweepDist = (Math.abs(sweepAngle) / 360f) * length;

        float startDist = (wavePhaseAngle / 360f) * length;
        startDist = (startDist % length + length) % length;
        float stopDist = startDist + sweepDist;

        wavySegmentPath.reset();

        if (stopDist <= length) {
            wavyProgressPathMeasure.getSegment(startDist, stopDist, wavySegmentPath, true);
        } else {
            wavyProgressPathMeasure.getSegment(startDist, length, wavySegmentPath, true);
            wavyProgressPathMeasure.getSegment(0, stopDist - length, wavySegmentPath, false);
        }
        wavySegmentPath.rLineTo(0, 0);

        canvas.save();
        canvas.rotate(startAngle - wavePhaseAngle, oval.centerX(), oval.centerY());
        canvas.drawPath(wavySegmentPath, paint);
        canvas.restore();
    }

    public void reset() {
        start = -1;
        lastWavyUpdate = 0;
    }

    public void setAngleOffset(float angleOffset) {
        this.angleOffset = angleOffset;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        bounds.set(
            left + (width - thickness / 2f - size) / 2f,
            top + (height - thickness / 2f - size) / 2f,
            left + (width + thickness / 2f + size) / 2f,
            top + (height + thickness / 2f + size) / 2f
        );
        super.setBounds(left, top, right, bottom);
        paint.setStrokeWidth(thickness);
    }

    public void setColor(int color) {
        paint.setColor(color);
    }

    public int getColor() {
        return paint.getColor();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) (size + thickness);
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) (size + thickness);
    }
}
