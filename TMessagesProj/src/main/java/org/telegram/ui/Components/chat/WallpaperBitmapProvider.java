package org.telegram.ui.Components.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ChatBackgroundDrawable;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped;
import org.telegram.ui.Components.blur3.utils.BitmapMemoizedMetadata;

public class WallpaperBitmapProvider {

    private final BlurredBackgroundSourceColor sourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundSourceBitmap sourceBitmap = new BlurredBackgroundSourceBitmap();

    private static final Rect tmpRect = new Rect();

    public BlurredBackgroundSource updateSourceFromBackgroundViewDrawable(
        Drawable drawable
    ) {
        if (drawable instanceof ColorDrawable) {
            final int color = ((ColorDrawable) drawable).getColor();
            sourceColor.setColor(color);
            return sourceColor;
        }

        if (drawable instanceof MotionBackgroundDrawable) {
            final MotionBackgroundDrawable motionDrawable = (MotionBackgroundDrawable) drawable;
            if (motionDrawable.getIntensity() < 0) {
                sourceColor.setColor(Color.BLACK);
                return sourceColor;
            }
            sourceBitmap.setBitmap(motionDrawable.getBitmap());
            return sourceBitmap;
        }

        if (drawable instanceof BitmapDrawable) {
            final Bitmap rawBitmap = ((BitmapDrawable) drawable).getBitmap();
            final Bitmap bitmap;
            if (zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass
                    && zxc.iconic.xenon.NekoConfig.advancedGlassWallpaperBlur) {
                // Unified wallpaper blur: pre-blur the wallpaper with the same
                // radius the glass content uses. We do this here on the CPU
                // (stack blur) instead of a RenderNode GPU blur, because the
                // previous GPU path recorded the wallpaper RenderNode from
                // inside draw() and raced the render thread (flickering), and
                // never re-recorded when the underlying bitmap changed (blur
                // disappeared until the chat was reopened).
                bitmap = getAdvancedBlurBitmap(rawBitmap);
            } else {
                bitmap = blurredFromBitmap.get(rawBitmap);
            }
            sourceBitmap.setBitmap(bitmap);
            return sourceBitmap;
        }

        if (drawable instanceof ChatBackgroundDrawable) {
            ChatBackgroundDrawable chatDrawable = (ChatBackgroundDrawable) drawable;
            return updateSourceFromBackgroundViewDrawable(chatDrawable.getDrawable(false));
        }

        if (drawable != null) {
            Canvas canvas = sourceBitmap.beginRecording(120, 160);
            tmpRect.set(drawable.getBounds());
            drawable.setBounds(0, 0, 120, 160);
            drawable.draw(canvas);
            drawable.setBounds(tmpRect);
            sourceBitmap.endRecording();
            final Bitmap captured = sourceBitmap.getBitmap();
            final Bitmap bitmap;
            if (zxc.iconic.xenon.NekoConfig.useAdvancedLiquidGlass
                    && zxc.iconic.xenon.NekoConfig.advancedGlassWallpaperBlur) {
                bitmap = getAdvancedBlurBitmap(captured);
            } else {
                bitmap = blurredFromBitmap.get(captured);
            }
            sourceBitmap.setBitmap(bitmap);
        }

        return sourceBitmap;
    }

    public int getNavigationBarColor(BlurredBackgroundSource source) {
        if (source instanceof BlurredBackgroundSourceColor) {
            return ((BlurredBackgroundSourceColor) source).getColor();
        }

        if (source instanceof BlurredBackgroundSourceBitmap) {
            final Bitmap bitmap = ((BlurredBackgroundSourceBitmap) source).getBitmap();
            return navbarColorFromBitmap.get(bitmap);
        }

        if (source instanceof BlurredBackgroundSourceWrapped) {
            return getNavigationBarColor(((BlurredBackgroundSourceWrapped) source).getSource());
        }

        return 0;
    }

