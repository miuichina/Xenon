package zxc.iconic.xenon;

import org.lsposed.lsparanoid.Obfuscate;
import org.telegram.messenger.BuildConfig;

import zxc.iconic.xenon.helpers.UserHelper;

/**
 * Build-time values from {@code local.properties} / Gradle ({@link BuildConfig}).
 * Configure {@code apiId} and {@code apiHash} from https://my.telegram.org — required for any Telegram client.
 */
@Obfuscate
public class Extra {

    public static int APP_ID = BuildConfig.API_ID;
    public static String APP_HASH = BuildConfig.API_HASH;

    public static boolean FORCE_ANALYTICS = "play".equals(BuildConfig.BUILD_TYPE);

    // Helper bot disabled in Xenon: returns null so InlineBotHelper.queryText
    // short-circuits without contacting any third-party bot.
    public static UserHelper.BotInfo getHelperBot() {
        return null;
    }

    public static boolean isDirectApp() {
        return "release".equals(BuildConfig.BUILD_TYPE) || "debug".equals(BuildConfig.BUILD_TYPE);
    }
}
