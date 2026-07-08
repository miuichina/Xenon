package zxc.iconic.xenon.helpers;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.OctagonBadgeDrawable;
import org.telegram.ui.Components.TextBadgeDrawable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

public class CustomBadgeController {

    private static final String BADGE_URL = "https://gist.githubusercontent.com/miuichina/021db1054eab6820e00c927c910e534a/raw/gistfile1.txt";
    private static final String PREFS_NAME = "custom_badges";
    private static final String KEY_CACHE = "badge_cache_v2";
    private static final String KEY_CACHE_TIME = "badge_cache_time";
    private static final long CACHE_TTL_MS = 3 * 60 * 60 * 1000L; // 3 hours
    private static final long CHANNEL_ID_OFFSET = 1_000_000_000_000L;

    /**
     * Holds all data for a single badge entry from the remote list.
     *
     * <p>Gist format (4 columns): {@code id, en_desc, ru_desc, badge_id}
     * <ul>
     *   <li>{@code badge_id == 0} → {@link OctagonBadgeDrawable} (animated octagon with text)</li>
     *   <li>{@code badge_id == 1} → {@link TextBadgeDrawable} (plain text, no background)</li>
     *   <li>{@code badge_id  > 1} → {@link AnimatedEmojiDrawable} using the value as document ID</li>
     * </ul>
     * Backward-compatible 2-column format: {@code id, desc} is treated as {@code badge_id = 0}.
     */
    public static class BadgeInfo {
        public final String descEn;
        public final String descRu;
        /** 0 = octagon, 1 = text-only, >1 = animated-emoji document ID */
        public final long badgeDocumentId;

        public BadgeInfo(String descEn, String descRu, long badgeDocumentId) {
            this.descEn = descEn != null ? descEn : "";
            this.descRu = descRu != null ? descRu : "";
            this.badgeDocumentId = badgeDocumentId;
        }

        /** Returns the description in the current app locale (ru → ru_desc, else en_desc). */
        public String getLocalizedDesc() {
            try {
                LocaleController.LocaleInfo localeInfo = LocaleController.getInstance().getCurrentLocaleInfo();
                if (localeInfo != null) {
                    String lang = localeInfo.pluralLangCode;
                    if (lang != null && lang.startsWith("ru") && !descRu.isEmpty()) {
                        return descRu;
                    }
                }
            } catch (Exception ignored) {}
            return !descEn.isEmpty() ? descEn : descRu;
        }

        /** Short display text that goes inside the badge shape (for non-emoji types). */
        public String getDisplayText() {
            return getLocalizedDesc();
        }
    }

    // -------------------------------------------------------------------------

    private static volatile CustomBadgeController instance;
    private volatile ConcurrentHashMap<Long, BadgeInfo> badges = new ConcurrentHashMap<>();

    public static CustomBadgeController getInstance() {
        if (instance == null) {
            synchronized (CustomBadgeController.class) {
                if (instance == null) {
                    instance = new CustomBadgeController();
                }
            }
        }
        return instance;
    }

    private CustomBadgeController() {
        loadCache();
    }

    public void init() {
        long lastFetch = getPrefs().getLong(KEY_CACHE_TIME, 0);
        boolean expired = System.currentTimeMillis() - lastFetch > CACHE_TTL_MS;
        // Always refetch when the in-memory map is empty so channels/users get badges
        // even if the cache file was wiped or failed to parse.
        if (expired || badges.isEmpty()) {
            FileLog.d("CustomBadgeController: refetching (expired=" + expired + ", empty=" + badges.isEmpty() + ")");
            Utilities.globalQueue.postRunnable(this::fetchBadges);
        }
    }

    // -------------------------------------------------------------------------
    // Public query API
    // -------------------------------------------------------------------------

