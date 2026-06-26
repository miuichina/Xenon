package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import zxc.iconic.xenon.NekoConfig;

/**
 * Core of the Xenon plugin engine.
 *
 * <p>Plugins are plain Lua scripts stored as {@code *.xplugin} files inside
 * {@code /xenonplugins/} in app internal storage. Each plugin runs in its own
 * luaj {@link Globals} (sandboxed), with a {@link PluginApi} bound as the global
 * {@code xenon} table. Plugins register handlers for hooks (e.g. {@code onResume},
 * {@code onSendMessage}) by calling {@code xenon.on(...)}.
 *
 * <p>Host code triggers hooks via {@link #fire(String, LuaValue...)} and the
 * specialized helpers ({@link #fireBooleanResult}).
 *
 * <p>Thread-safety: plugin handlers can be invoked from arbitrary threads.
 * luaj Globals are not strictly thread-safe, so each plugin keeps its own
 * Globals and is never shared between concurrent fires. Hook dispatch itself
 * iterates a {@link CopyOnWriteArrayList}, so add/remove during fire is safe.
 */
public class PluginManager {

    public static final String TAG = "XenonPlugin";
    public static final String PLUGINS_DIR = "xenonplugins";
    public static final String PLUGIN_EXT = ".xplugin";

    private static volatile PluginManager instance;
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static volatile long currentDialogId;
    private static volatile boolean requestFinishFragment;

    public static void setRequestFinishFragment(boolean v) {
        requestFinishFragment = v;
    }

    public static boolean checkRequestFinishFragment() {
        boolean v = requestFinishFragment;
        requestFinishFragment = false;
        return v;
    }

    public static void setCurrentActivity(Activity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static Activity getCurrentActivity() {
        return currentActivity.get();
    }

    public static void setCurrentDialogId(long dialogId) {
        currentDialogId = dialogId;
    }

    public static long getCurrentDialogId() {
        return currentDialogId;
    }

    private final CopyOnWriteArrayList<LoadedPlugin> plugins = new CopyOnWriteArrayList<>();
    private volatile boolean initialized;

    private PluginManager() {
    }

    private void ensureLoaded() {
        if (initialized && isEnabled()) return;
        initialized = true;
        Log.d(TAG, "ensureLoaded: pluginsEnabled=" + isEnabled());
        if (isEnabled()) {
            reloadAll();
        }
    }

    public static PluginManager getInstance() {
        if (instance == null) {
            synchronized (PluginManager.class) {
                if (instance == null) {
                    instance = new PluginManager();
                }
            }
        }
        return instance;
    }

    public static File getPluginsDir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), PLUGINS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * @return unmodifiable snapshot of currently loaded plugins.
     */
    public List<LoadedPlugin> getPlugins() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    public LoadedPlugin findPlugin(String fileName) {
        ensureLoaded();
        for (LoadedPlugin p : plugins) {
            if (p.fileName.equals(fileName)) return p;
        }
        return null;
    }

