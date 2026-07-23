package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import zxc.iconic.xenon.proxy.XrayConfigForm;
import zxc.iconic.xenon.proxy.XrayConfigSummary;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

/**
 * Form-based editor for a single proxy profile.
 *
 * <p>Sections:
 * <ol>
 *   <li><b>Profile</b> — name, check URL
 *   <li><b>Server</b> — protocol, address, port
 *   <li><b>Server credentials</b> — protocol-specific (uuid/password/method)
 *   <li><b>Transport</b> — network selector + per-network fields
 *   <li><b>TLS / Reality</b> — security selector + sni/alpn/fingerprint/etc
 *   <li><b>Import</b> — replace config from URI/clipboard
 *   <li><b>Advanced</b> — local SOCKS port
 *   <li><b>Delete</b> — delete profile
 * </ol>
 *
 * <p>Form state is held in an in-memory {@link XrayConfigForm.FormModel} that is parsed from
 * {@code profile.configJson} on load and serialized back via {@link XrayConfigForm#apply} on save.
 * The rest of the Xray config structure (inbounds, direct/block outbounds, log/dns) is preserved.
 */
public class NekoXrayProxyProfileEditActivity extends BaseNekoSettingsActivity {

    private static final String ARG_PROFILE_ID = "xray_profile_id";

    // — Profile section —
    private final int nameRow = rowId++;
    private final int checkUrlRow = rowId++;
    // — Server section —
    private final int protocolRow = rowId++;
    private final int addressRow = rowId++;
    private final int portRow = rowId++;
    // — Credentials section (rows allocated dynamically by protocol) —
    private final int userIdRow = rowId++;
    private final int flowRow = rowId++;
    private final int vmessSecurityRow = rowId++;
    private final int vmessAlterIdRow = rowId++;
    private final int passwordRow = rowId++;
    private final int ssMethodRow = rowId++;
    private final int socksUserRow = rowId++;
    private final int socksPassRow = rowId++;
    // — Transport section —
    private final int networkRow = rowId++;
    private final int tcpHeaderTypeRow = rowId++;
    private final int tcpHostRow = rowId++;
    private final int tcpPathRow = rowId++;
    private final int wsPathRow = rowId++;
    private final int wsHostRow = rowId++;
    private final int h2HostRow = rowId++;
    private final int h2PathRow = rowId++;
    private final int grpcServiceNameRow = rowId++;
    private final int grpcModeRow = rowId++;
    private final int hysteriaObfsTypeRow = rowId++;
    private final int hysteriaObfsPasswordRow = rowId++;
    // — TLS / Reality section —
    private final int securityRow = rowId++;
    private final int sniRow = rowId++;
    private final int alpnRow = rowId++;
    private final int fingerprintRow = rowId++;
    private final int allowInsecureRow = rowId++;
    private final int realityPublicKeyRow = rowId++;
    private final int realityShortIdRow = rowId++;
    private final int realitySpiderXRow = rowId++;
    // — Import section —
    private final int replaceClipboardRow = rowId++;
    private final int replaceUriRow = rowId++;
    // — Advanced section —
    private final int localPortRow = rowId++;
    // — Danger —
    private final int deleteRow = rowId++;

    private String profileId;
    private XrayProxyProfileStore.Profile profile;
    private XrayConfigForm.FormModel form;

    public NekoXrayProxyProfileEditActivity() {
        super();
    }

    private NekoXrayProxyProfileEditActivity(Bundle args) {
        super(args);
    }

    public static NekoXrayProxyProfileEditActivity forProfile(String id) {
        Bundle args = new Bundle();
        args.putString(ARG_PROFILE_ID, id);
        return new NekoXrayProxyProfileEditActivity(args);
    }

