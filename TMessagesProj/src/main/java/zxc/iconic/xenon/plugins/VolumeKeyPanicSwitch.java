package zxc.iconic.xenon.plugins;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Hardware "panic switch" that works even when the UI thread is wedged by a
 * misbehaving plugin.
 *
 * <p>Why this exists: a plugin that runs an infinite loop or recurses across
 * the Lua/Java bridge can block the main thread. Once the main thread is
 * blocked, nothing dispatched via {@link org.telegram.messenger.AndroidUtilities#runOnUIThread}
 * can ever run — so normal in-app buttons (and even the engine watchdog's
 * process-kill) rely on a timer, not user input. The only user action that
 * still reaches us in that state is a <b>hardware</b> event that the system
 * delivers on a binder thread, bypassing the app's main looper entirely.
 *
 * <p>Volume keys fit perfectly: they're delivered by the system AudioManager,
 * and each press updates {@link android.provider.Settings.System#VOLUME_MUSIC}
 * (and friends). A {@link ContentObserver} on those settings fires on a
 * <b>binder thread</b> — not the blocked UI thread — so it still runs.
 *
 * <p>Gesture: press the volume keys <b>4 times quickly</b> (either button, any
 * combination of up/down) within 2 seconds. On the 4th press, plugins are
 * force-disabled and unloaded, and the process is restarted so the next launch
 * is clean.
 *
 * <p>Register once from {@link org.telegram.messenger.ApplicationLoader#onCreate}.
 */
public final class VolumeKeyPanicSwitch {

    private static final long SEQUENCE_TIMEOUT_MS = 2000;
    private static final int REQUIRED_PRESSES = 4;

    private static boolean registered;
    private static long lastPressTime;
    private static int pressCount;
    private static int lastDirection; // 0 = none, 1 = up, -1 = down

    private VolumeKeyPanicSwitch() {
    }

    /**
     * Start listening for the panic gesture. Safe to call once at app start;
     * duplicate calls are ignored.
     */
    public static synchronized void register() {
        if (registered) return;
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // Snapshot the starting volume so the first observed delta has a
            // reference to compare against.
            lastDirection = 0;
            pressCount = 0;
            lastPressTime = 0;

            VolumeObserver observer = new VolumeObserver();
            // Observe all the stream volumes a volume key can change.
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_music"), false, observer);
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_ring"), false, observer);
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_notification"), false, observer);
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("volume_system"), false, observer);

            registered = true;
            FileLog.d("VolumeKeyPanicSwitch registered");
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static synchronized void onVolumeChanged(int direction) {
        long now = System.currentTimeMillis();

        // Reset if too much time passed since the last press.
        if (lastPressTime == 0 || (now - lastPressTime) > SEQUENCE_TIMEOUT_MS) {
            pressCount = 1;
            lastDirection = direction;
            lastPressTime = now;
            return;
        }

        // Count rapid presses in any direction. We used to require strict
        // alternation (up→down→up→down), but that made the gesture unreliable
        // when the active stream changed between presses. 4 presses of either
        // button within the window is enough to be intentional while staying
        // very unlikely to happen by accident during normal volume adjustment.
        pressCount++;
        lastDirection = direction;
        lastPressTime = now;

        if (pressCount >= REQUIRED_PRESSES) {
            pressCount = 0;
            lastDirection = 0;
            triggerPanic();
        }
    }

    /**
     * The actual kill switch: disable the engine, unload everything, restart.
     * Runs on a binder thread — deliberately NOT touching the UI thread, since
     * the whole point is that the UI thread may be hung.
     */
    private static void triggerPanic() {
        try {
            FileLog.e("VolumeKeyPanicSwitch: panic gesture detected — disabling plugin engine");
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                // Persist the disable so the next launch comes up clean.
                ctx.getSharedPreferences("nekoconfig", Context.MODE_PRIVATE)
                        .edit().putBoolean("pluginsEnabled", false).apply();
            }
            // Best-effort in-process cleanup (won't run if UI thread is stuck,
            // but harmless to try).
            try {
                PluginManager.getInstance().onEnabledChanged();
            } catch (Throwable ignored) {
            }
            // Restart the process. This is the reliable path: even with the UI
            // thread frozen, System.exit() terminates the process immediately,
            // and the boot guard will keep plugins off on relaunch.
            System.exit(3);
        } catch (Throwable t) {
            FileLog.e(t);
            try {
                System.exit(3);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class VolumeObserver extends ContentObserver {
        private final int[] streams = {
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_SYSTEM,
        };
        private final int[] lastVolumes = new int[4];
        private boolean initialized = false;

        VolumeObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            try {
                Context ctx = ApplicationLoader.applicationContext;
                if (ctx == null) return;
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;

                // First callback: snapshot current volumes so we have a baseline.
                // We don't emit a press here, but the NEXT change will be detected.
                if (!initialized) {
                    for (int i = 0; i < streams.length; i++) {
                        lastVolumes[i] = am.getStreamVolume(streams[i]);
                    }
                    initialized = true;
                    return;
                }

                // Check every tracked stream for a change. A volume key press
                // changes exactly one of them (whichever is the active stream),
                // so we pick up the press regardless of which stream it hit.
                for (int i = 0; i < streams.length; i++) {
                    int current = am.getStreamVolume(streams[i]);
                    int prev = lastVolumes[i];
                    lastVolumes[i] = current;
                    if (current != prev) {
                        int direction = current > prev ? 1 : -1;
                        onVolumeChanged(direction);
                        return; // one press = one stream change
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }
}
