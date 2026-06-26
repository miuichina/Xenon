package zxc.iconic.xenon.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.lib.VarArgFunction;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

/**
 * Builds the Lua-side API exposed to plugins as the global {@code xenon} table.
 *
 * <h3>Registration</h3>
 * <pre>{@code
 * xenon.on("onResume", function()
 *     xenon.sendMessage("test", 3284201935)
 * end)
 * }</pre>
 *
 * <h3>onSendMessage hook</h3>
 * The handler receives a table {@code {message=string, peer=number}} and may
 * return a table to control the send:
 * <pre>{@code
 * xenon.on("onSendMessage", function(ctx)
 *     -- rewrite the outgoing text
 *     return { message = ctx.message .. " :)", peer = ctx.peer }
 *     -- or block it entirely:
 *     -- return { cancel = true }
 * end)
 * }</pre>
 * Returning {@code nil} (or no return) leaves the send unchanged.
 *
 * <p>All functions run on whatever thread the host hook fired on; host actions
 * that require the main thread (e.g. actually sending a message) marshal
 * themselves there via {@link org.telegram.messenger.AndroidUtilities}.
 */
public class PluginApi {

    static volatile String currentPluginFileName;

    static void setCurrentPluginFileName(String name) {
        currentPluginFileName = name;
    }

