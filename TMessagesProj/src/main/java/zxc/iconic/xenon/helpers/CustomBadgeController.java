package zxc.iconic.xenon.helpers;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.OctagonBadgeDrawable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

public class CustomBadgeController {

    private static final String BADGE_URL = "https://gist.githubusercontent.com/miuichina/021db1054eab6820e00c927c910e534a/raw/gistfile1.txt";
    private static final String PREFS_NAME = "custom_badges";
    private static final String KEY_CACHE = "badge_cache";
    private static final String KEY_CACHE_TIME = "badge_cache_time";
    private static final long CACHE_TTL_MS = 0;

    private static volatile CustomBadgeController instance;
    private final ConcurrentHashMap<Long, String> badges = new ConcurrentHashMap<>();

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
        loadCache();
        long lastFetch = getPrefs().getLong(KEY_CACHE_TIME, 0);
        if (System.currentTimeMillis() - lastFetch > CACHE_TTL_MS) {
            FileLog.d("CustomBadgeController: cache expired, refetching");
            Utilities.globalQueue.postRunnable(() -> {
                fetchBadges();
            });
        }
    }

    public String getDescription(long id) {
        if (badges.isEmpty()) {
            loadCache();
        }
        String desc = badges.get(id);
        if (desc == null && id < 0) {
            desc = badges.get(id - 1000000000000L);
        }
        return desc;
    }

    public String getDescriptionExact(long id) {
        if (badges.isEmpty()) {
            loadCache();
        }
        return badges.get(id);
    }

    public int badgeCount() {
        return badges.size();
    }

    public boolean hasBadge(long id) {
        return badges.containsKey(id);
    }

    public OctagonBadgeDrawable createDrawable(boolean small) {
        return createDrawable(small, null);
    }

    public OctagonBadgeDrawable createDrawable(boolean small, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) {
        OctagonBadgeDrawable d = new OctagonBadgeDrawable(resourcesProvider);
        if (small) {
            d.setSize(org.telegram.messenger.AndroidUtilities.dp(20));
        }
        return d;
    }

    private void fetchBadges() {
        try {
            URL url = new URL(BADGE_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                FileLog.d("CustomBadgeController: HTTP " + code);
                return;
            }
            ConcurrentHashMap<Long, String> parsed = new ConcurrentHashMap<>();
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int comma = line.indexOf(',');
                    if (comma <= 0) continue;
                    String idStr = line.substring(0, comma).trim();
                    String desc = line.substring(comma + 1).trim();
                    if (idStr.isEmpty() || desc.isEmpty()) continue;
                    try {
                        long id = Long.parseLong(idStr);
                        parsed.put(id, desc);
                    } catch (NumberFormatException e) {
                        FileLog.d("CustomBadgeController: bad id " + idStr);
                    }
                }
            }
            conn.disconnect();
            badges.clear();
            badges.putAll(parsed);
            saveCache();
            FileLog.d("CustomBadgeController: loaded " + badges.size() + " badges");
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void loadCache() {
        try {
            String json = getPrefs().getString(KEY_CACHE, null);
            if (json == null) {
                return;
            }
            JSONArray arr = new JSONArray(json);
            ConcurrentHashMap<Long, String> parsed = new ConcurrentHashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                parsed.put(obj.getLong("id"), obj.getString("desc"));
            }
            badges.putAll(parsed);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void saveCache() {
        try {
            JSONArray arr = new JSONArray();
            for (ConcurrentHashMap.Entry<Long, String> entry : badges.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("id", entry.getKey());
                obj.put("desc", entry.getValue());
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
}
