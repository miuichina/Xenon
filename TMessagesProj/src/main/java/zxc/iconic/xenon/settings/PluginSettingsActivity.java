package zxc.iconic.xenon.settings;

import android.view.View;

import org.luaj.vm2.LuaValue;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
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
    // Permissions section rows.
    private int permHeaderRow;
    private int permGeneralRow;
    private int permMessagingRow;

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
        // Permissions section — always shown, even before plugin settings.
        permHeaderRow = rowIdCounter++;
        permGeneralRow = rowIdCounter++;
        permMessagingRow = rowIdCounter++;
        items.add(UItem.asHeader(LocaleController.getString(R.string.PluginPermissions)));
        // GENERAL is always granted and can't be revoked — disabled, checked.
        UItem general = UItem.asCheck(permGeneralRow, LocaleController.getString(R.string.PluginScopeGeneral))
                .setChecked(true);
        general.setEnabled(false);
        items.add(general);
        boolean messaging = plugin != null
                && getPrefs().getBoolean("plugin_scope_" + (plugin.pluginId != null ? plugin.pluginId : plugin.fileName) + "_" + PluginManager.SCOPE_MESSAGING, false);
        items.add(UItem.asCheck(permMessagingRow, LocaleController.getString(R.string.PluginScopeMessaging))
                .setChecked(messaging));
        items.add(UItem.asShadow(null));

        if (plugin == null || plugin.settings.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsEmpty)));
            return;
        }
        for (int i = 0; i < plugin.settings.size(); i++) {
            PluginManager.LoadedPlugin.PluginSetting s = plugin.settings.get(i);
            int rid = rowIdCounter++;
            settingRowIds.add(rid);
            String raw = getPrefs().getString(settingKey(s.key), null);
            if (s.type == PluginManager.LoadedPlugin.PluginSetting.Type.HEADER && !items.isEmpty()) {
                items.add(UItem.asShadow(null));
            }
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
                    // Static, non-interactive label. name is the text; if a
                    // default value is set, show it as a subtitle.
                    String subtitle = s.defaultString != null && !s.defaultString.isEmpty() ? s.defaultString : null;
                    UItem textItem = subtitle != null
                            ? TextSettingsCellFactory.of(rid, s.name, subtitle)
                            : TextSettingsCellFactory.of(rid, s.name);
                    textItem.setEnabled(false);
                    items.add(textItem);
                    break;
                }
                case BUTTON: {
                    items.add(TextSettingsCellFactory.of(rid, s.name).accent());
                    break;
                }
                case LIST: {
                    int currIdx;
                    if (raw != null) {
                        try { currIdx = Integer.parseInt(raw); } catch (NumberFormatException e) { currIdx = s.defaultInt; }
                    } else {
                        currIdx = s.defaultInt;
                    }
                    String currentVal = (s.options != null && currIdx >= 0 && currIdx < s.options.length)
                            ? s.options[currIdx] : "";
                    items.add(TextSettingsCellFactory.of(rid, s.name, currentVal));
                    break;
                }
                case HEADER: {
                    items.add(UItem.asHeader(s.name));
                    break;
                }
            }
        }
        if (!items.isEmpty()) {
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (plugin == null) return;
        // Permissions section: only MESSAGING is interactive (GENERAL is locked
        // on). Toggling persists the scope flag; hasScope() reads it live on
        // every API call, so no engine reload is needed for it to take effect.
        if (item.id == permMessagingRow) {
            String key = "plugin_scope_" + (plugin.pluginId != null ? plugin.pluginId : plugin.fileName) + "_" + PluginManager.SCOPE_MESSAGING;
            boolean newVal = !getPrefs().getBoolean(key, false);
            getPrefs().edit().putBoolean(key, newVal).apply();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(newVal);
            }
            return;
        }
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
            case HEADER: {
                break;
            }
            case TEXT: {
                // Static label — not interactive.
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
            case LIST: {
                if (s.options == null || s.options.length == 0) break;
                String raw = getPrefs().getString(settingKey(s.key), null);
                int currIdx;
                if (raw != null) {
                    try { currIdx = Integer.parseInt(raw); } catch (NumberFormatException e) { currIdx = s.defaultInt; }
                } else {
                    currIdx = s.defaultInt;
                }
                if (currIdx < 0 || currIdx >= s.options.length) currIdx = 0;
                java.util.ArrayList<String> opts = new java.util.ArrayList<>();
                for (String o : s.options) opts.add(o);
                final int finalIdx = currIdx;
                zxc.iconic.xenon.helpers.PopupHelper.show(opts, s.name, finalIdx,
                        getParentActivity(), view, selectedIdx -> {
                            getPrefs().edit().putString(settingKey(s.key), String.valueOf(selectedIdx)).apply();
                            updateRows();
                        }, resourcesProvider);
                break;
            }
        }
    }

    private String settingKey(String key) {
        String prefix = plugin != null && plugin.pluginId != null ? plugin.pluginId : (plugin != null ? plugin.fileName : "unknown");
        return prefix + "_" + key;
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
        return "plugin_settings_" + (plugin != null && plugin.pluginId != null ? plugin.pluginId : (plugin != null ? plugin.fileName : "unknown"));
    }
}
