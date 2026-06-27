package zxc.iconic.xenon.settings;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luaj.vm2.LuaValue;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.plugins.PluginManager;

public class PluginSettingsActivity extends BaseNekoSettingsActivity {

    private static PluginSettingsActivity currentInstance;

    public static void refreshCurrent() {
        if (currentInstance != null) {
            currentInstance.updateRows();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        currentInstance = this;
        PluginManager.setCurrentActivity(getParentActivity());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentInstance == this) currentInstance = null;
    }

    private PluginManager.LoadedPlugin plugin;
    private int rowIdCounter;
    private final List<Integer> settingRowIds = new ArrayList<>();

    public PluginSettingsActivity setPlugin(PluginManager.LoadedPlugin p) {
        this.plugin = p;
        return this;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        rowIdCounter = rowId;
        settingRowIds.clear();
        if (plugin != null) {
            plugin.invokeHook("onSettingsOpen", new LuaValue[0]);
            plugin.refreshSettingsFromGlobals();
        }
        if (plugin == null || plugin.settings.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsEmpty)));
            return;
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.PluginsSettings)));
        for (int i = 0; i < plugin.settings.size(); i++) {
            PluginManager.LoadedPlugin.PluginSetting s = plugin.settings.get(i);
            int rid = rowIdCounter++;
            settingRowIds.add(rid);
            String raw = getPrefs().getString(settingKey(s.key), null);
            switch (s.type) {
                case TOGGLE: {
                    boolean val;
                    if (raw != null) {
                        val = "true".equals(raw) || "1".equals(raw);
                    } else {
                        val = s.defaultBool;
                    }
                    items.add(UItem.asCheck(rid, s.name).setChecked(val));
                    break;
                }
                case SEEKBAR: {
                    int curr;
                    if (raw != null) {
                        try { curr = Integer.parseInt(raw); } catch (NumberFormatException e) { curr = s.defaultInt; }
                    } else {
                        curr = s.defaultInt;
                    }
                    SeekbarConfig config = new SeekbarConfig(
                            s.name,
                            String.valueOf(s.min), String.valueOf(s.max),
                            s.min, s.max, s.step,
                            progress -> {
                                int v = Math.round(progress / s.step) * s.step;
                                v = Math.max(s.min, Math.min(s.max, v));
                                getPrefs().edit().putString(settingKey(s.key), String.valueOf(v)).apply();
                            }
                    );
                    items.add(SeekbarCellFactory.of(rid, config, curr));
                    break;
                }
                case TEXT: {
                    String curr = raw != null ? raw : s.defaultString;
                    if (curr == null) curr = "";
                    if (curr.isEmpty()) {
                        items.add(TextSettingsCellFactory.of(rid, s.name));
                    } else {
                        items.add(TextSettingsCellFactory.of(rid, s.name, curr));
                    }
                    break;
                }
                case BUTTON: {
                    items.add(TextSettingsCellFactory.of(rid, s.name).accent());
                    break;
                }
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (plugin == null) return;
        int idx = settingRowIds.indexOf(item.id);
        if (idx < 0 || idx >= plugin.settings.size()) return;
        PluginManager.LoadedPlugin.PluginSetting s = plugin.settings.get(idx);
        switch (s.type) {
            case TOGGLE: {
                String raw = getPrefs().getString(settingKey(s.key), null);
                boolean oldVal = raw != null ? ("true".equals(raw) || "1".equals(raw)) : s.defaultBool;
                boolean newVal = !oldVal;
                getPrefs().edit().putString(settingKey(s.key), String.valueOf(newVal)).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(newVal);
                }
                updateRows();
                break;
            }
            case TEXT: {
                showTextEditDialog(s);
                break;
            }
            case BUTTON: {
                if (s.action != null) {
                    try {
                        s.action.call();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                if (PluginManager.checkRequestFinishFragment()) {
                    finishFragment();
                } else {
                    updateRows();
                }
                break;
            }
        }
    }

    private void showTextEditDialog(PluginManager.LoadedPlugin.PluginSetting s) {
        Context ctx = getParentActivity();
        if (ctx == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, resourcesProvider);
        builder.setTitle(s.name);
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);
        EditTextBoldCursor editText = new EditTextBoldCursor(ctx);
        editText.setTextSize(16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        String curr = getPrefs().getString(settingKey(s.key), s.defaultString);
        if (curr == null) curr = "";
        editText.setText(curr);
        editText.setSelection(editText.getText().length());
        if (s.hint != null && !s.hint.isEmpty()) {
            editText.setHint(s.hint);
            editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
        }
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);
        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
            String val = editText.getText().toString();
            getPrefs().edit().putString(settingKey(s.key), val).apply();
            updateRows();
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        AlertDialog dialog = builder.show();
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private String settingKey(String key) {
        return key;
    }

    static android.content.SharedPreferences getPrefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("xenon_plugins", android.content.Context.MODE_PRIVATE);
    }

    @Override
    protected String getActionBarTitle() {
        return plugin != null && plugin.name != null
                ? plugin.name
                : LocaleController.getString(R.string.PluginsSettings);
    }

    @Override
    protected String getKey() {
        return "plugin_settings_" + (plugin != null ? plugin.fileName : "unknown");
    }
}
