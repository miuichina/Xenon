package zxc.iconic.xenon.settings;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Settings screen for Liquid Glass, opened from Experimental Settings.
 * Contains a live {@link GlassPreviewCell}, advanced-glass sliders and a reset.
 */
public class NekoLiquidGlassSettingsActivity extends BaseNekoSettingsActivity {

    private final int useAdvancedLiquidGlassRow  = rowId++;
    private final int previewRow                 = rowId++;
    private final int advancedGlassAlphaRow      = rowId++;
    private final int advancedGlassWallpaperBlurRow = rowId++;
    private final int advancedGlassBlurRow       = rowId++;
    private final int advancedGlassFresnelRow    = rowId++;
    private final int advancedGlassDispersionRow = rowId++;
    private final int advancedGlassGlareRow      = rowId++;
    private final int advancedGlassTintPercentRow = rowId++;
    private final int liquidGlassIntensityRow    = rowId++;
    private final int liquidGlassThicknessRow    = rowId++;
    private final int resetRow                   = rowId++;

    private GlassPreviewCell previewCell;
    private FrameLayout previewContainer;

    private void ensurePreviewCreated() {
        if (previewContainer != null || getContext() == null
                || android.os.Build.VERSION.SDK_INT < 33) return;

        previewContainer = new FrameLayout(getContext());
        previewContainer.setClipToPadding(false);
        previewContainer.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        previewContainer.setMinimumHeight(GlassPreviewCell.heightPx() + AndroidUtilities.dp(16));

        previewCell = new GlassPreviewCell(getContext(), resourcesProvider);
        previewContainer.addView(previewCell,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, GlassPreviewCell.heightDp(),
                        Gravity.CENTER, 12, 0, 12, 0));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // --- Toggles (no section header here — the action bar title IS the header)
        items.add(UItem.asCheck(useAdvancedLiquidGlassRow,
                LocaleController.getString(R.string.UseAdvancedLiquidGlass),
                LocaleController.getString(R.string.UseAdvancedLiquidGlassDesc))
                .slug("useAdvancedLiquidGlass").setChecked(NekoConfig.useAdvancedLiquidGlass));
        if (NekoConfig.useAdvancedLiquidGlass) {
            items.add(UItem.asCheck(advancedGlassWallpaperBlurRow,
                    LocaleController.getString(R.string.AdvancedGlassWallpaperBlur),
                    LocaleController.getString(R.string.AdvancedGlassWallpaperBlurDesc))
                    .slug("advancedGlassWallpaperBlur")
                    .setChecked(NekoConfig.advancedGlassWallpaperBlur));
        }
        items.add(UItem.asShadow(null));

        // --- Live preview
        ensurePreviewCreated();
        if (previewContainer != null) {
            UItem pi = UItem.asCustom(previewContainer);
            pi.id = previewRow;
            items.add(pi);
            items.add(UItem.asShadow(null));
        }

