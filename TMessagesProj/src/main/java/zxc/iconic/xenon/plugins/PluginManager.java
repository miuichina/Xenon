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

    // ------------------------------------------------------------------
    // Global engine watchdog
    // ------------------------------------------------------------------

    /**
     * If any hook has been running for longer than this without returning, the
     * engine assumes the UI thread is wedged and force-restarts the process so
     * the next launch boots clean (with plugins disabled by the boot guard).
     * This is a last resort — the per-hook timeout (for fire-and-forget hooks)
     * and Throwable catch (for result hooks) handle most cases. But a plugin
     * that blocks the UI thread (e.g. a synchronous onSendMessage that loops)
     * can't be interrupted from Java, so the only recovery is to kill the
     * process and let Safe Mode take over.
     */
    private static final long ENGINE_WATCHDOG_TIMEOUT_MS = 10_000;
    private static volatile long hookStartTimestamp;
    private static volatile String hookInProgress;
    private static Thread watchdogThread;

    private static void startWatchdogIfNeeded() {
        if (watchdogThread != null && watchdogThread.isAlive()) return;
        hookStartTimestamp = System.currentTimeMillis();
        watchdogThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (hookStartTimestamp == 0) continue;
                long elapsed = System.currentTimeMillis() - hookStartTimestamp;
                if (elapsed > ENGINE_WATCHDOG_TIMEOUT_MS) {
                    String hook = hookInProgress;
                    org.telegram.messenger.FileLog.e("Plugin engine watchdog: hook '"
                            + hook + "' has been running for " + elapsed
                            + "ms — killing process to recover");
                    // Try to write a crash note first (best-effort).
                    try {
                        PluginSafeMode.reportPluginFailure(getCurrentActivity(),
                                "unknown", "watchdog: hook '" + hook + "' exceeded "
                                        + ENGINE_WATCHDOG_TIMEOUT_MS + "ms",
                                new java.util.concurrent.TimeoutException(
                                        "Plugin engine watchdog killed the process: hook '"
                                                + hook + "' did not return in "
                                                + ENGINE_WATCHDOG_TIMEOUT_MS + "ms"));
                    } catch (Throwable ignored) {
                    }
                    // Nuclear option: kill the process. Boot guard will disable
                    // plugins on the next launch and show the Safe Mode sheet.
                    System.exit(2);
                }
            }
        }, "XenonPluginWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /** Mark that a hook is about to run (called by fire/fireReturn). */
    private static void markHookStart(String hookName) {
        hookInProgress = hookName;
        hookStartTimestamp = System.currentTimeMillis();
        startWatchdogIfNeeded();
    }

    /** Mark that a hook finished (called by fire/fireReturn). */
    private static void markHookEnd() {
        hookStartTimestamp = 0;
        hookInProgress = null;
    }

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

    /**
     * Lightweight metadata for every {@code .xplugin} file on disk, regardless
     * of whether the engine is enabled or the plugin is active. Used by the UI
     * so the plugins list is always visible (even with the engine off), letting
     * the user toggle/remove plugins before re-enabling the engine.
     */
    public static class PluginInfo {
        public final String fileName;
        public final String name;
        public final String description;
        public final String pluginId;
        public final String author;
        public final String version;
        public final boolean active;

        public PluginInfo(String fileName, String name, String description, String pluginId, String author, String version, boolean active) {
            this.fileName = fileName;
            this.name = name;
            this.description = description;
            this.pluginId = pluginId;
            this.author = author;
            this.version = version;
            this.active = active;
        }
    }

    /**
     * All installed plugins as {@link PluginInfo}, parsed from disk without
     * executing any Lua. Works whether or not the engine is enabled, because the
     * user must be able to manage plugins while the engine is off.
     */
    public List<PluginInfo> getAllPluginInfos() {
        File dir = getPluginsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(PLUGIN_EXT));
        List<PluginInfo> result = new ArrayList<>();
        if (files == null) return result;
        for (File file : files) {
            String[] meta = parseMetadata(file);
            String name = meta != null && meta[0] != null ? meta[0] : null;
            String desc = meta != null && meta[1] != null ? meta[1] : null;
            String id = meta != null && meta[2] != null ? meta[2] : null;
            String author = meta != null && meta[3] != null ? meta[3] : null;
            String version = meta != null && meta[4] != null ? meta[4] : null;
            boolean active = false;
            if (isEnabled()) {
                for (LoadedPlugin p : plugins) {
                    if (p.fileName.equals(file.getName())) {
                        active = p.isEnabled();
                        break;
                    }
                }
            }
            result.add(new PluginInfo(file.getName(), name, desc, id, author, version, active));
        }
        return result;
    }

    public LoadedPlugin findPlugin(String fileName) {
        ensureLoaded();
        for (LoadedPlugin p : plugins) {
            if (p.fileName.equals(fileName)) return p;
        }
        return null;
    }

    /**
     * Load a plugin on demand for editing its settings, even when the plugin is
     * disabled or the engine is off. The plugin's Lua runs once (to build its
     * settings schema), but it is NOT added to the active plugins list — so it
     * won't receive hooks until properly enabled. Returns null if loading failed.
     */
    public LoadedPlugin loadPluginForSettings(String fileName) {
        File dir = getPluginsDir();
        File file = new File(dir, fileName);
        if (!file.exists()) return null;
        try {
            return loadFile(file);
        } catch (Throwable t) {
            FileLog.e("loadPluginForSettings failed for " + fileName, t);
            Log.e(TAG, "loadPluginForSettings: " + fileName + " threw: " + t.getMessage());
            return null;
        }
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
            Log.d(TAG, "onEnabledChanged: stopping all plugin code");
            PluginApi.stopAll();
            plugins.clear();
        }
    }

    /**
     * Reload all {@code *.xplugin} files from the plugins directory. Clears any
     * previously loaded plugins first. Safe to call repeatedly (e.g. after the
     * user adds/removes a plugin). No-op when plugins are disabled.
     */
    public void reloadAll() {
        PluginApi.stopAll();
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
            // Skip plugins the user has toggled off. We still show them in the
            // UI (via getAllPluginInfos, which reads the file without executing),
            // but their Lua code doesn't run and their hooks aren't registered.
            if (!getPrefs().getBoolean("plugin_enabled_" + file.getName(), true)) {
                Log.d(TAG, "reloadAll: skipping disabled plugin " + file.getName());
                continue;
            }
            LoadedPlugin plugin = null;
            try {
                plugin = loadFile(file);
            } catch (Throwable t) {
                // Catch EVERYTHING — Error (StackOverflowError, OutOfMemoryError),
                // RuntimeException, anything. One broken plugin's top-level code
                // must never take the whole app down. Quarantine it and move on.
                FileLog.e("Plugin " + file.getName() + " crashed during load", t);
                Log.e(TAG, "reloadAll: plugin " + file.getName() + " threw during load: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                quarantineFile(file.getName(), "loading", t);
                continue;
            }
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
            String err = lastParseError;
            if (err != null) {
                Log.e(TAG, "installFrom: plugin rejected: " + err);
            } else {
                Log.e(TAG, "installFrom: plugin must have a plugin_id (format: something_something)");
            }
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
                PluginApi.stopAllForPlugin(p.fileName);
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
        PluginApi.stopAllForPlugin(fileName);
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
        String pluginAuthor = null;
        LuaValue authorVal = globals.get("plugin_author");
        if (!authorVal.isnil()) {
            pluginAuthor = authorVal.tojstring().trim();
            if (pluginAuthor.isEmpty()) pluginAuthor = null;
        }
        String pluginVersion = null;
        LuaValue versionVal = globals.get("plugin_version");
        if (!versionVal.isnil()) {
            pluginVersion = sanitizeVersion(versionVal.tojstring());
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
                        case "list": {
                            // Parse the options array: { "Option A", "Option B", ... }
                            LuaValue optsVal = entry.get("options");
                            java.util.List<String> opts = new java.util.ArrayList<>();
                            if (optsVal.istable()) {
                                for (int oi = 1; oi <= optsVal.length(); oi++) {
                                    LuaValue v = optsVal.get(oi);
                                    if (!v.isnil()) opts.add(v.tojstring());
                                }
                            }
                            String[] optionsArr = opts.toArray(new String[0]);
                            // default is the index (0-based) of the selected option
                            LuaValue defVal = entry.get("default");
                            int defIdx = defVal.isnil() ? 0 : defVal.toint();
                            if (defIdx < 0) defIdx = 0;
                            if (optionsArr.length > 0 && defIdx >= optionsArr.length) defIdx = 0;
                            setting = new LoadedPlugin.PluginSetting(LoadedPlugin.PluginSetting.Type.LIST, skey, sname, false, defIdx, null, 0, 0, 0, null, null, optionsArr);
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
        return new LoadedPlugin(file.getName(), pluginName, pluginDesc, pluginId, pluginAuthor, pluginVersion, settings, globals, hooks);
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
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fire(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                // Run on a guarded executor with a timeout, so a plugin that loops
                // forever (while true do end) can't hang the calling thread (often
                // the UI thread, e.g. onResume). We can't hard-kill the Lua thread
                // (Java has no safe Thread.stop), but on timeout we flag the plugin
                // as misbehaving, disable it, and rebuild — which is enough to keep
                // the app responsive. Only applies to fire-and-forget hooks;
                // synchronous result hooks (fireReturn) are left intact.
                try {
                    invokeHookWithTimeout(plugin, hookName, args);
                } catch (LuaError e) {
                    FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                    Log.e(TAG, "fire(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
                }
            }
        } finally {
            markHookEnd();
        }
    }

    /** Background executor used for guarded hook invocation. */
    private static final java.util.concurrent.ExecutorService hookExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "XenonPluginHook");
                t.setDaemon(true);
                return t;
            });

    private static final long HOOK_TIMEOUT_MS = 5000;

    private void invokeHookWithTimeout(LoadedPlugin plugin, String hookName, LuaValue[] args) {
        java.util.concurrent.Future<?> future = hookExecutor.submit(() -> {
            try {
                plugin.invokeHook(hookName, args);
            } catch (LuaError e) {
                FileLog.e("Plugin " + plugin.fileName + " hook " + hookName + " error", e);
                Log.e(TAG, "fire(" + hookName + "): error in " + plugin.fileName + ": " + e.getMessage());
            }
        });
        try {
            future.get(HOOK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // Plugin is stuck. Cancel (best-effort interrupt) and quarantine it.
            future.cancel(true);
            Log.e(TAG, "fire(" + hookName + "): TIMEOUT after " + HOOK_TIMEOUT_MS
                    + "ms, quarantining " + plugin.fileName);
            quarantinePlugin(plugin, "hook '" + hookName + "' exceeded timeout");
        } catch (Exception e) {
            Log.e(TAG, "fire(" + hookName + "): wait failed: " + e.getMessage());
        }
    }

    /**
     * Invoke a SYNCHRONOUS result hook (fireReturn / fireBooleanResult) with a
     * Throwable-catch around it. These hooks can't use the timeout executor
     * (they must return a value immediately), but they still must not be able
     * to crash the app via a StackOverflowError / OutOfMemoryError escaping
     * Lua. If such an Error is thrown, we quarantine the offending plugin and
     * treat the hook as returning nil/false. Normal LuaError is rethrown so the
     * caller's catch(LuaError) handles it as before.
     */
    private LuaValue invokeHookReturnGuarded(LoadedPlugin plugin, String hookName, LuaValue[] args) {
        try {
            return plugin.invokeHookReturn(hookName, args);
        } catch (LuaError e) {
            // Normal Lua error — let the caller's catch(LuaError) deal with it.
            throw e;
        } catch (Throwable t) {
            // StackOverflowError, OutOfMemoryError, or any other JVM-level
            // error. These would otherwise propagate out of fireReturn and crash
            // the app. Quarantine the plugin and move on.
            Log.e(TAG, plugin.fileName + " hook " + hookName + " threw "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            quarantineFile(plugin.fileName, "hook '" + hookName + "'", t);
            return null;
        }
    }

    /**
     * Disable a misbehaving plugin permanently (until the user re-enables it)
     * and rebuild the engine so it stops receiving hooks. Used by the hook
     * timeout guard. Also surfaces a failure sheet so the user can copy the
     * reason (a hook running longer than the guard allows, i.e. an infinite loop).
     */
    private void quarantinePlugin(LoadedPlugin plugin, String reason) {
        try {
            getPrefs().edit().putBoolean("plugin_enabled_" + plugin.fileName, false).apply();
            FileLog.e("Plugin quarantined: " + plugin.fileName + " — " + reason);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                try {
                    PluginApi.stopAllForPlugin(plugin.fileName);
                    reloadAll();
                } catch (Exception ignored) {
                }
            });
            // Report with a synthetic throwable so the failure sheet + copy
            // button show a meaningful, copyable reason.
            Throwable synthetic = new java.util.concurrent.TimeoutException(reason);
            android.app.Activity activity = getCurrentActivity();
            PluginSafeMode.reportPluginFailure(activity, plugin.fileName, reason, synthetic);
        } catch (Exception ignored) {
        }
    }

    /**
     * Disable a plugin by file name without needing a loaded instance (e.g. when
     * its top-level code threw during load). Sets the prefs flag so it won't run
     * again, writes a full crash log, and shows a sheet so the user can copy the
     * exact error (the full stack trace, not a truncated class name).
     */
    private void quarantineFile(String fileName, String stage, Throwable t) {
        try {
            getPrefs().edit().putBoolean("plugin_enabled_" + fileName, false).apply();
            FileLog.e("Plugin quarantined: " + fileName + " — " + stage, t);
            android.app.Activity activity = getCurrentActivity();
            if (activity != null) {
                PluginSafeMode.reportPluginFailure(activity, fileName, stage, t);
            } else {
                // No activity available — fall back to a plain bulletin.
                String msg = t != null ? t.getClass().getSimpleName() : stage;
                PluginApi.showBulletin("Plugin " + fileName
                        + " was disabled: " + msg);
            }
        } catch (Exception ignored) {
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
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fireReturn(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                try {
                    LuaValue res = invokeHookReturnGuarded(plugin, hookName, args);
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
        } finally {
            markHookEnd();
        }
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
        markHookStart(hookName);
        try {
            for (LoadedPlugin plugin : plugins) {
                if (!plugin.isEnabled()) {
                    Log.d(TAG, "fireBooleanResult(" + hookName + "): " + plugin.displayName + " disabled, skipping");
                    continue;
                }
                try {
                    LuaValue res = invokeHookReturnGuarded(plugin, hookName, args);
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
        } finally {
            markHookEnd();
        }
    }

    /**
     * Quickly parse a .xplugin file's metadata (name, description, pluginId) without
     * keeping the plugin loaded. Returns an array of three strings: [name, description, pluginId],
     * or null if parsing fails.
     */
    private static String lastParseError;

    public static String getLastParseError() {
        return lastParseError;
    }

    public static String[] parseMetadata(File file) {
        lastParseError = null;
        if (file == null || !file.exists() || !file.getName().endsWith(PLUGIN_EXT)) return null;
        // SECURITY: parse metadata by SCANNING THE SOURCE TEXT, never by executing
        // the Lua. Executing (the old approach) meant a malformed or malicious
        // plugin could crash/hang the app the moment its file is previewed —
        // before it was even installed. Reading plugin_id / plugin_name /
        // plugin_description with a regex over the first chunk of the file is
        // crash-proof and fast, because no interpreter runs.
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // Only read up to 64KB; metadata lives at the top of the file.
            while ((n = in.read(buf)) > 0 && baos.size() < 65536) {
                baos.write(buf, 0, n);
            }
            String source = new String(baos.toByteArray(), "UTF-8");
            String name = extractStringAssignment(source, "plugin_name");
            String desc = extractStringAssignment(source, "plugin_description");
            String id = extractStringAssignment(source, "plugin_id");
            String author = extractStringAssignment(source, "plugin_author");
            String version = extractStringAssignment(source, "plugin_version");
            // Sanitize version: keep "N.M" format, max 5 digits after the dot.
            version = sanitizeVersion(version);
            return new String[]{name, desc, id, author, version};
        } catch (Throwable t) {
            // Catch Throwable (not Exception) — even an OOM while reading must
            // not crash the app here.
            FileLog.e(t);
            String msg = t.getMessage();
            lastParseError = msg != null ? msg : "unknown parse error";
            Log.e(TAG, "parseMetadata: failed for " + file.getName() + ": " + lastParseError);
            return null;
        }
    }

    /**
     * Pull a top-level {@code var = "..."} string assignment out of Lua source
     * without executing it. Matches common forms: single/double quotes, and an
     * optional "local" prefix. Returns the trimmed string value, or null.
     */
    private static String extractStringAssignment(String source, String varName) {
        if (source == null || varName == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:^|\\n)\\s*(?:local\\s+)?" + java.util.regex.Pattern.quote(varName)
                        + "\\s*=\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(source);
        if (m.find()) {
            String s = m.group(1);
            if (s != null) {
                s = s.trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * Normalize a plugin version string to "N.M" form. Keeps digits and at most
     * one dot; if there's a fractional part, limits it to 5 digits after the
     * dot. Returns null for garbage / empty input.
     */
    private static String sanitizeVersion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Keep only digits and the first dot.
        StringBuilder sb = new StringBuilder();
        boolean dotSeen = false;
        int afterDot = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' && !dotSeen) {
                sb.append('.');
                dotSeen = true;
            } else if (Character.isDigit(c)) {
                if (dotSeen) {
                    if (afterDot < 5) {
                        sb.append(c);
                        afterDot++;
                    }
                    // else: truncate beyond 5 digits after dot.
                } else {
                    sb.append(c);
                }
            }
            // Ignore anything else.
        }
        String result = sb.toString();
        if (result.isEmpty()) return null;
        // Strip a trailing dot.
        if (result.endsWith(".")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? null : result;
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
        public final String author;
        public final String version;
        public final List<PluginSetting> settings;
        private final Globals globals;
        private final LuaTable hooks;

        LoadedPlugin(String fileName, String name, String description, String pluginId, String author, String version, List<PluginSetting> settings, Globals globals, LuaTable hooks) {
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
            this.author = author;
            this.version = version;
            this.settings = settings;
        }

        public static class PluginSetting {
            public enum Type { TOGGLE, SEEKBAR, TEXT, BUTTON, HEADER, LIST }
            public final Type type;
            public final String key;
            public final String name;
            public final boolean defaultBool;
            public final int defaultInt;
            public final String defaultString;
            public final int min, max, step;
            public final String hint;
            public final LuaValue action;
            /** For LIST: the selectable option labels. */
            public final String[] options;

            PluginSetting(Type type, String key, String name, boolean defaultBool, int defaultInt, String defaultString, int min, int max, int step, String hint, LuaValue action) {
                this(type, key, name, defaultBool, defaultInt, defaultString, min, max, step, hint, action, null);
            }

            PluginSetting(Type type, String key, String name, boolean defaultBool, int defaultInt, String defaultString, int min, int max, int step, String hint, LuaValue action, String[] options) {
                this.type = type; this.key = key; this.name = name;
                this.defaultBool = defaultBool; this.defaultInt = defaultInt; this.defaultString = defaultString;
                this.min = min; this.max = max; this.step = step;
                this.hint = hint; this.action = action; this.options = options;
            }
        }

        public boolean isEnabled() {
            return PluginManager.getPrefs().getBoolean("plugin_enabled_" + fileName, true);
        }

        public void setEnabled(boolean enabled) {
            PluginManager.getPrefs().edit().putBoolean("plugin_enabled_" + fileName, enabled).apply();
        }

        public void refreshSettingsFromGlobals() {
            this.settings.clear();
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
                        PluginSetting setting = null;
                        switch (type) {
                            case "toggle": {
                                boolean def = entry.get("default").toboolean();
                                setting = new PluginSetting(PluginSetting.Type.TOGGLE, skey, sname, def, 0, null, 0, 0, 0, null, null);
                                break;
                            }
                            case "seekbar": {
                                int min = (int) entry.get("min").checkdouble();
                                int max = (int) entry.get("max").checkdouble();
                                int step = (int) entry.get("step").checkdouble();
                                if (step <= 0) step = 1;
                                int def = entry.get("default").isnil() ? min : (int) entry.get("default").checkdouble();
                                setting = new PluginSetting(PluginSetting.Type.SEEKBAR, skey, sname, false, def, null, min, max, step, null, null);
                                break;
                            }
                            case "text": {
                                LuaValue defVal = entry.get("default");
                                String def = defVal.isnil() ? "" : defVal.tojstring();
                                LuaValue hintVal = entry.get("hint");
                                String hint = hintVal.isnil() ? "" : hintVal.tojstring();
                                setting = new PluginSetting(PluginSetting.Type.TEXT, skey, sname, false, 0, def, 0, 0, 0, hint, null);
                                break;
                            }
                            case "button": {
                                LuaValue action = entry.get("action");
                                setting = new PluginSetting(PluginSetting.Type.BUTTON, skey, sname, false, 0, null, 0, 0, 0, null, action != null && !action.isnil() ? action : null);
                                break;
                            }
                            case "header": {
                                setting = new PluginSetting(PluginSetting.Type.HEADER, skey, sname, false, 0, null, 0, 0, 0, null, null);
                                break;
                            }
                        }
                        if (setting != null) {
                            this.settings.add(setting);
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }

        public void invokeHook(String hookName, LuaValue[] args) {
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
