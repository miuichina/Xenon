package org.telegram.ui.Components.blur3.source;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;

import java.util.List;

import me.vkryl.core.reference.ReferenceList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class BlurredBackgroundSourceRenderNode implements BlurredBackgroundSource {
    private final BlurredBackgroundSource fallbackSource;
    private final RenderNode renderNode;
    private RenderNodeWithHash renderNodeWithHash;

    private DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor;
    private int scrollableNoiseSuppressorIndex;
    public BlurredBackgroundSource underSource;
    private boolean noClip;
    private float pixelationScale = 1f;
    private float lastBlurRadius = -1f;

    private RuntimeShader progressiveShader;
    private float progressiveMaxRadius = -1f;
    private int progressiveWidth = -1;
    private int progressiveHeight = -1;
    private float progressiveFadeZoneFraction = -1f;

    private static final String PROGRESSIVE_BLUR_SHADER =
        "uniform shader inputTexture;\n" +
        "uniform float maxRadius;\n" +
        "uniform float textureWidth;\n" +
        "uniform float textureHeight;\n" +
        "uniform float fadeZoneFraction;\n" +
        "\n" +
        "half4 main(float2 coord) {\n" +
        "    float progress = clamp((coord.y / textureHeight) / fadeZoneFraction, 0.0, 1.0);\n" +
        "    float radius = progress * maxRadius;\n" +
        "    if (radius < 0.5) {\n" +
        "        return input.eval(coord);\n" +
        "    }\n" +
        "    float stride = max(1.0, radius / 2.0);\n" +
        "    half4 result = half4(0.0);\n" +
        "    float totalWeight = 0.0;\n" +
        "    for (int i = -2; i <= 2; i++) {\n" +
        "        for (int j = -2; j <= 2; j++) {\n" +
        "            float weight = exp(-(float(i * i + j * j)) / 2.0);\n" +
        "            float2 sampleCoord = coord + float2(float(i) * stride, float(j) * stride);\n" +
        "            sampleCoord = clamp(sampleCoord, float2(0.0, 0.0), float2(textureWidth, textureHeight));\n" +
        "            result += input.eval(sampleCoord) * half4(weight);\n" +
        "            totalWeight += weight;\n" +
        "        }\n" +
        "    }\n" +
        "    return result / half4(totalWeight);\n" +
        "}";

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void setProgressiveBlur(float maxRadius, int sourceWidth, int sourceHeight, float fadeZoneFraction) {
        if (sourceHeight <= 0) return;
        final float fraction = fadeZoneFraction > 0f ? fadeZoneFraction : 1f;
        if (progressiveMaxRadius == maxRadius && progressiveWidth == sourceWidth && progressiveHeight == sourceHeight && progressiveFadeZoneFraction == fraction) return;
        progressiveMaxRadius = maxRadius;
        progressiveWidth = sourceWidth;
        progressiveHeight = sourceHeight;
        progressiveFadeZoneFraction = fraction;
        if (progressiveShader == null) {
            progressiveShader = new RuntimeShader(PROGRESSIVE_BLUR_SHADER);
        }
        progressiveShader.setFloatUniform("maxRadius", maxRadius);
        progressiveShader.setFloatUniform("textureWidth", (float) sourceWidth);
        progressiveShader.setFloatUniform("textureHeight", (float) sourceHeight);
        progressiveShader.setFloatUniform("fadeZoneFraction", fraction);
        renderNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(progressiveShader, "inputTexture"));
        lastBlurRadius = -1f;
    }

    public void setPixelation(float scale) {
        this.pixelationScale = Math.max(1f, scale);
    }

    public BlurredBackgroundSourceRenderNode(BlurredBackgroundSource fallbackSource) {
        this.fallbackSource = fallbackSource;

        renderNode = new RenderNode(null);
    }

    public void setupRenderer(RenderNodeWithHash.Renderer renderer) {
        if (renderNodeWithHash == null) {
            renderNodeWithHash = new RenderNodeWithHash(renderNode, renderer);
        }
    }

    public void updateDisplayListIfNeeded() {
        renderNodeWithHash.updateDisplayListIfNeeded();
    }

    public void setSize(int width, int height) {
        renderNode.setPosition(0, 0, width, height);
    }

    public void setScrollableNoiseSuppressor(DownscaleScrollableNoiseSuppressor scrollableNoiseSuppressor, int index) {
        this.scrollableNoiseSuppressor = scrollableNoiseSuppressor;
        this.scrollableNoiseSuppressorIndex = index;
    }

    public void setUnderSource(BlurredBackgroundSource underSource) {
        this.underSource = underSource;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius) {
        if (lastBlurRadius != radius) {
            lastBlurRadius = radius;
            renderNode.setRenderEffect(radius > 0 ? RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) : null);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    public void setBlur(float radius, RenderEffect effect) {
        renderNode.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP), effect));
    }



    public void noClip() {
        this.noClip = true;
    }

    private boolean inRecording;
    private RecordingCanvas recordingCanvas;

    public boolean needUpdateDisplayList(int width, int height) {
        return !renderNode.hasDisplayList() || renderNode.getWidth() != width || renderNode.getHeight() != height;
    }

    public RecordingCanvas beginRecording(int width, int height) {
        if (inRecording) {
            throw new IllegalStateException();
        }

        inRecording = true;

        renderNode.setPosition(0, 0, width, height);
        recordingCanvas = renderNode.beginRecording(width, height);
        if (pixelationScale > 1f) {
            recordingCanvas.scale(1f / pixelationScale, 1f / pixelationScale);
            renderNode.setScaleX(pixelationScale);
            renderNode.setScaleY(pixelationScale);
            renderNode.setPivotX(0);
            renderNode.setPivotY(0);
        } else {
            renderNode.setScaleX(1f);
            renderNode.setScaleY(1f);
        }
        return recordingCanvas;
    }

    public void endRecording() {
        if (!inRecording) {
            throw new IllegalStateException();
        }

        renderNode.endRecording();
        inRecording = false;
        recordingCanvas = null;
    }

    public boolean isRecordingCanvas(Canvas canvas) {
        return canvas != null && canvas == recordingCanvas;
    }

    public boolean inRecording() {
        return inRecording;
    }

    @Override
    public void draw(Canvas canvas, float left, float top, float right, float bottom) {
        if (!canvas.isHardwareAccelerated()) {
            if (fallbackSource != null) {
                fallbackSource.draw(canvas, left, top, right, bottom);
            }
            return;
        }

        if (inRecording) {
            throw new IllegalStateException();
        }

        // Draw underSource (the chat wallpaper) directly. In advanced/unified
        // wallpaper-blur mode the wallpaper is already stack-blurred in
        // WallpaperBitmapProvider, so no GPU blur RenderNode is needed here.
        // The previous per-frame GPU blur node raced the render thread
        // (flickering) and never re-recorded when the underlying bitmap changed
        // (blur disappeared until the chat was reopened).
        if (underSource != null) {
            underSource.draw(canvas, left, top, right, bottom);
        }
        canvas.save();
        if (!noClip) {
            canvas.clipRect(left, top, right, bottom);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableNoiseSuppressor != null) {
            scrollableNoiseSuppressor.drawInline(canvas, scrollableNoiseSuppressorIndex);
        } else {
            canvas.drawRenderNode(renderNode);
        }
        canvas.restore();
    }

    public BlurredBackgroundSource getFallbackSource() {
        return fallbackSource;
    }

    public int getVisiblePositions(List<RectF> positions, int index, int expand) {
        int count = 0;

        for (BlurredBackgroundDrawableRenderNode d : drawables) {
            if (d.hasDisplayList() && d.getAlpha() > 0 && !d.getPaddedBounds().isEmpty()) {
                final RectF rectf;
                if (index < positions.size()) {
                    rectf = positions.get(index);
                } else {
                    rectf = new RectF();
                    positions.add(rectf);
                }
                d.getPositionRelativeSource(rectf);
                rectf.inset(-expand, -expand);

                index++;
                count++;
            }
        }

        return count;
    }

    private final ReferenceList<BlurredBackgroundDrawableRenderNode> drawables = new ReferenceList<>();

    private Runnable onDrawablesRelativePositionChangeListener;
    public void setOnDrawablesRelativePositionChangeListener(Runnable callback) {
        onDrawablesRelativePositionChangeListener = callback;
    }

    @Override
    public void dispatchOnDrawablesRelativePositionChange() {
        if (onDrawablesRelativePositionChangeListener != null) {
            onDrawablesRelativePositionChangeListener.run();
        }
    }

    public void invalidateDisplayListForDrawables() {
        for (BlurredBackgroundDrawableRenderNode d : drawables) {
            d.invalidateDisplayList();
        }
    }

    @Override
    public BlurredBackgroundDrawable createDrawable() {
        BlurredBackgroundDrawableRenderNode d = new BlurredBackgroundDrawableRenderNode(this);
        drawables.add(d);
        return d;
    }
}
