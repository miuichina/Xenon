package zxc.iconic.xenon.helpers.remote;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

/**
 * Checks for app updates against GitHub Releases for sinkclose/Xenon.
 *
 * <p>Two release channels coexist in the same repo, and the channel a given
 * build belongs to is baked in at compile time via
 * {@link BuildConfig#BUILD_CHANNEL}:
 * <ul>
 *   <li><b>stable</b> (default): polls {@code /releases/latest}. Release scheme:
 *     <ul>
 *       <li>tag_name = short commit hash of the build</li>
 *       <li>name     = first line of the commit message</li>
 *     </ul>
 *   </li>
 *   <li><b>posral</b>: polls {@code /releases} and keeps only tags starting with
 *     {@link #POSRAL_TAG_PREFIX} ({@code posral-<shorthash>}). These are
 *     published by the ayu-features CI as <i>prereleases</i>, which makes them
 *     invisible to GitHub's {@code /releases/latest} endpoint — so the two
 *     channels can never cross-update one another: a posral build will never be
 *     offered a stable APK and vice versa.</li>
 * </ul>
 *
 * <p>Common release fields:
 *   - body     = structured release notes (commit hash, checksums, etc.)
 *   - assets   = APK files (Xenon-{version}-{code}-{abi}.apk)
 *
 * <p>Version comparison: current build's GIT_COMMIT_SHORT (embedded at compile
 * time) is compared against the latest release's tag_name (with any posral-
 * prefix stripped first). If they differ, the latest release's published_at
 * timestamp is compared to ensure we only offer genuinely newer builds.
 */
public class GitHubUpdateHelper {

