package tw.screenlingo;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.*;
import com.google.mlkit.nl.translate.*;
import java.util.*;
import java.util.concurrent.*;

/** Process-wide batch survives rotation; SDK downloads may continue after leaving the screen. */
final class TranslationModelJobs {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    static volatile boolean busy;
    static volatile String message = "勾選需要的語言後按下載。英文已內建。";
    static synchronized void download(List<String> languages, boolean wifiOnly) {
        if (busy || languages.isEmpty()) return;
        busy = true; message = "正在準備下載…";
        List<String> requested = List.copyOf(languages);
        worker.execute(() -> {
            List<String> failed = new ArrayList<>();
            RemoteModelManager manager = RemoteModelManager.getInstance();
            DownloadConditions.Builder conditions = new DownloadConditions.Builder();
            if (wifiOnly) conditions.requireWifi();
            try {
                for (int i = 0; i < requested.size(); i++) {
                    String language = requested.get(i);
                    message = "準備 " + (i + 1) + " / " + requested.size() + "：" + Languages.label(language)
                        + (wifiOnly ? "（需 Wi-Fi）" : "");
                    try {
                        TranslateRemoteModel model = new TranslateRemoteModel.Builder(language).build();
                        if (!Tasks.await(manager.isModelDownloaded(model), 15, TimeUnit.SECONDS))
                            Tasks.await(manager.download(model, conditions.build()), 120, TimeUnit.SECONDS);
                    } catch (Exception e) { failed.add(Languages.label(language)); }
                }
                message = failed.isEmpty() ? "選取的翻譯模型已備妥，可離線使用。"
                    : "以下模型尚未備妥，請確認網路後重試：" + String.join("、", failed);
            } finally { busy = false; }
        });
    }
}