    public LoadedPlugin findByPluginId(String pluginId) {
        if (pluginId == null) return null;
        ensureLoaded();
        for (LoadedPlugin p : plugins) {
            if (pluginId.equals(p.pluginId)) return p;
        }
        return null;
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("xenon_plugins", Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return NekoConfig.pluginsEnabled;
    }

    /**
     * Called by {@link NekoConfig#togglePluginsEnabled()} whenever the global
     * plugins toggle flips. Reacts immediately: loads plugins when enabled,
     * unloads them all when disabled (no zombie Globals kept around).
     */
    public void onEnabledChanged() {
        Log.d(TAG, "onEnabledChanged: " + isEnabled());
        if (isEnabled()) {
            reloadAll();
        } else {
            Log.d(TAG, "onEnabledChanged: clearing plugins");
            plugins.clear();
        }
    }

    /**
     * Reload all {@code *.xplugin} files from the plugins directory. Clears any
     * previously loaded plugins first. Safe to call repeatedly (e.g. after the
     * user adds/removes a plugin). No-op when plugins are disabled.
     */
    public void reloadAll() {
        plugins.clear();
        if (!isEnabled()) {
            Log.d(TAG, "reloadAll: plugins disabled, skipping");
            return;
        }
        File dir = getPluginsDir();
        Log.d(TAG, "reloadAll: scanning " + dir.getAbsolutePath());
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        if (files == null || files.length == 0) {
            Log.d(TAG, "reloadAll: no .xplugin files found in " + dir.getAbsolutePath());
            return;
        }
        Log.d(TAG, "reloadAll: found " + files.length + " plugin file(s)");
        for (File file : files) {
            LoadedPlugin plugin = loadFile(file);
            if (plugin != null) {
                plugins.add(plugin);
                Log.d(TAG, "reloadAll: loaded plugin " + plugin.displayName);
            } else {
                Log.e(TAG, "reloadAll: failed to load plugin from " + file.getName());
            }
        }
        Log.d(TAG, "reloadAll: done, " + plugins.size() + " plugin(s) loaded");
    }

    /**
     * Install a plugin from an arbitrary input file (e.g. picked via the system
     * file picker). Copies it into the plugins directory and loads it. Returns
     * the installed plugin, or {@code null} on failure.
     */
    public LoadedPlugin installFrom(File source) {
        if (source == null || !source.exists()) {
            Log.e(TAG, "installFrom: source file is null or doesn't exist");
            return null;
        }
        Log.d(TAG, "installFrom: copying " + source.getName());
        // Reject plugins without plugin_id
        String[] meta = parseMetadata(source);
        if (meta == null || meta.length < 3 || meta[2] == null || meta[2].isEmpty()) {
            Log.e(TAG, "installFrom: plugin must have a plugin_id (format: something_something)");
            return null;
        }
        File dest = new File(getPluginsDir(), source.getName());
        if (!dest.getName().endsWith(PLUGIN_EXT)) {
            Log.e(TAG, "installFrom: " + dest.getName() + " doesn't end with " + PLUGIN_EXT);
            return null;
        }
        // If source is already inside the plugins dir, copy would truncate itself
        if (source.getAbsolutePath().equals(dest.getAbsolutePath())) {
            Log.d(TAG, "installFrom: source already in plugins dir, loading directly");
            LoadedPlugin plugin = loadFile(dest);
            if (plugin != null) {
                removeByPluginId(plugin.pluginId);
                plugins.add(plugin);
                Log.d(TAG, "installFrom: plugin " + plugin.displayName + " installed and loaded");
            }
            return plugin;
        }
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            Log.d(TAG, "installFrom: copied to " + dest.getAbsolutePath());
        } catch (IOException e) {
            FileLog.e(e);
            Log.e(TAG, "installFrom: copy failed - " + e.getMessage());
            return null;
        }
        LoadedPlugin plugin = loadFile(dest);
        if (plugin != null) {
            removeByPluginId(plugin.pluginId);
            plugins.add(plugin);
            Log.d(TAG, "installFrom: plugin " + plugin.displayName + " installed and loaded");
        } else {
            Log.e(TAG, "installFrom: plugin file copied but loading failed: " + dest.getName());
        }
        return plugin;
    }

    private void removeByPluginId(String pluginId) {
        if (pluginId == null) return;
        for (int i = 0; i < plugins.size(); i++) {
            LoadedPlugin p = plugins.get(i);
            if (pluginId.equals(p.pluginId)) {
                plugins.remove(i);
                File oldFile = new File(getPluginsDir(), p.fileName);
                if (oldFile.exists()) oldFile.delete();
                Log.d(TAG, "removeByPluginId: removed " + p.fileName + " (id=" + pluginId + ")");
                break;
            }
        }
    }

    /**
     * Remove a plugin by its file name. Unloads it from memory and deletes the
     * underlying {@code .xplugin} file.
     */
    public boolean remove(String fileName) {
        boolean removed = false;
        for (int i = 0; i < plugins.size(); i++) {
            LoadedPlugin p = plugins.get(i);
            if (p.fileName.equals(fileName)) {
                plugins.remove(i);
                removed = true;
                break;
            }
        }
        File file = new File(getPluginsDir(), fileName);
        if (file.exists()) {
            file.delete();
            Log.d(TAG, "remove: deleted file " + fileName);
        }
        Log.d(TAG, "remove: " + fileName + " removed=" + removed);
        return removed;
    }