    /**
     * Returns {@link BadgeInfo} for the given entity ID, or {@code null} if none.
     *
     * <p>Accepts any common representation used across Telegram / Bot API / gists:
     * <ul>
     *   <li>user id (positive)</li>
     *   <li>chat/channel id (positive raw {@code chat.id})</li>
     *   <li>modern dialog id ({@code -chat.id})</li>
     *   <li>Bot API / classic dialog id ({@code -100xxxxxxxxxx})</li>
     * </ul>
     */
    public BadgeInfo getBadgeInfo(long id) {
        if (id == 0) {
            return null;
        }
        BadgeInfo info = badges.get(id);
        if (info != null) {
            return info;
        }
        // Walk all equivalent key forms. Order is intentional: cheapest first.
        if (id > 0) {
            // Positive: user id OR raw chat/channel id.
            // Also try modern dialog (-id) and Bot API (-100...id) for channel gists.
            info = badges.get(-id);
            if (info == null) {
                info = badges.get(-CHANNEL_ID_OFFSET - id);
            }
        } else if (id <= -CHANNEL_ID_OFFSET) {
            // Bot API / classic: -100xxxxxxxxxx  →  channelId = -id - 10^12
            long channelId = -id - CHANNEL_ID_OFFSET;
            info = badges.get(-channelId);   // modern dialog
            if (info == null) {
                info = badges.get(channelId); // positive chat.id
            }
        } else {
            // Modern dialog: -chatId
            info = badges.get(id - CHANNEL_ID_OFFSET); // Bot API form
            if (info == null) {
                info = badges.get(-id); // positive chat.id
            }
        }
        return info;
    }

    /**
     * Canonical dialog id for a chat/channel peer ({@code -chat.id}),
     * matching {@link org.telegram.messenger.DialogObject#getDialogId}.
     */
    public static long chatDialogId(long chatId) {
        return chatId == 0 ? 0 : -chatId;
    }

    /** Returns the localized description string for the given ID, or {@code null}. */
    public String getDescription(long id) {
        BadgeInfo info = getBadgeInfo(id);
        return info != null ? info.getLocalizedDesc() : null;
    }

    public boolean hasBadge(long id) {
        return getBadgeInfo(id) != null;
    }

    public int badgeCount() {
        return badges.size();
    }

    // -------------------------------------------------------------------------
    // Badge drawable factory
    // -------------------------------------------------------------------------

    /**
     * Creates the correct drawable for the badge associated with {@code entityId}.
     *
     * @param entityId       User/channel/chat ID whose badge should be shown.
     * @param currentAccount Active account number (needed for animated emoji).
     * @param callbackView   View that will own the drawable's invalidation callbacks
     *                       (required for animated emoji; may be {@code null} for static types).
     * @param small          {@code true} for the small (20dp) size variant.
     * @param rp             Optional theme resources provider.
     * @return A ready-to-use {@link Drawable}, or {@code null} if no badge exists for this ID.
     */
    public Drawable createBadge(long entityId, int currentAccount, View callbackView,
                                boolean small, Theme.ResourcesProvider rp) {
        BadgeInfo info = getBadgeInfo(entityId);
        if (info == null) return null;

        float sizePx = AndroidUtilities.dp(small ? 20 : 24);

        if (info.badgeDocumentId > 1) {
            // Animated premium emoji – same pipeline as emoji status
            AnimatedEmojiDrawable emoji = AnimatedEmojiDrawable.make(
                    currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_EMOJI_STATUS, info.badgeDocumentId);
            if (callbackView != null) {
                emoji.setCallback(callbackView);
            }
            return emoji;
        } else if (info.badgeDocumentId == 1) {
            // Plain text badge, no background
            return new TextBadgeDrawable(info.getDisplayText(), small, rp);
        } else {
            // Animated octagon badge (badge_id == 0)
            OctagonBadgeDrawable d = new OctagonBadgeDrawable(info.getDisplayText(), rp);
            d.setSize(sizePx);
            return d;
        }
    }

    /**
     * Convenience overload that uses the currently selected account and no callback view.
     * Suitable for static (non-animated-emoji) badge types.
     */
    public Drawable createBadge(long entityId, View callbackView, boolean small,
                                Theme.ResourcesProvider rp) {
        return createBadge(entityId, UserConfig.selectedAccount, callbackView, small, rp);
    }

    // -------------------------------------------------------------------------
    // Lifecycle Helpers
    // -------------------------------------------------------------------------

    public void onAttachedToWindow(Drawable badge, View view) {
        if (badge instanceof AnimatedEmojiDrawable) {
            ((AnimatedEmojiDrawable) badge).addView(view);
        }
    }

    public void onDetachedFromWindow(Drawable badge, View view) {
        if (badge instanceof AnimatedEmojiDrawable) {
            ((AnimatedEmojiDrawable) badge).removeView(view);
        }
    }

    // -------------------------------------------------------------------------
    // Remote fetch
    // -------------------------------------------------------------------------

