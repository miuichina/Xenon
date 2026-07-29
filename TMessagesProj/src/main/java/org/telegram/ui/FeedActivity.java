package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.collection.LongSparseArray;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatMessageSharedResources;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;

    private RecyclerListView listView;
    private FeedAdapter adapter;
    private ArrayList<MessageObject> allMessages = new ArrayList<>();
    private ArrayList<MessageObject> feedItems = new ArrayList<>();
    private LongSparseArray<MessageObject.GroupedMessages> feedGroups = new LongSparseArray<>();

    private final HashMap<Integer, Long> classGuidToDialog = new HashMap<>();

    private int pendingLoads;
    private boolean loading;
    private boolean hasMoreToLoad = true;
    private boolean scrollToBottomPending;

    private FragmentFloatingButton scrollToBottomButton;
    private FrameLayout badgeContainer;
    private TextView badgeText;
    private int unreadCount;
    private boolean isNearBottom = true;

    private static final int CHAT_MESSAGE_CELL_VIEW_TYPE = 0;
    private static final int MESSAGES_PER_CHANNEL = 10;

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle args) {
        super(args);
        if (args != null) {
            hasMainTabs = args.getBoolean("hasMainTabs", false);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        additionNavigationBarHeight = hasMainTabs ? AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MainTabsFeed));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        adapter = new FeedAdapter(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                int lastVisible = lm.findLastVisibleItemPosition();
                int total = feedItems.size();

                isNearBottom = lastVisible >= total - 3;

                if (dy < 0 && !loading && hasMoreToLoad) {
                    if (lm.findFirstVisibleItemPosition() < 5) {
                        loadMoreMessages();
                    }
                }

                int firstVisible = lm.findFirstVisibleItemPosition();
                if (firstVisible <= 2) {
                    markFirstMessagesRead();
                }

                updateScrollToBottomButton();
            }
        });

        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        scrollToBottomButton = new FragmentFloatingButton(context, resourceProvider);
        scrollToBottomButton.setVisibility(View.INVISIBLE);
        scrollToBottomButton.setImageResource(R.drawable.pagedown);
        scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

        badgeContainer = new FrameLayout(context);
        badgeContainer.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(11), Theme.getColor(Theme.key_chats_actionBackground)));
        badgeContainer.setVisibility(View.INVISIBLE);

        badgeText = new TextView(context);
        badgeText.setTextSize(12);
        badgeText.setTextColor(Theme.getColor(Theme.key_chats_unreadCounterText));
        badgeText.setGravity(Gravity.CENTER);
        badgeText.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        badgeContainer.addView(badgeText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(22)));

        FrameLayout buttonWrap = new FrameLayout(context);
        buttonWrap.addView(scrollToBottomButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.NO_GRAVITY));
        buttonWrap.addView(badgeContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, AndroidUtilities.dp(22), Gravity.TOP | Gravity.RIGHT, 0, -AndroidUtilities.dp(2), 0, 0));

        contentView.addView(buttonWrap, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 14, 14));

        fragmentView = contentView;

        loadFeed();
        return contentView;
    }

    private void loadFeed() {
        if (loading) return;
        loading = true;
        hasMoreToLoad = true;
        scrollToBottomPending = true;

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> channelDialogs = new ArrayList<>();
        for (TLRPC.Dialog d : messagesController.dialogsChannelsOnly) {
            if (d.id < 0) {
                TLRPC.Chat chat = messagesController.getChat(-d.id);
                if (chat != null && !chat.megagroup) {
                    channelDialogs.add(d);
                }
            }
        }

        if (channelDialogs.isEmpty()) {
            ArrayList<TLRPC.Dialog> all = messagesController.getAllDialogs();
            for (TLRPC.Dialog d : all) {
                if (d.id < 0 && DialogObject.isChannel(d)) {
                    TLRPC.Chat chat = messagesController.getChat(-d.id);
                    if (chat != null && !chat.megagroup) {
                        channelDialogs.add(d);
                        break;
                    }
                }
            }
        }

        if (channelDialogs.isEmpty()) {
            loading = false;
            return;
        }

        pendingLoads = 0;

        for (TLRPC.Dialog dialog : channelDialogs) {
            long dialogId = dialog.id;
            int classGuid = ConnectionsManager.generateClassGuid();
            classGuidToDialog.put(classGuid, dialogId);

            getMessagesStorage().getMessages(
                dialogId, 0, false, MESSAGES_PER_CHANNEL,
                0, 0, 0, classGuid, 0, 0, 0, 0, true, false, null
            );
            pendingLoads++;
        }

        if (pendingLoads == 0) {
            loading = false;
        }
    }

    private void loadMoreMessages() {
        if (loading || feedItems.isEmpty() || !hasMoreToLoad) return;
        loading = true;

        HashMap<Long, Integer> oldestPerChannel = new HashMap<>();

        for (MessageObject msg : feedItems) {
            long dialogId = msg.getDialogId();
            int mid = msg.getId();
            Integer existing = oldestPerChannel.get(dialogId);
            if (existing == null || mid < existing) {
                oldestPerChannel.put(dialogId, mid);
            }
        }

        int loaded = 0;
        for (HashMap.Entry<Long, Integer> entry : oldestPerChannel.entrySet()) {
            long dialogId = entry.getKey();
            int oldestMid = entry.getValue();

            if (oldestMid <= 1) continue;

            int max_id = oldestMid - 1;
            int classGuid = ConnectionsManager.generateClassGuid();
            classGuidToDialog.put(classGuid, dialogId);

            getMessagesStorage().getMessages(
                dialogId, 0, false, MESSAGES_PER_CHANNEL,
                max_id, 0, 0, classGuid, 0, 0, 0, 0, true, false, null
            );
            loaded++;
        }

        pendingLoads = loaded;
        if (pendingLoads == 0) {
            loading = false;
            hasMoreToLoad = false;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            long dialogId = (Long) args[0];
            int guid = (Integer) args[10];
            Long expectedDialog = classGuidToDialog.get(guid);
            if (expectedDialog == null || expectedDialog != dialogId || account != currentAccount) return;

            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
            if (messages != null && !messages.isEmpty()) {
                for (MessageObject msg : messages) {
                    if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                        String key = dialogId + ":" + msg.getId();
                        allMessages.add(msg);
                    }
                }
            }

            classGuidToDialog.remove(guid);
            pendingLoads--;

            if (pendingLoads <= 0) {
                processLoadedMessages();
            }
        }
    }

    private void processLoadedMessages() {
        loading = false;

        Collections.sort(allMessages, (a, b) -> {
            if (a.messageOwner.date == b.messageOwner.date) {
                return a.getId() - b.getId();
            }
            return a.messageOwner.date - b.messageOwner.date;
        });

        HashSet<String> seen = new HashSet<>();
        feedItems.clear();
        for (MessageObject msg : allMessages) {
            String key = msg.getDialogId() + ":" + msg.getId();
            if (!seen.contains(key)) {
                seen.add(key);
                feedItems.add(msg);
            }
        }

        feedGroups.clear();
        ArrayList<MessageObject> filtered = new ArrayList<>();
        for (int i = 0; i < feedItems.size(); i++) {
            MessageObject msg = feedItems.get(i);
            if (msg.hasValidGroupId()) {
                long groupId = msg.getGroupIdForUse();
                MessageObject.GroupedMessages group = feedGroups.get(groupId);
                if (group == null) {
                    group = new MessageObject.GroupedMessages();
                    group.groupId = groupId;
                    feedGroups.put(groupId, group);
                }
                group.messages.add(msg);
                if (group.messages.get(0) != msg) continue;
            }
            filtered.add(msg);
        }
        feedItems = filtered;

        if (feedGroups.size() > 0) {
            for (int i = 0; i < feedGroups.size(); i++) {
                feedGroups.valueAt(i).calculate();
            }
        }

        hasMoreToLoad = true;

        adapter.notifyDataSetChanged();

        if (scrollToBottomPending) {
            scrollToBottomPending = false;
            listView.post(() -> {
                if (adapter.getItemCount() > 0) {
                    ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(
                        adapter.getItemCount() - 1, Integer.MIN_VALUE
                    );
                }
            });
        }
    }

    private void updateScrollToBottomButton() {
        if (feedItems.isEmpty()) {
            scrollToBottomButton.setVisibility(View.INVISIBLE);
            badgeContainer.setVisibility(View.INVISIBLE);
            return;
        }

        if (isNearBottom) {
            scrollToBottomButton.setVisibility(View.INVISIBLE);
            badgeContainer.setVisibility(View.INVISIBLE);
            unreadCount = 0;
        } else {
            scrollToBottomButton.setVisibility(View.VISIBLE);
            if (unreadCount > 0) {
                badgeContainer.setVisibility(View.VISIBLE);
                badgeText.setText(String.valueOf(unreadCount));
            } else {
                badgeContainer.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void scrollToBottom() {
        if (feedItems.isEmpty()) return;
        unreadCount = 0;
        updateScrollToBottomButton();
        if (adapter.getItemCount() > 0) {
            ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(
                adapter.getItemCount() - 1, Integer.MIN_VALUE
            );
        }
    }

    private void markFirstMessagesRead() {
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return true;
    }

    private void openMessageInChannel(MessageObject messageObject) {
        if (messageObject == null || getParentActivity() == null) return;
        long dialogId = messageObject.getDialogId();
        Bundle args = new Bundle();
        args.putLong("dialog_id", dialogId);
        args.putInt("message_id", messageObject.getId());
        presentFragment(new ChatActivity(args));
    }

    private class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {

        private final Context context;
        private final ChatMessageSharedResources sharedResources;

        FeedAdapter(Context context) {
            this.context = context;
            this.sharedResources = new ChatMessageSharedResources(context);
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            ChatMessageCell cell = new ChatMessageCell(context, currentAccount, false, sharedResources, resourceProvider);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return true;
                }

                @Override
                public void didPressImage(ChatMessageCell c, float x, float y, boolean fullPreview) {
                    MessageObject msg = c.getMessageObject();
                    if (msg == null) return;
                    if (msg.getPhoto() != null || msg.isVideo() || msg.isGif()) {
                        PhotoViewer.getInstance().setParentActivity(getParentActivity(), resourceProvider);
                        PhotoViewer.getInstance().openPhoto(
                            msg, 0L, 0L, 0L,
                            new PhotoViewer.EmptyPhotoViewerProvider(), true
                        );
                    }
                }

                @Override
                public void didPressChannelAvatar(ChatMessageCell c, TLRPC.Chat chat, int postId, float touchX, float touchY, boolean asForward) {
                    Bundle args = new Bundle();
                    args.putLong("chat_id", chat.id);
                    presentFragment(new ChatActivity(args));
                }

                @Override
                public void didPressUserAvatar(ChatMessageCell c, TLRPC.User user, float touchX, float touchY, boolean asForward) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", user.id);
                    presentFragment(new ProfileActivity(args));
                }

                @Override
                public void didPressOther(ChatMessageCell c, float otherX, float otherY) {
                    openMessageInChannel(c.getMessageObject());
                }

                @Override
                public void didLongPress(ChatMessageCell c, float x, float y) {
                    openMessageInChannel(c.getMessageObject());
                }
            });
            return new Holder(cell);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            MessageObject msg = feedItems.get(position);
            ChatMessageCell cell = (ChatMessageCell) holder.itemView;

            cell.isChat = true;
            cell.isMegagroup = false;
            cell.hasDiscussion = false;
            cell.isPinned = false;
            cell.isPinnedChat = false;

            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            TLRPC.Chat chat = messagesController.getChat(-msg.getDialogId());
            if (chat != null && chat.has_link && !chat.megagroup) {
                cell.hasDiscussion = chat.has_link;
            }

            MessageObject.GroupedMessages group = null;
            if (msg.hasValidGroupId()) {
                group = feedGroups.get(msg.getGroupIdForUse());
            }
            cell.setMessageObject(msg, group, false, false, true, false);
        }

        @Override
        public int getItemCount() {
            return feedItems.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
