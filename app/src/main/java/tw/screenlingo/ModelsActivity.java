package tw.screenlingo;

import android.app.*;
import android.os.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import java.util.*;
import java.util.concurrent.*;

public final class ModelsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, CheckBox> choices = new LinkedHashMap<>();
    private final Map<String, Button> deletes = new HashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Runnable poll = new Runnable() { public void run() { refresh(); main.postDelayed(this, 1500); } };
    private OcrModels ocr;
    private TextView thaiStatus, batchStatus;
    private Button thaiDownload, thaiDelete, batchDownload;
    private volatile boolean checking;
    private boolean visible;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setDecorFitsSystemWindows(false); ocr = new OcrModels(this);
        if (state != null) selected.addAll(state.getStringArrayList("selected") == null ? List.of() : state.getStringArrayList("selected"));
        ScrollView scroll = new ScrollView(this); LinearLayout root = Ui.column(this, 20); root.setBackgroundColor(Ui.BG);
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL);
        shell.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        root.addView(Ui.button(this, "返回設定", false, v -> finish()));
        root.addView(Ui.title(this, "語言模型", 28));
        root.addView(Ui.text(this, "先辨識畫面上的字，再翻成目標語言。兩種模型用途不同。", 14, Ui.MUTED));
        LinearLayout recognition = card(root); recognition.addView(Ui.title(this, "文字辨識模型", 21));
        recognition.addView(Ui.text(this, "已內建：拉丁字母（包含法文）、中文、日文、韓文、天城體。隨 App 安裝，不需另行下載。", 14, Ui.INK));
        Ui.gap(recognition, 16); recognition.addView(Ui.title(this, "泰文文字辨識", 18));
        thaiStatus = Ui.text(this, "正在檢查…", 14, Ui.MUTED); recognition.addView(thaiStatus);
        thaiDownload = Ui.button(this, "下載泰文辨識模型（約 1.1 MB）", false, v -> {
            try { ocr.downloadThai(new Prefs(this).wifiOnly()); refresh(); }
            catch (Exception e) { toast("無法開始下載，請確認網路及儲存空間後重試。"); }
        }); recognition.addView(thaiDownload);
        thaiDelete = Ui.button(this, "刪除／取消下載泰文辨識模型", false, v -> {
            if (!canDelete()) return;
            new AlertDialog.Builder(this).setMessage("刪除泰文辨識模型或取消下載？之後可重新下載。")
                .setNegativeButton("取消", null).setPositiveButton("刪除", (d, w) -> {
                    try { ocr.removeThai(); refresh(); } catch (Exception e) { toast(e.getMessage()); }
                }).show();
        }); recognition.addView(thaiDelete);
        recognition.addView(Ui.text(this, "泰文辨識下載後，自動模式也會使用。只讀泰文時，可在設定指定「泰文」以減少等待。", 13, Ui.MUTED));

        LinearLayout translation = card(root); translation.addView(Ui.title(this, "翻譯模型", 21));
        translation.addView(Ui.text(this, "可多選下載。翻譯模型不會增加文字辨識種類；泰文畫面另需上方的辨識模型。繁體與簡體中文共用中文翻譯模型。", 14, Ui.MUTED));
        Switch wifi = new Switch(this); wifi.setText("只透過 Wi-Fi 下載"); wifi.setChecked(new Prefs(this).wifiOnly());
        wifi.setOnCheckedChangeListener((b, value) -> new Prefs(this).data.edit().putBoolean("wifi", value).apply());
        translation.addView(wifi);
        batchStatus = Ui.text(this, "正在檢查…", 14, Ui.MUTED);
        batchDownload = Ui.button(this, "下載選取的翻譯模型", true, v -> {
            if (selected.isEmpty()) { toast("請先勾選語言。"); return; }
            TranslationModelJobs.download(new ArrayList<>(selected), new Prefs(this).wifiOnly()); refresh();
        });
        translation.addView(Ui.text(this, "英文：已內建。其他模型多為數十 MB，實際大小依語言而異。下載期間請保持網路連線；中斷後可重試。刪除模型前請先關閉懸浮翻譯。", 12, Ui.MUTED));
        LinkedHashSet<String> tags = new LinkedHashSet<>(List.of("zh", "fr", "th", "ja", "ko"));
        for (Languages.Entry entry : Languages.all(false)) tags.add(Languages.modelTag(entry.tag()));
        tags.remove("en"); tags.remove(null);
        for (String tag : tags) {
            LinearLayout row = new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            CheckBox choice = new CheckBox(this); choice.setText(label(tag) + " · 檢查中"); choice.setTextSize(14);
            choice.setChecked(selected.contains(tag)); choice.setMinHeight(Ui.dp(this, 56));
            choice.setOnCheckedChangeListener((b, value) -> { if (value) selected.add(tag); else selected.remove(tag); });
            row.addView(choice, new LinearLayout.LayoutParams(0, -2, 1)); choices.put(tag, choice);
            Button remove = Ui.button(this, "刪除", false, v -> deleteTranslation(tag));
            remove.setEnabled(false); row.addView(remove); deletes.put(tag, remove); translation.addView(row);
        }
        root.addView(Ui.text(this, "模型來源：辨識使用 Google ML Kit、Tesseract／tessdata_fast；翻譯使用 Google ML Kit。泰文模型從官方 GitHub 下載並驗證校驗碼。", 12, Ui.MUTED));
        scroll.addView(root); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = Ui.column(this, 14); footer.setBackgroundColor(android.graphics.Color.WHITE);
        footer.addView(batchStatus); footer.addView(batchDownload, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        shell.addView(footer); setContentView(shell);
    }
    private LinearLayout card(LinearLayout parent) {
        Ui.gap(parent, 20); LinearLayout card = Ui.column(this, 16);
        card.setBackground(Ui.rounded(this, android.graphics.Color.WHITE, 20)); parent.addView(card); return card;
    }
    private String label(String tag) { return tag.equals("zh") ? "中文（繁／簡共用）" : Languages.label(tag); }
    private boolean canDelete() {
        if (OverlayService.running || TranslationModelJobs.busy) { toast("請先關閉懸浮翻譯，並等下載完成後再刪除模型。"); return false; }
        return true;
    }
    private void deleteTranslation(String tag) {
        if (!canDelete()) return;
        new AlertDialog.Builder(this).setMessage("刪除「" + label(tag) + "」翻譯模型？之後可重新下載。")
            .setNegativeButton("取消", null).setPositiveButton("刪除", (d, w) ->
                RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(tag).build())
                    .addOnCompleteListener(t -> { if (!isDestroyed()) { toast(t.isSuccessful() ? "已刪除" : "刪除失敗，請稍後重試"); refresh(); } })).show();
    }
    private void refresh() {
        batchStatus.setText(TranslationModelJobs.message); batchDownload.setEnabled(!TranslationModelJobs.busy);
        batchDownload.setAlpha(TranslationModelJobs.busy ? .45f : 1);
        if (checking || !visible) return;
        checking = true;
        worker.execute(() -> {
            OcrModels.State state = ocr.state(); Set<String> installed = new HashSet<>(); boolean success;
            try {
                for (TranslateRemoteModel model : Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class), 10, TimeUnit.SECONDS)) installed.add(model.getLanguage());
                success = true;
            } catch (Exception e) { success = false; }
            boolean loaded = success;
            main.post(() -> {
                checking = false; if (isDestroyed() || !visible) return;
                thaiStatus.setText(state.status()); thaiDownload.setEnabled(!state.ready() && !state.downloading());
                thaiDownload.setText(state.ready() ? "泰文辨識模型已下載" : state.downloading() ? "正在下載泰文辨識模型…" : "下載泰文辨識模型（約 1.1 MB）");
                thaiDownload.setAlpha(thaiDownload.isEnabled() ? 1 : .45f);
                thaiDelete.setEnabled(state.ready() || state.downloading());
                thaiDelete.setAlpha(thaiDelete.isEnabled() ? 1 : .45f);
                for (String tag : choices.keySet()) {
                    choices.get(tag).setText(label(tag) + (loaded ? installed.contains(tag) ? " · 已下載" : " · 尚未下載" : " · 狀態讀取失敗"));
                    deletes.get(tag).setEnabled(loaded && installed.contains(tag) && !TranslationModelJobs.busy);
                    deletes.get(tag).setAlpha(deletes.get(tag).isEnabled() ? 1 : .3f);
                }
            });
        });
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() { super.onResume(); visible = true; main.post(poll); }
    @Override protected void onPause() { visible = false; main.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putStringArrayList("selected", new ArrayList<>(selected)); super.onSaveInstanceState(out); }
}
