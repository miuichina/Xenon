/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

public class CloseProgressDrawable2 extends Drawable {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long lastFrameTime;
    private DecelerateInterpolator interpolator = new DecelerateInterpolator();
    private RectF rect = new RectF();
    private float angle;
    private boolean animating;
    private int side;
    private int globalColorAlpha = 255;
    private int currentColor;

    private float progress = -1;
    private float arcLength;
    private long phaseStartTime;
    private int animPhase;

    private static final float MIN_ARC = 10;
    private static final float MAX_ARC = 324;
    private static final int PHASE_GROW = 0;
    private static final int PHASE_MAX = 1;
    private static final int PHASE_SHRINK = 2;
    private static final int PHASE_PAUSE = 3;
    private static final long GROW_DURATION = 3000;
    private static final long MAX_DURATION = 500;
    private static final long SHRINK_DURATION = 1000;
    private static final long PAUSE_DURATION = 2500;

    private long kickPhaseStartTime;
    private static final long KICK_INTERVAL = 1750;
    private static final long KICK_DURATION = 300;
    private static final float KICK_SPEED_MULTIPLIER = 3f;

    public CloseProgressDrawable2() {
        this(2);
    }

    public CloseProgressDrawable2(float widthDp) {
        super();
        paint.setColor(0xffffffff);
        paint.setStrokeWidth(AndroidUtilities.dp(widthDp));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        side = AndroidUtilities.dp(8);
        arcLength = MIN_ARC;
    }

    public void startAnimation() {
        if (animating) return;
        animating = true;
        lastFrameTime = System.currentTimeMillis();
        phaseStartTime = lastFrameTime;
        kickPhaseStartTime = lastFrameTime;
        animPhase = PHASE_GROW;
        arcLength = MIN_ARC;
        invalidateSelf();
    }

    public void stopAnimation() {
        animating = false;
    }

    public boolean isAnimating() {
        return animating;
    }

    public void setProgress(float p) {
        progress = p;
        invalidateSelf();
    }

    private void setColor(int value) {
        if (currentColor != value) {
            globalColorAlpha = Color.alpha(value);
            value = ColorUtils.setAlphaComponent(value, 255);
            paint.setColor(value);
        }
    }

    public void setSide(int value) {
        side = value;
    }

    private float easeInOut(float t) {
        return t * t * (3 - 2 * t);
    }

    @Override
    public void draw(Canvas canvas) {
        long newTime = System.currentTimeMillis();
        setColor(getCurrentColor());
        if (lastFrameTime != 0) {
            long dt = (newTime - lastFrameTime);
            if (progress >= 0 && progress > 5) {
                angle = -90;
            } else if (animating || angle != 0) {
                    long kickElapsed = newTime - kickPhaseStartTime;
                    if (kickElapsed >= KICK_INTERVAL) {
                        kickPhaseStartTime = newTime;
                        kickElapsed = 0;
                    }
                    float speedMul = kickElapsed < KICK_DURATION ? KICK_SPEED_MULTIPLIER : 1f;
                    angle += 360 * dt / 500.0f * speedMul;
                    angle -= (int) (angle / 720) * 720;
                    invalidateSelf();
            }
        }

        if (progress >= 0 && progress > 5) {
            arcLength = progress / 100f * 360;
        } else if (animating) {
            long elapsed = newTime - phaseStartTime;
            switch (animPhase) {
                case PHASE_GROW: {
                    float t = Math.min(1f, (float) elapsed / GROW_DURATION);
                    arcLength = MIN_ARC + (MAX_ARC - MIN_ARC) * easeInOut(t);
                    if (t >= 1f) {
                        animPhase = PHASE_MAX;
                        phaseStartTime = newTime;
                    }
                    invalidateSelf();
                    break;
                }
                case PHASE_MAX: {
                    arcLength = MAX_ARC;
                    if (elapsed >= MAX_DURATION) {
                        animPhase = PHASE_SHRINK;
                        phaseStartTime = newTime;
                    }
                    invalidateSelf();
                    break;
                }
                case PHASE_SHRINK: {
                    float t = Math.min(1f, (float) elapsed / SHRINK_DURATION);
                    arcLength = MAX_ARC - (MAX_ARC - MIN_ARC) * easeInOut(t);
                    if (t >= 1f) {
                        animPhase = PHASE_PAUSE;
                        phaseStartTime = newTime;
                    }
                    invalidateSelf();
                    break;
                }
                case PHASE_PAUSE: {
                    if (elapsed >= PAUSE_DURATION) {
                        animPhase = PHASE_GROW;
                        phaseStartTime = newTime;
                    }
                    invalidateSelf();
                    break;
                }
            }
        } else {
            arcLength = MIN_ARC;
        }

        if (globalColorAlpha == 255 || getBounds() == null || getBounds().isEmpty()) {
            canvas.save();
        } else {
            canvas.saveLayerAlpha(getBounds().left, getBounds().top, getBounds().right, getBounds().bottom, globalColorAlpha, Canvas.ALL_SAVE_FLAG);
        }
        canvas.translate(getIntrinsicWidth() / 2, getIntrinsicHeight() / 2);
        canvas.rotate(-45);
        float progress1 = 1.0f;
        float progress2 = 1.0f;
        float progress3 = 1.0f;
        float progress4 = 0.0f;
        if (angle >= 0 && angle < 90) {
            progress1 = (1.0f - angle / 90.0f);
        } else if (angle >= 90 && angle < 180) {
            progress1 = 0.0f;
            progress2 = 1.0f - (angle - 90) / 90.0f;
        } else if (angle >= 180 && angle < 270) {
            progress1 = progress2 = 0;
            progress3 = 1.0f - (angle - 180) / 90.0f;
        } else if (angle >= 270 && angle < 360) {
            progress1 = progress2 = progress3 = 0;
            progress4 = (angle - 270) / 90.0f;
        } else if (angle >= 360 && angle < 450) {
            progress1 = progress2 = progress3 = 0;
            progress4 = 1.0f - (angle - 360) / 90.0f;
        } else if (angle >= 450 && angle < 540) {
            progress2 = progress3 = 0;
            progress1 = (angle - 450) / 90.0f;
        } else if (angle >= 540 && angle < 630) {
            progress3 = 0;
            progress2 = (angle - 540) / 90.0f;
        } else if (angle >= 630 && angle < 720) {
            progress3 = (angle - 630) / 90.0f;
        }

        if (progress1 != 0) {
            canvas.drawLine(0, 0, 0, side * progress1, paint);
        }
        if (progress2 != 0) {
            canvas.drawLine(-side * progress2, 0, 0, 0, paint);
        }
        if (progress3 != 0) {
            canvas.drawLine(0, -side * progress3, 0, 0, paint);
        }
        if (progress4 != 1) {
            canvas.drawLine(side * progress4, 0, side, 0, paint);
        }

        canvas.restore();

        int cx = getBounds().centerX();
        int cy = getBounds().centerY();
        rect.set(cx - side, cy - side, cx + side, cy + side);
        float startAngle = (angle < 360 ? 0 : angle - 360) - 90;
        canvas.drawArc(rect, startAngle, arcLength, false, paint);

        lastFrameTime = newTime;
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter cf) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(24);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(24);
    }

    protected int getCurrentColor() {
        return Color.WHITE;
    }
}
