package zxc.iconic.xenon.helpers.remote;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * Checks for app updates against GitHub Releases for sinkclose/Xenon.
 *
 * Release scheme:
 *   - tag_name = short commit hash of the build
 *   - name     = first line of the commit message
 *   - body     = structured release notes (commit hash, checksums, etc.)
 *   - assets   = APK files (Xenon-{version}-{code}-{abi}.apk)
 *
 * Version comparison: current build's GIT_COMMIT_SHORT (embedded at compile time)
 * is compared against the latest release's tag_name.
 * If they differ, the latest release's published_at timestamp is compared
 * to ensure we only offer genuinely newer builds.
 */
public class GitHubUpdateHelper {

    private static final String TAG = "GitHubUpdateHelper";
    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/sinkclose/Xenon/releases/latest";
    private static final Gson GSON = new Gson();

    private GitHubUpdateHelper() {
    }

    /**
     * Callback for update check results.
     */
    public interface UpdateCallback {
        /**
         * Called when a newer release is found.
         *
         * @param release parsed latest release metadata
         */
        void onUpdateAvailable(GitHubRelease release);

        /**
         * Called when current build matches the latest release.
         */
        void onNoUpdate();

        /**
         * Called on network/parsing errors.
         */
        void onError(String error);
    }

    /**
     * Fetches the latest GitHub release and compares its tag (short commit hash)
     * against the current build's {@code BuildConfig.GIT_COMMIT_SHORT}.
     * Results are delivered on the UI thread.
     *
     * @param callback result callback (never null)
     */
    public static void checkForUpdates(UpdateCallback callback) {
        checkForUpdates(callback, false, null);
    }

    public static void checkForUpdates(UpdateCallback callback, boolean force) {
        checkForUpdates(callback, force, null);
    }

