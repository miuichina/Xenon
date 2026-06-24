package zxc.iconic.xenon.settings;

import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoBlurSettingsActivity extends BaseNekoSettingsActivity {

    private final int blurOverlayRow = rowId++;
    private final int replaceDialogsWithSheetRow = rowId++;
    private final int blurOverlayRadiusRow = rowId++;
    private final int blurPixelationRow = rowId++;
    private final int blurSmoothlyRow = rowId++;
    private final int blurAnimationDurationRow = rowId++;
    private final int disableBlurBsRow = rowId++;
    private final int blurOverlayRefreshRow = rowId++;
    private final int blurOverlayRefreshIntervalRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(blurOverlayRow, LocaleController.getString(R.string.BlurOverlay)).setChecked(NekoConfig.blurOverlay).slug("blurOverlay"));
        items.add(UItem.asCheck(replaceDialogsWithSheetRow, LocaleController.getString(R.string.ReplaceDialogsWithSheet)).setChecked(NekoConfig.replaceDialogsWithSheet).slug("replaceDialogsWithSheet"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.BlurSettingsHeader)));
        if (NekoConfig.blurOverlay) {
            SeekbarConfig radiusConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.BlurOverlayRadius),
                    "2", "20", 2, 20, 1,
                    progress -> NekoConfig.setBlurOverlayRadius(Math.round(progress)));
            items.add(SeekbarCellFactory.of(blurOverlayRadiusRow, radiusConfig, NekoConfig.blurOverlayRadius).slug("blurOverlayRadius"));
            SeekbarConfig pixelationConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.BlurPixelation),
                    "0", "100", 0, 100, 1,
                    progress -> NekoConfig.setBlurPixelation(Math.round(progress)));
            items.add(SeekbarCellFactory.of(blurPixelationRow, pixelationConfig, NekoConfig.blurPixelation).slug("blurPixelation"));
            items.add(UItem.asCheck(blurSmoothlyRow, LocaleController.getString(R.string.BlurSmoothly)).setChecked(NekoConfig.blurSmoothly).slug("blurSmoothly"));
            if (NekoConfig.blurSmoothly) {
                SeekbarConfig animDurationConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.BlurAnimationDuration),
                        "100", "1000", 100, 1000, 10,
                        progress -> NekoConfig.setBlurAnimationDuration(Math.round(progress / 10f) * 10));
                items.add(SeekbarCellFactory.of(blurAnimationDurationRow, animDurationConfig, NekoConfig.blurAnimationDuration).slug("blurAnimationDuration"));
            }
            items.add(UItem.asCheck(disableBlurBsRow, LocaleController.getString(R.string.DisableBlurBs)).setChecked(NekoConfig.disableBlurBs).slug("disableBlurBs"));
            items.add(UItem.asCheck(blurOverlayRefreshRow, LocaleController.getString(R.string.BlurOverlayRefresh)).setChecked(NekoConfig.blurOverlayRefresh).slug("blurOverlayRefresh"));
            if (NekoConfig.blurOverlayRefresh) {
                SeekbarConfig intervalConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.BlurOverlayRefreshInterval),
                        "1", "10", 1, 10, 1,
                        progress -> NekoConfig.setBlurOverlayRefreshInterval(Math.round(progress)));
                items.add(SeekbarCellFactory.of(blurOverlayRefreshIntervalRow, intervalConfig, NekoConfig.blurOverlayRefreshInterval).slug("blurOverlayRefreshInterval"));
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == blurOverlayRow) {
            NekoConfig.toggleBlurOverlay();
            item.checked = NekoConfig.blurOverlay;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurOverlay);
            }
            listView.adapter.update(true);
            if (NekoConfig.blurOverlay && !NekoConfig.replaceDialogsWithSheet) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                        LocaleController.getString(R.string.BlurOverlayBulletinText),
                        LocaleController.getString(R.string.BlurOverlayBulletinButton),
                        () -> {
                            NekoConfig.toggleReplaceDialogsWithSheet();
                            listView.adapter.update(true);
                        }).show();
            }
        } else if (id == replaceDialogsWithSheetRow) {
            NekoConfig.toggleReplaceDialogsWithSheet();
            item.checked = NekoConfig.replaceDialogsWithSheet;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.replaceDialogsWithSheet);
            }
        } else if (id == blurSmoothlyRow) {
            NekoConfig.toggleBlurSmoothly();
            item.checked = NekoConfig.blurSmoothly;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurSmoothly);
            }
            listView.adapter.update(true);
        } else if (id == disableBlurBsRow) {
            NekoConfig.toggleDisableBlurBs();
            item.checked = NekoConfig.disableBlurBs;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableBlurBs);
            }
        } else if (id == blurOverlayRefreshRow) {
            NekoConfig.toggleBlurOverlayRefresh();
            item.checked = NekoConfig.blurOverlayRefresh;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurOverlayRefresh);
            }
            listView.adapter.update(true);
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.BlurSettings);
    }
}
