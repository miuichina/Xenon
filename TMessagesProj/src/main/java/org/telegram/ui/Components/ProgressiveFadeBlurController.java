package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

import java.util.function.IntSupplier;

import zxc.iconic.xenon.NekoConfig;

public class ProgressiveFadeBlurController {

    private final BlurredBackgroundSourceRenderNode source;
    private final BlurredBackgroundSourceColor underSource;
    private final ChatActivityFadeView fadeView;
    private final View captureView;
    private int fadeZoneTop;
    private long lastUpdateTime;
    private int background = Color.TRANSPARENT;
    private IntSupplier backgroundColorProvider;
    private int lastBackgroundColor = Integer.MIN_VALUE;

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView) {
        this(parent, captureView, -1);
    }

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView, int insertIndex) {
        this(parent, captureView, insertIndex, null);
    }

    public ProgressiveFadeBlurController(ViewGroup parent, View captureView, int insertIndex, IntSupplier backgroundColorProvider) {
        this.backgroundColorProvider = backgroundColorProvider;
        this.captureView = captureView;
        underSource = new BlurredBackgroundSourceColor();
        source = new BlurredBackgroundSourceRenderNode(null);
        source.setUnderSource(underSource);
        fadeView = new ChatActivityFadeView(parent.getContext());
        fadeView.setup(new BlurredBackgroundDrawableViewFactory(source));
        fadeView.setOpaqueFade(true);
        fadeView.setFadeHeightTop(AndroidUtilities.dp(48), false);
        fadeView.setFadeHeightBottom(AndroidUtilities.dp(48), false);
        fadeView.setFadeTopAlpha(255);
        if (insertIndex >= 0) {
            parent.addView(fadeView, insertIndex, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } else {
            parent.addView(fadeView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    public void setFadeZoneTop(int fadeZoneTop) {
        this.fadeZoneTop = fadeZoneTop;
        fadeView.setFadeZoneTop(fadeZoneTop);
        fadeView.setDimFadeZoneTop(fadeZoneTop);
    }

    public void setFadeZoneBottom(int fadeZoneBottom) {
        fadeView.setFadeZoneBottom(fadeZoneBottom);
    }

    public void setBackgroundColor(int color) {
        backgroundColorProvider = null;
        background = color;
    }

    public void invalidate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || source.inRecording() || SizeNotifierFrameLayout.drawingBlur) {
            return;
        }
        final int fw = captureView.getWidth();
        final int fh = captureView.getHeight();
        if (fw <= 0 || fh <= 0) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - lastUpdateTime < 1000 / Math.max(15, NekoConfig.progressiveFadeBlurRefreshRate)) {
            return;
        }
        lastUpdateTime = now;
        final int color = backgroundColorProvider != null ? backgroundColorProvider.getAsInt() : background;
        if (color != lastBackgroundColor) {
            lastBackgroundColor = color;
            int opaqueColor = (color & 0x00FFFFFF) | 0xFF000000;
            underSource.setColor(opaqueColor);
            fadeView.setDimColor(color);
        }
        final int pixelation = Math.max(2, NekoConfig.blurredFadePixelation);
        source.setPixelation(pixelation);
        float topFraction = fadeZoneTop > AndroidUtilities.dp(48) ? Math.min(1f, (fadeZoneTop - AndroidUtilities.dp(48)) / (float) fh) : 1f;
        source.setProgressiveBlur(AndroidUtilities.dpf2(NekoConfig.progressiveFadeBlurMaxRadius) / pixelation, fw / pixelation, fh / pixelation, topFraction, 0f, NekoConfig.progressiveFadeBlurSamples);
        Canvas c = source.beginRecording(fw, fh);
        c.drawColor(color);
        captureView.draw(c);
        source.endRecording();
        fadeView.setDim(NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        fadeView.invalidate();
    }
}