package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.proxy.XrayConfigSummary;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

/**
 * Proxy profiles list with a Telegram-style selectable list.
 * Row click -> set profile as active. Trailing overflow icon -> per-profile action menu.
 * Long-press still opens the same action menu for accessibility.
 */
public class NekoXrayProxyProfilesActivity extends BaseNekoSettingsActivity {

    private final int addClipboardRow = rowId++;
    private final int addUriRow = rowId++;
    private final int addUrlRow = rowId++;
    private final int addFileRow = rowId++;
    private final int addEmptyRow = rowId++;

    /** Row ids for profile rows start from this offset. */
    private final int profileStartRow = 100;

    private final ArrayList<XrayProxyProfileStore.Profile> profiles = new ArrayList<>();

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        profiles.clear();
        profiles.addAll(XrayProxyProfileStore.getProfiles());
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        String activeId = active == null ? "" : active.id;

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyImportSection)));
        items.add(UItem.asButton(addClipboardRow, R.drawable.msg_copy,
                        LocaleController.getString(R.string.XrayProxyAddFromClipboard))
                .accent().slug("xrayProfileAddClipboard"));
        items.add(UItem.asButton(addUriRow, R.drawable.msg_link2,
                        LocaleController.getString(R.string.XrayProxyAddFromUri))
                .slug("xrayProfileAddUri"));
        items.add(UItem.asButton(addUrlRow, R.drawable.msg_link2,
                        LocaleController.getString(R.string.XrayProxyAddFromUrl))
                .slug("xrayProfileAddUrl"));
        items.add(UItem.asButton(addFileRow, R.drawable.msg_log,
                        LocaleController.getString(R.string.XrayProxyAddFromFile))
                .slug("xrayProfileAddFile"));
        items.add(UItem.asButton(addEmptyRow, R.drawable.msg_add,
                        LocaleController.getString(R.string.XrayProxyAddEmptyProfile))
                .slug("xrayProfileAddEmpty").accent());
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfilesHint)));

        if (profiles.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfilesEmpty)));
            return;
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyProfilesListSection)));
        for (int i = 0; i < profiles.size(); i++) {
            XrayProxyProfileStore.Profile profile = profiles.get(i);
            boolean isActive = profile.id.equals(activeId);
            String endpoint = XrayConfigSummary.endpoint(
                    profile.configJson,
                    LocaleController.getString(R.string.XrayProxyConfigEmpty));
            final String profileId = profile.id;
            items.add(XrayProfileCellFactory.of(
                    profileStartRow + i,
                    profile.name,
                    endpoint,
                    isActive,
                    () -> showProfileActionsById(profileId)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfilesListHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == addClipboardRow) {
            addFromClipboard();
            return;
        }
        if (id == addUriRow) {
            showUriImportDialog();
            return;
        }
        if (id == addUrlRow) {
            showUrlImportDialog();
            return;
        }
        if (id == addFileRow) {
            openFilePicker();
            return;
        }
        if (id == addEmptyRow) {
            XrayProxyProfileStore.Profile empty = XrayProxyProfileStore.createEmptyProfile();
            XrayProxyProfileStore.addProfile(empty, true);
            presentFragment(NekoXrayProxyProfileEditActivity.forProfile(empty.id));
            return;
        }
        if (id >= profileStartRow) {
            int index = id - profileStartRow;
            if (index >= 0 && index < profiles.size()) {
                XrayProxyProfileStore.Profile selected = profiles.get(index);
                XrayProxyProfileStore.setActiveProfile(selected.id);
                listView.adapter.update(true);
            }
        }
    }

    @Override
    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id < profileStartRow) {
            return false;
        }
        int index = id - profileStartRow;
        if (index < 0 || index >= profiles.size()) {
            return false;
        }
        showProfileActions(profiles.get(index), view);
        return true;
    }

    /**
     * Shows the per-profile action menu (Edit / Duplicate / Delete).
     * Used by both long-press on a row and the trailing overflow icon.
     */
    private void showProfileActionsById(String profileId) {
        XrayProxyProfileStore.Profile profile = findProfile(profileId);
        if (profile == null) {
            return;
        }
        View anchor = listView != null ? listView.findViewByItemId(locateRowId(profileId)) : null;
        showProfileActions(profile, anchor);
    }

    private void showProfileActions(XrayProxyProfileStore.Profile profile, View anchorView) {
        ItemOptions options = ItemOptions.makeOptions(this, anchorView == null ? fragmentView : anchorView);
        options.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit),
                () -> presentFragment(NekoXrayProxyProfileEditActivity.forProfile(profile.id)));
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.XrayProxyDuplicateProfile), () -> {
            XrayProxyProfileStore.Profile copy = profile.copy();
            copy.id = "";
            copy.name = profile.name + " (copy)";
            XrayProxyProfileStore.addProfile(copy, false);
            listView.adapter.update(true);
        });
        options.addIf(XrayProxyProfileStore.getProfiles().size() > 1,
                R.drawable.msg_delete, LocaleController.getString(R.string.Delete), true,
                () -> {
                    boolean deleted = XrayProxyProfileStore.deleteProfile(profile.id);
                    if (!deleted) {
                        showError(LocaleController.getString(R.string.XrayProxyDeleteLastProfileError));
                    }
                    listView.adapter.update(true);
                });
        options.setMinWidth(190);
        options.show();
    }

    /**
     * Maps a profile id to its UItem row id inside the current list snapshot.
     */
    private int locateRowId(String profileId) {
        for (int i = 0; i < profiles.size(); i++) {
            if (TextUtils.equals(profiles.get(i).id, profileId)) {
                return profileStartRow + i;
            }
        }
        return -1;
    }

    private XrayProxyProfileStore.Profile findProfile(String profileId) {
        for (int i = 0; i < profiles.size(); i++) {
            XrayProxyProfileStore.Profile candidate = profiles.get(i);
            if (TextUtils.equals(candidate.id, profileId)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Imports a profile from clipboard URI/subscription and stores it as a new profile.
     */
    private static final int REQUEST_CODE_FILE_PICKER = 1199;

    private void openFilePicker() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.XrayProxyAddFromFile)), REQUEST_CODE_FILE_PICKER);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.XrayProxyAddFromFile)), REQUEST_CODE_FILE_PICKER);
            } catch (Throwable e) {
                showError(e.getMessage());
            }
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_FILE_PICKER && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importProfilesFromFile(data.getData());
        }
    }

    private void importProfilesFromFile(Uri uri) {
        Activity activity = getParentActivity();
        if (activity == null || uri == null) {
            return;
        }

        AlertDialog progressDialog = new AlertDialog(activity, 3);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();

        Utilities.globalQueue.postRunnable(() -> {
            String text = null;
            try (InputStream is = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int read;
                int totalRead = 0;
                int maxBytes = 10 * 1024 * 1024;
                while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
                    sb.append(buffer, 0, read);
                    totalRead += read;
                    if (totalRead > maxBytes) {
                        break;
                    }
                }
                text = sb.toString();
            } catch (Throwable t) {
                FileLog.e(t);
            }

            final String fileContent = text;
            if (TextUtils.isEmpty(fileContent)) {
                AndroidUtilities.runOnUIThread(() -> {
                    progressDialog.dismiss();
                    showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
                });
                return;
            }

            processBulkImportInBackground(fileContent, progressDialog);
        });
    }

    private void addFromClipboard() {
        String clipText = readClipboardText();
        if (TextUtils.isEmpty(clipText)) {
            showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(activity, 3);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
        processBulkImportInBackground(clipText, progressDialog);
    }

    private void showUriImportDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyAddFromUri));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setHintText(LocaleController.getString(R.string.XrayProxyImportUriHint));
        editText.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        editText.setSingleLine(false);
        editText.setMinLines(3);
        editText.setMaxLines(8);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String raw = editText.getText() == null ? "" : editText.getText().toString().trim();
                dialog.dismiss();
                if (TextUtils.isEmpty(raw)) {
                    return;
                }
                AlertDialog progressDialog = new AlertDialog(context, 3);
                progressDialog.setCanceledOnTouchOutside(false);
                progressDialog.show();
                processBulkImportInBackground(raw, progressDialog);
            });
        }
    }

    private void showUrlImportDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyAddFromUrl));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setHintText(LocaleController.getString(R.string.XrayProxyImportUrlHint));
        editText.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String url = editText.getText() == null ? "" : editText.getText().toString().trim();
                dialog.dismiss();
                if (TextUtils.isEmpty(url)) {
                    return;
                }
                AlertDialog progressDialog = new AlertDialog(context, 3);
                progressDialog.setCanceledOnTouchOutside(false);
                progressDialog.show();

                Utilities.globalQueue.postRunnable(() -> {
                    XrayProxyProfileStore.Profile template = XrayProxyProfileStore.createEmptyProfile();
                    List<XrayUriConfigFactory.ParseResult> results = XrayUriConfigFactory.fromRemoteUrl(url, template.localPort);
                    processParseResultsInBackground(results, progressDialog);
                });
            });
        }
    }

    private void processBulkImportInBackground(String rawText, AlertDialog progressDialog) {
        Utilities.globalQueue.postRunnable(() -> {
            XrayProxyProfileStore.Profile template = XrayProxyProfileStore.createEmptyProfile();
            List<XrayUriConfigFactory.ParseResult> results = XrayUriConfigFactory.fromBulkText(rawText, template.localPort);
            processParseResultsInBackground(results, progressDialog);
        });
    }

    private void processParseResultsInBackground(List<XrayUriConfigFactory.ParseResult> results, AlertDialog progressDialog) {
        if (results == null || results.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
            });
            return;
        }

        XrayProxyProfileStore.Profile template = XrayProxyProfileStore.createEmptyProfile();
        int basePort = template.localPort;

        ArrayList<XrayProxyProfileStore.Profile> newProfiles = new ArrayList<>();
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

            int port = basePort + count;
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
            newProfiles.add(profile);
            count++;
        }

        int addedCount = XrayProxyProfileStore.addProfilesBatch(newProfiles, false);

        AndroidUtilities.runOnUIThread(() -> {
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
            if (addedCount > 0) {
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                AlertsCreator.showSimpleAlert(
                        NekoXrayProxyProfilesActivity.this,
                        LocaleController.getString(R.string.XrayProxyTitle),
                        LocaleController.formatStringSimple(
                                LocaleController.getString(R.string.XrayProxyImportSuccess), addedCount));
            } else {
                showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
            }
        });
    }

    private void createProfileFromParseResult(XrayUriConfigFactory.ParseResult result, int localPort) {
        if (result.config == null) {
            showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
            return;
        }

        String json;
        try {
            json = result.config.toString(2);
        } catch (Throwable ignore) {
            json = result.config.toString();
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(json, localPort);
        if (!validation.valid) {
            showError(validation.message);
            return;
        }

        XrayProxyProfileStore.Profile profile = XrayProxyProfileStore.createEmptyProfile();
        profile.localPort = localPort;
        profile.configJson = json;
        profile.name = TextUtils.isEmpty(result.nodeName)
                ? (result.protocol + " " + result.host + ":" + result.port)
                : result.nodeName;
        XrayProxyProfileStore.addProfile(profile, true);
        listView.adapter.update(true);
        presentFragment(NekoXrayProxyProfileEditActivity.forProfile(profile.id));
    }

    private String readClipboardText() {
        try {
            Activity activity = getParentActivity();
            if (activity == null) {
                return "";
            }
            ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return "";
            }
            ClipData clipData = manager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return "";
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(activity);
            return text == null ? "" : text.toString();
        } catch (Throwable ignore) {
            return "";
        }
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyProfiles);
    }

    @Override
    protected String getKey() {
        return "xrayProfiles";
    }
}
