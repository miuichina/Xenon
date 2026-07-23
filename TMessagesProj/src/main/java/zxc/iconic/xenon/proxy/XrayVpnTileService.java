package zxc.iconic.xenon.proxy;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import zxc.iconic.xenon.NekoConfig;

public class XrayVpnTileService extends TileService {

    private static boolean pendingPermission = false;

    @Override
    public void onTileAdded() {
        updateTileState();
    }

    @Override
    public void onStartListening() {
        if (pendingPermission && !NekoConfig.xrayVpnMode) {
            pendingPermission = false;
            Intent prepareIntent = VpnService.prepare(this);
            if (prepareIntent == null) {
                startVpn();
            }
        }
        updateTileState();
    }

    @Override
    public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::toggleVpn);
        } else {
            toggleVpn();
        }
    }

    private void toggleVpn() {
        if (NekoConfig.xrayVpnMode) {
            pendingPermission = false;
            NekoConfig.setXrayVpnMode(false);
            XrayVpnService.stopVpn(this);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent prepareIntent = VpnService.prepare(this);
                if (prepareIntent != null) {
                    pendingPermission = true;
                    startActivityAndCollapse(prepareIntent);
                    return;
                }
            }
            startVpn();
        }
        updateTileState();
    }

    private void startVpn() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null || active.configJson == null || active.configJson.isEmpty()) {
            return;
        }
        pendingPermission = false;
        NekoConfig.setXrayVpnMode(true);
        NekoConfig.setXrayAppProxyEnabled(true);
        XrayVpnService.startVpn(this);
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        if (NekoConfig.xrayVpnMode) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(LocaleController.getString(R.string.XrayProxyTitle));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(LocaleController.getString(R.string.XrayProxyTitle));
        }
        tile.updateTile();
    }
}