    private LoadedPlugin loadFile(File file) {
        Log.d(TAG, "loadFile: loading " + file.getName() + " (" + file.length() + " bytes)");
        String source;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            byte[] full = baos.toByteArray();
            source = full.length > 0 ? new String(full, "UTF-8") : "";
            Log.d(TAG, "loadFile: content (" + full.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "loadFile: error reading file: " + e.getMessage());
            return null;
        }
        Globals globals = JsePlatform.standardGlobals();
        PluginApi.setCurrentPluginFileName(file.getName());
        LuaTable[] tables = PluginApi.createApiTable(globals);
        globals.set("xenon", tables[0]);
        LuaTable hooks = tables[1];
        Log.d(TAG, "loadFile: hooks@" + System.identityHashCode(hooks) + " initialSize=" + hooks.length());
        try {
            globals.load(source, file.getName(), globals).call();
            Log.d(TAG, "loadFile: " + file.getName() + " executed, hooks@" + System.identityHashCode(hooks) + " size=" + hooks.length());
            LuaValue[] hookKeys = hooks.keys();
            if (hookKeys.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (LuaValue k : hookKeys) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(k.tojstring());
                }
                Log.d(TAG, "loadFile: registered hooks: [" + sb + "]");
            } else {
                Log.w(TAG, "loadFile: no hooks registered by " + file.getName());
            }
        } catch (LuaError e) {
            FileLog.e("Failed to load plugin " + file.getName(), e);
            Log.e(TAG, "loadFile: " + file.getName() + " failed - " + e.getMessage());
            return null;
        }
        // Read metadata from Lua globals
        String pluginName = null;
        String pluginDesc = null;
        String pluginId = null;
        LuaValue nameVal = globals.get("plugin_name");
        if (!nameVal.isnil()) {
            pluginName = nameVal.tojstring();
            if (pluginName.trim().isEmpty()) pluginName = null;
        }
        LuaValue descVal = globals.get("plugin_description");
        if (!descVal.isnil()) {
            pluginDesc = descVal.tojstring();
            if (pluginDesc.trim().isEmpty()) pluginDesc = null;
        }
        LuaValue idVal = globals.get("plugin_id");
        if (!idVal.isnil()) {
            String s = idVal.tojstring().trim();
            if (!s.isEmpty()) pluginId = s;
        }
        // Read settings from Lua globals
        List<LoadedPlugin.PluginSetting> settings = new ArrayList<>();
        LuaValue settingsVal = globals.get("plugin_settings");
        if (settingsVal.istable()) {
            for (int i = 1; i <= settingsVal.length(); i++) {
                LuaValue entry = settingsVal.get(i);
                if (!entry.istable()) continue;
                    try {
                        LuaValue typeVal = entry.get("type");
                        LuaValue keyVal = entry.get("key");
                        LuaValue nameEntry = entry.get("name");
                        if (typeVal.isnil() || keyVal.isnil() || nameEntry.isnil()) continue;
                        String type = typeVal.tojstring();
                        String skey = keyVal.tojstring();
                        String sname = nameEntry.tojstring();
                    LoadedPlugin.PluginSetting setting = null;
                    switch (type) {
                        case "toggle": {
                            boolean def = entry.get("default").toboolean();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.TOGGLE, skey, sname, def, 0, null, 0, 0, 0, null, null);
                            break;
                        }
                        case "seekbar": {
                            int min = (int) entry.get("min").checkdouble();
                            int max = (int) entry.get("max").checkdouble();
                            int step = (int) entry.get("step").checkdouble();
                            if (step <= 0) step = 1;
                            int def = entry.get("default").isnil() ? min : (int) entry.get("default").checkdouble();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.SEEKBAR, skey, sname, false, def, null, min, max, step, null, null);
                            break;
                        }
                        case "text": {
                            LuaValue defVal = entry.get("default");
                            String def = defVal.isnil() ? "" : defVal.tojstring();
                            LuaValue hintVal = entry.get("hint");
                            String hint = hintVal.isnil() ? "" : hintVal.tojstring();
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.TEXT, skey, sname, false, 0, def, 0, 0, 0, hint, null);
                            break;
                        }
                        case "button": {
                            LuaValue action = entry.get("action");
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.BUTTON, skey, sname, false, 0, null, 0, 0, 0, null, action != null && !action.isnil() ? action : null);
                            break;
                        }
                    }
                    if (setting != null) {
                        settings.add(setting);
                    }
                } catch (Exception e) {
                    FileLog.e("Failed to parse plugin setting entry " + i + " in " + file.getName(), e);
                }
            }
        }
        return new LoadedPlugin(file.getName(), pluginName, pluginDesc, pluginId, settings, globals, hooks);
    }

    // ------------------------------------------------------------------
    // Hook dispatch
    // ------------------------------------------------------------------

    /**
     * Fire a hook with no expected return value. Calls every registered handler
     * for {@code hookName}, passing the given Lua args. Exceptions in a single
     * plugin are logged and do not stop other plugins or the host.
     */
    public void fire(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fire(" + hookName + "): plugins disabled");
            return;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fire(" + hookName + "): no plugins loaded");
            return;
        }
        Log.d(TAG, "fire(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) {
                Log.d(TAG, "fire(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                continue;
            }
            try {
                plugin.invokeHook(hookName, args);
            } catch (LuaError e) {
                FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                Log.e(TAG, "fire(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Fire a hook and return the first handler's result. Iterates plugins in
     * order; the first handler that returns a non-{@code nil} value wins and
     * its {@link LuaValue} is returned. Handlers returning {@code nil} are
     * skipped, so plugins can observe without deciding.
     */
    public LuaValue fireReturn(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fireReturn(" + hookName + "): plugins disabled");
            return null;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fireReturn(" + hookName + "): no plugins loaded");
            return null;
        }
        Log.d(TAG, "fireReturn(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) {
                Log.d(TAG, "fireReturn(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                continue;
            }
            try {
                LuaValue res = plugin.invokeHookReturn(hookName, args);
                if (res != null && !res.isnil()) {
                    Log.d(TAG, "fireReturn(" + hookName + "): " + plugin.fileName + " returned a value");
                    return res;
                }
            } catch (LuaError e) {
                FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                Log.e(TAG, "fireReturn(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
            }
        }
        Log.d(TAG, "fireReturn(" + hookName + "): no plugin returned a value");
        return null;
    }

    /**
     * Fire a hook whose handler returns a boolean decision. Each handler is
     * asked in turn; if any returns {@code true} (or a Lua truthy value) the
     * result becomes {@code true}. Useful for "should I block?" style hooks.
     */
    public boolean fireBooleanResult(String hookName, LuaValue... args) {
        ensureLoaded();
        if (!isEnabled()) {
            Log.d(TAG, "fireBooleanResult(" + hookName + "): plugins disabled");
            return false;
        }
        if (plugins.isEmpty()) {
            Log.d(TAG, "fireBooleanResult(" + hookName + "): no plugins loaded");
            return false;
        }
        Log.d(TAG, "fireBooleanResult(" + hookName + "): firing on " + plugins.size() + " plugin(s)");
        for (LoadedPlugin plugin : plugins) {
            if (!plugin.isEnabled()) {
                Log.d(TAG, "fireBooleanResult(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                continue;
            }
            try {
                LuaValue res = plugin.invokeHookReturn(hookName, args);
                if (res != null && res.toboolean()) {
                    Log.d(TAG, "fireBooleanResult(" + hookName + "): " + plugin.fileName + " returned true");
                    return true;
                }
            } catch (LuaError e) {
                FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                Log.e(TAG, "fireBooleanResult(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Quickly parse a .xplugin file's metadata (name, description, pluginId) without
     * keeping the plugin loaded. Returns an array of three strings: [name, description, pluginId],
     * or null if parsing fails.
     */
    public static String[] parseMetadata(File file) {
        if (file == null || !file.exists() || !file.getName().endsWith(PLUGIN_EXT)) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            String source = new String(baos.toByteArray(), "UTF-8");
            Globals g = JsePlatform.standardGlobals();
            // Set up a mock xenon table so plugins that call xenon.on() etc.
            // at the top level don't crash during metadata parsing.
            LuaTable mockXenon = new LuaTable();
            mockXenon.set("on", new org.luaj.vm2.lib.TwoArgFunction() {
                public LuaValue call(LuaValue name, LuaValue handler) { return LuaValue.NIL; }
            });
            mockXenon.set("getSetting", new org.luaj.vm2.lib.TwoArgFunction() {
                public LuaValue call(LuaValue key, LuaValue def) { return LuaValue.NIL; }
            });
            mockXenon.set("setSetting", new org.luaj.vm2.lib.TwoArgFunction() {
                public LuaValue call(LuaValue key, LuaValue value) { return LuaValue.NIL; }
            });
            mockXenon.set("toast", new org.luaj.vm2.lib.OneArgFunction() {
                public LuaValue call(LuaValue arg) { return LuaValue.NIL; }
            });
            mockXenon.set("bulletin", new org.luaj.vm2.lib.OneArgFunction() {
                public LuaValue call(LuaValue arg) { return LuaValue.NIL; }
            });
            mockXenon.set("finish", new org.luaj.vm2.lib.ZeroArgFunction() {
                public LuaValue call() { return LuaValue.NIL; }
            });
            mockXenon.set("openPluginSettings", new org.luaj.vm2.lib.ZeroArgFunction() {
                public LuaValue call() { return LuaValue.NIL; }
            });
            g.set("xenon", mockXenon);
            g.load(source, file.getName(), g).call();
            String name = null;
            String desc = null;
            String id = null;
            LuaValue nv = g.get("plugin_name");
            if (!nv.isnil()) {
                String s = nv.tojstring().trim();
                if (!s.isEmpty()) name = s;
            }
            LuaValue dv = g.get("plugin_description");
            if (!dv.isnil()) {
                String s = dv.tojstring().trim();
                if (!s.isEmpty()) desc = s;
            }
            LuaValue idv = g.get("plugin_id");
            if (!idv.isnil()) {
                String s = idv.tojstring().trim();
                if (!s.isEmpty()) id = s;
            }
            return new String[]{name, desc, id};
        } catch (Exception e) {
            FileLog.e(e);
            Log.e(TAG, "parseMetadata: failed for " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * A loaded plugin: its file name plus the Lua globals it runs in.
     */
    public static class LoadedPlugin {
        public final String fileName;
        public final String displayName;
        public final String name;
        public final String description;
        public final String pluginId;
        public final List<PluginSetting> settings;
        private final Globals globals;
        private final LuaTable hooks;

        LoadedPlugin(String fileName, String name, String description, String pluginId, List<PluginSetting> settings, Globals globals, LuaTable hooks) {
            this.fileName = fileName;
            this.globals = globals;
            this.hooks = hooks;
            String disp = fileName;
            if (disp.endsWith(PLUGIN_EXT)) {
                disp = disp.substring(0, disp.length() - PLUGIN_EXT.length());
            }
            this.displayName = disp;
            this.name = name;
            this.description = description;
            this.pluginId = pluginId;
            this.settings = settings;
        }

        public static class PluginSetting {
            public enum Type { TOGGLE, SEEKBAR, TEXT, BUTTON }
            public final Type type;
            public final String key;
            public final String name;
            public final boolean defaultBool;
            public final int defaultInt;
            public final String defaultString;
            public final int min, max, step;
            public final String hint;
            public final LuaValue action;

            PluginSetting(Type type, String key, String name, boolean defaultBool, int defaultInt, String defaultString, int min, int max, int step, String hint, LuaValue action) {
                this.type = type; this.key = key; this.name = name;
                this.defaultBool = defaultBool; this.defaultInt = defaultInt; this.defaultString = defaultString;
                this.min = min; this.max = max; this.step = step;
                this.hint = hint; this.action = action;
            }
        }

        public boolean isEnabled() {
            return PluginManager.getPrefs().getBoolean("plugin_enabled_" + fileName, true);
        }

        public void setEnabled(boolean enabled) {
            PluginManager.getPrefs().edit().putBoolean("plugin_enabled_" + fileName, enabled).apply();
        }

        void invokeHook(String hookName, LuaValue[] args) {
            LuaValue handler = hooks.get(hookName);
            if (handler.isnil()) {
                Log.d(TAG, displayName + ": no handler for hook '" + hookName + "' (hooks table has " + hooks.keys().length + " entries)");
                return;
            }
            Log.d(TAG, displayName + ": invoking hook '" + hookName + "'");
            handler.invoke(args);
            Log.d(TAG, displayName + ": hook '" + hookName + "' completed");
        }

        LuaValue invokeHookReturn(String hookName, LuaValue[] args) {
            LuaValue handler = hooks.get(hookName);
            if (handler.isnil()) {
                Log.d(TAG, displayName + ": no handler for hook '" + hookName + "'");
                return null;
            }
            Log.d(TAG, displayName + ": invoking return-hook '" + hookName + "'");
            LuaValue result = handler.invoke(LuaValue.varargsOf(args)).arg1();
            Log.d(TAG, displayName + ": return-hook '" + hookName + "' returned " + (result.isnil() ? "nil" : result.toString()));
            return result;
        }
    }
}
