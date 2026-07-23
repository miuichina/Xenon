package zxc.iconic.xenon.proxy;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import zxc.iconic.xenon.NekoConfig;

/**
 * System-wide Android VPN Service for Xray proxy.
 *
 * <p>Implements the same pattern as v2rayNG's CoreVpnService:
 * verifies {@code VpnService.prepare()} inside {@code onStartCommand()},
 * configures the TUN interface via {@link Builder#establish()},
 * tracks the underlying default network via {@code ConnectivityManager.NetworkCallback},
 * and feeds the fd into the Xray core.
 */
public class XrayVpnService extends VpnService {

    private static final String CHANNEL_ID = "xray_vpn_channel";
    private static final int NOTIFICATION_ID = 21771;

    static volatile boolean IS_RUNNING = false;

    private ParcelFileDescriptor tunFd;
    private boolean configured = false;

    // ---- Default-network tracking (API 28+), mirrors v2rayNG CoreVpnService ----

    private ConnectivityManager connectivity;

    private final NetworkRequest defaultNetworkRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
            : null;

    private final ConnectivityManager.NetworkCallback defaultNetworkCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    setUnderlyingNetworks(new Network[]{network});
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    setUnderlyingNetworks(new Network[]{network});
                }

                @Override
                public void onLost(Network network) {
                    setUnderlyingNetworks(null);
                }
            }
            : null;

    // ---- Public start / stop helpers ------------------------------------------------

    public static void startVpn(Context ctx) {
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(ctx, XrayVpnService.class);
        intent.setAction("START_VPN");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    public static void stopVpn(Context ctx) {
        if (ctx == null) {
            return;
        }
        Intent intent = new Intent(ctx, XrayVpnService.class);
        intent.setAction("STOP_VPN");
        ctx.startService(intent);
    }

    // ---- Service lifecycle ---------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        FileLog.d("[XrayVpnService] onCreate");
        createNotificationChannel();

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "";
        FileLog.d("[XrayVpnService] onStartCommand action=" + action + " flags=" + flags + " startId=" + startId);

        if ("STOP_VPN".equals(action)) {
            stopVpnInternal();
            return START_NOT_STICKY;
        }

        if ("START_VPN".equals(action)) {
            FileLog.d("[XrayVpnService] startForeground channelId=" + CHANNEL_ID + " notificationId=" + NOTIFICATION_ID);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } catch (Throwable t) {
                    FileLog.e("[XrayVpnService] startForeground with type failed, falling back", t);
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }

            // configureVpn() calls builder.establish() which blocks — defer to a background
            // thread so the foreground-service timeout (5 s on API 26+) is never hit.
            new Thread(() -> {
                if (configureVpn() != null) {
                    startCore();
                    requestNetworkCallback();
                }
            }, "XrayVpn-configure").start();
        }
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        FileLog.d("[XrayVpnService] onRevoke");
        NekoConfig.setXrayVpnMode(false);
        stopVpnInternal();
    }

    @Override
    public void onDestroy() {
        FileLog.d("[XrayVpnService] onDestroy");
        releaseNetworkCallback();
        if (configured && tunFd != null) {
            try {
                tunFd.close();
            } catch (Throwable ignore) {
            }
            tunFd = null;
        }
        configured = false;
    }

    // ---- VPN configuration (mirrors CoreVpnService.configureVpnService) ------------

    @SuppressLint("VpnServicePolicy")
    private ParcelFileDescriptor configureVpn() {
        // Permission was already granted by the user before this service is started
        // (VpnService.prepare() was called from the Hub activity flow).
        // Calling prepare() again inside the service is unnecessary and breaks on
        // certain OEM firmwares (Oplus/Xiaomi) where the UID→package mapping fails.

        Builder builder = new Builder();

        // Network settings
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 30);
        builder.addRoute("0.0.0.0", 0);

        // IPv6 — disabled by default; only enable if user opted in
        // (left as a placeholder for future pref-driven toggle)
        /*
        if (MmkvManager.decodeSettingsBool("pref_ipv6_enabled") == true) {
            builder.addAddress("fdfe:dcba:9876::1", 126);
            builder.addRoute("::", 0);
        }
        */

        // DNS
        builder.addDnsServer("1.1.1.1");
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("8.8.4.4");

        // Session + configure intent
        builder.setSession(LocaleController.getString(R.string.XrayVpnSession));
        PendingIntent pi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, LaunchActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setConfigureIntent(pi);

        builder.setBlocking(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.allowFamily(android.system.OsConstants.AF_INET);
            builder.allowFamily(android.system.OsConstants.AF_INET6);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        // Per-app: exclude own package so Telegram uses direct SOCKS (like v2rayNG)
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Throwable ignore) {
        }

        // Close old fd if present (reconfiguration)
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (Throwable ignore) {
            }
            tunFd = null;
        }

        try {
            tunFd = builder.establish();
            if (tunFd == null) {
                FileLog.e("[XrayVpnService] configureVpn: establish returned null");
                NekoConfig.setXrayVpnMode(false);
                stopVpnInternal();
                return null;
            }
            FileLog.d("[XrayVpnService] configureVpn: establish success, tunFd=" + tunFd.getFd());
        } catch (Throwable t) {
            FileLog.e("[XrayVpnService] configureVpn: establish failed", t);
            NekoConfig.setXrayVpnMode(false);
            stopVpnInternal();
            return null;
        }

        configured = true;
        IS_RUNNING = true;
        return tunFd;
    }

    // ---- Core start ---------------------------------------------------------------

    private void startCore() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null || TextUtils.isEmpty(active.configJson)) {
            FileLog.e("[XrayVpnService] startCore failed: active profile is null or empty");
            stopVpnInternal();
            return;
        }

        // Build VPN config: tun inbound without embedded fd — fd is forwarded
        // as a separate argument to CoreController.startLoop(config, tunFd),
        // matching v2rayNG's pattern (fd is never placed into the JSON config).
        String configJson = XrayConfigForm.applyVpnMode(active.configJson, active.localPort);
        if (configJson == null) {
            FileLog.e("[XrayVpnService] startCore failed: applyVpnMode returned null");
            stopVpnInternal();
            return;
        }

        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        String fullConfig;
        try {
            fullConfig = XrayLocalSocksAuth.applyCredentials(configJson, active.localPort, credentials);
        } catch (Throwable t) {
            fullConfig = configJson;
        }

        final String targetConfig = fullConfig != null ? fullConfig : configJson;
        final int tunFdInt = tunFd != null ? tunFd.getFd() : 0;
        FileLog.d("[XrayVpnService] startCore: starting Xray core with tunFd=" + tunFdInt);

        XrayAppProxyManager.start(targetConfig, tunFdInt, (startOk, startMsg) -> {
            if (!startOk) {
                FileLog.e("[XrayVpnService] startCore: Xray core start failed: " + startMsg);
                stopVpnInternal();
            } else {
                FileLog.d("[XrayVpnService] startCore: Xray core start succeeded");
                AndroidUtilities.runOnUIThread(() ->
                        XrayTelegramProxyBridge.enableLocalProxy(active.localPort, credentials));
            }
        });
    }

    private void stopVpnInternal() {
        FileLog.d("[XrayVpnService] stopVpnInternal: stopping service");
        XrayAppProxyManager.stop(null);
        releaseNetworkCallback();

        configured = false;

        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (Throwable ignore) {
            }
            tunFd = null;
        }
        IS_RUNNING = false;
        stopForeground(true);
        stopSelf();
    }

    // ---- Network callback helpers -------------------------------------------------

    private void requestNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && connectivity != null && defaultNetworkCallback != null) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    private void releaseNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && connectivity != null && defaultNetworkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    // ---- Notification --------------------------------------------------------------

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, LaunchActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(LocaleController.getString(R.string.XrayProxyTitle))
                .setContentText(LocaleController.getString(R.string.XrayVpnSession))
                .setSmallIcon(R.drawable.notification)
                .setOngoing(true)
                .setContentIntent(pi)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createNotificationChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                LocaleController.getString(R.string.XrayProxyTitle),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}