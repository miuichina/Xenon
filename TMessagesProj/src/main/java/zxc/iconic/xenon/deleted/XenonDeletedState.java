package zxc.iconic.xenon.deleted;

import android.util.LongSparseArray;

import java.util.ArrayList;

public class XenonDeletedState {
    private static final LongSparseArray<ArrayList<Integer>> deletePermitted = new LongSparseArray<>();

    public static void permitDeleteMessage(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) {
            list = new ArrayList<>();
            deletePermitted.put(dialogId, list);
        }
        list.add(messageId);
    }

    public static boolean isDeletePermitted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) return false;
        return list.contains(messageId);
    }

    public static void messageDeleted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) return;
        list.remove((Object) messageId);
    }
}
