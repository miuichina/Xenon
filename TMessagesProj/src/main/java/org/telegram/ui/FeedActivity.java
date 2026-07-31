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
import android.widget.ImageView;
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
import org.telegram.ui.Components.CircularProgressDrawable;
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
    private final HashMap<Long, ChannelState> channelStates = new HashMap<>();

    private int pendingLoads;
    private boolean loading;
    private boolean hasMoreToLoad = true;
    private boolean scrollToBottomPending;
    private boolean newMessagesArrived;
    private int lastSelectionVersion = -1;

    private static final class ChannelState {
        final long dialogId;
        int minId = Integer.MAX_VALUE;
        boolean hasMore = true;
        boolean loading;
        ChannelState(long dialogId) { this.dialogId = dialogId; }
    }

    private ChatActivityBlurredRoundPageDownButton scrollToBottomButton;
    private int unreadCount;
    private boolean isNearBottom = true;
    private boolean scrollButtonVisible;
    private int channelsCount;
    private ImageView centerProgressBar;
    private View progressOverlay;

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
    private static final int MENU_FEED_CHANNELS = 1;

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
        lastSelectionVersion = FeedChannelsActivity.getSelectionVersion(currentAccount);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.feedChannelsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        int version = FeedChannelsActivity.getSelectionVersion(currentAccount);
        if (version != lastSelectionVersion) {
            lastSelectionVersion = version;
            reloadFeed();
        }
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.feedChannelsChanged);
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
        actionBar.setSubtitle("");
        actionBar.createMenu().addItem(MENU_FEED_CHANNELS, R.drawable.filled_profile_settings);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == MENU_FEED_CHANNELS) {
                    presentFragment(new FeedChannelsActivity());
                }
            }
        });

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

                markVisibleChannelsRead();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }

                if (wasNearBottom != isNearBottom) {
                    updateScrollToBottomButton();
                }
            }

            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    markVisibleChannelsRead();
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

        centerProgressBar = new ImageView(context);
        CircularProgressDrawable centerCpd = new CircularProgressDrawable(AndroidUtilities.dp(48), AndroidUtilities.dp(4), Theme.getColor(Theme.key_actionBarDefaultTitle));
        centerProgressBar.setImageDrawable(centerCpd);
        centerProgressBar.setVisibility(View.GONE);
        contentView.addView(centerProgressBar, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

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
        if (centerProgressBar != null) centerProgressBar.setVisibility(View.VISIBLE);

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> channelDialogs = new ArrayList<>();
        ArrayList<TLRPC.Dialog> all = messagesController.getAllDialogs();
        for (TLRPC.Dialog d : all) {
            if (d.id < 0 && DialogObject.isChannel(d)) {
                TLRPC.Chat chat = messagesController.getChat(-d.id);
                if (chat != null && !chat.megagroup) {
                    if (!FeedChannelsActivity.isChannelSelected(currentAccount, d.id)) continue;
                    channelDialogs.add(d);
                }
            }
        }

        if (channelDialogs.isEmpty()) {
            loading = false;
            if (centerProgressBar != null) centerProgressBar.setVisibility(View.GONE);
            hasMoreToLoad = false;
            processLoadedMessages();
            return;
        }

        channelStates.clear();
        pendingLoads = 0;

        for (TLRPC.Dialog dialog : channelDialogs) {
            long dialogId = dialog.id;
            ChannelState state = new ChannelState(dialogId);
            channelStates.put(dialogId, state);
            requestMessages(state, 0);
        }
    }

    private void reloadFeed() {
        allMessages.clear();
        feedItems.clear();
        feedGroups.clear();
        classGuidToDialog.clear();
        channelStates.clear();
        pendingLoads = 0;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        loading = false;
        loadFeed();
    }

    private void requestMessages(ChannelState state, int maxId) {
        int classGuid = ConnectionsManager.generateClassGuid();
        classGuidToDialog.put(classGuid, state.dialogId);
        state.loading = true;
        getMessagesStorage().getMessages(
            state.dialogId, 0, false, MESSAGES_PER_CHANNEL,
            maxId, 0, 0, classGuid, 0, 0, 0, 0, true, false, null
        );
        pendingLoads++;
    }

    private void loadMoreMessages() {
        if (loading || feedItems.isEmpty() || !hasMoreToLoad) return;

        int requested = 0;
        for (ChannelState state : channelStates.values()) {
            if (!state.hasMore || state.loading || state.minId == Integer.MAX_VALUE || state.minId <= 1) continue;
            requestMessages(state, state.minId - 1);
            requested++;
        }

        if (requested == 0) {
            hasMoreToLoad = false;
        } else {
            loading = true;
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

            boolean isEnd = (Boolean) args[9];
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
            if (messages != null && !messages.isEmpty()) {
                for (MessageObject msg : messages) {
                    if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                        allMessages.add(msg);
                    }
                }
            }

            ChannelState state = channelStates.get(dialogId);
            if (state != null) {
                state.loading = false;
                state.hasMore = !isEnd && messages != null && !messages.isEmpty();
                if (messages != null) {
                    for (MessageObject msg : messages) {
                        if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                            state.minId = Math.min(state.minId, msg.getId());
                        }
                    }
                }
            }

            classGuidToDialog.remove(guid);
            pendingLoads = Math.max(0, pendingLoads - 1);

            if (pendingLoads <= 0) {
                processLoadedMessages();
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (Long) args[0];
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            if (messages == null || messages.isEmpty()) return;
            if (!FeedChannelsActivity.isChannelSelected(currentAccount, dialogId)) return;
            MessagesController mc = MessagesController.getInstance(currentAccount);
            if (dialogId < 0) {
                TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat == null || chat.megagroup) return;
            } else {
                return;
            }
            boolean added = false;
            int addedCount = 0;
            ChannelState state = channelStates.get(dialogId);
            for (MessageObject msg : messages) {
                if (msg.messageOwner != null && msg.messageOwner.date > 0) {
                    allMessages.add(msg);
                    added = true;
                    addedCount++;
                    if (state == null) {
                        state = new ChannelState(dialogId);
                        channelStates.put(dialogId, state);
                    }
                    state.minId = Math.min(state.minId, msg.getId());
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
        } else if (id == NotificationCenter.feedChannelsChanged) {
            lastSelectionVersion = FeedChannelsActivity.getSelectionVersion(currentAccount);
            if (listView == null || adapter == null) {
                return;
            }
            reloadFeed();
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
        if (centerProgressBar != null) centerProgressBar.setVisibility(View.GONE);

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

        hasMoreToLoad = false;
        for (ChannelState s : channelStates.values()) {
            if (s.hasMore) {
                hasMoreToLoad = true;
                break;
            }
        }
        if (feedItems.isEmpty()) {
            hasMoreToLoad = false;
        }

        int oldSize = adapter.getItemCount();
        int newSize = feedItems.size();
        if (newSize > oldSize) {
            adapter.notifyItemRangeInserted(oldSize, newSize - oldSize);
        } else if (newSize < oldSize) {
            adapter.notifyItemRangeRemoved(newSize, oldSize - newSize);
        } else {
            adapter.notifyDataSetChanged();
        }

        HashSet<Long> uniqueDialogs = new HashSet<>();
        for (MessageObject msg : feedItems) {
            uniqueDialogs.add(msg.getDialogId());
        }
        channelsCount = uniqueDialogs.size();
        actionBar.setSubtitle(LocaleController.formatString("FeedChannelsCount", R.string.FeedChannelsCount, channelsCount));

        if (emptyView != null) {
            emptyView.setVisibility(feedItems.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (centerProgressBar != null && !feedItems.isEmpty()) {
            centerProgressBar.setVisibility(View.GONE);
        }

        if (scrollToBottomPending) {
            scrollToBottomPending = false;
            newMessagesArrived = false;
            listView.post(() -> {
                if (adapter.getItemCount() > 0) {
                    chatScrollHelper.scrollToPosition(adapter.getItemCount() - 1, Integer.MIN_VALUE, false, false);
                }
                markVisibleChannelsRead();
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

    private void markVisibleChannelsRead() {
        if (listView == null || listView.getLayoutManager() == null || feedItems.isEmpty()) return;
        LinearLayoutManager lm = (LinearLayoutManager) listView.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        HashMap<Long, Integer> maxIdPerChannel = new HashMap<>();
        HashMap<Long, Integer> maxDatePerChannel = new HashMap<>();
        for (int p = first; p <= last; p++) {
            if (p < 0 || p >= feedItems.size()) continue;
            MessageObject msg = feedItems.get(p);
            long did = msg.getDialogId();
            int id = msg.getId();
            Integer cur = maxIdPerChannel.get(did);
            if (cur == null || id > cur) {
                maxIdPerChannel.put(did, id);
                maxDatePerChannel.put(did, msg.messageOwner != null ? msg.messageOwner.date : 0);
            }
        }

        MessagesController mc = MessagesController.getInstance(currentAccount);
        boolean changed = false;
        for (HashMap.Entry<Long, Integer> e : maxIdPerChannel.entrySet()) {
            long did = e.getKey();
            int maxId = e.getValue();
            if (maxId <= 0) continue;
            TLRPC.Dialog d = mc.getDialog(did);
            int readMax = d != null ? d.read_inbox_max_id : 0;
            if (maxId <= readMax) continue;
            for (MessageObject m : allMessages) {
                if (m.getDialogId() == did && m.getId() <= maxId && m.isUnread()) {
                    m.setIsRead();
                    changed = true;
                }
            }
            mc.markDialogAsRead(did, maxId, 0, maxDatePerChannel.get(did), false, 0, 0, false, 0);
        }
        if (changed) {
            adapter.notifyItemRangeChanged(first, last - first + 1);
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

            long dialogId = msg.getDialogId();
            boolean samePrev = position > 0 && feedItems.get(position - 1).getDialogId() == dialogId;
            boolean sameNext = position < feedItems.size() - 1 && feedItems.get(position + 1).getDialogId() == dialogId;

            boolean pinnedTop = samePrev;
            boolean pinnedBottom = sameNext;
            boolean firstInChat = !samePrev;
            boolean lastInChatList = position == feedItems.size() - 1;

            MessageObject.GroupedMessages group = null;
            if (msg.hasValidGroupId()) {
                group = feedGroups.get(msg.getGroupIdForUse());
                if (group != null) {
                    pinnedTop = false;
                    pinnedBottom = false;
                }
            }
            cell.setMessageObject(msg, group, pinnedBottom, pinnedTop, firstInChat, lastInChatList);
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
