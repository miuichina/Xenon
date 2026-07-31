package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayProxySubscriptionStore;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

/**
 * Manage remote proxy subscriptions: each entry is a URL that is fetched and parsed
 * line-by-line into proxy profiles. Refreshing a subscription atomically replaces its
 * profiles (no duplicates). Profiles imported from a subscription are tagged with the
 * subscription id so they are removed/replaced on the next sync.
 */
public class NekoXrayProxySubscriptionsActivity extends BaseNekoSettingsActivity {

    private final int addRow = rowId++;
    private final int subsStartRow = 100;

    private final ArrayList<XrayProxySubscriptionStore.Subscription> subscriptions = new ArrayList<>();

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        subscriptions.clear();
        subscriptions.addAll(XrayProxySubscriptionStore.getSubscriptions());

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxySubscriptions)));
        items.add(UItem.asButton(addRow, R.drawable.msg_add,
                        LocaleController.getString(R.string.XrayProxyAddSubscription))
                .accent().slug("xraySubscriptionAdd"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxySubscriptionsHint)));

        if (subscriptions.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxySubscriptionsEmpty)));
            return;
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxySubscriptionsList)));
        for (int i = 0; i < subscriptions.size(); i++) {
            XrayProxySubscriptionStore.Subscription sub = subscriptions.get(i);
            String subtitle = buildSubtitle(sub);
            items.add(UItem.asButton(subsStartRow + i, R.drawable.msg_link2, sub.name, subtitle)
                    .slug("xraySubscription"));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxySubscriptionsListHint)));
    }

    private String buildSubtitle(XrayProxySubscriptionStore.Subscription sub) {
        int count = XrayProxyProfileStore.countProfilesBySubscription(sub.id);
        String countText = LocaleController.formatStringSimple(
                LocaleController.getString(R.string.XrayProxySubscriptionProfiles), count);
        String when = sub.lastUpdated > 0
                ? LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxySubscriptionUpdated), formatTime(sub.lastUpdated))
                : LocaleController.getString(R.string.XrayProxySubscriptionNotSynced);
        return sub.url + "\n" + countText + " · " + when;
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == addRow) {
            showAddSubscriptionDialog();
            return;
        }
        if (id >= subsStartRow) {
            int index = id - subsStartRow;
            if (index >= 0 && index < subscriptions.size()) {
                refreshSubscription(subscriptions.get(index));
            }
        }
    }

    @Override
    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id < subsStartRow) {
            return false;
        }
        int index = id - subsStartRow;
        if (index < 0 || index >= subscriptions.size()) {
            return false;
        }
        confirmDelete(subscriptions.get(index));
        return true;
    }

    private void showAddSubscriptionDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyAddSubscription));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor urlField = new EditTextBoldCursor(context);
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        urlField.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        urlField.setHintText(LocaleController.getString(R.string.XrayProxySubscriptionUrlHint));
        urlField.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setBackground(null);
        urlField.setPadding(0, 0, 0, 0);
        container.addView(urlField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 12));

        EditTextBoldCursor nameField = new EditTextBoldCursor(context);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameField.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        nameField.setHintText(LocaleController.getString(R.string.XrayProxySubscriptionNameHint));
        nameField.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameField.setBackground(null);
        nameField.setPadding(0, 0, 0, 0);
        container.addView(nameField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String url = urlField.getText() == null ? "" : urlField.getText().toString().trim();
                String name = nameField.getText() == null ? "" : nameField.getText().toString().trim();
                if (TextUtils.isEmpty(url)) {
                    return;
                }
                if (!url.toLowerCase(Locale.US).startsWith("http://") && !url.toLowerCase(Locale.US).startsWith("https://")) {
                    showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
                    return;
                }
                dialog.dismiss();
                XrayProxySubscriptionStore.Subscription sub = XrayProxySubscriptionStore.addSubscription(url, name);
                refreshSubscription(sub);
            });
        }
    }

    private void refreshSubscription(XrayProxySubscriptionStore.Subscription sub) {
        if (sub == null || TextUtils.isEmpty(sub.url)) {
            return;
        }
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(context, 3);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        final String subscriptionId = sub.id;
        final String url = sub.url;
        Utilities.globalQueue.postRunnable(() -> {
            int basePort = XrayProxyProfileStore.createEmptyProfile().localPort;
            List<XrayUriConfigFactory.ParseResult> results = XrayUriConfigFactory.fromRemoteUrl(url, basePort);
            ArrayList<XrayProxyProfileStore.Profile> newProfiles = buildProfiles(results, subscriptionId);
            int added = XrayProxyProfileStore.replaceSubscriptionProfiles(subscriptionId, newProfiles);
            if (added > 0) {
                XrayProxySubscriptionStore.setUpdated(subscriptionId, System.currentTimeMillis());
            }
            final int resultCount = added;
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                if (resultCount > 0) {
                    AlertsCreator.showSimpleAlert(
                            NekoXrayProxySubscriptionsActivity.this,
                            LocaleController.getString(R.string.XrayProxyTitle),
                            LocaleController.formatStringSimple(
                                    LocaleController.getString(R.string.XrayProxySubscriptionSynced), resultCount));
                } else {
                    showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
                }
            });
        });
    }

    private void confirmDelete(XrayProxySubscriptionStore.Subscription sub) {
        if (sub == null) {
            return;
        }
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setMessage(LocaleController.getString(R.string.XrayProxySubscriptionDeleteConfirm));
        builder.setTitle(sub.name);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            XrayProxyProfileStore.replaceSubscriptionProfiles(sub.id, null);
            XrayProxySubscriptionStore.deleteSubscription(sub.id);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
        showDialog(builder.create());
    }

    private ArrayList<XrayProxyProfileStore.Profile> buildProfiles(List<XrayUriConfigFactory.ParseResult> results, String subscriptionId) {
        ArrayList<XrayProxyProfileStore.Profile> profiles = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            return profiles;
        }
        int count = 0;
        for (XrayUriConfigFactory.ParseResult res : results) {
            if (res == null || !res.valid || res.config == null) {
                continue;
            }
            String json;
            try {
                json = res.config.toString(2);
            } catch (Throwable ignore) {
                json = res.config.toString();
            }
            int port = 10808 + count;
            if (port > 65535) {
                port = 10808 + (count % 50000);
            }
            XrayConfigValidator.ValidationResult val = XrayConfigValidator.validate(json, port);
            if (!val.valid) {
                continue;
            }
            XrayProxyProfileStore.Profile profile = new XrayProxyProfileStore.Profile();
            profile.id = XrayProxyProfileStore.generateId();
            profile.localPort = port;
            profile.configJson = json;
            profile.name = TextUtils.isEmpty(res.nodeName)
                    ? (res.protocol + " " + res.host + ":" + res.port)
                    : res.nodeName;
            profile.checkUrl = XrayProxyProfileStore.DEFAULT_CHECK_URL;
            profile.subscriptionId = subscriptionId;
            profiles.add(profile);
            count++;
        }
        return profiles;
    }

    private static String formatTime(long ts) {
        if (ts <= 0) {
            return "";
        }
        return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxySubscriptions);
    }

    @Override
    protected String getKey() {
        return "xraySubscriptions";
    }
}
