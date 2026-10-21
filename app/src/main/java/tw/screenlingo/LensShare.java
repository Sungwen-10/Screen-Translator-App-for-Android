package tw.screenlingo;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/** Shares a temporary screenshot through Google Lens's exported image share entry. */
final class LensShare {
    static final String GOOGLE = "com.google.android.googlequicksearchbox";
    static Intent prepare(Context context, Bitmap bitmap) throws IOException {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png").setPackage(GOOGLE);
        ComponentName lens = null;
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled
                    && GOOGLE.equals(info.activityInfo.packageName)
                    && info.activityInfo.name.toLowerCase(Locale.ROOT).contains(".lens.")) {
                lens = new ComponentName(info.activityInfo.packageName, info.activityInfo.name); break;
            }
        }
        if (lens == null) throw new IOException("找不到 Google 智慧鏡頭的圖片入口。請安裝或更新 Google App，或在浮譯設定改選離線翻譯。");
        return imageIntent(context, bitmap).setComponent(lens);
    }
    static Intent imageIntent(Context context, Bitmap bitmap) throws IOException {
        File directory = new File(context.getCacheDir(), "lens");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("無法建立截圖暫存，請確認手機剩餘空間。");
        File[] previous = directory.listFiles();
        long expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        if (previous != null) for (File file : previous) if (file.isFile() && file.lastModified() < expiry) file.delete();
        File screenshot = new File(directory, UUID.randomUUID() + ".png");
        try (FileOutputStream stream = new FileOutputStream(screenshot)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("無法儲存截圖。");
        } catch (IOException e) { screenshot.delete(); throw e; }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", screenshot);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri("螢幕截圖", uri));
        return intent;
    }
}
