package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

public class BlurBehindHelper {
    private final View view;
    private final SizeNotifierFrameLayout contentView;
    private final int colorKey;
    private final boolean isTop;
    private final Rect rect = new Rect();
    private final Paint paint = new Paint();
    private final float topShadowDp;
    private final float bottomShadowDp;
    private GradientDrawable topShadow;
    private GradientDrawable bottomShadow;

    public BlurBehindHelper(
        View view,
        SizeNotifierFrameLayout contentView,
        int colorKey,
        boolean isTop,
        float topShadowDp,
        float bottomShadowDp
    ) {
        this.view = view;
        this.contentView = contentView;
        this.colorKey = colorKey;
        this.isTop = isTop;
        this.topShadowDp = topShadowDp;
        this.bottomShadowDp = bottomShadowDp;

        contentView.blurBehindViews.add(view);
        if (topShadowDp > 0f) {
            topShadow = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Theme.getColor(Theme.key_dialogShadowLine), 0}
            );
        }
        if (bottomShadowDp > 0f) {
            bottomShadow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.getColor(Theme.key_dialogShadowLine), 0}
            );
        }
    }

    public BlurBehindHelper(
        View view,
        SizeNotifierFrameLayout contentView,
        int colorKey
    ) {
        this(view, contentView, colorKey, true, 0f, 0f);
    }

    public void draw(Canvas canvas) {
        draw(canvas, -1, -1);
    }

    public void draw(Canvas canvas, int heightOverride) {
        draw(canvas, heightOverride, -1);
    }

    public void draw(Canvas canvas, int heightOverride, int alphaOverride) {
        int w = view.getMeasuredWidth();
        int h = heightOverride >= 0 ? heightOverride : view.getMeasuredHeight();
        int topShadowPx = AndroidUtilities.dp(topShadowDp);
        int bottomShadowPx = AndroidUtilities.dp(bottomShadowDp);

        if (topShadow != null) {
            topShadow.setBounds(0, 0, w, topShadowPx);
            topShadow.draw(canvas);
        }
        if (bottomShadow != null) {
            bottomShadow.setBounds(0, h - bottomShadowPx, w, h);
            bottomShadow.draw(canvas);
        }

        if (h <= topShadowPx + bottomShadowPx) return;
        rect.set(0, topShadowPx, w, h - bottomShadowPx);
        paint.setColor(Theme.getColor(colorKey));
        paint.setAlpha(alphaOverride >= 0 ? alphaOverride : 255);
        Float y = computeY();
        if (y != null) {
            contentView.drawBlurRect(canvas, y, rect, paint, isTop);
        }
    }

    private Float computeY() {
        float y = 0f;
        View cur = view;
        while (cur != contentView) {
            y += cur.getY();
            if (cur.getParent() instanceof View) {
                cur = (View) cur.getParent();
            } else {
                return null;
            }
        }
        return y;
    }

    public static BlurBehindHelper create(
        View view,
        SizeNotifierFrameLayout contentView,
        int colorKey
    ) {
        return new BlurBehindHelper(view, contentView, colorKey, true, 0f, 0f);
    }

    public static BlurBehindHelper create(
        View view,
        SizeNotifierFrameLayout contentView,
        int colorKey,
        boolean isTop,
        float topShadowDp,
        float bottomShadowDp
    ) {
        return new BlurBehindHelper(view, contentView, colorKey, isTop, topShadowDp, bottomShadowDp);
    }
}
