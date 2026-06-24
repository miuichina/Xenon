package zxc.iconic.xenon.settings;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.TextAnimationEditText;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoTextAnimationSettingsActivity extends BaseNekoSettingsActivity {

    private final int textAnimationRow = rowId++;
    private final int textAnimTestInputRow = rowId++;
    private final int textAnimCursorRow = rowId++;
    private final int textAnimFadeRow = rowId++;
    private final int textAnimBlurRow = rowId++;
    private final int textAnimBlurDurationRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean animSupported = Build.VERSION.SDK_INT >= 26;
        items.add(UItem.asCheck(textAnimationRow, LocaleController.getString(R.string.TextAnimationToggle), animSupported ? null : LocaleController.getString(R.string.TextAnimationApiWarning)).slug("textAnimation").setChecked(NekoConfig.textAnimationEnabled).setEnabled(animSupported));
        if (NekoConfig.textAnimationEnabled && animSupported) {
            items.add(TextAnimationTestInputFactory.of(textAnimTestInputRow));
            SeekbarConfig cursorConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TextAnimCursor),
                    "0", "100", 0, 100,
                    progress -> NekoConfig.setTextAnimCursorSpeed(Math.round(progress)));
            items.add(SeekbarCellFactory.of(textAnimCursorRow, cursorConfig, NekoConfig.textAnimCursorSpeed).slug("textAnimCursor"));

            SeekbarConfig fadeConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TextAnimFadeDuration),
                    "50", "800", 50, 800, 5,
                    progress -> NekoConfig.setTextAnimFadeDuration(Math.round(progress / 5f) * 5));
            items.add(SeekbarCellFactory.of(textAnimFadeRow, fadeConfig, NekoConfig.textAnimFadeDuration).slug("textAnimFadeDuration"));

            SeekbarConfig blurConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TextAnimBlurStrength),
                    "0", "30", 0, 30,
                    progress -> NekoConfig.setTextAnimBlurStrength(Math.round(progress)));
            items.add(SeekbarCellFactory.of(textAnimBlurRow, blurConfig, NekoConfig.textAnimBlurStrength).slug("textAnimBlurStrength"));

            SeekbarConfig blurDurationConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TextAnimBlurDuration),
                    "50", "1000", 50, 1000, 5,
                    progress -> NekoConfig.setTextAnimBlurDuration(Math.round(progress / 5f) * 5));
            items.add(SeekbarCellFactory.of(textAnimBlurDurationRow, blurDurationConfig, NekoConfig.textAnimBlurDuration).slug("textAnimBlurDuration"));
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == textAnimTestInputRow) {
            if (view instanceof TextAnimationEditText) {
                view.requestFocus();
                AndroidUtilities.showKeyboard(view);
            }
        } else if (id == textAnimationRow) {
            if (Build.VERSION.SDK_INT >= 26) {
                NekoConfig.toggleTextAnimation();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NekoConfig.textAnimationEnabled);
                }
                listView.adapter.update(true);
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.TextAnimation);
    }

    private static class TextAnimationTestInputFactory extends UItem.UItemFactory<TextAnimationEditText> {
        static {
            setup(new TextAnimationTestInputFactory());
        }

        @Override
        public TextAnimationEditText createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            TextAnimationEditText editText = new TextAnimationEditText(context, resourcesProvider) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return super.onTouchEvent(event);
                }
            };
            editText.setHint(LocaleController.getString(R.string.TextAnimationTestHint));
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
            editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            editText.setBackground(null);
            editText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            editText.setSingleLine(false);
            editText.setMaxLines(3);
            editText.setMinHeight(AndroidUtilities.dp(48));
            editText.setFocusable(true);
            editText.setFocusableInTouchMode(true);
            return editText;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
        }

        public static UItem of(int id) {
            var item = UItem.ofFactory(TextAnimationTestInputFactory.class);
            item.id = id;
            return item;
        }
    }
}
