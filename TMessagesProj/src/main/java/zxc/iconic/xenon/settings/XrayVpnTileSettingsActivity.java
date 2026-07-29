package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.ui.LaunchActivity;

public class XrayVpnTileSettingsActivity extends Activity {

    public static final String ACTION_OPEN_PROXY_HUB = "zxc.iconic.xenon.OPEN_PROXY_HUB";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setAction(ACTION_OPEN_PROXY_HUB);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }
}