    private void fetchBadges() {
        try {
            URL url = new URL(BADGE_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                FileLog.d("CustomBadgeController: HTTP " + code);
                return;
            }

            ConcurrentHashMap<Long, BadgeInfo> parsed = new ConcurrentHashMap<>();
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    // Split into at most 4 parts
                    String[] parts = line.split(",", 4);
                    if (parts.length < 2) continue;

                    String idStr = parts[0].trim();
                    if (idStr.isEmpty()) continue;

                    try {
                        long id = Long.parseLong(idStr);
                        String descEn = parts[1].trim();
                        String descRu = parts.length >= 3 ? parts[2].trim() : "";
                        long badgeDocId = 0;
                        if (parts.length >= 4) {
                            try {
                                badgeDocId = Long.parseLong(parts[3].trim());
                            } catch (NumberFormatException e) {
                                FileLog.d("CustomBadgeController: bad badge_id in line: " + line);
                            }
                        }
                        parsed.put(id, new BadgeInfo(descEn, descRu, badgeDocId));
                    } catch (NumberFormatException e) {
                        FileLog.d("CustomBadgeController: bad id in line: " + line);
                    }
                }
            }
            conn.disconnect();
            badges = parsed;
            saveCache();
            FileLog.d("CustomBadgeController: loaded " + badges.size() + " badges");
            // Refresh visible UI so freshly fetched badges show up without requiring
            // the user to leave and re-enter the screen.
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    NotificationCenter.getInstance(UserConfig.selectedAccount)
                            .postNotificationName(NotificationCenter.updateInterfaces,
                                    MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // -------------------------------------------------------------------------
    // Cache persistence
    // -------------------------------------------------------------------------

    private void loadCache() {
        try {
            String json = getPrefs().getString(KEY_CACHE, null);
            if (json == null) return;
            JSONArray arr = new JSONArray(json);
            ConcurrentHashMap<Long, BadgeInfo> parsed = new ConcurrentHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                long id = obj.getLong("id");
                String descEn = obj.optString("en", "");
                String descRu = obj.optString("ru", "");
                // Backward compat: old cache used "desc" only
                if (descEn.isEmpty()) descEn = obj.optString("desc", "");
                long badgeDocId = obj.optLong("bid", 0);
                parsed.put(id, new BadgeInfo(descEn, descRu, badgeDocId));
            }
            badges = parsed;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveCache() {
        try {
            JSONArray arr = new JSONArray();
            for (ConcurrentHashMap.Entry<Long, BadgeInfo> entry : badges.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("id", entry.getKey());
                obj.put("en", entry.getValue().descEn);
                obj.put("ru", entry.getValue().descRu);
                obj.put("bid", entry.getValue().badgeDocumentId);
                arr.put(obj);
            }
            getPrefs().edit()
                    .putString(KEY_CACHE, arr.toString())
                    .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    /**
     * Atomically replaces the current badge drawable with a new one for the given entity ID.
     * Handles detach of old drawable, creation of new one, callback registration, and attach lifecycle.
     * Returns the new drawable (or {@code null} if no badge exists for the entity).
     */
    public Drawable updateBadgeDrawable(Drawable current, long entityId, View parent,
                                        boolean attached, boolean small, Theme.ResourcesProvider rp) {
        return updateBadgeDrawable(current, entityId, UserConfig.selectedAccount, parent, attached, small, rp);
    }

    public Drawable updateBadgeDrawable(Drawable current, long entityId, int currentAccount,
                                        View parent, boolean attached, boolean small, Theme.ResourcesProvider rp) {
        BadgeInfo info = getBadgeInfo(entityId);

        // Reuse the existing drawable when it still matches: avoids re-allocating on every
        // layout pass and keeps the particle animation state instead of restarting it.
        if (info != null && current != null) {
            String text = info.getDisplayText();
            if (info.badgeDocumentId > 1
                    && current instanceof AnimatedEmojiDrawable
                    && ((AnimatedEmojiDrawable) current).getDocumentId() == info.badgeDocumentId) {
                return current;
            } else if (info.badgeDocumentId == 1
                    && current instanceof TextBadgeDrawable
                    && text.equals(((TextBadgeDrawable) current).getText())) {
                return current;
            } else if (info.badgeDocumentId == 0
                    && current instanceof OctagonBadgeDrawable
                    && text.equals(((OctagonBadgeDrawable) current).getText())) {
                return current;
            }
        }

        if (current != null && attached) {
            onDetachedFromWindow(current, parent);
        }
        Drawable next = null;
        if (info != null) {
            next = createBadge(entityId, currentAccount, parent, small, rp);
            if (next != null) {
                next.setCallback(parent);
                if (attached) {
                    onAttachedToWindow(next, parent);
                }
            }
        }
        return next;
    }
}
