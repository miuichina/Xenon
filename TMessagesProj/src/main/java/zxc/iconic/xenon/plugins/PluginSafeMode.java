package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import zxc.iconic.xenon.NekoConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Crash-aware "Safe Mode" for the plugin engine.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li><b>Capture:</b> installs a global {@link Thread.UncaughtExceptionHandler}
 *       that wraps the previous one. When any thread crashes, it records the
 *       stack trace to the crash-log file and flips {@code pluginCrash} on, so
 *       the next launch knows plugins were (likely) the cause.</li>
 *   <li><b>Recover:</b> on the next app start, if a crash was recorded, disables
 *       plugins and shows a {@link BottomSheet} explaining what happened, with a
 *       button to copy the crash log.</li>
 * </ol>
 *
 * <p>The handler is deliberately minimal and defensive: writing files or shared
 * prefs from a crashing process is best-effort. The previous handler is always
 * invoked so the OS still gets the chance to terminate the process normally.
 */
public final class PluginSafeMode {

    private static final String PREFS = "xenon_plugins_safemode";
    private static final String KEY_CRASH_FLAG = "pluginCrash";
    private static final String KEY_CRASH_TIME = "pluginCrashTime";
    private static final String CRASH_LOG_NAME = "plugin_crash.txt";

    private PluginSafeMode() {
    }

    // ------------------------------------------------------------------
    // Crash capture
    // ------------------------------------------------------------------

    /**
     * Install the crash handler. Call once very early in app startup (e.g.
     * {@code ApplicationLoader.onCreate}). Chains onto whatever handler was
     * already installed, so existing logging/termination behaviour is preserved.
     */
    public static void install() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                recordCrash(thread, throwable);
            } catch (Throwable ignore) {
                // Never let the handler itself throw — that would swallow the
                // original crash. Best-effort only.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void recordCrash(Thread thread, Throwable throwable) {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;

        String trace = buildCrashTrace(thread, throwable);
        writeCrashLog(trace);

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRASH_FLAG, true)
                .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                .apply();
    }

    private static String buildCrashTrace(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append("Xenon plugin crash report\n");
        sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append("\n");
        sb.append("Thread: ").append(thread != null ? thread.getName() : "unknown").append("\n");
        sb.append("App: ").append(getAppVersion()).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (").append(Build.MODEL).append(")\n");
        sb.append("Plugins enabled: ").append(NekoConfig.pluginsEnabled).append("\n");
        sb.append("\n--- Stack trace ---\n");
        if (throwable != null) {
            sb.append(LogExceptionToString(throwable));
        } else {
            sb.append("(no throwable)");
        }
        return sb.toString();
    }

    private static String LogExceptionToString(Throwable t) {
        // Manual walk so we don't depend on android.util.Log.getStackTraceString
        // behaving a particular way; also keeps chained causes.
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            sb.append(current.toString()).append("\n");
            StackTraceElement[] frames = current.getStackTrace();
            for (StackTraceElement frame : frames) {
                sb.append("    at ").append(frame.toString()).append("\n");
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                sb.append("Caused by: ");
            }
            current = cause;
        }
        return sb.toString();
    }

    private static String getAppVersion() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageName() + " " +
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeCrashLog(String content) {
        try {
            File file = getCrashLogFile();
            if (file == null) return;
            // Write fresh each crash — last crash is the interesting one.
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static File getCrashLogFile() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return null;
        return new File(ctx.getFilesDir(), CRASH_LOG_NAME);
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    /**
     * Called from {@link org.telegram.ui.LaunchActivity#onResume} (or onCreate)
     * once the UI is ready. If a crash was recorded since the last successful
     * launch, this disables plugins and shows the crash sheet. Safe to call
     * repeatedly; the crash flag is cleared once acknowledged.
     */
    public static void checkAndHandleCrash(Activity activity) {
        if (activity == null) return;
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) return;

        boolean crashed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CRASH_FLAG, false);
        if (!crashed) {
            return;
        }

        long crashTime = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_CRASH_TIME, 0);

        // Disable plugins immediately so the next start is clean.
        NekoConfig.pluginsEnabled = false;
        ctx.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                .edit().putBoolean("pluginsEnabled", false).apply();
        PluginManager.getInstance().onEnabledChanged();

        // Clear the flag so we don't show this sheet twice.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CRASH_FLAG, false).apply();

        // Defer the sheet slightly so the activity is fully resumed.
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> showCrashSheet(activity, crashTime), 800);
    }

    private static void showCrashSheet(Activity activity, long crashTime) {
        try {
            final String crashLog = readCrashLog();

            BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, null);
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = org.telegram.messenger.AndroidUtilities.dp(24);
            layout.setPadding(pad, org.telegram.messenger.AndroidUtilities.dp(20), pad, pad);

            // Title
            TextView title = new TextView(activity);
            title.setText("Crashed!");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(org.telegram.messenger.AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(12));
            layout.addView(title);

            // Body
            TextView body = new TextView(activity);
            body.setText("The client crashed on the previous launch. Plugins have been "
                    + "disabled to keep things stable. If a plugin caused this, you can "
                    + "review or remove it. Copy the crash log below if you want to report it.");
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            body.setLineSpacing(org.telegram.messenger.AndroidUtilities.dp(2), 1f);
            body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            body.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(16));
            layout.addView(body);

            // Time line
            if (crashTime > 0) {
                TextView time = new TextView(activity);
                time.setText("Crash time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date(crashTime)));
                time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                time.setPadding(0, 0, 0, org.telegram.messenger.AndroidUtilities.dp(16));
                layout.addView(time);
            }

            final BottomSheet[] sheetRef = new BottomSheet[1];

            // Buttons
            layout.addView(makeButton(activity, "Open plugins", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                openPlugins(activity);
            }));
            layout.addView(divider(activity));
            layout.addView(makeButton(activity, "Copy crash log", Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), v -> {
                copyToClipboard(crashLog);
                Toast.makeText(activity, "Crash log copied", Toast.LENGTH_SHORT).show();
            }));
            layout.addView(divider(activity));
            layout.addView(makeButton(activity, "Close", Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), v -> {
                if (sheetRef[0] != null) sheetRef[0].dismiss();
            }));

            builder.setCustomView(layout);
            sheetRef[0] = builder.show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static View makeButton(Activity activity, String text, int color, View.OnClickListener onClick) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, org.telegram.messenger.AndroidUtilities.dp(14), 0, org.telegram.messenger.AndroidUtilities.dp(14));
        btn.setOnClickListener(onClick);
        return btn;
    }

    private static View divider(Activity activity) {
        View v = new View(activity);
        v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                org.telegram.messenger.AndroidUtilities.dp(1)));
        return v;
    }

    private static String readCrashLog() {
        File file = getCrashLogFile();
        if (file == null || !file.exists()) {
            return "(no crash log found)";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            FileLog.e(e);
            return "(failed to read crash log)";
        }
        return sb.toString();
    }

    private static void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Xenon crash log", text));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void openPlugins(Activity activity) {
        try {
            if (activity instanceof org.telegram.ui.LaunchActivity) {
                org.telegram.ui.LaunchActivity la = (org.telegram.ui.LaunchActivity) activity;
                la.presentFragment(new zxc.iconic.xenon.settings.NekoPluginsActivity());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
