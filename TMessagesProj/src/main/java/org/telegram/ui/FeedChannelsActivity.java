package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import zxc.iconic.xenon.settings.BaseNekoSettingsActivity;

public class FeedChannelsActivity extends BaseNekoSettingsActivity {

    private static final String PREF_KEY_SELECTED = "feedSelectedChannels";
    private static final String PREF_KEY_VERSION = "feedSelectionVersion";

    private static final int MENU_OVERFLOW = 1;
    private static final int MENU_SELECT_ALL = 2;
    private static final int MENU_UNSELECT_ALL = 3;

    private final int channelsStartRow = 100;

    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private final HashSet<Long> selected = new HashSet<>();
    private boolean initialized;

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SELECT_ALL) {
                    selectAll(true);
                } else if (id == MENU_UNSELECT_ALL) {
                    selectAll(false);
                }
            }
        });
        ActionBarMenuItem menuItem = actionBar.createMenu().addItem(MENU_OVERFLOW, R.drawable.ic_ab_other);
        menuItem.addSubItem(MENU_SELECT_ALL, 0, LocaleController.getString(R.string.SelectAll));
        menuItem.addSubItem(MENU_UNSELECT_ALL, 0, LocaleController.getString(R.string.DeselectAll));
        return actionBar;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        initChannels();

        items.add(UItem.asHeader(LocaleController.getString(R.string.ChannelsTab)));

        if (channels.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsEmpty)));
            return;
        }

        for (int i = 0; i < channels.size(); i++) {
            TLRPC.Chat chat = channels.get(i);
            UItem item = UItem.asUserCheckbox(channelsStartRow + i, chat);
            item.checked = selected.contains(chat.id);
            items.add(item);
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int index = item.id - channelsStartRow;
        if (index < 0 || index >= channels.size()) {
            return;
        }
        TLRPC.Chat chat = channels.get(index);
        if (!selected.remove(chat.id)) {
            selected.add(chat.id);
        }
        saveSelected();
        item.checked = selected.contains(chat.id);
        notifyItemChanged(item.id);
    }

    private void initChannels() {
        if (initialized) {
            return;
        }
        initialized = true;

        MessagesController mc = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> all = mc.getAllDialogs();
        for (TLRPC.Dialog d : all) {
            if (d.id < 0 && DialogObject.isChannel(d)) {
                TLRPC.Chat chat = mc.getChat(-d.id);
                if (chat != null && !chat.megagroup) {
                    channels.add(chat);
                }
            }
        }

        Set<String> stored = MessagesController.getMainSettings(currentAccount).getStringSet(PREF_KEY_SELECTED, null);
        if (stored == null) {
            for (TLRPC.Chat chat : channels) {
                selected.add(chat.id);
            }
        } else {
            for (String s : stored) {
                try {
                    selected.add(Long.parseLong(s));
                } catch (NumberFormatException ignore) {
                }
            }
        }
    }

    private void selectAll(boolean select) {
        initChannels();
        selected.clear();
        if (select) {
            for (TLRPC.Chat chat : channels) {
                selected.add(chat.id);
            }
        }
        saveSelected();
        updateRows();
    }

    private void saveSelected() {
        Set<String> set = new HashSet<>();
        for (Long id : selected) {
            set.add(String.valueOf(id));
        }
        SharedPreferences.Editor editor = MessagesController.getMainSettings(currentAccount).edit();
        editor.putStringSet(PREF_KEY_SELECTED, set);
        editor.putInt(PREF_KEY_VERSION, getSelectionVersion(currentAccount) + 1);
        editor.apply();
        getNotificationCenter().postNotificationName(NotificationCenter.feedChannelsChanged);
    }

    public static boolean isChannelSelected(int account, long dialogId) {
        Set<String> stored = MessagesController.getMainSettings(account).getStringSet(PREF_KEY_SELECTED, null);
        if (stored == null) {
            return true;
        }
        long chatId = dialogId < 0 ? -dialogId : dialogId;
        return stored.contains(String.valueOf(chatId));
    }

    public static int getSelectionVersion(int account) {
        return MessagesController.getMainSettings(account).getInt(PREF_KEY_VERSION, 0);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ChannelsTab);
    }
}