    @Override
    public boolean onFragmentCreate() {
        Bundle args = getArguments();
        profileId = args == null ? "" : args.getString(ARG_PROFILE_ID, "");
        reloadProfile();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadProfile();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void reloadProfile() {
        if (TextUtils.isEmpty(profileId)) {
            profile = null;
            form = null;
            return;
        }
        ArrayList<XrayProxyProfileStore.Profile> all = XrayProxyProfileStore.getProfiles();
        profile = null;
        for (int i = 0; i < all.size(); i++) {
            if (TextUtils.equals(all.get(i).id, profileId)) {
                profile = all.get(i);
                break;
            }
        }
        if (profile == null) {
            form = null;
            return;
        }
        form = XrayConfigForm.extract(profile.configJson);
        if (form == null) {
            // Either empty config or unparseable — start fresh blank model so the form can drive it.
            form = new XrayConfigForm.FormModel();
            form.protocol = "vless";
            form.network = "tcp";
            form.security = "tls";
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (profile == null || form == null) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfileNotFound)));
            return;
        }

        // — Profile section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyProfileSection)));
        items.add(UItem.asButtonSubtext(nameRow, R.drawable.msg_edit,
                LocaleController.getString(R.string.XrayProxyProfileName), profile.name).slug("xrayProfileName"));
        items.add(UItem.asButtonSubtext(checkUrlRow, R.drawable.msg_link,
                LocaleController.getString(R.string.XrayProxyCheckUrl), profile.checkUrl).slug("xrayProfileCheckUrl"));

        // — Server section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyServerSection)));
        items.add(UItem.asButtonSubtext(protocolRow, R.drawable.msg_info,
                LocaleController.getString(R.string.XrayProxyProtocol), form.protocol).slug("xrayProfileProtocol"));
        items.add(UItem.asButtonSubtext(addressRow, R.drawable.msg_location,
                LocaleController.getString(R.string.XrayProxyServerAddress), form.address).slug("xrayProfileAddress"));
        items.add(UItem.asButtonSubtext(portRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyServerPort), form.port).slug("xrayProfilePort"));

        // — Credentials section (per-protocol) —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyCredentialsSection)));
        String protocol = form.protocol;
        if ("vless".equals(protocol)) {
            items.add(UItem.asButtonSubtext(userIdRow, R.drawable.msg_info,
                    LocaleController.getString(R.string.XrayProxyUserId), form.userId).slug("xrayProfileUserId"));
            items.add(UItem.asButtonSubtext(flowRow, R.drawable.msg_speed,
                    LocaleController.getString(R.string.XrayProxyFlow), form.flow).slug("xrayProfileFlow"));
        } else if ("vmess".equals(protocol)) {
            items.add(UItem.asButtonSubtext(userIdRow, R.drawable.msg_info,
                    LocaleController.getString(R.string.XrayProxyUserId), form.userId).slug("xrayProfileUserId"));
            items.add(UItem.asButtonSubtext(vmessSecurityRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyVmessSecurity), form.vmessSecurity).slug("xrayProfileVmessSecurity"));
            items.add(UItem.asButtonSubtext(vmessAlterIdRow, R.drawable.msg_autodelete,
                    LocaleController.getString(R.string.XrayProxyVmessAlterId), form.vmessAlterId).slug("xrayProfileVmessAlterId"));
        } else if ("trojan".equals(protocol) || "shadowsocks".equals(protocol) || "hysteria".equals(protocol) || "socks".equals(protocol) || "http".equals(protocol)) {
            items.add(UItem.asButtonSubtext(passwordRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyPassword), form.password).slug("xrayProfilePassword"));
            if ("shadowsocks".equals(protocol)) {
                items.add(UItem.asButtonSubtext(ssMethodRow, R.drawable.msg_secret,
                        LocaleController.getString(R.string.XrayProxySsMethod), form.ssMethod).slug("xrayProfileSsMethod"));
            }
            if ("socks".equals(protocol) || "http".equals(protocol)) {
                items.add(UItem.asButtonSubtext(socksUserRow, R.drawable.msg_contact,
                        LocaleController.getString(R.string.XrayProxySocksUser), form.socksUser).slug("xrayProfileSocksUser"));
                items.add(UItem.asButtonSubtext(socksPassRow, R.drawable.msg_secret,
                        LocaleController.getString(R.string.XrayProxySocksPass), form.socksPass).slug("xrayProfileSocksPass"));
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyCredentialsHint)));

        // — Transport section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyTransportSection)));
        items.add(UItem.asButtonSubtext(networkRow, R.drawable.msg_share,
                LocaleController.getString(R.string.XrayProxyNetwork), form.network).slug("xrayProfileNetwork"));
        String network = form.network;
        if ("tcp".equals(network)) {
            items.add(UItem.asButtonSubtext(tcpHeaderTypeRow, R.drawable.msg_copy,
                    LocaleController.getString(R.string.XrayProxyTcpHeaderType), form.tcpHeaderType).slug("xrayProfileTcpHeaderType"));
            if ("http".equalsIgnoreCase(form.tcpHeaderType)) {
                items.add(UItem.asButtonSubtext(tcpHostRow, R.drawable.msg_link,
                        LocaleController.getString(R.string.XrayProxyTcpHost), form.tcpHost).slug("xrayProfileTcpHost"));
                items.add(UItem.asButtonSubtext(tcpPathRow, R.drawable.msg_link,
                        LocaleController.getString(R.string.XrayProxyTcpPath), form.tcpPath).slug("xrayProfileTcpPath"));
            }
        } else if ("ws".equals(network)) {
            items.add(UItem.asButtonSubtext(wsPathRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyWsPath), form.wsPath).slug("xrayProfileWsPath"));
            items.add(UItem.asButtonSubtext(wsHostRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyWsHost), form.wsHost).slug("xrayProfileWsHost"));
        } else if ("h2".equals(network)) {
            items.add(UItem.asButtonSubtext(h2HostRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyH2Host), form.h2Host).slug("xrayProfileH2Host"));
            items.add(UItem.asButtonSubtext(h2PathRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyH2Path), form.h2Path).slug("xrayProfileH2Path"));
        } else if ("grpc".equals(network)) {
            items.add(UItem.asButtonSubtext(grpcServiceNameRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyGrpcServiceName), form.grpcServiceName).slug("xrayProfileGrpcServiceName"));
            items.add(UItem.asButtonSubtext(grpcModeRow, R.drawable.msg_settings,
                    LocaleController.getString(R.string.XrayProxyGrpcMode), form.grpcMode).slug("xrayProfileGrpcMode"));
        } else if ("hysteria".equals(network)) {
            items.add(UItem.asButtonSubtext(hysteriaObfsTypeRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyHysteriaObfsType), form.hysteriaObfsType).slug("xrayProfileHysteriaObfsType"));
            items.add(UItem.asButtonSubtext(hysteriaObfsPasswordRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyHysteriaObfsPassword), form.hysteriaObfsPassword).slug("xrayProfileHysteriaObfsPassword"));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyTransportHint)));

        // — TLS / Reality section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxySecuritySection)));
        items.add(UItem.asButtonSubtext(securityRow, R.drawable.msg_secret,
                LocaleController.getString(R.string.XrayProxySecurity), form.security).slug("xrayProfileSecurity"));
        if ("tls".equals(form.security)) {
            items.add(UItem.asButtonSubtext(sniRow, R.drawable.msg_location,
                    LocaleController.getString(R.string.XrayProxySni), form.sni).slug("xrayProfileSni"));
            items.add(UItem.asButtonSubtext(alpnRow, R.drawable.msg_link,
                    LocaleController.getString(R.string.XrayProxyAlpn), form.alpn).slug("xrayProfileAlpn"));
            items.add(UItem.asButtonSubtext(fingerprintRow, R.drawable.msg_info,
                    LocaleController.getString(R.string.XrayProxyFingerprint), form.fingerprint).slug("xrayProfileFingerprint"));
            items.add(asCheckInline(allowInsecureRow, R.string.XrayProxyAllowInsecure, form.allowInsecure).slug("xrayProfileAllowInsecure"));
        } else if ("reality".equals(form.security)) {
            items.add(UItem.asButtonSubtext(sniRow, R.drawable.msg_location,
                    LocaleController.getString(R.string.XrayProxySni), form.sni).slug("xrayProfileSni"));
            items.add(UItem.asButtonSubtext(fingerprintRow, R.drawable.msg_info,
                    LocaleController.getString(R.string.XrayProxyFingerprint), form.fingerprint).slug("xrayProfileFingerprint"));
            items.add(UItem.asButtonSubtext(realityPublicKeyRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyRealityPublicKey), form.realityPublicKey).slug("xrayProfileRealityPublicKey"));
            items.add(UItem.asButtonSubtext(realityShortIdRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyRealityShortId), form.realityShortId).slug("xrayProfileRealityShortId"));
            items.add(UItem.asButtonSubtext(realitySpiderXRow, R.drawable.msg_secret,
                    LocaleController.getString(R.string.XrayProxyRealitySpiderX), form.realitySpiderX).slug("xrayProfileRealitySpiderX"));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxySecurityHint)));

        // — Import section (URI still supported as alternative path) —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyImportSection)));
        items.add(UItem.asButton(replaceClipboardRow, R.drawable.msg_copy,
                LocaleController.getString(R.string.XrayProxyReplaceFromClipboard)).accent().slug("xrayProfileReplaceClipboard"));
        items.add(UItem.asButton(replaceUriRow, R.drawable.msg_link2,
                LocaleController.getString(R.string.XrayProxyReplaceFromUri)).slug("xrayProfileReplaceUri"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyImportHint)));

        // — Advanced section —
        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyAdvancedSection)));
        items.add(UItem.asButtonSubtext(localPortRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyLocalPort), String.valueOf(profile.localPort)).slug("xrayProfilePort"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyLocalPortHint)));

        // — Danger —
        items.add(UItem.asHeader(""));
        items.add(UItem.asButton(deleteRow, R.drawable.msg_delete,
                LocaleController.getString(R.string.XrayProxyDeleteProfile)).red().slug("xrayProfileDelete"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyDeleteHint)));
    }

    /** asCheck inline with explicit value setter via UItem. */
    private static UItem asCheckInline(int id, int stringRes, boolean value) {
        return UItem.asCheck(id, LocaleController.getString(stringRes)).setChecked(value);
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (profile == null || form == null) {
            return;
        }
        int id = item.id;
        if (id == nameRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyProfileName), profile.name,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    v -> {
                        if (TextUtils.isEmpty(v.trim())) {
                            showError(LocaleController.getString(R.string.XrayProxyProfileNameEmpty));
                            return false;
                        }
                        profile.name = v.trim();
                        saveProfileNoForm();
                        return true;
                    });
            return;
        }
        if (id == checkUrlRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyCheckUrl), profile.checkUrl,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    v -> {
                        if (TextUtils.isEmpty(v.trim())) {
                            showError(LocaleController.getString(R.string.XrayProxyErrorUrlEmpty));
                            return false;
                        }
                        profile.checkUrl = v.trim();
                        saveProfileNoForm();
                        return true;
                    });
            return;
        }
        if (id == localPortRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyLocalPort), String.valueOf(profile.localPort),
                    InputType.TYPE_CLASS_NUMBER,
                    v -> {
                        try {
                            int parsed = Integer.parseInt(v.trim());
                            if (parsed < 1024 || parsed > 65535) {
                                showError(LocaleController.getString(R.string.XrayProxyErrorPortRange));
                                return false;
                            }
                            profile.localPort = parsed;
                            saveProfileNoForm();
                            return true;
                        } catch (Throwable t) {
                            showError(LocaleController.getString(R.string.XrayProxyErrorInvalidPort));
                            return false;
                        }
                    });
            return;
        }
        if (id == protocolRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyProtocol),
                    XrayConfigForm.PROTOCOLS, form.protocol, value -> {
                        form.protocol = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == addressRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyServerAddress), form.address,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    v -> {
                        form.address = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == portRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyServerPort), form.port,
                    InputType.TYPE_CLASS_NUMBER,
                    v -> {
                        try {
                            Integer.parseInt(v.trim());
                            form.port = v.trim();
                            applyFormAndSave();
                            return true;
                        } catch (Throwable t) {
                            showError(LocaleController.getString(R.string.XrayProxyErrorInvalidPort));
                            return false;
                        }
                    });
            return;
        }
        if (id == userIdRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyUserId), form.userId,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    v -> {
                        form.userId = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == flowRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyFlow),
                    Arrays.asList("", "xtls-rprx-vision"), form.flow,
                    value -> {
                        form.flow = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == vmessSecurityRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyVmessSecurity),
                    XrayConfigForm.VMESS_SECURITY, form.vmessSecurity,
                    value -> {
                        form.vmessSecurity = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == vmessAlterIdRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyVmessAlterId), form.vmessAlterId,
                    InputType.TYPE_CLASS_NUMBER,
                    v -> {
                        form.vmessAlterId = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == passwordRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyPassword), form.password,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    v -> {
                        form.password = v;
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == ssMethodRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxySsMethod),
                    XrayConfigForm.SS_METHODS, form.ssMethod,
                    value -> {
                        form.ssMethod = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == socksUserRow) {
            showTextField(LocaleController.getString(R.string.XrayProxySocksUser), form.socksUser,
                    InputType.TYPE_CLASS_TEXT,
                    v -> {
                        form.socksUser = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == socksPassRow) {
            showTextField(LocaleController.getString(R.string.XrayProxySocksPass), form.socksPass,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    v -> {
                        form.socksPass = v;
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == networkRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyNetwork),
                    XrayConfigForm.NETWORKS, form.network, value -> {
                        form.network = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == tcpHeaderTypeRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyTcpHeaderType),
                    Arrays.asList("none", "http"), form.tcpHeaderType, value -> {
                        form.tcpHeaderType = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == tcpHostRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyTcpHost), form.tcpHost,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.tcpHost = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == tcpPathRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyTcpPath), form.tcpPath,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.tcpPath = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == wsPathRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyWsPath), form.wsPath,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.wsPath = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == wsHostRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyWsHost), form.wsHost,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.wsHost = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == h2HostRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyH2Host), form.h2Host,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.h2Host = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == h2PathRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyH2Path), form.h2Path,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.h2Path = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == grpcServiceNameRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyGrpcServiceName), form.grpcServiceName,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.grpcServiceName = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == grpcModeRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyGrpcMode),
                    XrayConfigForm.GRPC_MODES, form.grpcMode, value -> {
                        form.grpcMode = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == hysteriaObfsTypeRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyHysteriaObfsType),
                    Arrays.asList("", "salamander"), form.hysteriaObfsType, value -> {
                        form.hysteriaObfsType = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == hysteriaObfsPasswordRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyHysteriaObfsPassword), form.hysteriaObfsPassword,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    v -> {
                        form.hysteriaObfsPassword = v;
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == securityRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxySecurity),
                    XrayConfigForm.SECURITIES, form.security, value -> {
                        form.security = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == sniRow) {
            showTextField(LocaleController.getString(R.string.XrayProxySni), form.sni,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.sni = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == alpnRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyAlpn), form.alpn,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.alpn = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == fingerprintRow) {
            showChoiceMenu(view, LocaleController.getString(R.string.XrayProxyFingerprint),
                    XrayConfigForm.TLS_FINGERPRINTS, form.fingerprint, value -> {
                        form.fingerprint = value;
                        applyFormAndSave();
                    });
            return;
        }
        if (id == allowInsecureRow) {
            form.allowInsecure = !form.allowInsecure;
            if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(form.allowInsecure);
            }
            applyFormAndSave();
            return;
        }
        if (id == realityPublicKeyRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyRealityPublicKey), form.realityPublicKey,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.realityPublicKey = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == realityShortIdRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyRealityShortId), form.realityShortId,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.realityShortId = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == realitySpiderXRow) {
            showTextField(LocaleController.getString(R.string.XrayProxyRealitySpiderX), form.realitySpiderX,
                    InputType.TYPE_CLASS_TEXT, v -> {
                        form.realitySpiderX = v.trim();
                        applyFormAndSave();
                        return true;
                    });
            return;
        }
        if (id == replaceClipboardRow) {
            importFromClipboard();
            return;
        }
        if (id == replaceUriRow) {
            showUriImportDialog();
            return;
        }
        if (id == deleteRow) {
            boolean deleted = XrayProxyProfileStore.deleteProfile(profile.id);
            if (!deleted) {
                showError(LocaleController.getString(R.string.XrayProxyDeleteLastProfileError));
                return;
            }
            finishFragment();
        }
    }

    /**
     * Serializes the current form back into profile.configJson and persists.
     */
    private void applyFormAndSave() {
        try {
            String json = XrayConfigForm.apply(form, profile.configJson);
            XrayConfigValidator.ValidationResult v = XrayConfigValidator.validate(json, profile.localPort);
            if (!v.valid) {
                showError(v.message);
                return;
            }
            profile.configJson = json;
            saveProfileNoForm();
        } catch (Throwable t) {
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
        }
    }

    private void saveProfileNoForm() {
        if (profile == null) {
            return;
        }
        XrayProxyProfileStore.updateProfile(profile);
        reloadProfile();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void showTextField(String title, String value, int inputType, ValueCommit callback) {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setText(value);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String newValue = editText.getText() == null ? "" : editText.getText().toString();
                if (callback.commit(newValue)) {
                    dialog.dismiss();
                }
            });
        }
    }

    /**
     * Shows a vertical choice popup (top-aligned to the tapped view) using {@link ItemOptions}.
     */
    private void showChoiceMenu(View anchor, String title, List<String> choices, String current, ChoiceCallback callback) {
        ItemOptions options = ItemOptions.makeOptions(this, anchor == null ? fragmentView : anchor);
        if (!TextUtils.isEmpty(title)) {
            options.setMinWidth(190);
        }
        for (String c : choices) {
            String label = TextUtils.isEmpty(c) ? LocaleController.getString(R.string.None) : c;
            boolean isCurrent = TextUtils.equals(c, current);
            options.addChecked(isCurrent, label, () -> callback.choice(c));
        }
        options.show();
    }

    private void showUriImportDialog() {
        Activity context = getParentActivity();
        if (context == null || profile == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyImportUri));

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
                XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromLink(raw, profile.localPort);
                if (!result.valid) {
                    showError(result.message);
                    return;
                }
                applyImportedConfig(result);
                dialog.dismiss();
            });
        }
    }

    private void importFromClipboard() {
        if (profile == null) {
            return;
        }
        String clipText = readClipboardText();
        XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromClipboardText(clipText, profile.localPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }
        applyImportedConfig(result);
    }

    private void applyImportedConfig(XrayUriConfigFactory.ParseResult result) {
        if (profile == null || result.config == null) {
            showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
            return;
        }

        String json;
        try {
            json = result.config.toString(2);
        } catch (Throwable ignore) {
            json = result.config.toString();
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(json, profile.localPort);
        if (!validation.valid) {
            showError(validation.message);
            return;
        }

        profile.configJson = json;
        if (TextUtils.isEmpty(profile.name) || "Proxy".equals(profile.name)) {
            profile.name = TextUtils.isEmpty(result.nodeName)
                    ? (result.protocol + " " + result.host + ":" + result.port)
                    : result.nodeName;
        }
        saveProfileNoForm();
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
        return profile == null ? LocaleController.getString(R.string.XrayProxyProfile) : profile.name;
    }

    @Override
    protected String getKey() {
        return "xrayProfileEdit";
    }

    private interface ValueCommit {
        boolean commit(String value);
    }

    private interface ChoiceCallback {
        void choice(String value);
    }
}
