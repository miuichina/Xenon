package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class OctagonBadgeDrawable extends Drawable {

    private Paint bgPaint;
    private Paint textPaint;
    private Paint particlePaint;
    private Path octagonPath;
    private Path starPath;
    private float size = AndroidUtilities.dp(22);
    private final Particle[] particles = new Particle[14];
    private long lastUpdateTime;

    public OctagonBadgeDrawable() {
        this(null);
    }

    public OctagonBadgeDrawable(Theme.ResourcesProvider resourcesProvider) {
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor((accentColor & 0x00FFFFFF) | 0xB3000000);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        double luminance = (0.299 * Color.red(accentColor) + 0.587 * Color.green(accentColor) + 0.114 * Color.blue(accentColor)) / 255;
        textPaint.setColor(luminance > 0.5 ? 0xFF000000 : 0xFFFFFFFF);

        particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        particlePaint.setColor(0xCCFFFFFF);

        octagonPath = new Path();
        starPath = new Path();

        long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < particles.length; i++) {
            particles[i] = new Particle();
            resetParticle(particles[i], 0, 0, now, true);
        }
    }

    public void setSize(float size) {
        this.size = size;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) size;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) size;
    }

    private void buildOctagonPath(RectF bounds) {
        octagonPath.reset();
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float outerR = Math.min(bounds.width(), bounds.height()) / 2f;
        float innerR = outerR * 0.62f;
        for (int i = 0; i < 8; i++) {
            float angle = (float) Math.toRadians(-90 + i * 45);
            float r = (i % 2 == 0) ? outerR : innerR;
            float px = cx + r * (float) Math.cos(angle);
            float py = cy + r * (float) Math.sin(angle);
            if (i == 0) octagonPath.moveTo(px, py);
            else octagonPath.lineTo(px, py);
        }
        octagonPath.close();
    }

    private void buildStarPath(float r) {
        starPath.reset();
        for (int i = 0; i < 8; i++) {
            float radius = i % 2 == 0 ? r : r * 0.35f;
            float angle = (float) Math.toRadians(i * 45);
            float px = radius * (float) Math.cos(angle);
            float py = radius * (float) Math.sin(angle);
            if (i == 0) starPath.moveTo(px, py);
            else starPath.lineTo(px, py);
        }
        starPath.close();
    }

    private static class Particle {
        float x, y, vx, vy, alpha, scale;
        long lifeTime, bornTime;
        float particleSize;
    }

    private void resetParticle(Particle p, float cx, float cy, long now, boolean randomPhase) {
        float angle = (float) (Math.random() * 2 * Math.PI);
        float speed = 0.05f + (float) Math.random() * 0.15f;
        p.vx = (float) Math.cos(angle) * speed;
        p.vy = (float) Math.sin(angle) * speed;
        p.x = cx;
        p.y = cy;
        p.alpha = 0.7f + (float) Math.random() * 0.3f;
        p.scale = 0.4f + (float) Math.random() * 0.5f;
        p.lifeTime = 2000 + (long) (Math.random() * 2000);
        p.particleSize = AndroidUtilities.dp(3 + (float) Math.random() * 2);
        if (randomPhase) {
            p.bornTime = now - (long) (Math.random() * p.lifeTime);
            float dt = (now - p.bornTime) * 0.5f;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        } else {
            p.bornTime = now;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        RectF bounds = new RectF(getBounds());
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        long now = SystemClock.elapsedRealtime();
        if (lastUpdateTime == 0) lastUpdateTime = now;
        float frameDt = Math.min(now - lastUpdateTime, 50) / 16.67f;
        lastUpdateTime = now;

        drawParticles(canvas, bounds, frameDt, now);
        drawOctagon(canvas, bounds);

        if (getCallback() != null) {
            getCallback().invalidateDrawable(this);
        }
    }

    private void drawOctagon(Canvas canvas, RectF bounds) {
        float textSize = size * 0.55f;
        textPaint.setTextSize(textSize);

        buildOctagonPath(bounds);
        canvas.drawPath(octagonPath, bgPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = bounds.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(":3", bounds.centerX(), textY, textPaint);
    }

    private void drawParticles(Canvas canvas, RectF bounds, float frameDt, long now) {
        float cx = bounds.centerX();
        float cy = bounds.centerY();

        for (Particle p : particles) {
            float elapsed = now - p.bornTime;

            if (elapsed >= p.lifeTime) {
                resetParticle(p, cx, cy, now, false);
                continue;
            }

            float progress = elapsed / (float) p.lifeTime;

            p.x += p.vx * frameDt;
            p.y += p.vy * frameDt;

            p.alpha = 1f - progress * progress;
            float s = p.scale * (1f - progress * 0.3f);

            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.scale(s, s);

            buildStarPath(p.particleSize);
            particlePaint.setAlpha((int) (180 * p.alpha));
            canvas.drawPath(starPath, particlePaint);
            canvas.restore();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
