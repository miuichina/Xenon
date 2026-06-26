package zxc.iconic.xenon.deleted;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class XenonDeletedMessagesController {

    private static XenonDeletedMessagesController instance;
    private File storageDir;

    public static XenonDeletedMessagesController getInstance() {
        if (instance == null) {
            instance = new XenonDeletedMessagesController();
        }
        return instance;
    }

    private XenonDeletedMessagesController() {
        storageDir = new File(ApplicationLoader.applicationContext.getDir("deleted_messages", Context.MODE_PRIVATE), "messages");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    public void onMessageDeleted(TLRPC.Message message, long dialogId, int accountId) {
        if (message == null) return;
        Utilities.globalQueue.postRunnable(() -> saveMessage(message, dialogId, accountId));
    }

    private void saveMessage(TLRPC.Message message, long dialogId, int accountId) {
        try {
            int size = message.getObjectSize();
            FileLog.d("XenonSave: msg " + message.id + " dialog " + dialogId + " size " + size);
            NativeByteBuffer buffer = new NativeByteBuffer(size);
            message.serializeToStream(buffer);
            buffer.rewind();

            byte[] data = new byte[buffer.remaining()];
            buffer.buffer.get(data);
            buffer.reuse();

            File dialogDir = new File(storageDir, dialogId + "_" + accountId);
            if (!dialogDir.exists()) dialogDir.mkdirs();

            File msgFile = new File(dialogDir, message.id + ".dat");
            try (FileOutputStream fos = new FileOutputStream(msgFile)) {
                fos.write(data);
            }
            FileLog.d("XenonSave: saved to " + msgFile.getAbsolutePath());
        } catch (Exception e) {
            FileLog.e("XenonSaveDeletedMessage err", e);
        }
    }

    public TLRPC.Message getMessage(long dialogId, int messageId, int accountId) {
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        File msgFile = new File(dialogDir, messageId + ".dat");
        if (!msgFile.exists()) return null;

        try {
            byte[] data = new byte[(int) msgFile.length()];
            try (FileInputStream fis = new FileInputStream(msgFile)) {
                fis.read(data);
            }

            NativeByteBuffer buffer = new NativeByteBuffer(data.length);
            buffer.buffer.put(java.nio.ByteBuffer.wrap(data));
            buffer.rewind();

            int constructor = buffer.readInt32(false);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(buffer, constructor, false);
            buffer.reuse();
            return message;
        } catch (Exception e) {
            FileLog.e("XenonLoadDeletedMessage", e);
            return null;
        }
    }

    public List<Integer> getExistingMessageIds(long dialogId, List<Integer> messageIds, int accountId) {
        List<Integer> existing = new ArrayList<>();
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return existing;

        for (int msgId : messageIds) {
            if (new File(dialogDir, msgId + ".dat").exists()) {
                existing.add(msgId);
            }
        }
        return existing;
    }

    public void deleteMessages(long dialogId, List<Integer> messageIds, int accountId) {
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return;

        for (int msgId : messageIds) {
            File f = new File(dialogDir, msgId + ".dat");
            if (f.exists()) f.delete();
        }
    }

    public void deleteCurrent(long dialogId) {
        File dialogDir = new File(storageDir, dialogId + "_0");
        if (dialogDir.exists()) {
            File[] files = dialogDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
    }

    public Set<Integer> getAllSavedMessageIds(long dialogId, int accountId) {
        Set<Integer> ids = new HashSet<>();
        File dialogDir = new File(storageDir, dialogId + "_" + accountId);
        if (!dialogDir.exists()) return ids;
        File[] files = dialogDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(".dat")) {
                    try {
                        ids.add(Integer.parseInt(name.substring(0, name.length() - 4)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return ids;
    }
}
