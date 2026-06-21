package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;

import zxc.iconic.xenon.NekoConfig;

public class TextAnimationEditText extends EditTextCaption {

    private static class CharAnim {
        int index;
        long startTime;
        long duration;
        long blurDuration;
    }

    private static class DeletedCharAnim {
        String ch;
        float x, y;
        long startTime;
        long duration;
    }

    private static final PathInterpolator bezier = new PathInterpolator(0.47f, 0f, 0f, 1f);

    private final ArrayList<CharAnim> charAnims = new ArrayList<>();
    private final ArrayList<DeletedCharAnim> deletedCharAnims = new ArrayList<>();
    private final Paint animPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float animCursorX = -1;
    private int animCursorLine = -1;
    private ValueAnimator cursorAnimator;
    private boolean cursorAnimating;
    private int cursorColor = 0xff54a1db;

    private static Field mShowCursorField;
    private Object editorObj;

    public TextAnimationEditText(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        animPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setStyle(Paint.Style.FILL);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!NekoConfig.textAnimationEnabled) return;
                if (count > 0) {
                    Layout layout = getLayout();
                    if (layout == null) return;
                    long now = System.currentTimeMillis();
                    for (int i = start; i < start + count; i++) {
                        if (i >= s.length()) break;
                        DeletedCharAnim anim = new DeletedCharAnim();
                        anim.ch = String.valueOf(s.charAt(i));
                        anim.x = layout.getPrimaryHorizontal(i);
                        int line = layout.getLineForOffset(i);
                        anim.y = layout.getLineBaseline(line);
                        anim.startTime = now;
                        anim.duration = Math.max(50, NekoConfig.textAnimFadeDuration);
                        deletedCharAnims.add(anim);
                    }
                    invalidate();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!NekoConfig.textAnimationEnabled) return;
                Iterator<CharAnim> it = charAnims.iterator();
                while (it.hasNext()) {
                    CharAnim anim = it.next();
                    if (anim.index >= start && anim.index < start + before) {
                        it.remove();
                    } else if (anim.index >= start) {
                        anim.index += count - before;
                    }
                    if (anim.index >= s.length()) {
                        it.remove();
                    }
                }
                if (count > 0 && s instanceof Editable) {
                    Editable editable = (Editable) s;
                    long now = System.currentTimeMillis();
                    for (int i = start; i < start + count; i++) {
                        if (i >= s.length()) break;
                        CharAnim anim = new CharAnim();
                        anim.index = i;
                        anim.startTime = now;
                        anim.duration = Math.max(50, NekoConfig.textAnimFadeDuration);
                        anim.blurDuration = Math.max(50, NekoConfig.textAnimBlurDuration);
                        charAnims.add(anim);
                        editable.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), i, i + 1, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    invalidate();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                charAnims.removeIf(a -> a.index >= s.length());
            }
        });
    }

    @Override
    public void setCursorColor(int color) {
        super.setCursorColor(color);
        cursorColor = color;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (!NekoConfig.textAnimationEnabled || NekoConfig.textAnimCursorSpeed <= 0) return;
        Layout layout = getLayout();
        if (layout == null) return;
        float newX = layout.getPrimaryHorizontal(selStart);
        int newLine = layout.getLineForOffset(selStart);
        if (newLine != animCursorLine) {
            animCursorLine = newLine;
            animCursorX = newX;
            if (cursorAnimator != null) {
                cursorAnimator.cancel();
            }
            invalidate();
            return;
        }
        if (animCursorX < 0) {
            animCursorX = newX;
            return;
        }
        if (Math.abs(newX - animCursorX) < AndroidUtilities.dp(1)) {
            animCursorX = newX;
            return;
        }
        if (cursorAnimator != null) {
            cursorAnimator.cancel();
        }
        float fromX = animCursorX;
        long duration = Math.max(50, 500 - NekoConfig.textAnimCursorSpeed * 4);
        cursorAnimator = ValueAnimator.ofFloat(fromX, newX);
        cursorAnimator.setDuration(duration);
        cursorAnimator.setInterpolator(bezier);
        cursorAnimator.addUpdateListener(a -> {
            animCursorX = (float) a.getAnimatedValue();
            cursorAnimating = true;
            invalidate();
        });
        cursorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cursorAnimating = false;
                invalidate();
            }
        });
        cursorAnimator.start();
    }

    private boolean isCursorBlinkVisible() {
        try {
            if (mShowCursorField == null) {
                Field mEditorField = TextView.class.getDeclaredField("mEditor");
                mEditorField.setAccessible(true);
                editorObj = mEditorField.get(this);
                mShowCursorField = editorObj.getClass().getDeclaredField("mShowCursor");
                mShowCursorField.setAccessible(true);
            }
            if (mShowCursorField != null && editorObj != null) {
                long mShowCursor = mShowCursorField.getLong(editorObj);
                return (SystemClock.uptimeMillis() - mShowCursor) % (2 * 500) < 500 && isFocused();
            }
        } catch (Exception e) {}
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean smoothCursor = NekoConfig.textAnimationEnabled && NekoConfig.textAnimCursorSpeed > 0 && cursorAnimating;
        float origWidth = getCursorWidth();
        if (smoothCursor) {
            setCursorWidth(0);
        }
        super.onDraw(canvas);
        if (smoothCursor) {
            setCursorWidth(origWidth);
            drawAnimatedCursor(canvas);
        }
        drawCharAnimations(canvas);
    }

    private void drawAnimatedCursor(Canvas canvas) {
        if (!isCursorBlinkVisible()) return;
        Layout layout = getLayout();
        if (layout == null) return;
        int sel = getSelectionStart();
        int line = layout.getLineForOffset(sel);
        canvas.save();
        int voffsetCursor = 0;
        if ((getGravity() & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffsetCursor = getTotalPaddingTop() - getExtendedPaddingTop();
        }
        canvas.translate(getPaddingLeft() - getScrollX(), getExtendedPaddingTop() + voffsetCursor - getScrollY());
        cursorPaint.setColor(cursorColor);
        float x = animCursorX;
        float cursorSize = AndroidUtilities.dp(24);
        float lineTop = layout.getLineTop(line);
        float lineBottom = layout.getLineBottom(line);
        float centerY = (lineTop + lineBottom) / 2;
        canvas.drawRect(x, centerY - cursorSize / 2, x + AndroidUtilities.dp(2), centerY + cursorSize / 2, cursorPaint);
        canvas.restore();
    }

    private void drawCharAnimations(Canvas canvas) {
        if (!NekoConfig.textAnimationEnabled || (charAnims.isEmpty() && deletedCharAnims.isEmpty())) return;
        Layout layout = getLayout();
        if (layout == null) return;

        long now = System.currentTimeMillis();

        canvas.save();
        int voffsetText = 0;
        if ((getGravity() & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffsetText = getTotalPaddingTop() - getExtendedPaddingTop();
        }
        canvas.translate(getPaddingLeft() - getScrollX(), getExtendedPaddingTop() + voffsetText - getScrollY());
        canvas.clipRect(getScrollX(), getScrollY(),
                getScrollX() + getWidth() - getPaddingLeft() - getPaddingRight(),
                getScrollY() + getHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom());

        animPaint.setColor(getCurrentTextColor());
        animPaint.setTypeface(getTypeface());
        animPaint.setTextSize(getTextSize());
        animPaint.setStyle(Paint.Style.FILL);

        int blur = NekoConfig.textAnimBlurStrength;

        // --- 1. APPEARANCE ANIMATION ---
        CharSequence text = getText();
        if (text != null && !charAnims.isEmpty()) {
            Iterator<CharAnim> it = charAnims.iterator();
            while (it.hasNext()) {
                CharAnim anim = it.next();
                if (anim.index >= text.length()) {
                    it.remove();
                    continue;
                }

                long elapsed = now - anim.startTime;
                float linearProgress = Math.min(1f, elapsed / (float) Math.max(1, anim.duration));
                if (linearProgress >= 1) {
                    it.remove();

                    float x = layout.getPrimaryHorizontal(anim.index);
                    int line = layout.getLineForOffset(anim.index);
                    float y = layout.getLineBaseline(line);
                    String ch = String.valueOf(text.charAt(anim.index));
                    animPaint.setAlpha(255);
                    animPaint.setMaskFilter(null);
                    canvas.drawText(ch, x, y, animPaint);

                    final int doneIndex = anim.index;
                    post(() -> {
                        Editable editable = getText();
                        if (editable != null && doneIndex < editable.length()) {
                            ForegroundColorSpan[] spans = editable.getSpans(doneIndex, doneIndex + 1, ForegroundColorSpan.class);
                            for (ForegroundColorSpan span : spans) {
                                if (span.getForegroundColor() == Color.TRANSPARENT) {
                                    editable.removeSpan(span);
                                }
                            }
                        }
                    });
                    continue;
                }

                float progress = bezier.getInterpolation(linearProgress);

                float x = layout.getPrimaryHorizontal(anim.index);
                int line = layout.getLineForOffset(anim.index);
                float y = layout.getLineBaseline(line);
                String ch = String.valueOf(text.charAt(anim.index));

                int alpha = (int) (progress * 255);
                animPaint.setAlpha(alpha);
                animPaint.setMaskFilter(null);

                if (blur > 0 && Build.VERSION.SDK_INT >= 26) {
                    float blurLinear = Math.min(1f, elapsed / (float) Math.max(1, anim.blurDuration));
                    float blurProgress = bezier.getInterpolation(blurLinear);
                    float blurRadius = blur * (1 - blurProgress);
                    if (blurRadius > 0.5f) {
                        animPaint.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
                    }
                }

                canvas.drawText(ch, x, y, animPaint);
            }
        }

        // --- 2. DISAPPEARANCE ANIMATION (Reverse blur) ---
        if (!deletedCharAnims.isEmpty()) {
            Iterator<DeletedCharAnim> delIt = deletedCharAnims.iterator();
            while (delIt.hasNext()) {
                DeletedCharAnim anim = delIt.next();

                long elapsed = now - anim.startTime;
                float linearProgress = Math.min(1f, elapsed / (float) Math.max(1, anim.duration));
                float progress = bezier.getInterpolation(linearProgress);

                if (progress >= 1) {
                    delIt.remove();
                    continue;
                }

                int alpha = (int) ((1 - progress) * 255);
                animPaint.setAlpha(alpha);
                animPaint.setMaskFilter(null);

                if (blur > 0 && Build.VERSION.SDK_INT >= 26) {
                    float blurRadius = blur * progress;
                    if (blurRadius > 0.5f) {
                        animPaint.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
                    }
                }

                canvas.drawText(anim.ch, anim.x, anim.y, animPaint);
            }
        }

        animPaint.setMaskFilter(null);
        canvas.restore();

        if (!charAnims.isEmpty() || !deletedCharAnims.isEmpty()) {
            invalidate();
        }
    }
}
