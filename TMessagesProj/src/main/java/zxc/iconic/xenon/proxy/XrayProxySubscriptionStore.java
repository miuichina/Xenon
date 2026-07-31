package zxc.iconic.xenon.proxy;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;

/**
 * Persistent storage for Xray proxy subscriptions (remote URLs that are fetched and
 * parsed into proxy profiles). Profiles imported from a subscription are tagged with
 * the subscription id in {@link XrayProxyProfileStore} so they can be replaced on refresh.
 */
public final class XrayProxySubscriptionStore {

    private static final String PREFS_NAME = "nekoconfig";
    private static final String KEY_SUBS_JSON = "xrayProxySubscriptionsV1";

    private static final Object LOCK = new Object();
    private static ArrayList<Subscription> cached;

    private XrayProxySubscriptionStore() {
    }

    public static final class Subscription {
        public String id;
        public String name;
        public String url;
        public long lastUpdated;

        public Subscription copy() {
            Subscription copy = new Subscription();
            copy.id = id;
            copy.name = name;
            copy.url = url;
            copy.lastUpdated = lastUpdated;
            return copy;
        }

        private JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", id == null ? "" : id);
            json.put("name", name == null ? "" : name);
            json.put("url", url == null ? "" : url);
            json.put("lastUpdated", lastUpdated);
            return json;
        }

        private static Subscription fromJson(JSONObject json) {
            if (json == null) {
                return null;
            }
            Subscription sub = new Subscription();
            sub.id = json.optString("id", "");
            sub.name = json.optString("name", "");
            sub.url = json.optString("url", "");
            sub.lastUpdated = json.optLong("lastUpdated", 0);
            if (TextUtils.isEmpty(sub.id)) {
                return null;
            }
            return sub;
        }
    }

    public static ArrayList<Subscription> getSubscriptions() {
        synchronized (LOCK) {
            ensureLoadedLocked();
            ArrayList<Subscription> result = new ArrayList<>(cached.size());
            for (int i = 0; i < cached.size(); i++) {
                result.add(cached.get(i).copy());
            }
            return result;
        }
    }

    public static Subscription addSubscription(String url, String name) {
        synchronized (LOCK) {
            ensureLoadedLocked();
            Subscription sub = new Subscription();
            sub.id = "sub_" + System.currentTimeMillis() + "_" + ((int) (Math.random() * 100000));
            sub.url = url == null ? "" : url.trim();
            sub.name = TextUtils.isEmpty(name) ? deriveName(sub.url) : name.trim();
            sub.lastUpdated = 0;
            cached.add(sub);
            persistLocked();
            return sub.copy();
        }
    }

    public static boolean deleteSubscription(String id) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            for (int i = 0; i < cached.size(); i++) {
                if (id.equals(cached.get(i).id)) {
                    cached.remove(i);
                    persistLocked();
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean setUpdated(String id, long timestamp) {
        if (TextUtils.isEmpty(id)) {
            return false;
        }
        synchronized (LOCK) {
            ensureLoadedLocked();
            for (int i = 0; i < cached.size(); i++) {
                if (id.equals(cached.get(i).id)) {
                    cached.get(i).lastUpdated = timestamp;
                    persistLocked();
                    return true;
                }
            }
            return false;
        }
    }

    private static void ensureLoadedLocked() {
        if (cached != null) {
            return;
        }
        cached = new ArrayList<>();
        SharedPreferences prefs = getPreferences();
        if (prefs == null) {
            return;
        }
        String raw = prefs.getString(KEY_SUBS_JSON, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Subscription sub = Subscription.fromJson(array.optJSONObject(i));
                if (sub != null) {
                    cached.add(sub);
                }
            }
        } catch (Throwable ignore) {
            cached.clear();
        }
    }

    private static void persistLocked() {
        SharedPreferences prefs = getPreferences();
        if (prefs == null) {
            return;
        }
        JSONArray array = new JSONArray();
        for (int i = 0; i < cached.size(); i++) {
            try {
                array.put(cached.get(i).toJson());
            } catch (Throwable ignore) {
            }
        }
        prefs.edit().putString(KEY_SUBS_JSON, array.toString()).apply();
    }

    private static String deriveName(String url) {
        if (TextUtils.isEmpty(url)) {
            return "Subscription";
        }
        try {
            String host = java.net.URI.create(url).getHost();
            if (!TextUtils.isEmpty(host)) {
                return host;
            }
        } catch (Throwable ignore) {
        }
        return "Subscription";
    }

    private static SharedPreferences getPreferences() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
    }
}