    private final BitmapMemoizedMetadata<Bitmap> blurredFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::blurBitmap);
    private final BitmapMemoizedMetadata<Integer> navbarColorFromBitmap = new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::averageBottomColor);

    // Advanced/unified wallpaper blur cache. Memoised by (bitmap, generationId)
    // like the stock cache. The radius it was generated with is tracked
    // separately: when the user moves the advancedGlassBlur slider, the cache
    // instance is dropped so the next updateSourceFromBackgroundViewDrawable
    // call re-blurs with the new radius.
    private int lastAdvancedBlurRadius = Integer.MIN_VALUE;
    private BitmapMemoizedMetadata<Bitmap> blurredAdvancedFromBitmap =
            new BitmapMemoizedMetadata<>(WallpaperBitmapProvider::blurBitmapAdvancedCurrent);

    private Bitmap getAdvancedBlurBitmap(Bitmap bitmap) {
        final int radius = zxc.iconic.xenon.NekoConfig.advancedGlassBlur;
        if (radius != lastAdvancedBlurRadius) {
            lastAdvancedBlurRadius = radius;
            // Radius changed -> the memoised bitmap is stale. Rebuild the cache.
            blurredAdvancedFromBitmap = new BitmapMemoizedMetadata<>(
                    WallpaperBitmapProvider::blurBitmapAdvancedCurrent);
        }
        return blurredAdvancedFromBitmap.get(bitmap);
    }

    private static Bitmap blurBitmapAdvancedCurrent(Bitmap bitmap) {
        return blurBitmapAdvanced(bitmap, zxc.iconic.xenon.NekoConfig.advancedGlassBlur);
    }

    private static Bitmap blurBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        final float scale = Math.max(bitmap.getWidth() / 90f, bitmap.getHeight() / 120f);
        final Bitmap result = Utilities.stackBlurBitmapWithScaleFactor(bitmap, scale);
        result.setHasAlpha(false);
        return result;
    }

    private static Bitmap blurBitmapAdvanced(Bitmap bitmap, int radius) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        // Match the look of messages blurred under glass: a modest 2x downscale
        // (same as DownscaledRenderNode scale 2,2) keeps the wallpaper recognisable
        // through the glass, and the stack-blur radius maps 1:1 to the
        // advancedGlassBlur slider so the wallpaper and the glass content always
        // share the same softness. The previous version used a huge downsample
        // (up to 37x) which collapsed the wallpaper into a flat matte and hid it
        // behind the glass.
        final float clamped = Math.max(0, Math.min(40, radius));
        final int blurRadius = Math.max(1, (int) Math.round(org.telegram.messenger.AndroidUtilities.dpf2(clamped)));
        final int w = Math.max(1, bitmap.getWidth() / 2);
        final int h = Math.max(1, bitmap.getHeight() / 2);
        final Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);
        canvas.save();
        canvas.scale((float) w / bitmap.getWidth(), (float) h / bitmap.getHeight());
        canvas.drawBitmap(bitmap, 0, 0, null);
        canvas.restore();
        org.telegram.messenger.Utilities.stackBlurBitmap(result, blurRadius);
        result.setHasAlpha(false);
        return result;
    }

    private static int averageBottomColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0;
        }

        int height = bitmap.getHeight();
        int width = bitmap.getWidth();
        int bottomHeight = (int) (height * 0.1f);
        int startY = height - bottomHeight;

        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        long sumA = 0;
        int count = 0;

        int[] pixels = new int[width * bottomHeight];
        bitmap.getPixels(pixels, 0, width, 0, startY, width, bottomHeight);

        for (int color : pixels) {
            sumA += (color >>> 24) & 0xFF;
            sumR += (color >> 16) & 0xFF;
            sumG += (color >> 8) & 0xFF;
            sumB += color & 0xFF;
            count++;
        }

        if (count == 0) return Color.TRANSPARENT;

        int a = (int) (sumA / count);
        int r = (int) (sumR / count);
        int g = (int) (sumG / count);
        int b = (int) (sumB / count);

        return Color.argb(a, r, g, b);
    }

}
