package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileOutputStream;

import androidx.core.content.FileProvider;

import zxc.iconic.xenon.NekoConfig;

public class CloudSettingsHelper {

    private static final class InstanceHolder {
        private static final CloudSettingsHelper instance = new CloudSettingsHelper();
    }

    public static CloudSettingsHelper getInstance() {
        return InstanceHolder.instance;
    }

    public void showDialog(BaseFragment parentFragment) {
        if (parentFragment == null) {
            return;
        }

        Context context = parentFragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = parentFragment.getResourceProvider();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.CloudConfig));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.CloudConfigDesc)));
        builder.setTopImage(R.drawable.cloud, Theme.getColor(Theme.key_dialogTopBackground, resourcesProvider));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        ButtonWithCounterView saveButton = new ButtonWithCounterView(context, true, resourcesProvider).setRound();
        saveButton.setText(LocaleController.getString(R.string.SaveSettingsToFile), false);
        linearLayout.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 0));
        saveButton.setOnClickListener(view -> {
            try {
                String json = NekoConfig.exportConfigs();
                File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "xenon_settings_backup.json");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(json.getBytes("UTF-8"));
                }
                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/json");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(share, "Save Xenon Settings"));
            } catch (Exception e) {
                FileLog.e(e);
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, "Failed to save settings").show();
            }
        });

        ButtonWithCounterView restoreButton = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
        restoreButton.setText(LocaleController.getString(R.string.RestoreSettingsFromFile), false);
        linearLayout.addView(restoreButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 0));
        restoreButton.setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            parentFragment.startActivityForResult(intent, 2001);
        });

        builder.setView(linearLayout);
        parentFragment.showDialog(builder.create());
    }

    public void doAutoSync() {
    }
}
