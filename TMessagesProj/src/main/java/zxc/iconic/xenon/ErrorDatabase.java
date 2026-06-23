package zxc.iconic.xenon;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.ui.Components.BulletinFactory;

public class ErrorDatabase {

    public static void showErrorToast(TLObject method, String text) {
        if (text.equals("FILE_REFERENCE_EXPIRED")) {
            return;
        }
        AndroidUtilities.runOnUIThread(() ->
            BulletinFactory.global().createSimpleBulletin(R.raw.error, text).show()
        );
    }
}