        // --- Glass parameter sliders
        items.add(UItem.asHeader(LocaleController.getString(R.string.AdvancedGlassSection)));
        if (NekoConfig.useAdvancedLiquidGlass) {

            items.add(SeekbarCellFactory.of(advancedGlassBlurRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassBlur),
                            "0", "40", 0, 40,
                            progress -> {
                                int v = Math.max(0, Math.min(40, Math.round(progress)));
                                if (v != NekoConfig.advancedGlassBlur) {
                                    NekoConfig.setAdvancedGlassBlur(v);
                                    invalidatePreview();
                                }
                            }),
                    NekoConfig.advancedGlassBlur).slug("advancedGlassBlur"));
            items.add(SeekbarCellFactory.of(advancedGlassFresnelRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassRefraction),
                            "0", "200", 0, 200,
                            progress -> {
                                float v = Math.max(0f, Math.min(2f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassFresnel) > 0.001f) {
                                    NekoConfig.setAdvancedGlassFresnel(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassFresnel * 100)).slug("advancedGlassFresnel"));
            items.add(SeekbarCellFactory.of(advancedGlassDispersionRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassDispersion),
                            "0", "100", 0, 100,
                            progress -> {
                                float v = Math.max(0f, Math.min(1f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassDispersion) > 0.001f) {
                                    NekoConfig.setAdvancedGlassDispersion(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassDispersion * 100)).slug("advancedGlassDispersion"));
            items.add(SeekbarCellFactory.of(advancedGlassGlareRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassGlare),
                            "10", "200", 10, 200,
                            progress -> {
                                float v = Math.max(0.1f, Math.min(2f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassGlare) > 0.001f) {
                                    NekoConfig.setAdvancedGlassGlare(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassGlare * 100)).slug("advancedGlassGlare"));
            items.add(SeekbarCellFactory.of(advancedGlassTintPercentRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassTintPercent),
                            "0", "100", 0, 100,
                            progress -> {
                                int v = Math.max(0, Math.min(100, Math.round(progress)));
                                if (v != NekoConfig.advancedGlassTintPercent) {
                                    NekoConfig.setAdvancedGlassTintPercent(v);
                                    invalidatePreview();
                                }
                            }),
                    NekoConfig.advancedGlassTintPercent).slug("advancedGlassTintPercent"));
        } else {
            // Standard (non-advanced) sliders
            items.add(SeekbarCellFactory.of(liquidGlassIntensityRow,
                    new SeekbarConfig(LocaleController.getString(R.string.LiquidGlassIntensity),
                            "0", "150", 0, 150,
                            progress -> {
                                float v = Math.max(0f, Math.min(1.5f, progress / 100f));
                                if (Math.abs(v - NekoConfig.liquidGlassIntensity) > 0.001f) {
                                    NekoConfig.setLiquidGlassIntensity(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.liquidGlassIntensity * 100)).slug("liquidGlassIntensity"));
            items.add(SeekbarCellFactory.of(liquidGlassThicknessRow,
                    new SeekbarConfig(LocaleController.getString(R.string.LiquidGlassThickness),
                            "5", "20", 5, 20,
                            progress -> {
                                int v = Math.max(5, Math.min(20, Math.round(progress)));
                                if (v != NekoConfig.liquidGlassThickness) {
                                    NekoConfig.setLiquidGlassThickness(v);
                                    invalidatePreview();
                                }
                            }),
                    NekoConfig.liquidGlassThickness).slug("liquidGlassThickness"));
        }
        items.add(UItem.asShadow(null));

        // --- Reset
        items.add(UItem.asButton(resetRow, R.drawable.msg_reset,
                LocaleController.getString(R.string.AdvancedGlassReset)).accent().slug("advancedGlassReset"));
        items.add(UItem.asShadow(null));
    }

    private void invalidatePreview() {
        if (previewCell != null) {
            previewCell.invalidateGlass();
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        final int id = item.id;

        if (id == useAdvancedLiquidGlassRow) {
            NekoConfig.toggleUseAdvancedLiquidGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useAdvancedLiquidGlass);
            }
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            showRestartBulletin();

        } else if (id == advancedGlassWallpaperBlurRow) {
            NekoConfig.toggleAdvancedGlassWallpaperBlur();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.advancedGlassWallpaperBlur);
            }
            listView.post(this::invalidatePreview);
            showRestartBulletin();

        } else if (id == resetRow) {
            confirmReset();
        }
    }

    private void confirmReset() {
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
        b.setTitle(LocaleController.getString(R.string.AdvancedGlassReset));
        b.setMessage(LocaleController.getString(R.string.AdvancedGlassResetConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Reset), (d, w) -> {
            NekoConfig.resetAdvancedGlassToDefaults();
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.AdvancedGlassResetDone)).show();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dlg = b.create();
        dlg.show();
        dlg.redPositive();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.LiquidGlassTitle);
    }

    @Override
    protected String getKey() {
        return "liquidglass";
    }
}