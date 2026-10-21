package tw.screenlingo;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;

/** Optional OCR data, installed atomically only after size and SHA-256 validation. */
final class OcrModels {
    static final long THAI_BYTES = 1072600;
    static final String THAI_SHA256 = "294227cc2d1292b0acb28d61d4115c88252b96d466ca90b417cf4cf0c67bf07c";
    static final String THAI_URL = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/tha.traineddata";
    private final Context context;
    private final Prefs prefs;
    record State(boolean ready, boolean downloading, String status) { }
    OcrModels(Context context) { this.context = context.getApplicationContext(); prefs = new Prefs(this.context); }
    File root() { return new File(context.getFilesDir(), "ocr"); }
    File thaiFile() { return new File(root(), "tessdata/tha.traineddata"); }
    boolean thaiReady() { return thaiFile().isFile() && thaiFile().length() == THAI_BYTES; }
    private DownloadManager manager() { return context.getSystemService(DownloadManager.class); }

    State state() {
        synchronized (OcrModels.class) {
            if (thaiReady()) return new State(true, false, "已下載 · 可離線辨識");
            long id = prefs.data.getLong("thaiDownload", -1);
            if (id == -1) return new State(false, false, prefs.data.getString("thaiDownloadError", "尚未下載 · 約 1.1 MB"));
            try (Cursor c = manager().query(new DownloadManager.Query().setFilterById(id))) {
                if (c == null || !c.moveToFirst()) return failed("下載已中斷，請重試");
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    try (InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(manager().openDownloadedFile(id))) {
                        installThai(input);
                    }
                    manager().remove(id); prefs.data.edit().remove("thaiDownload").remove("thaiDownloadError").apply();
                    return new State(true, false, "已下載 · 可離線辨識");
                }
                if (status == DownloadManager.STATUS_FAILED) { manager().remove(id); return failed("下載失敗，請確認網路後重試"); }
                long bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                String message = status == DownloadManager.STATUS_PAUSED ? (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "等待 Wi-Fi…" : "等待網路／稍後重試…")
                    : "下載中… " + Math.min(100, bytes * 100 / THAI_BYTES) + "%";
                return new State(false, true, message);
            } catch (Exception e) { manager().remove(id); return failed("模型驗證或安裝失敗，請重新下載"); }
        }
    }
    private State failed(String message) {
        prefs.data.edit().remove("thaiDownload").putString("thaiDownloadError", message).apply();
        return new State(false, false, message);
    }
    void downloadThai(boolean wifiOnly) {
        synchronized (OcrModels.class) {
            if (thaiReady() || prefs.data.contains("thaiDownload")) return;
            // Only the app's own temporary model file is replaced.
            File pending = new File(context.getExternalFilesDir("ocr-downloads"), "tha.pending");
            if (pending.exists() && !pending.delete()) throw new IllegalStateException("無法清除上次下載，請稍後重試");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(THAI_URL))
                .setTitle("浮譯：泰文文字辨識模型")
                .setDescription("下載後會驗證模型，可離線辨識泰文")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, "ocr-downloads", "tha.pending");
            if (wifiOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            long id = manager().enqueue(request);
            prefs.data.edit().putLong("thaiDownload", id).remove("thaiDownloadError").apply();
        }
    }
    void removeThai() throws IOException {
        synchronized (OcrModels.class) {
            if (OverlayService.running) throw new IOException("請先關閉懸浮翻譯再刪除模型");
            long id = prefs.data.getLong("thaiDownload", -1);
            if (id != -1) manager().remove(id);
            prefs.data.edit().remove("thaiDownload").remove("thaiDownloadError").apply();
            if (thaiFile().exists() && !thaiFile().delete()) throw new IOException("刪除失敗，請稍後重試");
        }
    }
    void installThai(InputStream input) throws Exception {
        synchronized (OcrModels.class) {
            File destination = thaiFile(), directory = destination.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("無法建立模型資料夾");
            File temporary = new File(directory, "tha.installing");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
                try (OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        size += count;
                        if (size > THAI_BYTES) throw new IOException("模型檔案大小不符");
                        digest.update(buffer, 0, count); output.write(buffer, 0, count);
                    }
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                if (size != THAI_BYTES || !THAI_SHA256.contentEquals(hash)) throw new IOException("模型校驗失敗");
                if (!temporary.renameTo(destination)) throw new IOException("模型安裝失敗");
            } finally { if (temporary.exists()) temporary.delete(); }
        }
    }
}
