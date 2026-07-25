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
    private long lastUpdateTime;

    private float rotationOffset;
    private float indeterminateArcLength = 10;
    private long indeterminatePhaseStartTime;
    private int indeterminatePhase;
    private static final float INDETERMINATE_MIN_ARC = 10;
    private static final float INDETERMINATE_MAX_ARC = 313;
    private static final int INDET_GROW = 0;
    private static final int INDET_MAX = 1;
    private static final int INDET_SHRINK = 2;
    private static final int INDET_PAUSE = 3;
    private static final long INDET_GROW_DURATION = 2500;
    private static final long INDET_MAX_DURATION = 417;
    private static final long INDET_SHRINK_DURATION = 833;
    private static final long INDET_PAUSE_DURATION = 2083;

    private long kickPhaseStartTime;
    private static final long KICK_INTERVAL = 1458;
    private static final long KICK_DURATION = 250;
    private static final float KICK_SPEED_MULTIPLIER = 3f;
    private static final float BASE_ROTATION_SPEED = 360f / 2083f;

    private long lastWavyUpdate;
    private float wavePhaseAngle;
    private float wavyAmplitudeSmooth = 1f;
    private float wavyLastAmplitudeSmooth = 1f;
    private final Path wavyProgressPath = new Path();
    private final PathMeasure wavyProgressPathMeasure = new PathMeasure();
    private final Path wavySegmentPath = new Path();
    private RectF wavyLastOval = new RectF();
    private int wavyLastGeneration;

    public static final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();

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
        long now = SystemClock.elapsedRealtime();
        if (start < 0) {
            start = now;
            indeterminatePhaseStartTime = now;
            kickPhaseStartTime = now;
            lastWavyUpdate = now;
            lastUpdateTime = now;
        }

        long dt = now - lastUpdateTime;
        if (dt > 17) dt = 17;
        lastUpdateTime = now;

        if (lastWavyUpdate != 0) {
            long wavyDt = now - lastWavyUpdate;
            if (wavyDt > 50) wavyDt = 50;
            wavePhaseAngle += (wavyDt * NekoConfig.wavySpeed) / 1000f;
            wavePhaseAngle %= 360f;
            wavyAmplitudeSmooth += (1f - wavyAmplitudeSmooth) * Math.min(1f, wavyDt / 80f);
        }
        lastWavyUpdate = now;

        long kickElapsed = now - kickPhaseStartTime;
        if (kickElapsed >= KICK_INTERVAL) {
            kickPhaseStartTime = now;
            kickElapsed = 0;
        }
        float rotSpeed = BASE_ROTATION_SPEED;
        if (kickElapsed < KICK_DURATION) {
            rotSpeed *= KICK_SPEED_MULTIPLIER;
        }
        rotationOffset += rotSpeed * dt;
        while (rotationOffset > 360) rotationOffset -= 360;

        if (indeterminatePhaseStartTime == 0) {
            indeterminatePhaseStartTime = now;
            kickPhaseStartTime = now;
        }
        long elapsed = now - indeterminatePhaseStartTime;
        switch (indeterminatePhase) {
            case INDET_GROW: {
                float t = Math.min(1f, (float) elapsed / INDET_GROW_DURATION);
                float smooth = t * t * (3 - 2 * t);
                indeterminateArcLength = INDETERMINATE_MIN_ARC + (INDETERMINATE_MAX_ARC - INDETERMINATE_MIN_ARC) * smooth;
                if (t >= 1f) {
                    indeterminatePhase = INDET_MAX;
                    indeterminatePhaseStartTime = now;
                }
                break;
            }
            case INDET_MAX: {
                indeterminateArcLength = INDETERMINATE_MAX_ARC;
                if (elapsed >= INDET_MAX_DURATION) {
                    indeterminatePhase = INDET_SHRINK;
                    indeterminatePhaseStartTime = now;
                }
                break;
            }
            case INDET_SHRINK: {
                float t = Math.min(1f, (float) elapsed / INDET_SHRINK_DURATION);
                float smooth = t * t * (3 - 2 * t);
                indeterminateArcLength = INDETERMINATE_MAX_ARC - (INDETERMINATE_MAX_ARC - INDETERMINATE_MIN_ARC) * smooth;
                if (t >= 1f) {
                    indeterminatePhase = INDET_PAUSE;
                    indeterminatePhaseStartTime = now;
                }
                break;
            }
            case INDET_PAUSE: {
                if (elapsed >= INDET_PAUSE_DURATION) {
                    indeterminatePhase = INDET_GROW;
                    indeterminatePhaseStartTime = now;
                    kickPhaseStartTime = now;
                }
                break;
            }
        }

        float rad = Math.max(4, indeterminateArcLength);
        float inset = AndroidUtilities.dp(2.5f);
        RectF insetOval = new RectF(bounds);
        insetOval.inset(inset, inset);

        if (Math.abs(rad) < 360) {
            int alpha = paint.getAlpha();
            paint.setAlpha(alpha * 40 / 100);
            float gap = 22;
            float bgSweep = 360 - rad - 2 * gap;
            if (bgSweep > 0) {
                canvas.drawArc(insetOval, angleOffset + rotationOffset + rad + gap, bgSweep, false, paint);
            }
            paint.setAlpha(alpha);
        }
        drawWavyArc(canvas, insetOval, angleOffset + rotationOffset, rad, paint);
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
        indeterminatePhaseStartTime = 0;
        indeterminateArcLength = INDETERMINATE_MIN_ARC;
        indeterminatePhase = INDET_GROW;
        kickPhaseStartTime = 0;
        rotationOffset = 0;
        lastWavyUpdate = 0;
        wavyAmplitudeSmooth = 1f;
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
