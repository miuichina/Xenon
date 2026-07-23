package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
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

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.proxy.XrayAppProxyManager;
import zxc.iconic.xenon.proxy.XrayConfigSummary;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayLocalSocksAuth;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayTelegramProxyBridge;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;
import zxc.iconic.xenon.proxy.XrayVpnService;

/**
 * Unified dashboard for the app-only Xray proxy.
 *
 * <p>Layout:
 * <ol>
 *   <li><b>Connection</b> — enable toggle and a bold status row colored by core state.
 *   <li><b>Active profile</b> — inline radio list of every saved profile. Tapping a row
 *       activates it in place; the previously active row is reshown with the long-press
 *       action menu (Edit / Duplicate / Delete) via {@link #onItemLongClick}.
 *   <li><b>Diagnostics</b> — delay check button that shows the measured RTT inline as the
 *       row's subtitle, rather than popping an {@link AlertDialog}.
 * </ol>
 *
 * <p>Overflow menu hosts Logs / Advanced / About. Start/stop flows stay as in the
 * previous implementation, just with the state changes surfaced through the radio list.
 */
public class NekoXrayProxyHubActivity extends BaseNekoSettingsActivity {

    private static final int MENU_LOGS = 100;
    private static final int MENU_ADVANCED = 101;
    private static final int MENU_ABOUT = 102;

    private static final int PROFILES_BASE_ROW = 1000;

    private final int enabledRow = rowId++;
    private final int vpnModeRow = rowId++;
    private final int statusRow = rowId++;
    private final int profilesHeaderRow = rowId++;
    private final int addProfileRow = rowId++;
    private final int delayCheckRow = rowId++;
    private final int delayResultRow = rowId++;

