package zxc.iconic.xenon.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.EmojiHelper;
import zxc.iconic.xenon.helpers.PopupHelper;

public class NekoAppearanceSettingsActivity extends BaseNekoSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private final int emojiSetsRow = rowId++;
    private final int predictiveBackAnimationRow = rowId++;
    private final int appBarShadowRow = rowId++;
    private final int formatTimeWithSecondsRow = rowId++;
    private final int disableNumberRoundingRow = rowId++;
    private final int hideBottomNavigationBarRow = rowId++;
    private final int dynamicTabSizeRow = rowId++;
    private final int mainTabsCustomizeRow = rowId++;
    private final int tabletModeRow = rowId++;

    private final int hideStoriesRow = rowId++;
    private final int mediaPreviewRow = rowId++;

    private final int hideAllTabRow = rowId++;
    private final int tabsTitleTypeRow = rowId++;
    private final int tabsPositionRow = rowId++;

    private final int strokeOnViewsRow = rowId++;
    private final int blurSettingsRow = rowId++;
    private final int hideRecordButtonRow = rowId++;
    private final int disableGooeyAvatarAnimationRow = rowId++;
    private final int gooeyAvatarOffsetRow = rowId++;
    private final int keepUnreadChatsOnTopRow = rowId++;
    private final int keepUnreadArchivedOnTopRow = rowId++;
    private final int alternativeTransitionRow = rowId++;
    private final int alternativeTransitionSpeedRow = rowId++;
    private final int alternativeTransitionEaseRow = rowId++;
    private final int alternativeTransitionEaseDescriptionRow = rowId++;
    private final int aospTransitionRow = rowId++;
    private final int material3SwitchesRow = rowId++;
    private final int m3SectionsStyleRow = rowId++;
    private final int material3ChatHeadersRow = rowId++;
    private final int loadingIndicatorsRow = rowId++;
    private final int chatHeaderSettingsRow = rowId++;
    private final int nonIslandTabBarsRow = rowId++;
    private final int nonIslandGlobalSearchRow = rowId++;
    private final int nonIslandChatElementsRow = rowId++;
    private final int hideFadeViewRow = rowId++;
    private final int disableGlassGlareRow = rowId++;
    private final int disableScrimBlurRow = rowId++;
    private final int nonIslandBottomBarRow = rowId++;

    private final int textAnimationSettingsRow = rowId++;
    private final int roundedBulletinRow = rowId++;

    private final int forceBlurLiquidGlassRow = rowId++;
    private final int liquidGlassRow = rowId++;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded && listView != null) {
            notifyItemChanged(emojiSetsRow, PARTIAL);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ChangeChannelNameColor2)));
        items.add(EmojiSetCellFactory.of(emojiSetsRow, LocaleController.getString(R.string.EmojiSets)).slug("emojiSets"));
        items.add(UItem.asCheck(predictiveBackAnimationRow, LocaleController.getString(R.string.PredictiveBackAnimation)).slug("predictiveBackAnimation").setChecked(NekoConfig.predictiveBackAnimation));
        items.add(UItem.asCheck(disableGooeyAvatarAnimationRow, LocaleController.getString(R.string.DisableGooeyAvatarAnimation)).setChecked(NekoConfig.disableGooeyAvatarAnimation).slug("disableGooeyAvatarAnimation"));
        SeekbarConfig offsetConfig = new SeekbarConfig(
                LocaleController.getString(R.string.GooeyAvatarOffset),
                LocaleController.getString(R.string.GooeyAvatarOffsetLeft),
                LocaleController.getString(R.string.GooeyAvatarOffsetRight),
                -100, 100, 1,
                progress -> NekoConfig.setGooeyAvatarOffset(Math.round(progress)));
        items.add(SeekbarCellFactory.of(gooeyAvatarOffsetRow, offsetConfig, NekoConfig.gooeyAvatarOffset).slug("gooeyAvatarOffset"));
        items.add(UItem.asCheck(keepUnreadChatsOnTopRow, LocaleController.getString(R.string.KeepUnreadChatsOnTop)).setChecked(NekoConfig.keepUnreadChatsOnTop).slug("keepUnreadChatsOnTop"));
        if (NekoConfig.keepUnreadChatsOnTop) {
            items.add(UItem.asCheck(keepUnreadArchivedOnTopRow, LocaleController.getString(R.string.KeepUnreadArchivedOnTop)).setChecked(NekoConfig.keepUnreadArchivedOnTop).slug("keepUnreadArchivedOnTop"));
        }
        items.add(UItem.asCheck(hideRecordButtonRow, LocaleController.getString(R.string.HideRecordButton)).setChecked(NekoConfig.hideRecordButton).slug("hideRecordButton"));
        items.add(UItem.asCheck(roundedBulletinRow, LocaleController.getString(R.string.RoundedBulletin)).setChecked(NekoConfig.roundedBulletin).slug("roundedBulletin"));
        items.add(UItem.asCheck(appBarShadowRow, LocaleController.getString(R.string.DisableAppBarShadow)).slug("appBarShadow").setChecked(NekoConfig.disableAppBarShadow));
        items.add(UItem.asCheck(formatTimeWithSecondsRow, LocaleController.getString(R.string.FormatWithSeconds)).slug("formatTimeWithSeconds").setChecked(NekoConfig.formatTimeWithSeconds));
        items.add(UItem.asCheck(disableNumberRoundingRow, LocaleController.getString(R.string.DisableNumberRounding), "4.8K -> 4777").slug("disableNumberRounding").setChecked(NekoConfig.disableNumberRounding));
        items.add(UItem.asCheck(hideBottomNavigationBarRow, LocaleController.getString(R.string.HideBottomNavigationBar)).setChecked(NekoConfig.hideBottomNavigationBar).slug("hideBottomNavigationBar"));
        items.add(UItem.asCheck(dynamicTabSizeRow, LocaleController.getString(R.string.DynamicTabSize)).slug("dynamicTabSize").setChecked(NekoConfig.dynamicTabSize));
        items.add(TextSettingsCellFactory.of(mainTabsCustomizeRow, LocaleController.getString(R.string.MainTabsCustomizeTitle), LocaleController.getString(R.string.MainTabsCustomizeHint)).slug("mainTabsCustomize"));
        items.add(TextSettingsCellFactory.of(tabletModeRow, LocaleController.getString(R.string.TabletMode), switch (NekoConfig.tabletMode) {
            case NekoConfig.TABLET_AUTO -> LocaleController.getString(R.string.TabletModeAuto);
            case NekoConfig.TABLET_ENABLE -> LocaleController.getString(R.string.Enable);
            default -> LocaleController.getString(R.string.Disable);
        }).slug("tabletMode"));
        items.add(UItem.asCheck(alternativeTransitionRow, LocaleController.getString(R.string.AlternativeTransition)).setChecked(NekoConfig.alternativeTransition).slug("alternativeTransition"));
        if (NekoConfig.alternativeTransition) {
            SeekbarConfig speedConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TransitionSpeed),
                    "100", "1000", 100, 1000, 5,
                    progress -> NekoConfig.setAlternativeTransitionSpeed(Math.round(progress / 5f) * 5));
            items.add(SeekbarCellFactory.of(alternativeTransitionSpeedRow, speedConfig, NekoConfig.alternativeTransitionSpeed).slug("alternativeTransitionSpeed"));
            items.add(TextSettingsCellFactory.of(alternativeTransitionEaseRow, "Change ease", NekoConfig.alternativeTransitionEase).slug("alternativeTransitionEase"));
            var description = new SpannableStringBuilder(LocaleController.getString(R.string.AlternativeTransitionEaseDescription));
            int linkStart = description.toString().indexOf("cubic-bezier.com");
            if (linkStart >= 0) {
                description.setSpan(new URLSpanNoUnderline("https://cubic-bezier.com"), linkStart, linkStart + "cubic-bezier.com".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            items.add(UItem.asShadow(alternativeTransitionEaseDescriptionRow, description));
        }
        items.add(UItem.asHeader("Material Design 3"));
        items.add(UItem.asCheck(material3SwitchesRow, LocaleController.getString(R.string.Switches)).setChecked(NekoConfig.material3Switches).slug("material3Switches"));
        items.add(UItem.asCheck(m3SectionsStyleRow, LocaleController.getString(R.string.ListItems)).setChecked(NekoConfig.m3SectionsStyle).slug("m3SectionsStyle"));
        items.add(InfoCheckCellFactory.of(loadingIndicatorsRow, LocaleController.getString(R.string.LoadingIndicators), NekoConfig.wavyEnabled, () -> showLoadingIndicatorsInfo()).slug("loadingIndicators"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Chat Header"));
        items.add(TextSettingsCellFactory.of(chatHeaderSettingsRow, LocaleController.getString(R.string.ChatHeaderSettings), "›").slug("chatHeaderSettings"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuNonIslandUI)));
        items.add(UItem.asCheck(nonIslandTabBarsRow, LocaleController.getString(R.string.InuNonIslandTabBars)).setChecked(NekoConfig.nonIslandTabBars).slug("nonIslandTabBars"));
        items.add(UItem.asCheck(nonIslandGlobalSearchRow, LocaleController.getString(R.string.InuNonIslandGlobalSearch)).setChecked(NekoConfig.nonIslandGlobalSearch).slug("nonIslandGlobalSearch"));
        items.add(UItem.asCheck(nonIslandChatElementsRow, LocaleController.getString(R.string.InuNonIslandChatElements)).setChecked(NekoConfig.nonIslandChatElements).slug("nonIslandChatElements"));
        items.add(UItem.asCheck(hideFadeViewRow, LocaleController.getString(R.string.InuHideFadeView)).setChecked(NekoConfig.hideFadeView).slug("hideFadeView"));
        items.add(UItem.asCheck(disableGlassGlareRow, LocaleController.getString(R.string.InuDisableGlassGlare)).setChecked(NekoConfig.disableGlassGlare).slug("disableGlassGlare"));
        items.add(UItem.asCheck(disableScrimBlurRow, LocaleController.getString(R.string.InuDisableScrimBlur)).setChecked(NekoConfig.disableScrimBlur).slug("disableScrimBlur"));
        items.add(UItem.asCheck(nonIslandBottomBarRow, LocaleController.getString(R.string.InuNonIslandBottomBar)).setChecked(NekoConfig.nonIslandBottomBar).slug("nonIslandBottomBar"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.TextAnimation)));
        items.add(TextSettingsCellFactory.of(textAnimationSettingsRow, LocaleController.getString(R.string.TextAnimation), "›").slug("textAnimationSettings"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.SavedDialogsTab)));
        items.add(UItem.asCheck(hideStoriesRow, LocaleController.getString(R.string.HideStories)).slug("hideStories").setChecked(NekoConfig.hideStories));
        items.add(UItem.asCheck(mediaPreviewRow, LocaleController.getString(R.string.MediaPreview)).slug("mediaPreview").setChecked(NekoConfig.mediaPreview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Filters)));
        items.add(UItem.asCheck(hideAllTabRow, LocaleController.getString(R.string.HideAllTab)).slug("hideAllTab").setChecked(NekoConfig.hideAllTab));
        items.add(TextSettingsCellFactory.of(tabsTitleTypeRow, LocaleController.getString(R.string.TabTitleType), switch (NekoConfig.tabsTitleType) {
            case NekoConfig.TITLE_TYPE_TEXT ->
                    LocaleController.getString(R.string.TabTitleTypeText);
            case NekoConfig.TITLE_TYPE_ICON ->
                    LocaleController.getString(R.string.TabTitleTypeIcon);
            default -> LocaleController.getString(R.string.TabTitleTypeMix);
        }).slug("tabsTitleType"));
        items.add(TextSettingsCellFactory.of(tabsPositionRow, LocaleController.getString(R.string.TabsPosition), LocaleController.getString(NekoConfig.bottomFilterTabs ? R.string.TabsPositionBottom : R.string.TabsPositionTop)).slug("tabsPosition"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.LiteOptionsBlur2)));
        items.add(UItem.asCheck(strokeOnViewsRow, LocaleController.getString(R.string.StrokeOnViews)).setChecked(NekoConfig.strokeOnViews).slug("strokeOnViews"));
        items.add(TextSettingsCellFactory.of(blurSettingsRow, LocaleController.getString(R.string.BlurSettings), "›").slug("blurSettings"));
        items.add(UItem.asShadow(null));

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.LiquidGlassSettings)));
            items.add(UItem.asCheck(forceBlurLiquidGlassRow, LocaleController.getString(R.string.ForceBlurLiquidGlass)).setChecked(NekoConfig.forceBlurLiquidGlass).slug("forceBlurLiquidGlass"));
            items.add(TextSettingsCellFactory.of(liquidGlassRow, LocaleController.getString(R.string.LiquidGlassTitle), LocaleController.getString(R.string.LiquidGlassSettingsDesc)).slug("liquidGlass"));
            items.add(UItem.asShadow(null));
        }

    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == textAnimationSettingsRow) {
            presentFragment(new NekoTextAnimationSettingsActivity());
        } else if (id == tabletModeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabletModeAuto));
            types.add(NekoConfig.TABLET_AUTO);
            arrayList.add(LocaleController.getString(R.string.Enable));
            types.add(NekoConfig.TABLET_ENABLE);
            arrayList.add(LocaleController.getString(R.string.Disable));
            types.add(NekoConfig.TABLET_DISABLE);
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabletMode), types.indexOf(NekoConfig.tabletMode), getParentActivity(), view, i -> {
                NekoConfig.setTabletMode(types.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                AndroidUtilities.resetTabletFlag();
                if (getParentActivity() instanceof LaunchActivity) {
                    ((LaunchActivity) getParentActivity()).invalidateTabletMode();
                }
            }, resourcesProvider);
        } else if (id == emojiSetsRow) {
            presentFragment(new NekoEmojiSettingsActivity());
        } else if (id == disableNumberRoundingRow) {
            NekoConfig.toggleDisableNumberRounding();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableNumberRounding);
            }
        } else if (id == appBarShadowRow) {
            NekoConfig.toggleDisableAppBarShadow();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableAppBarShadow);
            }
            parentLayout.setHeaderShadow(NekoConfig.disableAppBarShadow ? null : parentLayout.getParentActivity().getDrawable(R.drawable.header_shadow).mutate());
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == mediaPreviewRow) {
            NekoConfig.toggleMediaPreview();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.mediaPreview);
            }
        } else if (id == hideStoriesRow) {
            NekoConfig.toggleHideStories();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideStories);
            }
            getNotificationCenter().postNotificationName(NotificationCenter.storiesEnabledUpdate);
        } else if (id == formatTimeWithSecondsRow) {
            NekoConfig.toggleFormatTimeWithSeconds();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.formatTimeWithSeconds);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == hideAllTabRow) {
            NekoConfig.toggleHideAllTab();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideAllTab);
            }
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (id == tabsTitleTypeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeText));
            types.add(NekoConfig.TITLE_TYPE_TEXT);
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeIcon));
            types.add(NekoConfig.TITLE_TYPE_ICON);
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeMix));
            types.add(NekoConfig.TITLE_TYPE_MIX);
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabTitleType), types.indexOf(NekoConfig.tabsTitleType), getParentActivity(), view, i -> {
                NekoConfig.setTabsTitleType(types.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            }, resourcesProvider);
        } else if (id == predictiveBackAnimationRow) {
            NekoConfig.togglePredictiveBackAnimation();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.predictiveBackAnimation);
            }
            showRestartBulletin();
        } else if (id == hideBottomNavigationBarRow) {
            NekoConfig.toggleHideBottomNavigationBar();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideBottomNavigationBar);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == dynamicTabSizeRow) {
            NekoConfig.toggleDynamicTabSize();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.dynamicTabSize);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == mainTabsCustomizeRow) {
            presentFragment(new MainTabsSettingsActivity());
        } else if (id == tabsPositionRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabsPositionTop));
            arrayList.add(LocaleController.getString(R.string.TabsPositionBottom));
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabsPosition), NekoConfig.bottomFilterTabs ? 1 : 0, getParentActivity(), view, i -> {
                NekoConfig.setBottomFilterTabs(i == 1);
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                parentLayout.rebuildAllFragmentViews(false, false);
            }, resourcesProvider);
        } else if (id == strokeOnViewsRow) {
            NekoConfig.toggleStrokeOnViews();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.strokeOnViews);
            }
        } else if (id == blurSettingsRow) {
            presentFragment(new NekoBlurSettingsActivity());
        } else if (id == disableGooeyAvatarAnimationRow) {
            NekoConfig.toggleDisableGooeyAvatarAnimation();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableGooeyAvatarAnimation);
            }
            showRestartBulletin();
        } else if (id == keepUnreadChatsOnTopRow) {
            NekoConfig.toggleKeepUnreadChatsOnTop();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.keepUnreadChatsOnTop);
            }
            showRestartBulletin();
            listView.adapter.update(true);
        } else if (id == keepUnreadArchivedOnTopRow) {
            NekoConfig.toggleKeepUnreadArchivedOnTop();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.keepUnreadArchivedOnTop);
            }
        } else if (id == hideRecordButtonRow) {
            NekoConfig.toggleHideRecordButton();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideRecordButton);
            }
        } else if (id == alternativeTransitionRow) {
            NekoConfig.toggleAlternativeTransition();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.alternativeTransition);
            }
            listView.adapter.update(true);
            // Predictive back is registered once in LaunchActivity.onCreate, so switching the
            // Material 3 predictive-back animation only takes effect after a restart.
            showRestartBulletin();
        } else if (id == alternativeTransitionEaseRow) {
            showEaseDialog();
        } else if (id == aospTransitionRow) {
            NekoConfig.toggleAospTransition();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.aospTransition);
            }
        } else if (id == material3SwitchesRow) {
            NekoConfig.toggleMaterial3Switches();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.material3Switches);
            }
            showRestartBulletin();
        } else if (id == m3SectionsStyleRow) {
            NekoConfig.toggleM3SectionsStyle();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.m3SectionsStyle);
            }
            showRestartBulletin();
        } else if (id == nonIslandTabBarsRow) {
            NekoConfig.toggleNonIslandTabBars();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandTabBars);
            }
        } else if (id == nonIslandGlobalSearchRow) {
            NekoConfig.toggleNonIslandGlobalSearch();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandGlobalSearch);
            }
        } else if (id == nonIslandChatElementsRow) {
            if (!NekoConfig.nonIslandChatElements && NekoConfig.material3ChatHeaders) {
                showHeaderConflictBulletin(LocaleController.getString(R.string.InuMaterial3ChatHeaders), LocaleController.getString(R.string.InuNonIslandChatElements), () -> {
                    NekoConfig.toggleMaterial3ChatHeaders();
                    NekoConfig.toggleNonIslandChatElements();
                    listView.adapter.update(true);
                });
                return;
            }
            NekoConfig.toggleNonIslandChatElements();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandChatElements);
            }
        } else if (id == material3ChatHeadersRow) {
            if (!NekoConfig.material3ChatHeaders && NekoConfig.nonIslandChatElements) {
                showHeaderConflictBulletin(LocaleController.getString(R.string.InuNonIslandChatElements), LocaleController.getString(R.string.InuMaterial3ChatHeaders), () -> {
                    NekoConfig.toggleNonIslandChatElements();
                    NekoConfig.toggleMaterial3ChatHeaders();
                    listView.adapter.update(true);
                });
                return;
            }
            NekoConfig.toggleMaterial3ChatHeaders();
            if (view instanceof InfoCheckCell) {
                ((InfoCheckCell) view).setChecked(NekoConfig.material3ChatHeaders);
            }
        } else if (id == loadingIndicatorsRow) {
            NekoConfig.toggleWavyEnabled();
            if (view instanceof InfoCheckCell) {
                ((InfoCheckCell) view).setChecked(NekoConfig.wavyEnabled);
            }
        } else if (id == hideFadeViewRow) {
            NekoConfig.toggleHideFadeView();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideFadeView);
            }
        } else if (id == disableGlassGlareRow) {
            NekoConfig.toggleDisableGlassGlare();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableGlassGlare);
            }
        } else if (id == disableScrimBlurRow) {
            NekoConfig.toggleDisableScrimBlur();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableScrimBlur);
            }
        } else if (id == nonIslandBottomBarRow) {
            NekoConfig.toggleNonIslandBottomBar();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandBottomBar);
            }
            showRestartBulletin();
        } else if (id == roundedBulletinRow) {
            NekoConfig.toggleRoundedBulletin();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.roundedBulletin);
            }
        } else if (id == forceBlurLiquidGlassRow) {
            NekoConfig.toggleForceBlurLiquidGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.forceBlurLiquidGlass);
            }
        } else if (id == liquidGlassRow) {
            presentFragment(new NekoLiquidGlassSettingsActivity());
        } else if (id == chatHeaderSettingsRow) {
            presentFragment(new NekoChatHeaderSettingsActivity());
        }
    }

    private void showLoadingIndicatorsInfo() {
        if (getParentActivity() == null) return;
        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(getParentActivity(), false, resourcesProvider);
        sheet.setTitle(LocaleController.getString(R.string.LoadingIndicators));

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        View previewView = new View(getParentActivity()) {
            private final org.telegram.ui.Components.CircularProgressDrawable defaultDrawable;
            private final org.telegram.ui.Components.CircularProgressDrawable md3Drawable;
            {
                int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
                defaultDrawable = new org.telegram.ui.Components.CircularProgressDrawable(color);
                defaultDrawable.size = AndroidUtilities.dp(36);
                defaultDrawable.thickness = AndroidUtilities.dp(3);
                md3Drawable = new org.telegram.ui.Components.CircularProgressDrawable(color);
                md3Drawable.size = AndroidUtilities.dp(36);
                md3Drawable.thickness = AndroidUtilities.dp(3);
                setWillNotDraw(false);
            }
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int halfW = w / 2;
                int size = AndroidUtilities.dp(70);
                int cy = getHeight() / 2;
                int leftCenterX = halfW / 2;
                int rightCenterX = halfW + halfW / 2;
                boolean savedWavy = zxc.iconic.xenon.NekoConfig.wavyEnabled;

                zxc.iconic.xenon.NekoConfig.wavyEnabled = false;
                defaultDrawable.setBounds(leftCenterX - size / 2, cy - size / 2, leftCenterX + size / 2, cy + size / 2);
                defaultDrawable.draw(canvas);

                zxc.iconic.xenon.NekoConfig.wavyEnabled = true;
                md3Drawable.setBounds(rightCenterX - size / 2, cy - size / 2, rightCenterX + size / 2, cy + size / 2);
                md3Drawable.draw(canvas);

                zxc.iconic.xenon.NekoConfig.wavyEnabled = savedWavy;
                invalidate();
            }
        };
        previewView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(90)));
        container.addView(previewView);

        LinearLayout labelsLayout = new LinearLayout(getParentActivity());
        labelsLayout.setOrientation(LinearLayout.HORIZONTAL);
        labelsLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView leftLabel = new TextView(getParentActivity());
        leftLabel.setText("Telegram");
        leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        leftLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        leftLabel.setGravity(Gravity.CENTER);
        leftLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labelsLayout.addView(leftLabel);

        TextView rightLabel = new TextView(getParentActivity());
        rightLabel.setText(LocaleController.getString(R.string.MaterialDesign3));
        rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        rightLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        rightLabel.setGravity(Gravity.CENTER);
        rightLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labelsLayout.addView(rightLabel);

        container.addView(labelsLayout);

        sheet.setCustomView(container);
        sheet.show();
    }

    private void showChatHeadersInfo() {
        if (getParentActivity() == null) return;
        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(getParentActivity(), false, resourcesProvider);
        sheet.setTitle(LocaleController.getString(R.string.InuMaterial3ChatHeaders));

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, 0);

        int currentAccount = UserConfig.selectedAccount;
        TLRPC.User user = org.telegram.messenger.MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).clientUserId);
        if (user == null) user = UserConfig.getInstance(currentAccount).getCurrentUser();
        String userName = user != null ? UserObject.getUserName(user) : "User";
        String onlineText = LocaleController.getString(R.string.Online);

        org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap wallpaperSource = new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap();

        org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory = new org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory(wallpaperSource);
        org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider colorProvider = org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider);

        org.telegram.ui.ActionBar.ActionBar normalActionBar = new org.telegram.ui.ActionBar.ActionBar(getParentActivity(), resourcesProvider);
        normalActionBar.setOccupyStatusBar(false);
        normalActionBar.setTitle("");
        normalActionBar.setupGlass(factory, colorProvider, false);
        ChatAvatarContainer normalAvatar = new ChatAvatarContainer(getParentActivity(), null, false, resourcesProvider);
        normalAvatar.setOccupyStatusBar(false);
        normalAvatar.setUserAvatar(user, true);
        normalAvatar.setTitle(userName, false, false, false, false, null, false);
        normalAvatar.setSubtitle(onlineText);
        normalAvatar.setGlassMode();
        normalAvatar.setM3HeaderMode(false);
        normalActionBar.setChatAvatarContainer(normalAvatar);
        normalActionBar.setBackButtonDrawable(new org.telegram.ui.ActionBar.BackDrawable(false));
        normalActionBar.addView(normalAvatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        normalActionBar.createMenu().addItem(999, R.drawable.ic_ab_other);
        normalActionBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
        container.addView(normalActionBar);

        TextView normalLabel = new TextView(getParentActivity());
        normalLabel.setText("Telegram");
        normalLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        normalLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        normalLabel.setGravity(Gravity.CENTER);
        normalLabel.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        container.addView(normalLabel);

        org.telegram.ui.ActionBar.ActionBar m3ActionBar = new org.telegram.ui.ActionBar.ActionBar(getParentActivity(), resourcesProvider);
        m3ActionBar.setOccupyStatusBar(false);
        m3ActionBar.setTitle("");
        m3ActionBar.inu_m3ChatHeader = true;
        m3ActionBar.setupGlass(factory, colorProvider, false);
        ChatAvatarContainer m3Avatar = new ChatAvatarContainer(getParentActivity(), null, false, resourcesProvider);
        m3Avatar.setOccupyStatusBar(false);
        m3Avatar.setUserAvatar(user, true);
        m3Avatar.setTitle(userName, false, false, false, false, null, false);
        m3Avatar.setSubtitle(onlineText);
        m3Avatar.setM3HeaderMode(true);
        m3ActionBar.setChatAvatarContainer(m3Avatar);
        m3ActionBar.setBackButtonDrawable(new org.telegram.ui.ActionBar.BackDrawable(false));
        m3ActionBar.addView(m3Avatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        m3ActionBar.createMenu().addItem(999, R.drawable.ic_ab_other);
        m3ActionBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
        container.addView(m3ActionBar);

        TextView m3Label = new TextView(getParentActivity());
        m3Label.setText(LocaleController.getString(R.string.MaterialDesign3));
        m3Label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        m3Label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        m3Label.setGravity(Gravity.CENTER);
        m3Label.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        container.addView(m3Label);

        sheet.setCustomView(container);
        sheet.show();
    }

    private void showHeaderConflictBulletin(String disableWhat, String enableWhat, Runnable onDisable) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                LocaleController.formatString(R.string.InuMaterial3ChatHeadersConflict, disableWhat, enableWhat),
                LocaleController.getString(R.string.Disable),
                onDisable).show();
    }

    private void showEaseDialog() {
        Context context = getParentActivity();
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle("Change ease");

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setText(NekoConfig.alternativeTransitionEase);
        editText.setSelection(editText.getText().length());

        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) {
                NekoConfig.setAlternativeTransitionEase(text);
                listView.adapter.update(true);
            }
        });

        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        String defaultEase = "0.37,0.01,0.1,1";
        builder.setNeutralButton(LocaleController.getString("Reset", R.string.Reset), (dialog, which) -> {
            NekoConfig.setAlternativeTransitionEase(defaultEase);
            listView.adapter.update(true);
        });

        AlertDialog dialog = builder.show();
        if (NekoConfig.alternativeTransitionEase.equals(defaultEase)) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setAlpha(0.5f);
        }

        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ChangeChannelNameColor2);
    }

    @Override
    protected String getKey() {
        return "a";
    }

    private static class EmojiSetCellFactory extends UItem.UItemFactory<EmojiSetCell> {
        static {
            setup(new EmojiSetCellFactory());
        }

        @Override
        public EmojiSetCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EmojiSetCell(context, false, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            var cell = (EmojiSetCell) view;
            var pack = cell.getPack();
            var newPack = EmojiHelper.getInstance().getCurrentEmojiPackInfo();
            cell.setData(newPack, pack != null && !pack.getPackId().equals(newPack.getPackId()), divider);
        }

        public static UItem of(int id, String title) {
            var item = UItem.ofFactory(EmojiSetCellFactory.class);
            item.id = id;
            item.text = title;
            return item;
        }
    }
}
