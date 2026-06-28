package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;

/**
 * A card-style row for a single plugin, rendered inside the plugins list.
 * Each card has its own rounded white background, separated from the next by
 * a gap. Inside: title + toggle, description, divider, and action buttons.
 */
public class PluginCardCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();

    /** Vertical inset of the white card surface (gap between cards top/bottom). */
    private static final int CARD_INSET_V = 5;

    private TextCheckCell toggleCell;
    private TextView descView;
    private View settingsButton;
    private View deleteButton;

    public PluginCardCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setupView(context);
    }

    @Override
    public void setBackgroundColor(int color) {
        // Ignore — the adapter tries to apply the list background on custom
        // factory cells, which would cover the gap between cards. We draw our
        // own card surface in onDraw instead.
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getMeasuredWidth();
        int h = getMeasuredHeight();

        // Layer 1: gray "gap" background covering the WHOLE cell, so the area
        // between cards (the vertical inset) is gray regardless of the list
        // background.
        backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        bgRect.set(0, 0, w, h);
        canvas.drawRect(bgRect, backgroundPaint);

        // Layer 2: white rounded card on top, full width, inset only vertically
        // so cards span the same width as other menu items.
        backgroundPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        int insetV = AndroidUtilities.dp(CARD_INSET_V);
        bgRect.set(0, insetV, w, h - insetV);
        canvas.drawRoundRect(bgRect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), backgroundPaint);
    }

    private void setupView(Context context) {
        // Horizontal padding matches other menu items (no inset now); vertical
        // padding extends past the card inset so content sits inside the white
        // surface.
        int padH = AndroidUtilities.dp(16);
        int padV = AndroidUtilities.dp(CARD_INSET_V + 12);
        setPadding(padH, padV, padH, padV);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        // Title + toggle cell (reuses the themed switch).
        toggleCell = new TextCheckCell(context, resourcesProvider);
        toggleCell.setBackgroundColor(Color.TRANSPARENT);
        content.addView(toggleCell, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Description.
        descView = new TextView(context);
        descView.setTextSize(13);
        descView.setMaxLines(3);
        descView.setLineSpacing(AndroidUtilities.dp(1), 1f);
        content.addView(descView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        // Divider.
        View divider = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                dividerPaint.setColor(Theme.getColor(Theme.key_divider, resourcesProvider));
                dividerPaint.setAlpha(60);
                canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), dividerPaint);
            }
        };
        content.addView(divider, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(1), 0, 10, 0, 4));

        // Action buttons row.
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        settingsButton = makeIconButton(context, R.drawable.msg_settings_old,
                LocaleController.getString(R.string.PluginsOpenSettings),
                Theme.key_windowBackgroundWhiteBlackText);
        actions.addView(settingsButton, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 24, 0));

        deleteButton = makeIconButton(context, R.drawable.msg_delete,
                LocaleController.getString(R.string.Delete),
                Theme.key_text_RedBold);
        actions.addView(deleteButton, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 24, 0, 0, 0));

        content.addView(actions, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private View makeIconButton(Context context, int iconRes, CharSequence label, int tintColorKey) {
        LinearLayout btn = new LinearLayout(context);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        // Compact vertical padding for a comfortable tap target without
        // stretching the row height. Width wraps to icon + text.
        // No selector background — keeps the button flat (no ripple dots).
        int vPad = AndroidUtilities.dp(6);
        int hPad = AndroidUtilities.dp(6);
        btn.setPadding(hPad, vPad, hPad, vPad);

        int color = Theme.getColor(tintColorKey, resourcesProvider);

        Drawable icon = context.getResources().getDrawable(iconRes).mutate();
        int sz = AndroidUtilities.dp(18);
        SimpleIconView iconView = new SimpleIconView(context, icon, sz, color);
        btn.addView(iconView, LayoutHelper.createLinear(sz, sz, 0, 0, 6, 0));

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(color);
        btn.addView(tv, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        return btn;
    }

    public void bind(UItem item) {
        String title = item.text != null ? item.text.toString() : "";
        String desc = item.subtext != null ? item.subtext.toString() : "";

        toggleCell.setTextAndCheck(title, item.checked, false);
        toggleCell.setEnabled(true);
        descView.setText(desc);
        descView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
    }

    public TextCheckCell getToggleCell() {
        return toggleCell;
    }

    public View getSettingsButton() {
        return settingsButton;
    }

    public View getDeleteButton() {
        return deleteButton;
    }

    private static class SimpleIconView extends View {
        private final Drawable drawable;
        private final int size;
        private final int color;

        SimpleIconView(Context context, Drawable drawable, int size, int color) {
            super(context);
            this.drawable = drawable;
            this.size = size;
            this.color = color;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (drawable == null) return;
            drawable.setBounds(0, 0, size, size);
            drawable.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    color, android.graphics.PorterDuff.Mode.MULTIPLY));
            drawable.draw(canvas);
        }
    }
}
