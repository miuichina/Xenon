package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatMessageSharedResources;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundPageDownButton;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.ui.Components.RecyclerAnimationScrollHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private boolean hasMainTabs;
    private int additionNavigationBarHeight;
    private int navigationBarHeight;
    private int statusBarHeight;

    private RecyclerListView listView;
    private FeedAdapter adapter;
    private final ArrayList<MessageObject> allMessages = new ArrayList<>();
    private ArrayList<MessageObject> feedItems = new ArrayList<>();
    private final LongSparseArray<MessageObject.GroupedMessages> feedGroups = new LongSparseArray<>();

    private final HashMap<Integer, Long> classGuidToDialog = new HashMap<>();

    private int pendingLoads;
    private boolean loading;
    private boolean hasMoreToLoad = true;
    private boolean scrollToBottomPending;
    private boolean newMessagesArrived;
    private int lastProcessedCount;

    private ChatActivityBlurredRoundPageDownButton scrollToBottomButton;
    private int unreadCount;
    private boolean isNearBottom = true;
    private boolean scrollButtonVisible;

    private ChatActivityFadeView chatActivityFadeView;
    private RecyclerAnimationScrollHelper chatScrollHelper;
    private TextView emptyView;

    /* Blur3 */

    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryFrosted;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass;

    private IBlur3Capture iBlur3Capture;

    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();
    private final RectF iBlur3PositionMainTabs = new RectF(); {
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
    }

    private static final int MESSAGES_PER_CHANNEL = 10;

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle args) {
        super(args);
        if (args != null) {
            hasMainTabs = args.getBoolean("hasMainTabs", false);
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlassFrosted.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                    }
                }
            });
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                    }
                }
            });
            iBlur3FactoryFrosted = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlassFrosted);
            iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
            iBlur3FactoryFrosted = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
            iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        additionNavigationBarHeight = hasMainTabs ? AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        statusBarHeight = AndroidUtilities.getStatusBarHeight(context);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MainTabsFeed));

        FrameLayout contentView = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                }
                super.dispatchDraw(canvas);
            }
        };
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        adapter = new FeedAdapter(context);
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter);
        listView.setClipToPadding(false);
        listView.setPadding(0, statusBarHeight + ActionBar.getCurrentActionBarHeight() + AndroidUtilities.dp(4), 0, navigationBarHeight + additionNavigationBarHeight + AndroidUtilities.dp(4));
        LinearLayoutManager layoutManager = (LinearLayoutManager) listView.getLayoutManager();
        chatScrollHelper = new RecyclerAnimationScrollHelper(listView, layoutManager);
        chatScrollHelper.setScrollDirection(RecyclerAnimationScrollHelper.SCROLL_DIRECTION_DOWN);

        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                int lastVisible = lm.findLastVisibleItemPosition();
                int total = feedItems.size();

                boolean wasNearBottom = isNearBottom;
                isNearBottom = lastVisible >= total - 3;

                if (dy < 0 && !loading && hasMoreToLoad) {
                    if (lm.findFirstVisibleItemPosition() < 5) {
                        loadMoreMessages();
                    }
                }

                markVisibleMessagesRead(lm);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }

                if (wasNearBottom != isNearBottom) {
                    updateScrollToBottomButton();
                }
            }
        });

        listView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void onDrawOver(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View child = parent.getChildAt(i);
                    if (!(child instanceof ChatMessageCell)) continue;
                    ChatMessageCell cell = (ChatMessageCell) child;
                    ImageReceiver imageReceiver = cell.getAvatarImage();
                    if (imageReceiver == null) continue;

                    int bottom = (int) child.getY() + child.getHeight() - child.getPaddingBottom();
                    imageReceiver.setImageY(bottom - imageReceiver.getImageHeight());
                    imageReceiver.setVisible(true, false);
                    imageReceiver.draw(canvas);
                }
            }
        });

        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.MainTabsFeed));
        emptyView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder));
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        /* Top fade blur */
        chatActivityFadeView = new ChatActivityFadeView(context);
        iBlur3FactoryFrosted.setSourceRootView(new ViewPositionWatcher(contentView), contentView);
        chatActivityFadeView.setup(iBlur3FactoryFrosted, new BlurredBackgroundColorProviderThemed(resourceProvider, Theme.key_chat_topPanelBackground));
        contentView.addView(chatActivityFadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateFadeZoneTop();

        /* Scroll-to-bottom button */
        scrollToBottomButton = ChatActivityBlurredRoundPageDownButton.create(
            context, 56, 48, resourceProvider,
            iBlur3FactoryFrosted,
            new BlurredBackgroundColorProviderThemed(resourceProvider, Theme.key_windowBackgroundWhite),
            R.drawable.pagedown
        );
        scrollToBottomButton.setVisibility(View.INVISIBLE);
        scrollToBottomButton.setOnClickListener(v -> scrollToBottom());
        contentView.addView(scrollToBottomButton, LayoutHelper.createFrame(57, 64, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 14, 14 + additionNavigationBarHeight));

        iBlur3Capture = new ViewGroupPartRenderer(listView, contentView, listView::drawChild);
        listView.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));

        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            final int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            listView.setPadding(0, statusBarHeight + ActionBar.getCurrentActionBarHeight() + AndroidUtilities.dp(4), 0, navigationBarHeight + additionNavigationBarHeight + AndroidUtilities.dp(4));
            updateFadeZoneTop();
            if (scrollToBottomButton != null) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scrollToBottomButton.getLayoutParams();
                lp.bottomMargin = 14 + additionNavigationBarHeight + navigationBarHeight;
                scrollToBottomButton.setLayoutParams(lp);
            }
            return WindowInsetsCompat.CONSUMED;
        });

        fragmentView = contentView;

        loadFeed();
        return contentView;
    }

    private void updateFadeZoneTop() {
        if (chatActivityFadeView == null) return;
        int fadeHeight = statusBarHeight + ActionBar.getCurrentActionBarHeight() + AndroidUtilities.dp(7);
        chatActivityFadeView.setFadeZoneTop(fadeHeight);
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null) {
            return;
        }

        final int additionalList = AndroidUtilities.dp(48);
        final int mainTabBottom = fragmentView.getMeasuredHeight() - navigationBarHeight - AndroidUtilities.dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT);

        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), ActionBar.getCurrentActionBarHeight() + statusBarHeight + additionalList);
        iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
        iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -AndroidUtilities.dp(48));

        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());

        if (iBlur3SourceGlassFrosted != null) {
            iBlur3SourceGlassFrosted.setSize(fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
            iBlur3SourceGlassFrosted.updateDisplayListIfNeeded();
        }
        if (iBlur3SourceGlass != null) {
            iBlur3SourceGlass.setSize(fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
            iBlur3SourceGlass.updateDisplayListIfNeeded();
        }
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    @Override
    public void onParentScrollToTop() {
        if (feedItems.isEmpty()) return;
        ((LinearLayoutManager) listView.getLayoutManager()).scrollToPositionWithOffset(0, 0);
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
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;
        if (id == NotificationCenter.messagesDidLoad) {
            long dialogId = (Long) args[0];
            int guid = (Integer) args[10];
            Long expectedDialog = classGuidToDialog.get(guid);
            if (expectedDialog == null || expectedDialog != dialogId) return;

            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
            if (messages != null && !messages.isEmpty()) {
                for (MessageObject msg : messages) {
                    if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                        allMessages.add(msg);
                    }
                }
            }

            classGuidToDialog.remove(guid);
            pendingLoads--;

            if (pendingLoads <= 0) {
                processLoadedMessages();
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) return;
            MessagesController mc = MessagesController.getInstance(currentAccount);
            if (dialogId < 0) {
                TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat == null || chat.megagroup) return;
            } else {
                return;
            }
            boolean added = false;
            int addedCount = 0;
            for (MessageObject msg : messages) {
                if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                    allMessages.add(msg);
                    added = true;
                    addedCount++;
                }
            }
            if (added) {
                newMessagesArrived = true;
                if (!isNearBottom) {
                    unreadCount += addedCount;
                    updateScrollToBottomButton();
                }
                if (!loading) {
                    processLoadedMessages();
                }
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            if (listView != null) {
                int count = listView.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = listView.getChildAt(a);
                    if (child instanceof ChatMessageCell) {
                        ChatMessageCell cell = (ChatMessageCell) child;
                        MessageObject msg = cell.getMessageObject();
                        if (msg != null) {
                            cell.setIsUpdating(true);
                            MessageObject.GroupedMessages group = msg.hasValidGroupId() ? feedGroups.get(msg.getGroupIdForUse()) : null;
                            cell.setMessageObject(msg, group, cell.isPinnedBottom(), cell.isPinnedTop(), cell.isFirstInChat(), cell.isLastInChatList());
                            cell.setIsUpdating(false);
                        }
                    }
                }
            }
        }
    }

    private void processLoadedMessages() {
        loading = false;

        String anchorKey = null;
        int anchorOffset = 0;
        if (!scrollToBottomPending && listView != null && listView.getLayoutManager() != null && !feedItems.isEmpty()) {
            LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
            int anchorPos = lm.findFirstVisibleItemPosition();
            if (anchorPos != RecyclerView.NO_POSITION && anchorPos < feedItems.size()) {
                View anchorView = lm.findViewByPosition(anchorPos);
                anchorOffset = anchorView != null ? anchorView.getTop() - listView.getPaddingTop() : 0;
                MessageObject m = feedItems.get(anchorPos);
                anchorKey = m.getDialogId() + ":" + m.getId();
            }
        }

        Collections.sort(allMessages, (a, b) -> {
            if (a.messageOwner.date == b.messageOwner.date) {
                return a.getId() - b.getId();
            }
            return a.messageOwner.date - b.messageOwner.date;
        });

        HashSet<String> seen = new HashSet<>();
        ArrayList<MessageObject> deduped = new ArrayList<>();
        for (MessageObject msg : allMessages) {
            String key = msg.getDialogId() + ":" + msg.getId();
            if (seen.add(key)) {
                deduped.add(msg);
            }
        }
        allMessages.clear();
        allMessages.addAll(deduped);

        feedItems.clear();
        feedItems.addAll(deduped);
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

        if (scrollToBottomPending) {
            hasMoreToLoad = feedItems.size() > 0;
        } else {
            hasMoreToLoad = deduped.size() > lastProcessedCount;
        }
        lastProcessedCount = deduped.size();

        adapter.notifyDataSetChanged();

        if (emptyView != null) {
            emptyView.setVisibility(feedItems.isEmpty() ? View.VISIBLE : View.GONE);
        }

        if (scrollToBottomPending) {
            scrollToBottomPending = false;
            newMessagesArrived = false;
            listView.post(() -> {
                if (adapter.getItemCount() > 0) {
                    chatScrollHelper.scrollToPosition(adapter.getItemCount() - 1, Integer.MIN_VALUE, false, false);
                }
            });
        } else {
            boolean wasNew = newMessagesArrived;
            newMessagesArrived = false;
            LinearLayoutManager lm = listView.getLayoutManager() != null ? (LinearLayoutManager) listView.getLayoutManager() : null;
            if (lm != null) {
                if (wasNew && isNearBottom) {
                    if (adapter.getItemCount() > 0) {
                        chatScrollHelper.scrollToPosition(adapter.getItemCount() - 1, Integer.MIN_VALUE, false, false);
                    }
                } else if (anchorKey != null) {
                    int newPos = -1;
                    for (int i = 0; i < feedItems.size(); i++) {
                        MessageObject m = feedItems.get(i);
                        if (anchorKey.equals(m.getDialogId() + ":" + m.getId())) {
                            newPos = i;
                            break;
                        }
                    }
                    if (newPos >= 0) {
                        lm.scrollToPositionWithOffset(newPos, anchorOffset);
                    }
                }
            }
        }
    }

    private void updateScrollToBottomButton() {
        if (feedItems.isEmpty()) {
            setScrollButtonVisible(false);
            return;
        }

        if (isNearBottom) {
            unreadCount = 0;
            if (scrollToBottomButton != null) {
                scrollToBottomButton.setCount(0, true);
            }
            setScrollButtonVisible(false);
        } else {
            setScrollButtonVisible(true);
            if (unreadCount > 0) {
                scrollToBottomButton.setCount(unreadCount, true);
            }
        }
    }

    private void setScrollButtonVisible(boolean visible) {
        if (scrollToBottomButton == null) return;
        if (visible == scrollButtonVisible) return;
        scrollButtonVisible = visible;

        if (visible) {
            scrollToBottomButton.setVisibility(View.VISIBLE);
            scrollToBottomButton.setAlpha(0f);
            scrollToBottomButton.setScaleX(0.7f);
            scrollToBottomButton.setScaleY(0.7f);
            scrollToBottomButton.animate().cancel();
            scrollToBottomButton.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start();
        } else {
            scrollToBottomButton.animate().cancel();
            scrollToBottomButton.animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(200)
                .withEndAction(() -> {
                    if (!scrollButtonVisible) {
                        scrollToBottomButton.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
        }
    }

    private void scrollToBottom() {
        if (feedItems.isEmpty()) return;
        unreadCount = 0;
        updateScrollToBottomButton();
        if (adapter.getItemCount() > 0) {
            chatScrollHelper.scrollToPosition(adapter.getItemCount() - 1, Integer.MIN_VALUE, false, true);
        }
    }

    private void markVisibleMessagesRead(LinearLayoutManager lm) {
        HashMap<Long, Integer> maxIdsByDialog = new HashMap<>();
        int firstVisible = lm.findFirstVisibleItemPosition();
        int lastVisible = lm.findLastVisibleItemPosition();
        for (int i = firstVisible; i <= lastVisible && i < feedItems.size() && i >= 0; i++) {
            MessageObject msg = feedItems.get(i);
            long dialogId = msg.getDialogId();
            int mid = msg.getId();
            Integer existing = maxIdsByDialog.get(dialogId);
            if (existing == null || mid > existing) {
                maxIdsByDialog.put(dialogId, mid);
            }
        }
        MessagesController mc = MessagesController.getInstance(currentAccount);
        for (HashMap.Entry<Long, Integer> entry : maxIdsByDialog.entrySet()) {
            long dialogId = entry.getKey();
            int maxId = entry.getValue();
            MessageObject msg = null;
            for (int i = 0; i < feedItems.size(); i++) {
                MessageObject m = feedItems.get(i);
                if (m.getDialogId() == dialogId && m.getId() == maxId) {
                    msg = m;
                    break;
                }
            }
            int maxDate = msg != null ? msg.messageOwner.date : 0;
            mc.markDialogAsRead(dialogId, maxId, maxId, maxDate, false, 0, 0, true, 0);
        }
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

            msg.forceAvatar = true;

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