    static LuaTable[] createApiTable(Globals globals) {
        LuaTable hooks = new LuaTable();
        globals.set("xenon_hooks", hooks);

        LuaTable api = new LuaTable();
        api.set("on", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue handler) {
                Log.d("XenonPlugin", "xenon.on called: name=" + name.tojstring() + " handler_type=" + handler.type());
                hooks.set(name.checkjstring(), handler);
                Log.d("XenonPlugin", "xenon.on: registered '" + name.tojstring() + "', hooks size=" + hooks.length());
                return LuaValue.NIL;
            }
        });
        api.set("sendMessage", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue text, LuaValue peer) {
                Log.d("XenonPlugin", "xenon.sendMessage called: text=" + text.tojstring() + " peer=" + peer.tolong());
                sendMessage(text.checkjstring(), peer.checklong());
                return LuaValue.NIL;
            }
        });
        api.set("toast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                Log.d("XenonPlugin", "xenon.toast called: " + arg.tojstring());
                showToast(arg.checkjstring());
                return LuaValue.NIL;
            }
        });
        api.set("bulletin", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                Log.d("XenonPlugin", "xenon.bulletin called: " + arg.tojstring());
                showBulletin(arg.checkjstring());
                return LuaValue.NIL;
            }
        });
        api.set("getSetting", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue def) {
                String val = getSetting(key.checkjstring(), def.isnil() ? null : def.tojstring());
                if (val == null) return LuaValue.NIL;
                return LuaValue.valueOf(val);
            }
        });
        api.set("setSetting", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue key, LuaValue value) {
                setSetting(key.checkjstring(), value.tojstring());
                return LuaValue.NIL;
            }
        });
        api.set("getOpenChatId", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(PluginManager.getCurrentDialogId());
            }
        });
        api.set("openActivity", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String name = arg.checkjstring();
                openActivity(name);
                return LuaValue.NIL;
            }
        });
        final String capturedFileName = currentPluginFileName;
        api.set("openPluginSettings", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                if (capturedFileName != null) {
                    openPluginSettingsFor(capturedFileName);
                }
                return LuaValue.NIL;
            }
        });
        api.set("createDialog", new VarArgFunction() {
            @Override
            public LuaValue invoke(LuaValue[] args) {
                String title = args.length > 0 ? args[0].optjstring("") : "";
                String message = args.length > 1 ? args[1].optjstring("") : "";
                LuaValue buttons = args.length > 2 ? args[2] : LuaValue.NIL;
                createDialog(title, message, buttons);
                return LuaValue.NIL;
            }
        });
        api.set("createBottomSheet", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue title, LuaValue items) {
                createBottomSheet(title.optjstring(""), items);
                return LuaValue.NIL;
            }
        });
        api.set("finish", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                PluginManager.setRequestFinishFragment(true);
                return LuaValue.NIL;
            }
        });
        Log.d("XenonPlugin", "createApiTable: hooks table created, identityHash=" + System.identityHashCode(hooks));
        return new LuaTable[]{api, hooks};
    }

    static void sendMessage(final String text, final long peerId) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                int account = UserConfig.selectedAccount;
                MessagesController mc = MessagesController.getInstance(account);
                long dialogId = resolveDialogId(peerId, mc);
                if (dialogId != 0) {
                    doSendMessage(account, mc, dialogId, text);
                    return;
                }
                loadFromDbAndSend(account, peerId, text, mc);
            } catch (Exception e) {
                FileLog.e("Plugin sendMessage failed", e);
                Log.e("XenonPlugin", "sendMessage exception: " + e.getMessage());
            }
        });
    }

    private static long resolveDialogId(long peerId, MessagesController mc) {
        if (peerId > 0) {
            TLRPC.User user = mc.getUser(peerId);
            if (user != null) return peerId;
            TLRPC.Chat chat = mc.getChat(peerId);
            if (chat != null) return -peerId;
        } else {
            TLRPC.Chat chat = mc.getChat(-peerId);
            if (chat != null) return peerId;
        }
        return 0;
    }

    private static void loadFromDbAndSend(int account, long peerId, String text, MessagesController mc) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        if (peerId > 0) {
            TLRPC.User user = storage.getUser(peerId);
            if (user != null) {
                mc.putUser(user, true);
                doSendMessage(account, mc, peerId, text);
                return;
            }
            TLRPC.Chat chat = storage.getChat(peerId);
            if (chat != null) {
                mc.putChat(chat, true);
                doSendMessage(account, mc, -peerId, text);
                return;
            }
        } else {
            TLRPC.Chat chat = storage.getChat(-peerId);
            if (chat != null) {
                mc.putChat(chat, true);
                doSendMessage(account, mc, peerId, text);
                return;
            }
        }
        loadPeerAndSend(account, peerId, text, mc);
    }

    private static void loadPeerAndSend(int account, long peerId, String text, MessagesController mc) {
        if (peerId < 0) {
            loadChat(account, -peerId, () -> {
                TLRPC.Chat chat = mc.getChat(-peerId);
                if (chat != null) {
                    doSendMessage(account, mc, peerId, text);
                } else {
                    Log.e("XenonPlugin", "sendMessage: failed to load chat " + (-peerId));
                }
            });
        } else {
            loadChat(account, peerId, () -> {
                TLRPC.Chat chat = mc.getChat(peerId);
                if (chat != null) {
                    doSendMessage(account, mc, -peerId, text);
                } else {
                    loadUser(account, peerId, () -> {
                        TLRPC.User user = mc.getUser(peerId);
                        if (user != null) {
                            doSendMessage(account, mc, peerId, text);
                        } else {
                            Log.e("XenonPlugin", "sendMessage: failed to load peer " + peerId);
                        }
                    });
                }
            });
        }
    }

    private static void loadChat(int account, long chatId, Runnable callback) {
        TLRPC.TL_messages_getChats req = new TLRPC.TL_messages_getChats();
        req.id.add(chatId);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof TLRPC.messages_Chats) {
                TLRPC.messages_Chats chats = (TLRPC.messages_Chats) response;
                for (TLRPC.Chat chat : chats.chats) {
                    MessagesController.getInstance(account).putChat(chat, false);
                }
            } else {
                Log.e("XenonPlugin", "loadChat error: " + (error != null ? error.text : "bad response"));
            }
            callback.run();
        });
    }

    private static void loadUser(int account, long userId, Runnable callback) {
        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = (int) userId;
        inputUser.access_hash = 0;
        req.id.add(inputUser);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null && response instanceof org.telegram.tgnet.Vector) {
                org.telegram.tgnet.Vector vector = (org.telegram.tgnet.Vector) response;
                for (Object obj : vector.objects) {
                    if (obj instanceof TLRPC.User) {
                        MessagesController.getInstance(account).putUser((TLRPC.User) obj, false);
                    }
                }
            } else {
                Log.e("XenonPlugin", "loadUser error: " + (error != null ? error.text : "bad response"));
            }
            callback.run();
        });
    }

    private static void doSendMessage(int account, MessagesController mc, long dialogId, String text) {
        TLRPC.InputPeer inputPeer = mc.getInputPeer(dialogId);
        if (inputPeer == null) {
            Log.e("XenonPlugin", "doSendMessage: getInputPeer returned null for dialogId=" + dialogId);
            return;
        }
        TLRPC.TL_messages_sendMessage req = new TLRPC.TL_messages_sendMessage();
        req.peer = inputPeer;
        req.message = text;
        req.random_id = Utilities.random.nextLong();
        req.clear_draft = true;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null) {
                FileLog.e("Plugin sendMessage error: " + error.text);
                Log.e("XenonPlugin", "sendMessage error: " + error.text);
            } else if (response instanceof TLRPC.Updates) {
                mc.processUpdates((TLRPC.Updates) response, false);
            }
        });
    }

    static void openActivity(final String name) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                Intent intent;
                switch (name) {
                    case "settings":
                        intent = new Intent(a, org.telegram.ui.LaunchActivity.class);
                        intent.setAction("neko_settings");
                        break;
                    case "plugins":
                        intent = new Intent(a, org.telegram.ui.LaunchActivity.class);
                        intent.setAction("neko_plugins");
                        break;
                    default:
                        Log.e("XenonPlugin", "openActivity: unknown activity '" + name + "'");
                        return;
                }
                a.startActivity(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void openPluginSettingsFor(final String fileName) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (!(a instanceof org.telegram.ui.LaunchActivity)) return;
                org.telegram.ui.LaunchActivity launch = (org.telegram.ui.LaunchActivity) a;
                PluginManager.LoadedPlugin plugin = PluginManager.getInstance().findPlugin(fileName);
                if (plugin != null) {
                    launch.presentFragment(new zxc.iconic.xenon.settings.PluginSettingsActivity().setPlugin(plugin));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    static void createDialog(final String title, final String message, final LuaValue buttons) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                if (zxc.iconic.xenon.NekoConfig.replaceDialogsWithSheet) {
                    showSheetAsDialog(a, title, message, buttons);
                } else {
                    AlertDialog.Builder builder = new AlertDialog.Builder(a);
                    if (title != null && !title.isEmpty()) builder.setTitle(title);
                    if (message != null && !message.isEmpty()) builder.setMessage(message);
                    if (buttons.istable()) {
                        LuaTable t = (LuaTable) buttons;
                        if (t.length() > 0) {
                            LuaValue pos = t.get(1);
                            if (!pos.isnil()) {
                                String text = pos.optjstring("OK");
                                LuaValue cb = t.get("callback1");
                                builder.setPositiveButton(text, (dialog, which) -> {
                                    if (cb != null && !cb.isnil()) cb.call();
                                });
                            }
                        }
                        if (t.length() > 1) {
                            LuaValue neg = t.get(2);
                            if (!neg.isnil()) {
                                String text = neg.optjstring("Cancel");
                                LuaValue cb = t.get("callback2");
                                builder.setNegativeButton(text, (dialog, which) -> {
                                    if (cb != null && !cb.isnil()) cb.call();
                                });
                            }
                        }
                    }
                    builder.show();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static void showSheetAsDialog(Activity a, String title, String message, LuaValue buttons) {
        org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(a, false, null);
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(a);
            tv.setText(title);
            tv.setTextSize(18);
            tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            layout.addView(tv);
        }
        if (message != null && !message.isEmpty()) {
            TextView mv = new TextView(a);
            mv.setText(message);
            mv.setTextSize(15);
            mv.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            mv.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
            layout.addView(mv);
        }
        final org.telegram.ui.ActionBar.BottomSheet[] sheetRef = new org.telegram.ui.ActionBar.BottomSheet[1];
        if (buttons.istable()) {
            LuaTable t = (LuaTable) buttons;
            if (t.length() > 0) {
                LuaValue pos = t.get(1);
                if (!pos.isnil()) {
                    String text = pos.optjstring("OK");
                    LuaValue cb = t.get("callback1");
                    TextView btn = new TextView(a);
                    btn.setText(text);
                    btn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    btn.setTextSize(16);
                    btn.setGravity(android.view.Gravity.CENTER);
                    btn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                    final LuaValue callback = cb;
                    btn.setOnClickListener(v -> {
                        if (callback != null && !callback.isnil()) callback.call();
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                    });
                    layout.addView(btn);
                }
            }
            if (t.length() > 1) {
                LuaValue neg = t.get(2);
                if (!neg.isnil()) {
                    String text = neg.optjstring("Cancel");
                    LuaValue cb = t.get("callback2");
                    TextView btn = new TextView(a);
                    btn.setText(text);
                    btn.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
                    btn.setTextSize(16);
                    btn.setGravity(android.view.Gravity.CENTER);
                    btn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                    final LuaValue callback = cb;
                    btn.setOnClickListener(v -> {
                        if (callback != null && !callback.isnil()) callback.call();
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                    });
                    layout.addView(btn);
                }
            }
        }
        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    static void createBottomSheet(final String title, final LuaValue items) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                Activity a = PluginManager.getCurrentActivity();
                if (a == null) return;
                org.telegram.ui.ActionBar.BottomSheet.Builder builder = new org.telegram.ui.ActionBar.BottomSheet.Builder(a, false, null);
                LinearLayout layout = new LinearLayout(a);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
                if (title != null && !title.isEmpty()) {
                    TextView tv = new TextView(a);
                    tv.setText(title);
                    tv.setTextSize(18);
                    tv.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                    tv.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                    tv.setPadding(0, 0, 0, AndroidUtilities.dp(12));
                    layout.addView(tv);
                }
                final org.telegram.ui.ActionBar.BottomSheet[] sheetRef = new org.telegram.ui.ActionBar.BottomSheet[1];
                if (items.istable()) {
                    LuaTable t = (LuaTable) items;
                    for (int i = 1; i <= t.length(); i++) {
                        LuaValue entry = t.get(i);
                        if (!entry.istable()) continue;
                        LuaValue cb = entry.get("callback");
                        TextView row = new TextView(a);
                        row.setText(entry.get("text").optjstring(""));
                        row.setTextSize(16);
                        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
                        if (cb != null && !cb.isnil()) {
                            final LuaValue onClick = cb;
                            row.setOnClickListener(v -> {
                                onClick.call();
                                if (sheetRef[0] != null) sheetRef[0].dismiss();
                            });
                        }
                        layout.addView(row);
                        if (i < t.length()) {
                            View sep = new View(a);
                            sep.setBackgroundColor(Theme.getColor(Theme.key_divider));
                            sep.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
                            layout.addView(sep);
                        }
                    }
                }
                builder.setCustomView(layout);
                sheetRef[0] = builder.show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static int getIconDrawable(String iconName) {
        switch (iconName) {
            case "add": return org.telegram.messenger.R.drawable.msg_addcontact;
            case "autodelete": return org.telegram.messenger.R.drawable.msg_autodelete;
            case "background": return org.telegram.messenger.R.drawable.msg_background;
            case "block": return org.telegram.messenger.R.drawable.msg_block2;
            case "calendar": return org.telegram.messenger.R.drawable.msg_calendar;
            case "call": return org.telegram.messenger.R.drawable.msg_callback;
            case "cancel": return org.telegram.messenger.R.drawable.msg_cancel;
            case "channel": return org.telegram.messenger.R.drawable.msg_channel;
            case "clear": return org.telegram.messenger.R.drawable.msg_clear;
            case "copy": return org.telegram.messenger.R.drawable.msg_copy;
            case "delete": return org.telegram.messenger.R.drawable.msg_delete;
            case "discussion": return org.telegram.messenger.R.drawable.msg_discussion;
            case "edit": return org.telegram.messenger.R.drawable.msg_edit;
            case "fave": case "heart": return org.telegram.messenger.R.drawable.msg_fave;
            case "unfave": return org.telegram.messenger.R.drawable.msg_unfave;
            case "forward": return org.telegram.messenger.R.drawable.msg_forward;
            case "help": return org.telegram.messenger.R.drawable.msg_help;
            case "home": return org.telegram.messenger.R.drawable.msg_home;
            case "info": return org.telegram.messenger.R.drawable.msg_info;
            case "leave": return org.telegram.messenger.R.drawable.msg_leave;
            case "link": return org.telegram.messenger.R.drawable.msg_link;
            case "log": return org.telegram.messenger.R.drawable.msg_log;
            case "mute": return org.telegram.messenger.R.drawable.msg_mute;
            case "pin": return org.telegram.messenger.R.drawable.msg_pin;
            case "report": return org.telegram.messenger.R.drawable.msg_report;
            case "saved": return org.telegram.messenger.R.drawable.msg_saved;
            case "search": return org.telegram.messenger.R.drawable.msg_search;
            case "send": return org.telegram.messenger.R.drawable.msg_send;
            case "settings": return org.telegram.messenger.R.drawable.msg_settings_old;
            case "share": return org.telegram.messenger.R.drawable.msg_share;
            case "stats": return org.telegram.messenger.R.drawable.msg_stats;
            case "sticker": return org.telegram.messenger.R.drawable.msg_sticker;
            case "theme": return org.telegram.messenger.R.drawable.msg_theme;
            case "topic": return org.telegram.messenger.R.drawable.msg_topics;
            case "translate": return org.telegram.messenger.R.drawable.msg_translate;
            case "videocall": return org.telegram.messenger.R.drawable.msg_videocall;
            case "vote": return org.telegram.messenger.R.drawable.msg_unvote;
            default: return org.telegram.messenger.R.drawable.msg_edit;
        }
    }

    private static SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("xenon_plugins", Context.MODE_PRIVATE);
    }

    static String getSetting(String key, String defaultValue) {
        return getPrefs().getString(key, defaultValue);
    }

    static void setSetting(String key, String value) {
        getPrefs().edit().putString(key, value).apply();
    }

    static void showBulletin(final String text) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, text).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    static void showToast(final String text) {
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
            try {
                android.widget.Toast.makeText(
                        ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