    public static void checkForUpdates(UpdateCallback callback, boolean force, String installedSha256) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + ": checking for updates...");
                GitHubRelease release = fetchLatestRelease();
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    FileLog.d(TAG + ": release is null or has no tag");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }

                release.parseSha256FromBody();

                if (findApkDownloadUrl(release) == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build for this release"));
                    return;
                }

                if (installedSha256 != null && release.apkSha256 != null) {
                    if (installedSha256.equalsIgnoreCase(release.apkSha256)) {
                        FileLog.d(TAG + ": SHA256 matches (" + installedSha256.substring(0, 16) + "...), no update");
                        AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                        return;
                    }
                    FileLog.d(TAG + ": SHA256 differs (installed=" + installedSha256.substring(0, 16)
                            + "... release=" + release.apkSha256.substring(0, 16) + "...), update available");
                    AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
                    return;
                }

                FileLog.d(TAG + ": SHA256 unavailable (installed=" + (installedSha256 != null ? "set" : "null")
                        + " release=" + (release.apkSha256 != null ? "set" : "null")
                        + "), falling back to commit hash");

                String currentHash = BuildConfig.GIT_COMMIT_SHORT;
                String remoteHash = release.commitShortHash;
                FileLog.d(TAG + ": local=" + currentHash
                        + " remote=" + (remoteHash != null ? remoteHash : release.tagName)
                        + " name=" + release.name);
                if (TextUtils.isEmpty(currentHash) || "unknown".equals(currentHash)) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("Build commit hash is not embedded"));
                    return;
                }

                String local = currentHash.trim().toLowerCase();
                if (!TextUtils.isEmpty(remoteHash)) {
                    String remote = remoteHash.trim().toLowerCase();
                    boolean isSameBuild = remote.startsWith(local) || local.startsWith(remote);
                    if (isSameBuild) {
                        FileLog.d(TAG + ": commit hashes match, no update");
                        AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                        return;
                    }
                }
                // fallback: compare against tagName
                String tagNameLower = release.tagName.trim().toLowerCase();
                boolean isSameBuild = tagNameLower.startsWith(local) || local.startsWith(tagNameLower);
                if (isSameBuild) {
                    FileLog.d(TAG + ": hashes match, no update");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                } else if (isReleaseOlderThanInstalled(release)) {
                    FileLog.d(TAG + ": remote release is older than installed, ignoring");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                } else {
                    FileLog.d(TAG + ": update available");
                    AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
                }
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonUpdateCheck").start();
    }

    /**
     * Performs the HTTP request and parses JSON response.
     *
     * @return parsed release or null on failure
     */
    @Nullable
    private static GitHubRelease fetchLatestRelease() throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(GITHUB_API_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Xenon-Updater/" + BuildConfig.VERSION_NAME);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int code = connection.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub API returned HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            return GSON.fromJson(sb.toString(), GitHubRelease.class);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Finds the best APK download URL from release assets.
     * @param release the release to search
     * @return download URL of the arm64 APK, or {@code null} if the release
     *         does not contain a suitable arm64 build. Xenon ships arm64-only
     *         APKs by design (see {@code TMessagesProj_App/build.gradle}'s
     *         {@code splits.abi.include "arm64-v8a"}); offering any other ABI
     *         here would silently install something that won't run on a
     *         64-bit-only device, so we deliberately do NOT fall back to a
     *         "universal" or non-arm64 APK — better to surface "no compatible
     *         build" than to push an APK the package installer will reject.
     */
    @Nullable
    public static String findApkDownloadUrl(GitHubRelease release) {
        if (release == null || release.assets == null) {
            return null;
        }
        for (GitHubAsset asset : release.assets) {
            if (asset.name == null || !asset.name.endsWith(".apk")) {
                continue;
            }
            String lower = asset.name.toLowerCase();
            if (lower.contains("debug")) {
                continue;
            }
            if (lower.contains("arm64")) {
                return asset.browserDownloadUrl;
            }
        }
        return null;
    }

    /**
     * Extracts the arm64 APK file size from release assets.
     *
     * @param release the release to search
     * @return file size in bytes, or {@code -1} if no arm64 APK is present
     *         (Xenon is arm64-only — see {@link #findApkDownloadUrl}).
     */
    public static long findApkSize(GitHubRelease release) {
        if (release == null || release.assets == null) {
            return -1;
        }
        for (GitHubAsset asset : release.assets) {
            if (asset.name == null || !asset.name.endsWith(".apk")) continue;
            String lower = asset.name.toLowerCase();
            if (lower.contains("debug")) continue;
            if (lower.contains("arm64")) return asset.size;
        }
        return -1;
    }

    /**
     * Returns {@code true} if the given GitHub release was published before or
     * at the same time as the locally installed APK. Used to suppress the
     * "update available" prompt on rollbacks (e.g. when {@code latest} on
     * GitHub points back to an older build than the user already has).
     *
     * <p>{@code release.publishedAt} is ISO-8601 with a {@code Z} suffix
     * (e.g. {@code 2026-05-15T19:30:00Z}); we parse it via
     * {@link java.time.OffsetDateTime#parse} and compare against
     * {@code PackageInfo.lastUpdateTime}. If parsing fails or local install
     * time is unknown, we fall back to "treat as newer" so the existing
     * hash-mismatch path can still fire and the user is not silently locked
     * out of legitimate updates.
     */
    static boolean isReleaseOlderThanInstalled(GitHubRelease release) {
        if (release == null || android.text.TextUtils.isEmpty(release.publishedAt)) {
            return false;
        }
        long releaseEpochMs;
        try {
            releaseEpochMs = java.time.OffsetDateTime.parse(release.publishedAt)
                    .toInstant().toEpochMilli();
        } catch (Throwable t) {
            FileLog.e(TAG + ": failed to parse published_at=" + release.publishedAt, t);
            return false;
        }
        long installedEpochMs;
        try {
            android.content.pm.PackageInfo pi = ApplicationLoader.applicationContext
                    .getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            installedEpochMs = pi.lastUpdateTime;
        } catch (Throwable t) {
            return false;
        }
        if (installedEpochMs <= 0) {
            return false;
        }
        // Allow a small skew (60s) so a release published the same minute as
        // the local build doesn't get rejected on the boundary.
        return releaseEpochMs + 60_000L <= installedEpochMs;
    }

    /**
     * GitHub Release JSON model.
     */
    public static class GitHubRelease {
        @SerializedName("tag_name")
        public String tagName;

        @SerializedName("name")
        public String name;

        @SerializedName("body")
        public String body;

        @SerializedName("prerelease")
        public boolean prerelease;

        @SerializedName("published_at")
        public String publishedAt;

        @SerializedName("html_url")
        public String htmlUrl;

        @SerializedName("assets")
        public List<GitHubAsset> assets;

        public String apkSha256;
        public String commitShortHash;

        void parseSha256FromBody() {
            if (body == null) return;
            String fallback = null;
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                String trimmedLower = trimmed.toLowerCase();

                // Parse Short Hash: xxxxxxx from the body
                if (commitShortHash == null && trimmedLower.startsWith("short hash")) {
                    int ci = trimmed.indexOf(':');
                    if (ci >= 0) {
                        String val = trimmed.substring(ci + 1).trim();
                        if (!val.isEmpty()) commitShortHash = val;
                    }
                }

                int colon = trimmed.indexOf(':');
                String firstHex = null;
                for (int i = 0; i < trimmed.length(); i++) {
                    char c = trimmed.charAt(i);
                    if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                        int start = i;
                        while (i < trimmed.length() && ((trimmed.charAt(i) >= '0' && trimmed.charAt(i) <= '9')
                                || (trimmed.charAt(i) >= 'a' && trimmed.charAt(i) <= 'f')
                                || (trimmed.charAt(i) >= 'A' && trimmed.charAt(i) <= 'F'))) {
                            i++;
                        }
                        String hex = trimmed.substring(start, i);
                        if (hex.length() == 64) {
                            if (firstHex == null) firstHex = hex;
                            if (line.toLowerCase().contains("arm64") || line.toLowerCase().contains("arm64-v8a")) {
                                apkSha256 = hex.toLowerCase();
                                return;
                            }
                        }
                    }
                }
                if (firstHex != null && fallback == null) {
                    boolean shaHint = false;
                    if (colon >= 0) {
                        String key = trimmed.substring(0, colon).trim().toLowerCase();
                        shaHint = key.contains("sha256") || key.contains("sha-256") || key.contains("checksum");
                    }
                    if (!shaHint) {
                        int firstSpace = Integer.MAX_VALUE;
                        int firstTab = trimmedLower.indexOf('\t');
                        int firstSpaceChar = trimmedLower.indexOf(' ');
                        if (firstTab >= 0) firstSpace = Math.min(firstSpace, firstTab);
                        if (firstSpaceChar >= 0) firstSpace = Math.min(firstSpace, firstSpaceChar);
                        String prefix = firstSpace < Integer.MAX_VALUE ? trimmedLower.substring(0, firstSpace) : trimmedLower;
                        shaHint = "sha256".equals(prefix) || "sha-256".equals(prefix) || "checksum".equals(prefix);
                    }
                    if (shaHint) {
                        fallback = firstHex;
                    }
                }
            }
            if (fallback != null) {
                apkSha256 = fallback.toLowerCase();
            }
        }
    }

    /**
     * GitHub Release Asset JSON model.
     */
    public static class GitHubAsset {
        @SerializedName("name")
        public String name;

        @SerializedName("browser_download_url")
        public String browserDownloadUrl;

        @SerializedName("size")
        public long size;

        @SerializedName("content_type")
        public String contentType;
    }
}