    private static final String TAG = "GitHubUpdateHelper";
    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/sinkclose/Xenon/releases/latest";
    private static final String GITHUB_API_RELEASES_URL =
            "https://api.github.com/repos/sinkclose/Xenon/releases?per_page=100";
    /**
     * Tag prefix that marks ayu-features (prerelease) builds. A posral build
     * only ever looks at releases whose tag starts with this, and the CI for
     * ayu-features publishes with this prefix. Git forbids "[" / "]" in ref
     * names, so the human-readable "[posral]" lives in the release <i>name</i>
     * while the <i>tag</i> uses this dash form.
     */
    public static final String POSRAL_TAG_PREFIX = "posral-";
    /**
     * Value of {@link BuildConfig#BUILD_CHANNEL} that selects the posral stream.
     */
    public static final String CHANNEL_POSRAL = "posral";
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
        checkForUpdates(callback, false);
    }

    public static void checkForUpdates(UpdateCallback callback, boolean force) {
        new Thread(() -> {
            try {
                boolean posral = CHANNEL_POSRAL.equalsIgnoreCase(BuildConfig.BUILD_CHANNEL);
                FileLog.d(TAG + ": checking for updates (channel="
                        + BuildConfig.BUILD_CHANNEL + ", posral=" + posral + ")...");
                GitHubRelease release = posral
                        ? fetchLatestPrefixedRelease(POSRAL_TAG_PREFIX)
                        : fetchLatestRelease();
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    FileLog.d(TAG + ": release is null or has no tag");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }

                if (force) {
                    // Force mode: skip hash comparison, always report as available
                    if (findApkDownloadUrl(release) == null) {
                        AndroidUtilities.runOnUIThread(() ->
                                callback.onError("No arm64 build for this release"));
                    } else {
                        AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
                    }
                    return;
                }

                String currentHash = BuildConfig.GIT_COMMIT_SHORT;
                FileLog.d(TAG + ": local=" + currentHash
                        + " remote=" + release.tagName
                        + " name=" + release.name);
                if (TextUtils.isEmpty(currentHash) || "unknown".equals(currentHash)) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("Build commit hash is not embedded"));
                    return;
                }

                // Strip the posral- prefix before comparing so the embedded hash
                // (bare short hash) matches the tag body (posral-<shorthash>).
                String tagBody = release.tagName;
                if (posral && tagBody.toLowerCase().startsWith(POSRAL_TAG_PREFIX)) {
                    tagBody = tagBody.substring(POSRAL_TAG_PREFIX.length());
                }

                // Use startsWith comparison because git short hash length varies
                // depending on clone depth (shallow vs full), so the embedded hash
                // and the release tag may have different lengths (e.g. 7 vs 9 chars).
                String remote = tagBody.trim().toLowerCase();
                String local = currentHash.trim().toLowerCase();
                boolean isSameBuild = remote.startsWith(local)
                        || local.startsWith(remote);
                if (isSameBuild) {
                    FileLog.d(TAG + ": hashes match, no update");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                } else if (isReleaseOlderThanInstalled(release)) {
                    // Hash differs but the GitHub "latest" was published before
                    // the locally installed build — almost certainly a rollback
                    // on the release page. Treat as up-to-date instead of
                    // offering to install an older APK over the current one.
                    FileLog.d(TAG + ": remote release is older than installed, ignoring");
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                } else {
                    String apkUrl = findApkDownloadUrl(release);
                    FileLog.d(TAG + ": update available, apk=" + apkUrl);
                    String commitLog = fetchCommitLog(release.tagName, release.body);
                    if (commitLog != null) {
                        release.body = commitLog;
                    }
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
     * Fetches the latest <b>stable</b> (main) release regardless of the current
     * build channel. This always calls {@link #fetchLatestRelease()} (the
     * {@code /releases/latest} endpoint) and <strong>always</strong> reports the
     * release as an available update — no hash comparison.
     *
     * <p>Intended for the "Switch to main" button that lets posral (ayu-features)
     * users escape back to the main channel.
     */
    public static void checkForMainUpdate(UpdateCallback callback) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + ": checking for main (stable) update...");
                GitHubRelease release = fetchLatestRelease();
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }
                String apkUrl = findApkDownloadUrl(release);
                if (apkUrl == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build available"));
                    return;
                }
                String commitLog = fetchCommitLog(release.tagName, release.body);
                if (commitLog != null) {
                    release.body = commitLog;
                }
                AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonMainUpdateCheck").start();
    }

    /**
     * Fetches the latest <b>ayu-features</b> (posral) release regardless of the
     * current build channel. This calls {@link #fetchLatestPrefixedRelease(String)}
     * with {@link #POSRAL_TAG_PREFIX} and always reports the release as available.
     *
     * <p>Intended for the "Switch to ayu-features" button that lets main/stable
     * users switch to the posral channel.
     */
    public static void checkForAyuUpdate(UpdateCallback callback) {
        new Thread(() -> {
            try {
                FileLog.d(TAG + ": checking for ayu-features (posral) update...");
                GitHubRelease release = fetchLatestPrefixedRelease(POSRAL_TAG_PREFIX);
                if (release == null || TextUtils.isEmpty(release.tagName)) {
                    AndroidUtilities.runOnUIThread(callback::onNoUpdate);
                    return;
                }
                String apkUrl = findApkDownloadUrl(release);
                if (apkUrl == null) {
                    AndroidUtilities.runOnUIThread(() ->
                            callback.onError("No arm64 build available"));
                    return;
                }
                String commitLog = fetchCommitLog(release.tagName, release.body);
                if (commitLog != null) {
                    release.body = commitLog;
                }
                AndroidUtilities.runOnUIThread(() -> callback.onUpdateAvailable(release));
            } catch (Exception e) {
                FileLog.e(TAG, e);
                String msg = e.getMessage();
                AndroidUtilities.runOnUIThread(() ->
                        callback.onError(msg != null ? msg : "Unknown error"));
            }
        }, "XenonAyuUpdateCheck").start();
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
     * Lists recent releases and returns the newest one whose {@code tag_name}
     * starts with {@code prefix}. Used by the posral channel, whose releases
     * are published as GitHub <i>prereleases</i> and therefore never appear at
     * {@code /releases/latest}. The list endpoint returns releases sorted by
     * {@code created_at} descending, so the first matching tag is the newest.
     *
     * <p>Unlike {@link #fetchLatestRelease()} this needs the unauthenticated
     * list endpoint to see prereleases; {@code per_page=100} covers far more
     * history than the posral stream is ever expected to accumulate.
     *
     * @param prefix tag prefix to match (case-insensitive), e.g. {@code "posral-"}
     * @return the newest matching release, or {@code null} if none matched
     */
    @Nullable
    private static GitHubRelease fetchLatestPrefixedRelease(String prefix) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(GITHUB_API_RELEASES_URL);
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

            GitHubRelease[] releases = GSON.fromJson(sb.toString(), GitHubRelease[].class);
            if (releases == null) {
                return null;
            }
            GitHubRelease best = null;
            for (GitHubRelease release : releases) {
                if (release != null && release.tagName != null
                        && release.tagName.toLowerCase().startsWith(prefix)) {
                    if (best == null) {
                        best = release;
                    } else if (release.publishedAt != null && best.publishedAt != null
                            && release.publishedAt.compareTo(best.publishedAt) > 0) {
                        best = release;
                    }
                }
            }
            return best;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Fetches commit log (shortHash: message) from GitHub since the embedded
     * build hash up to (including) the release commit. Parses release body
     * in both plain and Markdown format ({@code - **Short Hash:**}).
     * Returns formatted string or {@code null} on failure.
     */
    @Nullable
    public static String fetchCommitLog(String tagName, String releaseBody) {
        String sinceHash = BuildConfig.GIT_COMMIT_SHORT;
        if (TextUtils.isEmpty(sinceHash) || "unknown".equals(sinceHash)) {
            return null;
        }

        String releaseRef = null;
        String branch = null;
        if (!TextUtils.isEmpty(releaseBody)) {
            for (String line : releaseBody.split("\n")) {
                String trimmed = line.trim();
                int idx;
                if ((idx = trimmed.indexOf("Short Hash:")) >= 0) {
                    releaseRef = trimmed.substring(idx + "Short Hash:".length()).trim();
                } else if ((idx = trimmed.indexOf("Branch:")) >= 0) {
                    branch = trimmed.substring(idx + "Branch:".length()).trim();
                }
            }
        }
        // Strip Markdown formatting (**bold**, `backticks`)
        if (releaseRef != null) {
            releaseRef = releaseRef.replace("*", "").replace("`", "").trim();
        }
        if (branch != null) {
            branch = branch.replace("*", "").replace("`", "").trim();
        }

        // Fallback: use tag name (strip posral- prefix)
        if (TextUtils.isEmpty(releaseRef) && !TextUtils.isEmpty(tagName)) {
            String tag = tagName;
            boolean posral = CHANNEL_POSRAL.equalsIgnoreCase(BuildConfig.BUILD_CHANNEL);
            if (posral && tag.toLowerCase().startsWith(POSRAL_TAG_PREFIX)) {
                tag = tag.substring(POSRAL_TAG_PREFIX.length());
            }
            releaseRef = tag.trim();
        }
        if (TextUtils.isEmpty(releaseRef)) {
            return null;
        }

        boolean posral = CHANNEL_POSRAL.equalsIgnoreCase(BuildConfig.BUILD_CHANNEL);
        if (TextUtils.isEmpty(branch)) {
            branch = posral ? "ayu-features" : "master";
        }

        // If release ref matches embedded hash, no new commits
        String localLower = sinceHash.trim().toLowerCase();
        String releaseLower = releaseRef.trim().toLowerCase();
        if (localLower.startsWith(releaseLower) || releaseLower.startsWith(localLower)) {
            return null;
        }

        try {
            URL url = new URL("https://api.github.com/repos/sinkclose/Xenon/commits"
                    + "?sha=" + URLEncoder.encode(branch, "UTF-8")
                    + "&per_page=100");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Xenon-Updater/" + BuildConfig.VERSION_NAME);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            int code = connection.getResponseCode();
            if (code != 200) {
                connection.disconnect();
                return null;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(4096);
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            connection.disconnect();

            JSONArray arr = new JSONArray(sb.toString());
            StringBuilder result = new StringBuilder();
            boolean collecting = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String sha = obj.optString("sha", "");
                if (sha.isEmpty()) continue;
                String shaLower = sha.trim().toLowerCase();

                // Stop at embedded hash
                if (shaLower.startsWith(localLower) || localLower.startsWith(shaLower)) {
                    break;
                }
                // Start collecting from release commit
                if (!collecting) {
                    if (shaLower.startsWith(releaseLower) || releaseLower.startsWith(shaLower)) {
                        collecting = true;
                    } else {
                        continue;
                    }
                }

                String shortHash = sha.length() >= 7 ? sha.substring(0, 7) : sha;
                JSONObject commitObj = obj.optJSONObject("commit");
                String msg = commitObj != null ? commitObj.optString("message", "") : "";
                String firstLine = msg.isEmpty() ? "" : msg.split("\n", 2)[0].trim();
                if (result.length() > 0) result.append('\n');
                result.append(shortHash).append(": ").append(firstLine);
            }
            return result.length() > 0 ? result.toString() : null;
        } catch (Exception e) {
            FileLog.e(TAG, e);
            return null;
        }
    }

    /**
     * Fetches the best APK download URL from release assets.
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
