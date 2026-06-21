package zxc.iconic.xenon.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import zxc.iconic.xenon.NekoConfig;

/**
 * Live preview of a glass surface on top of the current chat wallpaper.
 * The wallpaper is drawn full-bleed (center-cropped) and recorded into the
 * backing {@link BlurredBackgroundSourceRenderNode} with a blur RenderEffect,
 * so the glass drawable can sample it the same way it samples the real chat
 * background. Sliders (blur, tint, etc.) call {@link #invalidateGlass()} which
 * updates the blur radius and forces a redraw.
 */
@SuppressLint("ViewConstructor")
public class GlassPreviewCell extends View {

    private final Theme.ResourcesProvider resourcesProvider;

    // Source pipeline: wallpaperBitmap -> renderNodeSource (with blur) -> glassDrawable
    @Nullable private Bitmap wallpaperBitmap;
    @Nullable private final BlurredBackgroundSourceRenderNode renderNodeSource;
    @Nullable private BlurredBackgroundDrawable glassDrawable;

    private final android.graphics.RectF bubbleRect = new android.graphics.RectF();
    private final float cornerRadius;
    private boolean lastAdvanced;
    private BackgroundGradientDrawable.Disposable backgroundGradientDisposable;

    @RequiresApi(api = 33)
    public GlassPreviewCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        renderNodeSource = new BlurredBackgroundSourceRenderNode(null);
        cornerRadius = dp(20);
        setWillNotDraw(false);
        rebuildGlass();
    }

    // ---------------------------------------------------------------------------
    // Glass drawable lifecycle
    // ---------------------------------------------------------------------------

    private void rebuildGlass() {
        if (renderNodeSource == null) return;
        glassDrawable = renderNodeSource.createDrawable();
        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).setLiquidGlassEffectAllowed();
        }
        glassDrawable.setColorProvider(BlurredBackgroundProviderImpl.chatTitlePill(resourcesProvider));
        glassDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        glassDrawable.setRadius(cornerRadius);
        lastAdvanced = NekoConfig.useAdvancedLiquidGlass;
    }

    /** Call whenever any glass parameter slider changes. */
    public void invalidateGlass() {
        if (!(glassDrawable instanceof BlurredBackgroundDrawableRenderNode)) {
            rebuildGlass();
            refreshRenderNodeBlur();
            invalidate();
            return;
        }
        if (lastAdvanced != NekoConfig.useAdvancedLiquidGlass) {
            // Toggle changed — need a fresh LiquidGlassEffect with recompiled shader.
            rebuildGlass();
            refreshRenderNodeBlur();
        } else {
            refreshRenderNodeBlur();
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).recreateLiquidGlassEffect();
        }
        invalidate();
    }

    /**
     * Re-apply the blur RenderEffect to the backing RenderNode so the
     * preview reflects the current {@code advancedGlassBlur} slider value.
     */
    private void refreshRenderNodeBlur() {
        if (renderNodeSource == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float radius = dpf2(NekoConfig.useAdvancedLiquidGlass
                ? Math.max(2f, NekoConfig.advancedGlassBlur * 0.4f)
                : 8f);
        renderNodeSource.setBlur(radius);
        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).invalidateDisplayList();
        }
    }

    // ---------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        final float hm = dp(21);
        final float vm = dp(28);
        final float bh = dp(56);
        final float cy = getMeasuredHeight() / 2f;
        bubbleRect.set(hm, cy - bh / 2f, getMeasuredWidth() - hm, cy + bh / 2f);
        if (glassDrawable != null) {
            glassDrawable.setBounds(
                    (int) bubbleRect.left, (int) bubbleRect.top,
                    (int) bubbleRect.right, (int) bubbleRect.bottom);
            glassDrawable.setRadius(cornerRadius);
        }
        captureWallpaper();
    }

    // ---------------------------------------------------------------------------
    // Wallpaper capture
    // ---------------------------------------------------------------------------

    /**
     * Rasterises the current wallpaper into a bitmap, then records that bitmap
     * into the backing RenderNode with a blur RenderEffect applied. This gives
     * the glass drawable a blurred source to sample from, matching how the
     * real chat background works.
     */
    private void captureWallpaper() {
        if (getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0 || renderNodeSource == null) return;

        final Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaper == null) {
            wallpaperBitmap = null;
            return;
        }
        wallpaper.setAlpha(255);
        wallpaperBitmap = centerCropDrawable(wallpaper, getMeasuredWidth(), getMeasuredHeight());

        // Record the wallpaper bitmap into the RenderNode source.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final Canvas c = renderNodeSource.beginRecording(getMeasuredWidth(), getMeasuredHeight());
            c.drawBitmap(wallpaperBitmap, 0f, 0f, null);
            renderNodeSource.endRecording();
        }
        refreshRenderNodeBlur();

        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).invalidateDisplayList();
        }
        invalidate();
    }

    // ---------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        drawWallpaper(canvas);
        drawBubble(canvas);
    }

    private void drawWallpaper(@NonNull Canvas canvas) {
        final Drawable d = Theme.getCachedWallpaperNonBlocking();
        if (d == null) {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
            return;
        }
        d.setAlpha(255);
        final int w = getMeasuredWidth(), h = getMeasuredHeight();
        if (d instanceof BackgroundGradientDrawable bgd) {
            backgroundGradientDisposable = bgd.drawExactBoundsSize(canvas, this);
        } else if (d instanceof ColorDrawable || d instanceof GradientDrawable
                || d instanceof MotionBackgroundDrawable) {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
        } else if (d instanceof BitmapDrawable bmd) {
            if (bmd.getTileModeX() == Shader.TileMode.REPEAT) {
                canvas.save();
                float scale = 2.0f / AndroidUtilities.density;
                canvas.scale(scale, scale);
                d.setBounds(0, 0, (int) Math.ceil(w / scale), (int) Math.ceil(h / scale));
                d.draw(canvas);
                canvas.restore();
            } else {
                float sx = (float) w / d.getIntrinsicWidth();
                float sy = (float) h / d.getIntrinsicHeight();
                float sc = Math.max(sx, sy);
                int dw = (int) Math.ceil(d.getIntrinsicWidth() * sc);
                int dh = (int) Math.ceil(d.getIntrinsicHeight() * sc);
                canvas.save();
                canvas.clipRect(0, 0, w, h);
                d.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                d.draw(canvas);
                canvas.restore();
            }
        } else {
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
        }
    }

    private void drawBubble(@NonNull Canvas canvas) {
        if (glassDrawable == null || bubbleRect.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0.5f));
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, p);
            return;
        }
        glassDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        glassDrawable.draw(canvas);
    }

    // ---------------------------------------------------------------------------
    // Bitmap helpers
    // ---------------------------------------------------------------------------

    private static @NonNull Bitmap centerCropDrawable(@NonNull Drawable d, int w, int h) {
        if (d instanceof BitmapDrawable bmd && bmd.getBitmap() != null && !bmd.getBitmap().isRecycled()) {
            return centerCropBitmap(bmd.getBitmap(), w, h);
        }
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        if (d instanceof ColorDrawable || d instanceof GradientDrawable || d instanceof MotionBackgroundDrawable
                || d instanceof BackgroundGradientDrawable) {
            d.setBounds(0, 0, w, h);
            d.draw(c);
        } else {
            final int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                final float sc = Math.max((float) w / dw, (float) h / dh);
                final int sw = Math.round(dw * sc), sh = Math.round(dh * sc);
                d.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
            } else {
                d.setBounds(0, 0, w, h);
            }
            d.draw(c);
        }
        return out;
    }

    private static @NonNull Bitmap centerCropBitmap(@NonNull Bitmap src, int w, int h) {
        final float sc = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        final int sw = Math.round(src.getWidth() * sc), sh = Math.round(src.getHeight() * sc);
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        // Draw the full source bitmap scaled to cover (w x h) with centre alignment.
        // Previously a Matrix was created but never passed to drawBitmap, so the
        // bitmap was drawn at 1:1 pixels (showing only a corner, not a center crop).
        final android.graphics.RectF dst = new android.graphics.RectF(
                (w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f);
        final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, null, dst, p);
        return out;
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (backgroundGradientDisposable != null) {
            backgroundGradientDisposable.dispose();
            backgroundGradientDisposable = null;
        }
    }

    public static int heightDp()  { return 140; }
    public static int heightPx()  { return dp(heightDp()); }
}