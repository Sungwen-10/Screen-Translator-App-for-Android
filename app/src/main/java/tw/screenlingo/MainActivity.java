package tw.screenlingo;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int OVERLAY = 10, CAPTURE = 11, NOTIFY = 12, AUDIO = 13;
    private Prefs prefs;
    private TextView status;
    private Button start;
    private Switch wifiToggle;
    private Button recordButton;
    private TextView recordStatus;
    private boolean pendingRecord;
    private boolean waitingOverlay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        prefs = new Prefs(this);
        waitingOverlay = state != null && state.getBoolean("waitingOverlay");
        pendingRecord = state != null && state.getBoolean("pendingRecord");
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root = Ui.column(this, 24); root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(Ui.dp(this, 24) + bars.left, Ui.dp(this, 24) + bars.top,
                Ui.dp(this, 24) + bars.right, Ui.dp(this, 24) + bars.bottom);
            return insets;
        });
        root.addView(Ui.text(this, "SCREENLINGO  /  GOOGLE LENS", 12, Ui.TEAL));
        Ui.gap(root, 12); root.addView(Ui.title(this, "浮譯", 38));
        root.addView(Ui.text(this, "點一下，讓看不懂的文字變熟悉。", 17, Ui.MUTED));
        Ui.gap(root, 24);
        LinearLayout control = card(root);
        status = Ui.title(this, "尚未啟用", 19); control.addView(status);
        Ui.gap(control, 8);
        control.addView(Ui.text(this, "半球貼在左右邊緣；拖曳移動，放手自動貼邊。\n點一下擷取翻譯；長按可控制錄影或停止。", 14, Ui.MUTED));
        Ui.gap(control, 16);
        start = Ui.button(this, "啟用懸浮翻譯", true, v -> {
            if (OverlayService.running) moveTaskToBack(true); else requestStart();
        });
        control.addView(start, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        Ui.gap(control, 8);
        control.addView(Ui.button(this, "關閉懸浮球與螢幕擷取", false, v -> {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.STOP));
            updateStatus();
        }), new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        Ui.gap(control, 8);
        control.addView(Ui.button(this, "開啟英文試譯頁", false, v -> startActivity(new Intent(this, DemoActivity.class))),
            new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        LinearLayout mode = card(root);
        mode.addView(Ui.title(this, "翻譯方式", 20));
        spinner(mode, Arrays.asList("Google 智慧鏡頭（線上，預設）", "浮譯離線翻譯（原位覆蓋）"), prefs.lens() ? 0 : 1,
            position -> prefs.put("mode", position == 0 ? "lens" : "offline"));
        mode.addView(Ui.text(this, "智慧鏡頭：按球後，截圖會交給 Google 線上處理並切換到 Google 畫面。請按「翻譯」，目標選「中文（繁體）」或其他語言；按返回回到遊戲。需安裝 Google App 並連網。", 14, Ui.MUTED));
        Ui.gap(mode, 8);
        mode.addView(Ui.text(this, "智慧鏡頭的語言與放大操作由 Google 畫面控制；下方語言偏好及模型只用於離線翻譯。此版不會自動按下 Google 的翻譯按鈕。", 13, Ui.MUTED));
        Ui.gap(mode, 8);
        mode.addView(Ui.text(this, "離線模式已加強遊戲彩色／描邊字辨識。複雜畫面需要較多時間，仍可能漏字或誤譯；辨識進度不代表正在下載模型。", 13, Ui.MUTED));

        LinearLayout recording = card(root);
        recording.addView(Ui.title(this, "內建螢幕錄影", 20));
        recordStatus = Ui.text(this, "尚未錄影", 14, Ui.MUTED); recording.addView(recordStatus);
        toggle(recording, "錄製遊戲聲音（不錄麥克風）", "依此開關決定有聲或靜音；部分遊戲不允許擷取聲音。啟用時系統會要求音訊權限。", "recordSound", prefs.data.getBoolean("recordSound", false));
        recordButton = Ui.button(this, "開始錄影（可同時翻譯）", true, v -> {
            if (OverlayService.recording) startService(new Intent(this, OverlayService.class).setAction(OverlayService.RECORD_STOP)); else requestRecording();
        }); recording.addView(recordButton);
        Ui.gap(recording, 8);
        recording.addView(Ui.button(this, "播放最近一次錄影", false, v -> {
            String saved = prefs.data.getString("lastVideo", "");
            if (saved.isEmpty()) { Toast.makeText(this, "尚無已儲存的錄影。", Toast.LENGTH_LONG).show(); return; }
            try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(saved), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
            catch (Exception e) { Toast.makeText(this, "找不到影片或播放器，請到相簿 Movies/ScreenLingo 查看。", Toast.LENGTH_LONG).show(); }
        }));
        recording.addView(Ui.text(this, "長按半球開始／停止錄影，紅色計時鈕也可停止；儲存在相簿 Movies/ScreenLingo。遊戲、智慧鏡頭及返回過程會持續錄在同一支影片。影片保持開錄時的尺寸，轉向後等比縮放並補黑邊，計時不中斷。請使用浮譯內建錄影，不需啟動 XRecorder。", 13, Ui.MUTED));

        LinearLayout lang = card(root);
        lang.addView(Ui.title(this, "離線翻譯偏好", 20));
        Ui.gap(lang, 14); lang.addView(Ui.text(this, "翻譯成", 13, Ui.MUTED));
        languageSpinner(lang, "target", prefs.target(), false);
        Ui.gap(lang, 8); lang.addView(Ui.text(this, "原文語言（短句判斷不準時可指定）", 13, Ui.MUTED));
        languageSpinner(lang, "source", prefs.source(), true);
        Ui.gap(lang, 8); lang.addView(Ui.text(this, "畫面文字種類", 13, Ui.MUTED));
        String[] scripts = {"auto", "latin", "zh", "ja", "ko", "hi", "th"};
        String[] names = {"自動 · 包含已下載的泰文辨識", "拉丁字母（英語、法文等）", "中文＋拉丁字母", "日文＋拉丁字母", "韓文＋拉丁字母", "天城體（印地語等）＋拉丁字母", "泰文（需下載辨識模型）"};
        spinner(lang, Arrays.asList(names), Arrays.asList(scripts).indexOf(prefs.script()),
            position -> prefs.put("script", scripts[position]));
        lang.addView(Ui.text(this, "法文辨識已內建；泰文需先下載文字辨識模型。可翻譯的語言比可辨識的文字種類多，例如此版仍無法讀取阿拉伯文字形。", 12, Ui.MUTED));

        LinearLayout appearance = card(root);
        appearance.addView(Ui.title(this, "貼邊半球", 20));
        seek(appearance, "不透明度", "opacity", 25, 100, Math.round(prefs.opacity() * 100), "%");
        seek(appearance, "大小", "size", 40, 72, prefs.size(), " dp");
        toggle(appearance, "常駐模式", "關閉後：開啟智慧鏡頭即停止擷取；離線模式看完一次譯文後停止。錄影中會保持運作。", "resident", prefs.resident());
        appearance.addView(Ui.button(this, "重設懸浮球位置", false, v -> {
            prefs.data.edit().remove("ballX").remove("ballY").apply();
            Toast.makeText(this, "已重設位置", Toast.LENGTH_SHORT).show();
        }));

        LinearLayout offline = card(root);
        offline.addView(Ui.title(this, "離線模型", 20));
        toggle(offline, "只透過 Wi-Fi 下載", "翻譯模型每個約數十 MB；泰文辨識模型另約 1.1 MB。", "wifi", prefs.wifiOnly());
        offline.addView(Ui.button(this, "選擇及下載語言模型", true, v -> startActivity(new Intent(this, ModelsActivity.class))));
        Ui.gap(offline, 8);
        offline.addView(Ui.button(this, "預先下載原文／目標語言模型", false, v -> preload((Button) v)));
        Ui.gap(offline, 8);
        offline.addView(Ui.button(this, "管理已下載的模型", false, v -> manageModels()));
        Ui.gap(offline, 12);
        offline.addView(Ui.text(this, "離線模式的截圖與近期譯文只在記憶體暫存。智慧鏡頭模式會在 App 私有快取儲存 PNG，透過暫時讀取權限交給 Google；下次分享時清理超過 24 小時的快取，也可從 Android 的應用程式設定清除快取。Google 的線上處理依其服務政策。離線模型下載與 SDK 運作也可能連線 Google。", 13, Ui.MUTED));

        LinearLayout help = card(root);
        help.addView(Ui.title(this, "使用方式", 20));
        Ui.gap(help, 10);
        help.addView(Ui.text(this, "1. 啟用並允許「顯示在其他應用程式上層」。\n2. 在 Android 視窗允許擷取整個螢幕。\n3. 切換到要閱讀的 App，按懸浮球。\n4. 智慧鏡頭模式：在 Google 畫面按「翻譯」，選目標語言；按返回回到原 App。\n5. 離線模式：雙指可放大至 4 倍，放大後拖曳閱讀；點譯文看全文，點空白或懸浮球還原。\n6. 換頁後再按懸浮球擷取新畫面。", 14, Ui.MUTED));
        Ui.gap(help, 12);
        help.addView(Ui.text(this, "常駐會顯示系統通知與螢幕擷取指示。系統停止擷取、鎖定畫面或重新開機後，可能需重新啟用。禁止截圖的 App 無法讀取。", 13, Ui.MUTED));
        Ui.gap(help, 12);
        help.addView(Ui.text(this, "本服務可能包含 Google 提供的翻譯。Google 對翻譯的準確性、可靠性、適售性、特定用途適用性及不侵權性，不提供任何明示或默示保證。", 12, Ui.MUTED));
        Ui.gap(root, 12);
        root.addView(Ui.attribution(this), new LinearLayout.LayoutParams(Ui.dp(this, 158), Ui.dp(this, 30)));
        root.addView(Ui.text(this, "Google 智慧鏡頭分享 · 離線 ML Kit／Tesseract\n0.1.8 / Android 11+ / Pixel 5 優先", 12, Ui.MUTED));
        scroll.addView(root); setContentView(scroll);
        if (getIntent().getBooleanExtra("restart", false)) handler.post(this::requestStart);
        if (getIntent().getBooleanExtra("askRecord", false)) handler.post(this::requestRecording);
    }
    private LinearLayout card(LinearLayout root) {
        Ui.gap(root, 16); LinearLayout c = Ui.column(this, 20);
        c.setBackground(Ui.rounded(this, android.graphics.Color.WHITE, 24)); root.addView(c); return c;
    }
    private void languageSpinner(LinearLayout parent, String key, String selected, boolean auto) {
        List<Languages.Entry> entries = Languages.all(auto);
        int initial = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).tag().equals(selected)) initial = i;
        spinner(parent, entries, initial, p -> prefs.put(key, entries.get(p).tag()));
    }
    private interface Selection { void select(int position); }
    private void spinner(LinearLayout parent, List<?> entries, int initial, Selection select) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DIALOG);
        ArrayAdapter<Object> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>(entries));
        spinner.setAdapter(adapter); spinner.setSelection(Math.max(0, initial));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int position, long id) { select.select(position); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
    }
    private void seek(LinearLayout parent, String title, String key, int min, int max, int value, String unit) {
        Ui.gap(parent, 16); TextView label = Ui.text(this, title + "  " + value + unit, 14, Ui.MUTED);
        parent.addView(label); SeekBar seek = new SeekBar(this); seek.setMax(max - min); seek.setProgress(value - min);
        seek.setContentDescription(title);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                label.setText(title + "  " + (p + min) + unit);
                if (user) prefs.data.edit().putInt(key, p + min).apply();
            }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        parent.addView(seek, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
    }
    private void toggle(LinearLayout parent, String label, String detail, String key, boolean checked) {
        Ui.gap(parent, 14); Switch toggle = new Switch(this); toggle.setText(label); toggle.setTextSize(15);
        toggle.setTextColor(Ui.INK); toggle.setChecked(checked); toggle.setMinHeight(Ui.dp(this, 48));
        if (key.equals("wifi")) wifiToggle = toggle;
        toggle.setOnCheckedChangeListener((b, isChecked) -> prefs.data.edit().putBoolean(key, isChecked).apply());
        parent.addView(toggle); parent.addView(Ui.text(this, detail, 12, Ui.MUTED)); Ui.gap(parent, 12);
    }
    private void requestStart() {
        if (OverlayService.running) return;
        if (!Settings.canDrawOverlays(this)) {
            waitingOverlay = true;
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), OVERLAY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.data.getBoolean("notificationAsked", false)) {
            prefs.data.edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFY); return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
            ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            : manager.createScreenCaptureIntent();
        startActivityForResult(consent, CAPTURE);
    }
    private void requestRecording() {
        pendingRecord = true;
        if (prefs.data.getBoolean("recordSound", false) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO); return;
        }
        if (OverlayService.running) {
            startService(new Intent(this, OverlayService.class).setAction(OverlayService.RECORD_START));
            pendingRecord = false; moveTaskToBack(true);
        } else requestStart();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY && waitingOverlay) {
            waitingOverlay = false;
            if (Settings.canDrawOverlays(this)) requestStart();
            else { pendingRecord = false; Toast.makeText(this, "需要上層顯示權限才能顯示懸浮球", Toast.LENGTH_LONG).show(); }
        }
        if (request == CAPTURE && result == RESULT_OK && data != null) {
            Intent service = new Intent(this, OverlayService.class).putExtra("code", result).putExtra("grant", data).putExtra("record", pendingRecord);
            pendingRecord = false;
            startForegroundService(service); moveTaskToBack(true);
        }
        if (request == CAPTURE && result != RESULT_OK) pendingRecord = false;
    }
    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g); if (r == NOTIFY) requestStart();
        if (r == AUDIO) {
            if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) requestRecording();
            else { pendingRecord = false; Toast.makeText(this, "音訊權限未允許。可關閉「錄製遊戲聲音」後開始靜音錄影。", Toast.LENGTH_LONG).show(); }
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("waitingOverlay", waitingOverlay); super.onSaveInstanceState(out);
        out.putBoolean("pendingRecord", pendingRecord);
    }
    private void updateStatus() {
        if (status == null) return;
        status.setText(OverlayService.running ? "● 懸浮翻譯已啟用" : "○ 懸浮翻譯已關閉");
        start.setText(OverlayService.running ? "返回桌面，開始閱讀" : "啟用懸浮翻譯");
        if (recordStatus != null) recordStatus.setText(OverlayService.recordingBusy ? "正在處理錄影…" : OverlayService.recording ? "● 錄影中 " + OverlayService.recordingTime() : "尚未錄影");
        if (recordButton != null) {
            recordButton.setText(OverlayService.recording ? "停止錄影並儲存" : "開始錄影（可同時翻譯）");
            recordButton.setEnabled(!OverlayService.recordingBusy);
        }
    }
    @Override protected void onResume() { super.onResume(); if (wifiToggle != null) wifiToggle.setChecked(prefs.wifiOnly()); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }

    private void preload(Button button) {
        String source = prefs.source().equals("auto") ? "en" : Languages.modelTag(prefs.source());
        String target = Languages.modelTag(prefs.target());
        if (source.equals(target)) { Toast.makeText(this, "原文與目標語言相同，不需下載", Toast.LENGTH_LONG).show(); return; }
        Translator translator = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build());
        button.setEnabled(false); button.setText("正在準備模型…首次可能需要數分鐘");
        com.google.mlkit.common.model.DownloadConditions.Builder conditions = new com.google.mlkit.common.model.DownloadConditions.Builder();
        if (prefs.wifiOnly()) conditions.requireWifi();
        translator.downloadModelIfNeeded(conditions.build()).addOnCompleteListener(task -> {
            translator.close(); button.setEnabled(true); button.setText("預先下載原文／目標語言模型");
            if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this)
                .setMessage(task.isSuccessful() ? "模型已備妥，可離線使用此語言組合。" : "下載失敗。請確認網路；若開啟只用 Wi-Fi，請連接 Wi-Fi 後重試。")
                .setPositiveButton("知道了", null).show();
        });
    }
    private void manageModels() {
        if (OverlayService.running) { Toast.makeText(this, "請先關閉懸浮翻譯再管理模型", Toast.LENGTH_LONG).show(); return; }
        RemoteModelManager manager = RemoteModelManager.getInstance();
        manager.getDownloadedModels(TranslateRemoteModel.class).addOnSuccessListener(models -> {
            if (isFinishing() || isDestroyed()) return;
            List<TranslateRemoteModel> list = new ArrayList<>(models);
            if (list.isEmpty()) { Toast.makeText(this, "尚無已下載的語言模型", Toast.LENGTH_LONG).show(); return; }
            list.sort(Comparator.comparing(TranslateRemoteModel::getLanguage));
            String[] labels = list.stream().map(m -> Languages.label(m.getLanguage()) + " · 點選刪除").toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("已下載的模型").setItems(labels, (d, i) ->
                new AlertDialog.Builder(this).setMessage("刪除 " + Languages.label(list.get(i).getLanguage()) + " 的離線模型？")
                    .setNegativeButton("取消", null).setPositiveButton("刪除", (dialog, which) ->
                        manager.deleteDownloadedModel(list.get(i)).addOnCompleteListener(t ->
                            Toast.makeText(this, t.isSuccessful() ? "已刪除" : "刪除失敗，請稍後重試", Toast.LENGTH_LONG).show())).show())
                .setNegativeButton("關閉", null).show();
        }).addOnFailureListener(e -> Toast.makeText(this, "無法取得模型清單", Toast.LENGTH_LONG).show());
    }
}