    private final ArrayList<XrayProxyProfileStore.Profile> profiles = new ArrayList<>();
    private String lastDelayText = "";

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem overflow = menu.addItem(0, R.drawable.ic_ab_other);
        overflow.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        overflow.addSubItem(MENU_LOGS, R.drawable.msg_log, LocaleController.getString(R.string.XrayProxyLogs));
        overflow.addSubItem(MENU_ADVANCED, R.drawable.msg_settings, LocaleController.getString(R.string.XrayProxyAdvancedSection));
        overflow.addSubItem(MENU_ABOUT, R.drawable.msg_info, LocaleController.getString(R.string.XrayProxyAbout));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                    return;
                }
                if (id == MENU_LOGS) {
                    presentFragment(new NekoXrayProxyLogsActivity());
                    return;
                }
                if (id == MENU_ADVANCED) {
                    presentFragment(new NekoXrayProxyAdvancedActivity());
                    return;
                }
                if (id == MENU_ABOUT) {
                    showAboutDialog();
                }
            }
        });
        return actionBar;
    }

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
        boolean running = XrayAppProxyManager.isRunning();
        boolean canRun = hasUsableActiveProfile(active);

        // — Connection section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.Connection)));
        items.add(UItem.asCheck(enabledRow,
                        LocaleController.getString(R.string.XrayProxyEnable),
                        LocaleController.getString(R.string.XrayProxyEnableDesc))
                .setChecked(NekoConfig.xrayAppProxyEnabled)
                .setEnabled(canRun || NekoConfig.xrayAppProxyEnabled)
                .slug("xrayProxyEnabled"));
        items.add(UItem.asCheck(vpnModeRow,
                        LocaleController.getString(R.string.XrayVpnMode),
                        LocaleController.getString(R.string.XrayVpnModeDesc))
                .setChecked(NekoConfig.xrayVpnMode)
                .setEnabled(canRun)
                .slug("xrayVpnMode"));
        UItem statusItem = UItem.asButton(statusRow,
                running ? R.drawable.msg_online : R.drawable.msg_disable,
                LocaleController.getString(R.string.XrayProxyStatus),
                running ? LocaleController.getString(R.string.XrayProxyStatusRunning)
                        : LocaleController.getString(R.string.XrayProxyStatusStopped))
                .slug("xrayProxyStatus");
        if (running) {
            statusItem.accent();
        } else {
            statusItem.red();
        }
        statusItem.setEnabled(false);
        items.add(statusItem);
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyConnectionHint)));

        // — Active profile section: inline selectable list —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyActiveProfile)));
        if (profiles.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfilesEmpty)));
        } else {
            for (int i = 0; i < profiles.size(); i++) {
                XrayProxyProfileStore.Profile profile = profiles.get(i);
                boolean isActive = profile.id.equals(activeId);
                String endpoint = XrayConfigSummary.endpoint(
                        profile.configJson,
                        LocaleController.getString(R.string.XrayProxyConfigEmpty));
                items.add(UItem.asRadio(PROFILES_BASE_ROW + i,
                        profile.name,
                        endpoint).setChecked(isActive));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyHubProfilesHint)));
        }

        // — Quick add —
        items.add(UItem.asButton(addProfileRow, R.drawable.msg_add,
                LocaleController.getString(R.string.XrayProxyAddEmptyProfile)).accent().slug("xrayProxyAddEmpty"));

        // — Diagnostics section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyDiagnosticsSection)));
        UItem delayItem = UItem.asButton(delayCheckRow, R.drawable.proxy_check,
                LocaleController.getString(R.string.XrayProxyDelayCheck))
                .setEnabled(canRun).slug("xrayProxyDelay");
        items.add(delayItem);
        if (!TextUtils.isEmpty(lastDelayText)) {
            items.add(UItem.asShadow(lastDelayText));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyRuntimeHint)));
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == enabledRow) {
            NekoConfig.setXrayAppProxyEnabled(!NekoConfig.xrayAppProxyEnabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.xrayAppProxyEnabled);
            }
            if (NekoConfig.xrayAppProxyEnabled) {
                if (NekoConfig.xrayVpnMode) {
                    startVpnWithChecks();
                } else {
                    startAppOnlyCore();
                }
            } else {
                stopProxyFlow();
            }
            listView.adapter.update(true);
            return;
        }

        if (id == vpnModeRow) {
            boolean targetState = !NekoConfig.xrayVpnMode;
            if (targetState) {
                try {
                    Activity act = getParentActivity();
                    Context ctx = act != null ? act : getContext();
                    Intent prepareIntent = VpnService.prepare(ctx);
                    if (prepareIntent != null) {
                        startActivityForResult(prepareIntent, REQUEST_CODE_VPN_PERMISSION);
                        return;
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                NekoConfig.setXrayVpnMode(true);
                NekoConfig.setXrayAppProxyEnabled(true);
                startVpnWithChecks();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(true);
                }
            } else {
                NekoConfig.setXrayVpnMode(false);
                stopVpnAndCore();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(false);
                }
            }
            listView.adapter.update(true);
            return;
        }

        if (id == addProfileRow) {
            showAddConfigOptions(view);
            return;
        }

        if (id == delayCheckRow) {
            runDelayCheck();
            return;
        }

        if (id >= PROFILES_BASE_ROW) {
            int index = id - PROFILES_BASE_ROW;
            if (index < 0 || index >= profiles.size()) {
                return;
            }
            XrayProxyProfileStore.Profile selected = profiles.get(index);
            if (XrayProxyProfileStore.setActiveProfile(selected.id)) {
                // If core is running, restart with the new active profile so the change takes effect immediately.
                if (NekoConfig.xrayAppProxyEnabled && XrayAppProxyManager.isRunning()) {
                    restartProxyFlowWithActive();
                }
                listView.adapter.update(true);
            }
        }
    }

    @Override
    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id < PROFILES_BASE_ROW) {
            return false;
        }
        int index = id - PROFILES_BASE_ROW;
        if (index < 0 || index >= profiles.size()) {
            return false;
        }
        showProfileActions(profiles.get(index), view);
        return true;
    }

    private void showProfileActions(XrayProxyProfileStore.Profile profile, View anchorView) {
        org.telegram.ui.Components.ItemOptions options =
                org.telegram.ui.Components.ItemOptions.makeOptions(this, anchorView == null ? fragmentView : anchorView);
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

    private boolean hasUsableActiveProfile(XrayProxyProfileStore.Profile active) {
        if (active == null || TextUtils.isEmpty(active.configJson)) {
            return false;
        }
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            return false;
        }
        return XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort).valid;
    }

    /**
     * Starts core with active profile (app-only SOCKS, no VPN).
     * VPN mode is handled separately via {@link #startVpnWithChecks()}.
     */
    private void startAppOnlyCore() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyNoProfiles));
            listView.adapter.update(true);
            return;
        }

        if (!XrayAppProxyManager.isLibraryAvailable()) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            listView.adapter.update(true);
            return;
        }

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            listView.adapter.update(true);
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort);
        if (!result.valid) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(result.message);
            listView.adapter.update(true);
            return;
        }

        XrayVpnService.stopVpn(getContext());
        XrayAppProxyManager.start(runtimeConfig.configJson, (success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                NekoConfig.setXrayAppProxyEnabled(false);
                showError(message);
                listView.adapter.update(true);
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(true);
            XrayTelegramProxyBridge.enableLocalProxy(active.localPort, runtimeConfig.credentials);
            listView.adapter.update(true);
        }));
    }

    private void startVpnWithChecks() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null || TextUtils.isEmpty(active.configJson)) {
            NekoConfig.setXrayVpnMode(false);
            showError(LocaleController.getString(R.string.XrayProxyNoProfiles));
            listView.adapter.update(true);
            return;
        }
        if (!XrayAppProxyManager.isLibraryAvailable()) {
            NekoConfig.setXrayVpnMode(false);
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            listView.adapter.update(true);
            return;
        }
        XrayVpnService.startVpn(getContext());
        listView.adapter.update(true);
    }

    private void stopVpnAndCore() {
        XrayVpnService.stopVpn(getContext());
        if (!NekoConfig.xrayAppProxyEnabled) {
            return;
        }
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            listView.adapter.update(true);
        }));
    }

    private void restartProxyFlowWithActive() {
        if (NekoConfig.xrayVpnMode) {
            startVpnWithChecks();
        } else {
            XrayAppProxyManager.stop((stopOk, stopMsg) -> AndroidUtilities.runOnUIThread(() -> {
                XrayTelegramProxyBridge.disableLocalProxyIfOwned();
                if (!stopOk) {
                    return;
                }
                startAppOnlyCore();
            }));
        }
    }

    /**
     * Stops app-only core and disables local Telegram proxy if it was configured by this feature.
     */
    private void stopProxyFlow() {
        XrayVpnService.stopVpn(getContext());
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            listView.adapter.update(true);
        }));
    }

    private void runDelayCheck() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null) {
            showError(LocaleController.getString(R.string.XrayProxyNoProfiles));
            return;
        }

        if (!XrayAppProxyManager.isLibraryAvailable()) {
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            return;
        }

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }

        String checkUrl = TextUtils.isEmpty(active.checkUrl)
                ? XrayProxyProfileStore.DEFAULT_CHECK_URL
                : active.checkUrl.trim();
        lastDelayText = LocaleController.getString(R.string.XrayProxyDelayChecking);
        listView.adapter.update(true);

        XrayAppProxyManager.measureDelay(runtimeConfig.configJson, checkUrl, (success, delayMs, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                lastDelayText = LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxyDelayCheckFailed), message);
            } else {
                lastDelayText = LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxyDelayCheckResultInline),
                        String.valueOf(delayMs));
            }
            listView.adapter.update(true);
        }));
    }

    /**
     * Builds runtime config by injecting current local SOCKS credentials into selected profile.
     */
    private RuntimeConfig buildRuntimeConfig(XrayProxyProfileStore.Profile active) throws Exception {
        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        String configJson = XrayLocalSocksAuth.applyCredentials(active.configJson, active.localPort, credentials);
        return new RuntimeConfig(configJson, credentials);
    }

    private static final class RuntimeConfig {
        final String configJson;
        final XrayLocalSocksAuth.Credentials credentials;

        RuntimeConfig(String configJson, XrayLocalSocksAuth.Credentials credentials) {
            this.configJson = configJson;
            this.credentials = credentials;
        }
    }

    /**
     * Shows an informational Telegram-style alert listing share-URI protocols
     * supported by {@link XrayUriConfigFactory}.
     */
    private void showAboutDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        List<XrayUriConfigFactory.ProtocolInfo> protocols = XrayUriConfigFactory.getSupportedProtocols();

        StringBuilder message = new StringBuilder();
        message.append(LocaleController.getString(R.string.XrayProxyAboutSummary));
        if (!protocols.isEmpty()) {
            message.append("\n\n");
            message.append(LocaleController.getString(R.string.XrayProxyAboutSupportedProtocols));
            String rowTemplate = LocaleController.getString(R.string.XrayProxyAboutProtocolRow);
            for (XrayUriConfigFactory.ProtocolInfo info : protocols) {
                StringBuilder schemes = new StringBuilder();
                for (String scheme : info.uriSchemes) {
                    if (schemes.length() > 0) {
                        schemes.append(", ");
                    }
                    schemes.append(scheme).append("://");
                }
                message.append('\n');
                message.append(LocaleController.formatStringSimple(rowTemplate, info.displayName, schemes.toString()));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyAboutTitle));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    private static final int REQUEST_CODE_FILE_PICKER = 1199;

    private void showAddConfigOptions(View anchorView) {
        ItemOptions options = ItemOptions.makeOptions(this, anchorView == null ? fragmentView : anchorView);
        options.add(R.drawable.msg_edit, LocaleController.getString(R.string.XrayProxyAddEmptyProfile), () -> {
            XrayProxyProfileStore.Profile empty = XrayProxyProfileStore.createEmptyProfile();
            XrayProxyProfileStore.addProfile(empty, true);
            presentFragment(NekoXrayProxyProfileEditActivity.forProfile(empty.id));
        });
        options.add(R.drawable.msg_copy, LocaleController.getString(R.string.XrayProxyAddFromClipboard), this::addFromClipboard);
        options.add(R.drawable.msg_link2, LocaleController.getString(R.string.XrayProxyAddFromUri), this::showUriImportDialog);
        options.add(R.drawable.msg_log, LocaleController.getString(R.string.XrayProxyAddFromFile), this::openFilePicker);
        options.setMinWidth(200);
        options.show();
    }

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

    private static final int REQUEST_CODE_VPN_PERMISSION = 1200;

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_FILE_PICKER && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            importProfilesFromFile(data.getData());
        } else if (requestCode == REQUEST_CODE_VPN_PERMISSION) {
            if (resultCode == Activity.RESULT_OK) {
                NekoConfig.setXrayVpnMode(true);
                NekoConfig.setXrayAppProxyEnabled(true);
                startVpnWithChecks();
            } else {
                NekoConfig.setXrayVpnMode(false);
                showError(LocaleController.getString(R.string.XrayVpnPermissionDenied));
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
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

    private void processBulkImportInBackground(String rawText, AlertDialog progressDialog) {
        Utilities.globalQueue.postRunnable(() -> {
            XrayProxyProfileStore.Profile template = XrayProxyProfileStore.createEmptyProfile();
            int basePort = template.localPort;

            List<XrayUriConfigFactory.ParseResult> results = XrayUriConfigFactory.fromBulkText(rawText, basePort);
            if (results == null || results.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (progressDialog != null) {
                        progressDialog.dismiss();
                    }
                    showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
                });
                return;
            }

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
                            NekoXrayProxyHubActivity.this,
                            LocaleController.getString(R.string.XrayProxyTitle),
                            LocaleController.formatStringSimple(
                                    LocaleController.getString(R.string.XrayProxyImportSuccess), addedCount));
                } else {
                    showError(LocaleController.getString(R.string.XrayProxyImportNoValid));
                }
            });
        });
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

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyTitle);
    }

    @Override
    protected String getKey() {
        return "xrayHub";
    }
}
