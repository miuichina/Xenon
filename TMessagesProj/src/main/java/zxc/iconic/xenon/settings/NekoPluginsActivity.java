package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.plugins.PluginManager;

public class NekoPluginsActivity extends BaseNekoSettingsActivity {

    private static final int REQUEST_CODE_INSTALL = 7001;

    private final int enableRow = rowId++;
    private final int godModeRow = rowId++;
    private final int installRow = rowId++;
    private final int pluginsHeaderRow = rowId++;
    private int nextPluginRow = rowId;

    private final Map<Integer, PluginManager.PluginInfo> pluginRows = new HashMap<>();
    private boolean firstLoad = true;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Plugins)));
        items.add(UItem.asCheck(enableRow, LocaleController.getString(R.string.PluginsEnable),
                LocaleController.getString(R.string.PluginsEnableDesc)).setChecked(NekoConfig.pluginsEnabled));
        items.add(UItem.asShadow(null));

        items.add(UItem.asCheck(godModeRow, LocaleController.getString(R.string.PluginGodMode),
                LocaleController.getString(R.string.PluginGodModeDesc)).setChecked(NekoConfig.pluginGodMode));
        items.add(UItem.asShadow(null));

        items.add(TextSettingsCellFactory.of(installRow, LocaleController.getString(R.string.PluginsInstall)).accent());
        items.add(UItem.asShadow(null));

        // Plugin list is always shown — parsed from disk, independent of whether
        // the engine is enabled. The user can toggle/remove plugins before
        // turning the engine back on.
        List<PluginManager.PluginInfo> infos = PluginManager.getInstance().getAllPluginInfos();
        if (infos.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsEmpty)));
        } else {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PluginsInstalled)));
            pluginRows.clear();
            nextPluginRow = rowId;
            int unnamedCount = 0;
            for (PluginManager.PluginInfo info : infos) {
                int rid = nextPluginRow++;
                pluginRows.put(rid, info);
                String title = info.name != null ? info.name : info.fileName + " ⚠";
                String desc = info.description != null
                        ? info.description
                        : LocaleController.getString(R.string.PluginsNoDescription);
                // Reflect the stored toggle (plugin_enabled_<fileName>) so the
                // check is accurate even with the engine off, and stays fully
                // clickable so the user can pick which plugins run after
                // re-enabling the engine.
                boolean enabled = PluginSettingsActivity.getPrefs()
                        .getBoolean("plugin_enabled_" + info.fileName, true);
                items.add(UItem.asCheck(rid, title, desc).setChecked(enabled));
                if (info.name == null) unnamedCount++;
            }
            items.add(UItem.asShadow(null));

            if (firstLoad) {
                firstLoad = false;
                int count = infos.size();
                int unnamed = unnamedCount;
                AndroidUtilities.runOnUIThread(() -> {
                    if (isFinishing()) return;
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                            LocaleController.formatString(R.string.PluginsLoadedCount, count)).show();
                }, 300);
                if (unnamed > 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isFinishing()) return;
                        BulletinFactory.of(this).createErrorBulletin(
                                LocaleController.getString(R.string.PluginsPluginNoName)).show();
                    }, 1300);
                }
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        int id = item.id;
        if (id == enableRow) {
            NekoConfig.togglePluginsEnabled();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.pluginsEnabled);
            }
            updateRows();
        } else if (id == godModeRow) {
            if (NekoConfig.pluginGodMode) {
                // Turning off is unconditional — no confirmation needed.
                NekoConfig.togglePluginGodMode();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NekoConfig.pluginGodMode);
                }
            } else {
                // Turning on is dangerous: force a 60s cooldown on the confirm
                // button so it can't be enabled by an accidental tap.
                showGodModeConfirmDialog(view);
            }
        } else if (id == installRow) {
            // Allow installing plugins regardless of the engine state. They'll
            // activate once the engine is enabled.
            launchFilePicker();
        } else if (pluginRows.containsKey(id)) {
            PluginManager.PluginInfo info = pluginRows.get(id);
            showPluginBottomSheet(info);
        }
    }

    /**
     * God Mode confirmation dialog. The OK button is disabled for 60s with a
     * live countdown, so enabling it can't be an accident; only once the timer
     * reaches zero does the button turn active and actually flip the toggle.
     */
    private void showGodModeConfirmDialog(View toggleView) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(R.string.PluginGodMode));
        builder.setMessage(LocaleController.getString(R.string.PluginGodModeWarn));
        final int[] seconds = {60};
        final AlertDialog[] dialogRef = new AlertDialog[1];
        builder.setPositiveButton(LocaleController.formatString(R.string.PluginGodModeCountdown, seconds[0]),
                (d, which) -> {
                    NekoConfig.togglePluginGodMode();
                    if (toggleView instanceof TextCheckCell) {
                        ((TextCheckCell) toggleView).setChecked(NekoConfig.pluginGodMode);
                    }
                });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialogRef[0] = dialog;
        dialog.show();
        final TextView okButton = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (okButton != null) {
            okButton.setEnabled(false);
        }
        final Handler handler = new Handler(activity.getMainLooper());
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (seconds[0] > 0) {
                    seconds[0]--;
                    if (okButton != null && seconds[0] > 0) {
                        okButton.setText(LocaleController.formatString(R.string.PluginGodModeCountdown, seconds[0]));
                    }
                    handler.postDelayed(this, 1000);
                } else if (okButton != null && dialogRef[0] != null && dialogRef[0].isShowing()) {
                    okButton.setEnabled(true);
                    okButton.setText(LocaleController.getString("OK", R.string.OK));
                }
            }
        };
        handler.postDelayed(ticker, 1000);
        // Stop the ticker if the dialog is dismissed before the timer ends.
        dialog.setOnDismissListener(d -> handler.removeCallbacks(ticker));
    }

    private void showPluginBottomSheet(PluginManager.PluginInfo info) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        final BottomSheet[] sheetRef = new BottomSheet[1];

        String titleText = info.name != null ? info.name : info.fileName;
        TextView titleView = new TextView(activity);
        titleView.setText(titleText);
        titleView.setTextSize(18);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        layout.addView(titleView);

        if (info.description != null || info.name == null) {
            TextView descView = new TextView(activity);
            String descText = info.description != null
                    ? info.description
                    : LocaleController.getString(R.string.PluginsNoDescription);
            if (info.name == null) {
                descText = LocaleController.getString(R.string.PluginsPluginNoName);
            }
            descView.setText(descText);
            descView.setTextSize(14);
            descView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            descView.setPadding(0, 0, 0, AndroidUtilities.dp(16));
            layout.addView(descView);
        }

        View separator = new View(activity);
        separator.setBackgroundColor(Theme.getColor(Theme.key_divider));
        separator.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
        layout.addView(separator);

        // Toggle writes directly to prefs, so it works even with the engine off.
        final boolean[] enabled = { PluginSettingsActivity.getPrefs().getBoolean("plugin_enabled_" + info.fileName, true) };
        TextCheckCell toggleCell = new TextCheckCell(activity);
        toggleCell.setTextAndCheck(LocaleController.getString(R.string.PluginsPluginEnabled),
                enabled[0], false);
        toggleCell.setOnClickListener(v -> {
            enabled[0] = !enabled[0];
            PluginSettingsActivity.getPrefs().edit()
                    .putBoolean("plugin_enabled_" + info.fileName, enabled[0]).apply();
            toggleCell.setChecked(enabled[0]);
            // If the engine is on, apply the change live.
            if (NekoConfig.pluginsEnabled) {
                PluginManager.getInstance().reloadAll();
            }
            updateRows();
        });
        layout.addView(toggleCell);

        // Settings button: only meaningful for an active plugin (needs Globals).
        if (NekoConfig.pluginsEnabled) {
            PluginManager.LoadedPlugin loaded = PluginManager.getInstance().findPlugin(info.fileName);
            if (loaded != null && !loaded.settings.isEmpty()) {
                int accentColorPill = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
                double luminance = (0.299 * android.graphics.Color.red(accentColorPill) + 0.587 * android.graphics.Color.green(accentColorPill) + 0.114 * android.graphics.Color.blue(accentColorPill)) / 255.0;
                int pillTextColor = luminance > 0.5 ? 0xff000000 : 0xffffffff;

                android.widget.FrameLayout settingsWrap = new android.widget.FrameLayout(activity);
                settingsWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(4));

                TextView settingsBtn = new TextView(activity);
                settingsBtn.setText(LocaleController.getString(R.string.PluginsOpenSettings));
                settingsBtn.setTextColor(pillTextColor);
                settingsBtn.setTextSize(15);
                settingsBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                settingsBtn.setGravity(android.view.Gravity.CENTER);
                settingsBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accentColorPill));
                settingsBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                settingsBtn.setElevation(AndroidUtilities.dp(2));
                settingsBtn.setOnClickListener(v -> {
                    sheetRef[0].dismiss();
                    presentFragment(new PluginSettingsActivity().setPlugin(loaded));
                });
                settingsWrap.addView(settingsBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
                layout.addView(settingsWrap);
            }
        }

        android.widget.FrameLayout deleteWrap = new android.widget.FrameLayout(activity);
        deleteWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        int redColor = Theme.getColor(Theme.key_text_RedBold, resourcesProvider);
        TextView deleteBtn = new TextView(activity);
        deleteBtn.setText(LocaleController.getString(R.string.Delete));
        deleteBtn.setTextColor(0xffffffff);
        deleteBtn.setTextSize(15);
        deleteBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        deleteBtn.setGravity(android.view.Gravity.CENTER);
        deleteBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), redColor));
        deleteBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        deleteBtn.setElevation(AndroidUtilities.dp(2));
        deleteBtn.setOnClickListener(v -> {
            PluginManager.getInstance().remove(info.fileName);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.PluginsRemoved)).show();
            sheetRef[0].dismiss();
            updateRows();
        });
        deleteWrap.addView(deleteBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(deleteWrap);

        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    private void launchFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_INSTALL);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(
                    LocaleController.getString(R.string.PluginsInstallFailed)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_INSTALL) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        installFromUri(data.getData());
    }

    private void installFromUri(Uri uri) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(() -> {
            File tempFile = null;
            String fileName = null;
            String[] meta = null;
            try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    fileName = queryFileName(uri);
                    if (fileName == null || !fileName.endsWith(PluginManager.PLUGIN_EXT)) {
                        fileName = "plugin" + PluginManager.PLUGIN_EXT;
                    }
                    tempFile = new File(activity.getCacheDir(), fileName);
                    AndroidUtilities.copyFile(is, tempFile);
                    meta = PluginManager.parseMetadata(tempFile);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File fTemp = tempFile;
            final String fName = fileName;
            final String[] fMeta = meta;
            AndroidUtilities.runOnUIThread(() -> {
                try { progressDialog.dismiss(); } catch (Exception e) { FileLog.e(e); }
                if (fTemp == null || !fTemp.exists()) {
                    BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.PluginsInstallFailed)).show();
                    return;
                }
                showInstallPreview(fTemp, fName, fMeta);
            });
        });
    }

    public static void showInstallBottomSheet(Activity activity, File pluginFile, Theme.ResourcesProvider resourcesProvider, java.util.function.Consumer<PluginManager.LoadedPlugin> onInstalled) {
        if (activity == null || pluginFile == null) return;
        String[] meta = PluginManager.parseMetadata(pluginFile);
        String fileName = pluginFile.getName();
        String pluginId = meta != null && meta.length > 2 ? meta[2] : null;
        if (pluginId == null || pluginId.isEmpty()) {
            BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                    "Plugin missing plugin_id").show();
            return;
        }
        String pluginName = meta != null && meta[0] != null ? meta[0] : (fileName != null ? fileName : "Plugin");
        boolean hasDesc = meta != null && meta[1] != null && !meta[1].isEmpty();
        String pluginDesc = hasDesc ? meta[1] : LocaleController.getString(R.string.PluginsNoDescription);
        // Check if a plugin with the same pluginId is already installed
        PluginManager.LoadedPlugin existingSameId = pluginId != null ? PluginManager.getInstance().findByPluginId(pluginId) : null;

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        // Star icon + plugin name (centered, horizontal)
        LinearLayout headerLine = new LinearLayout(activity);
        headerLine.setOrientation(LinearLayout.HORIZONTAL);
        headerLine.setGravity(android.view.Gravity.CENTER);
        headerLine.setPadding(0, 0, 0, AndroidUtilities.dp(8));

        org.telegram.ui.Components.BackupImageView iconView = new org.telegram.ui.Components.BackupImageView(activity);
        iconView.setImageResource(R.drawable.msg_fave);
        int starColor = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0xffffffff : 0xff000000;
        iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(starColor, android.graphics.PorterDuff.Mode.SRC_IN));
        int iconSize = AndroidUtilities.dp(9);
        headerLine.addView(iconView, LayoutHelper.createLinear(iconSize, iconSize, 0, 0, AndroidUtilities.dp(4), 0));

        TextView nameView = new TextView(activity);
        nameView.setText(pluginName);
        nameView.setTextSize(18);
        nameView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        headerLine.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(headerLine);

        // Plugin ID line
        if (pluginId != null) {
            TextView idView = new TextView(activity);
            String idText = pluginId;
            if (existingSameId != null) {
                idText = pluginId + " (" + LocaleController.getString(R.string.PluginsUpdate) + ")";
            }
            idView.setText(idText);
            idView.setTextSize(12);
            idView.setTextColor(existingSameId != null
                    ? Theme.getColor(Theme.key_text_RedBold, resourcesProvider)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
            idView.setGravity(android.view.Gravity.CENTER);
            idView.setPadding(0, 0, 0, AndroidUtilities.dp(12));
            layout.addView(idView);
        }

        // Description or placeholder
        TextView descView = new TextView(activity);
        descView.setText(pluginDesc);
        descView.setTextSize(14);
        descView.setTextColor(hasDesc
                ? Theme.getColor(Theme.key_dialogTextGray, resourcesProvider)
                : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        descView.setGravity(android.view.Gravity.CENTER);
        descView.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        layout.addView(descView);

        View separator = new View(activity);
        separator.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        separator.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
        layout.addView(separator);

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        double installLuminance = (0.299 * android.graphics.Color.red(accentColor) + 0.587 * android.graphics.Color.green(accentColor) + 0.114 * android.graphics.Color.blue(accentColor)) / 255.0;
        int installTextColor = installLuminance > 0.5 ? 0xff000000 : 0xffffffff;

        // Pill-shaped install button
        android.widget.FrameLayout installWrap = new android.widget.FrameLayout(activity);
        installWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(6));

        TextView installBtn = new TextView(activity);
        installBtn.setText(LocaleController.getString(R.string.PluginsInstall));
        installBtn.setTextColor(installTextColor);
        installBtn.setTextSize(15);
        installBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        installBtn.setGravity(android.view.Gravity.CENTER);
        installBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accentColor));
        installBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        installBtn.setElevation(AndroidUtilities.dp(2));

        BottomSheet[] sheetRef = new BottomSheet[1];
        installBtn.setOnClickListener(v -> {
            if (sheetRef[0] != null) sheetRef[0].dismiss();
            doInstallPluginBackground(activity, pluginFile, fileName, onInstalled);
        });
        installWrap.addView(installBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(installWrap);

        // Pill-shaped close button
        android.widget.FrameLayout closeWrap = new android.widget.FrameLayout(activity);
        closeWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        TextView closeBtn = new TextView(activity);
        closeBtn.setText(LocaleController.getString(R.string.Close));
        closeBtn.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        closeBtn.setTextSize(15);
        closeBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        closeBtn.setGravity(android.view.Gravity.CENTER);
        closeBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24),
                Theme.multAlpha(accentColor, 0.10f)));
        closeBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        closeBtn.setOnClickListener(v -> {
            if (sheetRef[0] != null) sheetRef[0].dismiss();
        });
        closeWrap.addView(closeBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(closeWrap);

        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    private static void doInstallPluginBackground(Activity activity, File tempFile, String fileName, java.util.function.Consumer<PluginManager.LoadedPlugin> onInstalled) {
        Utilities.globalQueue.postRunnable(() -> {
            PluginManager.LoadedPlugin result = null;
            try {
                File dest = new File(PluginManager.getPluginsDir(), fileName);
                if (dest.exists()) dest.delete();
                if (tempFile.renameTo(dest)) {
                    result = PluginManager.getInstance().installFrom(dest);
                } else {
                    AndroidUtilities.copyFile(tempFile, dest);
                    result = PluginManager.getInstance().installFrom(dest);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final PluginManager.LoadedPlugin installed = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (installed != null) {
                    if (onInstalled != null) {
                        onInstalled.accept(installed);
                    } else {
                        BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                                LocaleController.getString(R.string.PluginsInstallSuccess)).show();
                    }
                } else {
                    BulletinFactory.global().createErrorBulletin(
                            LocaleController.getString(R.string.PluginsInstallFailed)).show();
                }
            });
        });
    }

    private void showInstallPreview(File tempFile, String fileName, String[] meta) {
        showInstallBottomSheet(getParentActivity(), tempFile, resourcesProvider, plugin -> updateRows());
    }

    private String queryFileName(Uri uri) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getParentActivity().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    @Override
    public void onResume() {
        super.onResume();
        PluginManager.setCurrentActivity(getParentActivity());
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.Plugins);
    }

    @Override
    protected String getKey() {
        return "plugins";
    }
}
