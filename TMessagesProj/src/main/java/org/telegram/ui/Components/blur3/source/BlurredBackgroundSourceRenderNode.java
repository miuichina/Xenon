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
    private int progressiveHeight = -1;

    private static final String PROGRESSIVE_BLUR_SHADER =
        "uniform shader inputTexture;\n" +
        "uniform float maxRadius;\n" +
        "uniform float textureHeight;\n" +
        "\n" +
        "half4 main(float2 coord) {\n" +
        "    float t = coord.y / textureHeight;\n" +
        "    float radius = t * maxRadius;\n" +
        "    int radiusInt = int(ceil(radius));\n" +
        "    \n" +
        "    half4 result = half4(0.0);\n" +
        "    float totalWeight = 0.0;\n" +
        "    \n" +
        "    for (int i = -40; i <= 40; i++) {\n" +
        "        if (abs(i) > radiusInt) break;\n" +
        "        float2 offset = float2(0.0, float(i) / textureHeight);\n" +
        "        float weight = 1.0 - abs(float(i)) / (radius + 1.0);\n" +
        "        result += input.eval(coord + offset) * half4(weight);\n" +
        "        totalWeight += weight;\n" +
        "    }\n" +
        "    \n" +
        "    return result / half4(totalWeight);\n" +
        "}";

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void setProgressiveBlur(float maxRadius, int sourceHeight) {
        if (sourceHeight <= 0) return;
        if (progressiveMaxRadius == maxRadius && progressiveHeight == sourceHeight) return;
        progressiveMaxRadius = maxRadius;
        progressiveHeight = sourceHeight;
        if (progressiveShader == null) {
            progressiveShader = new RuntimeShader(PROGRESSIVE_BLUR_SHADER);
        }
        progressiveShader.setFloatUniform("maxRadius", maxRadius);
        progressiveShader.setFloatUniform("textureHeight", (float) sourceHeight);
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
