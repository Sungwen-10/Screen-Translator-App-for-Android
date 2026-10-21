package tw.screenlingo;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class OverlayService extends Service {
    public static volatile boolean running;
    static volatile boolean recording, recordingBusy;
    static volatile long recordingStartedAt;
    static final String STOP = "tw.screenlingo.STOP", RECORD_START = "tw.screenlingo.RECORD_START", RECORD_STOP = "tw.screenlingo.RECORD_STOP";
    private static final String CHANNEL = "translation";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private Prefs prefs;
    private ScreenCapture capture;
    private TranslationEngine engine;
    private Toast notice;
    private TextView ball, progressText, recordChip;
    private View translatedLayer, panel;
    private WindowManager.LayoutParams ballParams;
    private WindowManager.LayoutParams recordChipParams;
    private boolean busy, destroyed, ready;
    private long translationJob;
    private boolean stopAfterRecording;
    private final Runnable recordClock = new Runnable() {
        @Override public void run() {
            if (destroyed || !recording) return;
            if (recordChip != null) recordChip.setText("● " + recordingTime() + "  停止");
            updateNotification(); main.postDelayed(this, 1000);
        }
    };
    private int lastSize;
    private boolean ballDocked = true, dockRight = true;
    private TranslationEngine.Result lastResult;
    private Bitmap readingFrame;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener settingsChanged = (p, key) -> main.post(() -> {
        if (destroyed) return;
        if ("opacity".equals(key) || "size".equals(key)) applyBallSettings();
        if ("ballX".equals(key) && !p.contains("ballX")) resetPosition();
    });

    @Override public void onCreate() {
        super.onCreate(); prefs = new Prefs(this); windows = getSystemService(WindowManager.class);
        prefs.data.registerOnSharedPreferenceChangeListener(settingsChanged);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopEverything(); return START_NOT_STICKY; }
        if (RECORD_START.equals(intent.getAction())) { beginRecording(); return START_NOT_STICKY; }
        if (RECORD_STOP.equals(intent.getAction())) { endRecording(); return START_NOT_STICKY; }
        if (capture != null) return START_NOT_STICKY;
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        Intent grant = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("grant", Intent.class) : intent.getParcelableExtra("grant");
        if (grant == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "懸浮翻譯運作狀態", NotificationManager.IMPORTANCE_LOW));
            startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            MediaProjection projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("code", Activity.RESULT_CANCELED), grant);
            if (projection == null) throw new IllegalStateException();
            capture = new ScreenCapture(this, projection, () -> {
                showNotice("螢幕擷取已停止，錄影會結束並儲存；需要時請重新啟用浮譯。", Toast.LENGTH_LONG); stopEverything();
            }, () -> { ready = true; if (intent.getBooleanExtra("record", false)) beginRecording(); });
            showBall(); running = true;
        } catch (Exception e) {
            showNotice("啟用失敗，請重新允許上層顯示與螢幕擷取。", Toast.LENGTH_LONG); stopSelf();
        }
        return START_NOT_STICKY;
    }
    static String recordingTime() {
        long seconds = Math.max(0, SystemClock.elapsedRealtime() - recordingStartedAt) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
    private void showNotice(String message, int duration) {
        clearNotice(); notice = Toast.makeText(getApplicationContext(), message, duration); notice.show();
    }
    private void clearNotice() { if (notice != null) { notice.cancel(); notice = null; } }
    private Notification notification() {
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_app)
            .setContentTitle(recording ? "浮譯錄影中 " + recordingTime() : recordingBusy ? "正在處理錄影…" : "浮譯已啟用")
            .setContentText("點懸浮球翻譯；長按可控制錄影。")
            .setContentIntent(settings).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true);
        if (recording) {
            PendingIntent end = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class).setAction(RECORD_STOP), PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "停止錄影並儲存", end).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, "關閉浮譯", stop).build()).build();
    }
    private void updateNotification() { if (!destroyed) getSystemService(NotificationManager.class).notify(7, notification()); }
    private void beginRecording() {
        if (!ready || capture == null) { showNotice("請先啟用懸浮翻譯，等擷取就緒後再錄影。", Toast.LENGTH_LONG); if (capture == null) stopSelf(); return; }
        if (recording || recordingBusy) return;
        if (prefs.data.getBoolean("recordSound", false) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class).putExtra("askRecord", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); return;
        }
        removePanel(); recordingBusy = true; updateNotification();
        capture.startRecording(prefs.data.getBoolean("recordSound", false), new ScreenCapture.RecordingListener() {
            public void started(boolean sound) {
                if (destroyed) return;
                recordingBusy = false; recording = true; recordingStartedAt = SystemClock.elapsedRealtime();
                recordChip = Ui.text(OverlayService.this, "● 00:00  停止", 13, Color.WHITE);
                recordChip.setGravity(Gravity.CENTER); recordChip.setBackground(Ui.rounded(OverlayService.this, Color.rgb(165, 38, 44), 18));
                recordChip.setContentDescription("停止錄影並儲存"); recordChip.setOnClickListener(v -> endRecording());
                recordChipParams = params(Ui.dp(OverlayService.this, 130), Ui.dp(OverlayService.this, 38));
                positionRecordChip();
                windows.addView(recordChip, recordChipParams); main.post(recordClock);
                if (stopAfterRecording) endRecording();
            }
            public void saved(android.net.Uri uri) {
                if (!destroyed) resetRecording(); showNotice("影片已儲存至相簿 Movies/ScreenLingo", Toast.LENGTH_LONG);
                if (stopAfterRecording && !destroyed) stopSelf();
            }
            public void error(String message) {
                if (!destroyed) resetRecording(); showNotice(message, Toast.LENGTH_LONG);
                if (stopAfterRecording && !destroyed) stopSelf();
            }
        });
    }
    private void resetRecording() {
        recording = recordingBusy = false; main.removeCallbacks(recordClock);
        if (recordChip != null && recordChip.isAttachedToWindow()) windows.removeViewImmediate(recordChip);
        recordChip = null; updateNotification();
    }
    private void positionRecordChip() {
        if (recordChipParams == null) return;
        android.graphics.Insets safe = windows.getMaximumWindowMetrics().getWindowInsets()
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        recordChipParams.x = safe.left + Ui.dp(this, 12); recordChipParams.y = safe.top + Ui.dp(this, 8);
        if (recordChip != null && recordChip.isAttachedToWindow()) windows.updateViewLayout(recordChip, recordChipParams);
    }
    private void endRecording() {
        if (capture == null || (!recording && !recordingBusy)) return;
        recordingBusy = true; if (recordChip != null) recordChip.setText("正在儲存…");
        main.removeCallbacks(recordClock); capture.stopRecording(); updateNotification();
    }
    private void stopEverything() {
        if (recording || recordingBusy) { stopAfterRecording = true; endRecording(); }
        else stopSelf();
    }
    private WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT; return p;
    }
    private void showBall() {
        ball = Ui.title(this, "譯", 21); ball.setTextColor(Color.WHITE); ball.setGravity(Gravity.CENTER);
        ball.setContentDescription("翻譯螢幕；半球貼邊；拖曳移動；長按選單");
        ball.setBackground(Ui.rounded(this, Ui.TEAL, 100)); ball.setElevation(Ui.dp(this, 8));
        ballParams = params(Ui.dp(this, prefs.size()), Ui.dp(this, prefs.size()));
        resetPosition(); windows.addView(ball, ballParams); applyBallSettings();
        ball.setOnClickListener(v -> {
            if (translatedLayer != null || panel != null) dismissResult();
            else translate();
        });
        ball.setOnLongClickListener(v -> { if (!busy) showMenu(); return true; });
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int x, y; boolean dragging, longPressed;
            final Runnable longPress = () -> { if (!dragging && !busy) { longPressed = true; ball.performLongClick(); } };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY(); x = ballParams.x; y = ballParams.y;
                        dragging = false; longPressed = false; main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout()); return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > ViewConfiguration.get(v.getContext()).getScaledTouchSlop()) {
                            if (!dragging) {
                                dragging = true; ballDocked = false; updateBallShape();
                                x = Math.round(downX - ballParams.width / 2f);
                            }
                            main.removeCallbacks(longPress);
                        }
                        if (dragging && !longPressed) {
                            ballParams.x = x + Math.round(e.getRawX() - downX); ballParams.y = y + Math.round(e.getRawY() - downY);
                            clampBall(); windows.updateViewLayout(ball, ballParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (dragging) dockBall(); else if (!longPressed) v.performClick(); return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress); if (dragging) dockBall(); return true;
                    default: return true;
                }
            }
        });
    }
    private void resetPosition() {
        if (ballParams == null) return;
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        float px = prefs.data.getFloat("ballX", .95f), py = prefs.data.getFloat("ballY", .45f);
        ballDocked = true; dockRight = px >= .5f; updateBallShape();
        ballParams.x = dockRight ? screen.width() : 0;
        ballParams.y = Math.round(py * (screen.height() - ballParams.height));
        clampBall(); if (ball != null && ball.isAttachedToWindow()) windows.updateViewLayout(ball, ballParams);
    }
    private void clampBall() {
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        android.graphics.Insets insets = windows.getMaximumWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        ballParams.x = Math.max(insets.left, Math.min(ballParams.x, screen.width() - ballParams.width - insets.right));
        ballParams.y = Math.max(insets.top, Math.min(ballParams.y, screen.height() - ballParams.height - insets.bottom));
    }
    private void savePosition() {
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        prefs.data.edit().putFloat("ballX", dockRight ? 1f : 0f)
            .putFloat("ballY", ballParams.y / (float) Math.max(1, screen.height() - ballParams.height)).apply();
    }
    private void updateBallShape() {
        int size = Ui.dp(this, prefs.size());
        ballParams.width = ballDocked ? (size + 1) / 2 : size; ballParams.height = size;
        ball.setSystemGestureExclusionRects(java.util.Collections.singletonList(new Rect(0, 0, ballParams.width, size)));
        ball.setTextSize(ballDocked ? 15 : 21);
        ball.setBackground(ballDocked ? new HalfBallDrawable(dockRight) : Ui.rounded(this, Ui.TEAL, 100));
    }
    private void dockBall() {
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        dockRight = ballParams.x + ballParams.width / 2 > screen.centerX(); ballDocked = true; updateBallShape();
        ballParams.x = dockRight ? screen.width() : 0; clampBall(); windows.updateViewLayout(ball, ballParams); savePosition();
    }
    private void applyBallSettings() {
        if (ball == null) return;
        ball.setAlpha(prefs.opacity());
        int size = prefs.size();
        if (size != lastSize) {
            lastSize = size; resetPosition();
        }
    }
    private void translate() {
        if (busy) return;
        if (!ready) { showNotice("擷取準備中，請稍候再按。", Toast.LENGTH_SHORT); return; }
        final boolean useLens = prefs.lens();
        final long job = ++translationJob;
        clearNotice(); busy = true; ball.setVisibility(View.GONE); if (recordChip != null) recordChip.setVisibility(View.GONE); removePanel(); removeTranslation(); releaseReadingFrame(); lastResult = null;
        main.postDelayed(() -> {
            if (destroyed || job != translationJob) return;
            capture.capture(new ScreenCapture.Callback() {
                @Override public void ready(Bitmap bitmap) {
                    if (destroyed || job != translationJob) { bitmap.recycle(); return; }
                    if (useLens) { openLens(bitmap, job); return; }
                    if (engine == null) engine = new TranslationEngine(OverlayService.this);
                    readingFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (recordChip != null) recordChip.setVisibility(View.VISIBLE);
                    ball.setVisibility(View.VISIBLE); ball.setText("…"); showProgress("辨識文字…");
                    // Immersive games draw menus where the hidden navigation bar would
                    // normally be. OCR must retain those pixels; Android clips the
                    // overlay itself wherever a real system surface stays visible.
                    Rect content = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                    // Snapshot preferences once so changing settings cannot change a job midway.
                    engine.run(bitmap, prefs.script(), prefs.source(), prefs.target(), prefs.wifiOnly(), content, new TranslationEngine.Callback() {
                        public void progress(String text) { if (!destroyed && job == translationJob && progressText != null) progressText.setText(text); }
                        public void success(TranslationEngine.Result result) {
                            if (destroyed || job != translationJob) return; busy = false; ball.setText("×"); lastResult = result;
                            removePanel(); showTranslation(result);
                            showNotice("已覆蓋原文。雙指可放大，放大後拖曳閱讀；點譯文看全文。", Toast.LENGTH_LONG);
                        }
                        public void failure(String message) { if (job == translationJob) showFailure(message); }
                    });
                }
                @Override public void error(String message) { if (job == translationJob) showFailure(message); }
            });
        }, 300);
    }
    private void openLens(Bitmap bitmap, long job) {
        // PNG compression runs outside the UI/capture threads. The worker owns this bitmap.
        new Thread(() -> {
            try {
                Intent share = LensShare.prepare(getApplicationContext(), bitmap);
                main.post(() -> {
                    if (destroyed || job != translationJob) return;
                    try {
                        clearNotice();
                        startActivity(share);
                        busy = false; ball.setVisibility(View.VISIBLE); ball.setText("譯");
                        if (recordChip != null) recordChip.setVisibility(View.VISIBLE);
                        if (!prefs.resident() && !recording && !recordingBusy) stopSelf();
                    } catch (RuntimeException e) { showFailure("無法開啟 Google 智慧鏡頭。請更新 Google App 後重試，或改選離線翻譯。"); }
                });
            } catch (Exception e) {
                main.post(() -> { if (job == translationJob) showFailure(e instanceof java.io.IOException ? e.getMessage() : "無法準備截圖，請確認剩餘空間後重試。"); });
            } finally { bitmap.recycle(); }
        }, "lens-screenshot").start();
    }
    private void showTranslation(TranslationEngine.Result result) {
        removeTranslation();
        FrameLayout frame = new FrameLayout(this);
        TranslationOverlay canvas = new TranslationOverlay(this, result, readingFrame, new TranslationOverlay.Listener() {
            public void block(TranslationEngine.Block block) { showBlock(block); }
            public void dismiss() { dismissResult(); }
        });
        frame.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        AttributionBadge.add(frame, prefs);
        frame.setFocusableInTouchMode(true);
        frame.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        frame.setOnKeyListener((v, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) { dismissResult(); return true; }
            return false;
        });
        translatedLayer = frame;
        // This layer consumes taps so the app underneath cannot be activated by a translated label.
        WindowManager.LayoutParams p = params(-1, -1); p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windows.addView(translatedLayer, p); frame.requestFocus(); raiseBall();
    }
    private void raiseBall() {
        if (ball != null && ball.isAttachedToWindow()) {
            windows.removeViewImmediate(ball); windows.addView(ball, ballParams);
        }
        if (recordChip != null && recordChip.isAttachedToWindow()) {
            windows.removeViewImmediate(recordChip); windows.addView(recordChip, recordChipParams);
        }
    }
    private LinearLayout makePanel() {
        removePanel();
        LinearLayout content = Ui.column(this, 20); content.setBackground(Ui.rounded(this, Color.WHITE, 22));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.addView(content);
        panel = scroll;
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        WindowManager.LayoutParams p = params(Math.min(screen.width() - Ui.dp(this, 32), Ui.dp(this, 430)), Math.min(screen.height() * 2 / 3, Ui.dp(this, 540)));
        p.gravity = Gravity.CENTER; p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        scroll.setOnKeyListener((v, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                if (busy) stopEverything(); else if (translatedLayer != null) removePanel(); else dismissResult(); return true;
            }
            return false;
        });
        windows.addView(scroll, p); scroll.setFocusableInTouchMode(true); scroll.requestFocus(); raiseBall();
        return content;
    }
    private void showProgress(String message) {
        LinearLayout content = makePanel(); content.addView(Ui.title(this, "正在翻譯畫面", 20)); Ui.gap(content, 16);
        ProgressBar spinner = new ProgressBar(this); content.addView(spinner, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        Ui.gap(content, 12); progressText = Ui.text(this, message, 15, Ui.MUTED); content.addView(progressText);
        Ui.gap(content, 20); content.addView(Ui.button(this, "取消並停止擷取", false, v -> stopEverything()));
    }
    private void showFailure(String message) {
        if (destroyed) return; busy = false; releaseReadingFrame(); ball.setVisibility(View.VISIBLE); ball.setText("譯");
        if (recordChip != null) recordChip.setVisibility(View.VISIBLE);
        LinearLayout content = makePanel(); content.addView(Ui.title(this, "這次未能翻譯", 20)); Ui.gap(content, 12);
        content.addView(Ui.text(this, message, 15, Ui.MUTED)); Ui.gap(content, 20);
        content.addView(Ui.button(this, "關閉", true, v -> dismissResult()));
        Ui.gap(content, 8); content.addView(Ui.button(this, "開啟設定", false, v -> openSettings()));
    }
    private void showBlock(TranslationEngine.Block block) {
        LinearLayout content = makePanel();
        content.addView(Ui.title(this, Languages.label(lastResult.target()), 20)); Ui.gap(content, 12);
        TextView translated = Ui.text(this, block.translated, 19, Ui.INK); translated.setTextIsSelectable(true); content.addView(translated);
        Ui.gap(content, 16); content.addView(Ui.text(this, "原文" + (block.language == null ? "" : " · " + Languages.label(block.language)), 12, Ui.MUTED));
        TextView original = Ui.text(this, block.original, 15, Ui.MUTED); original.setTextIsSelectable(true); content.addView(original);
        if (!block.note.isEmpty()) { Ui.gap(content, 8); content.addView(Ui.text(this, block.note, 13, Ui.MUTED)); }
        Ui.gap(content, 16);
        content.addView(Ui.button(this, "複製譯文", false, v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("浮譯", block.translated));
            showNotice("已複製譯文", Toast.LENGTH_SHORT);
        }));
        Ui.gap(content, 8); content.addView(Ui.button(this, "返回覆蓋畫面", true, v -> removePanel()));
        Ui.gap(content, 8); content.addView(Ui.button(this, "還原原文", false, v -> dismissResult()));
        Ui.gap(content, 12); content.addView(Ui.attribution(this), new LinearLayout.LayoutParams(Ui.dp(this, 158), Ui.dp(this, 30)));
    }
    private void showMenu() {
        LinearLayout content = makePanel(); content.addView(Ui.title(this, "浮譯", 22)); Ui.gap(content, 16);
        content.addView(Ui.button(this, recording ? "停止錄影並儲存" : "開始錄影（可同時翻譯）", false, v -> { if (recording) endRecording(); else beginRecording(); removePanel(); })); Ui.gap(content, 10);
        if (lastResult != null) { content.addView(Ui.button(this, "檢視全部譯文", true, v -> showAll())); Ui.gap(content, 10); }
        content.addView(Ui.button(this, "開啟設定", true, v -> openSettings())); Ui.gap(content, 10);
        content.addView(Ui.button(this, "關閉懸浮球與擷取", false, v -> stopEverything())); Ui.gap(content, 10);
        content.addView(Ui.button(this, "繼續閱讀", false, v -> removePanel()));
    }
    private void showAll() {
        LinearLayout content = makePanel(); content.addView(Ui.title(this, "全部譯文", 22)); Ui.gap(content, 12);
        content.addView(Ui.text(this, "小字或省略的內容可在這裡閱讀。", 13, Ui.MUTED));
        for (TranslationEngine.Block block : lastResult.blocks()) {
            if (!block.changed()) continue;
            Ui.gap(content, 16); TextView translated = Ui.text(this, block.translated, 18, Ui.INK);
            translated.setTextIsSelectable(true); content.addView(translated);
            Ui.gap(content, 6); content.addView(Ui.text(this, block.original, 13, Ui.MUTED));
        }
        Ui.gap(content, 16); content.addView(Ui.attribution(this), new LinearLayout.LayoutParams(Ui.dp(this, 158), Ui.dp(this, 30)));
        Ui.gap(content, 12); content.addView(Ui.button(this, "返回覆蓋畫面", true, v -> removePanel()));
    }
    private void openSettings() {
        removePanel(); removeTranslation(); releaseReadingFrame(); lastResult = null; ball.setText("譯");
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private void dismissResult() {
        if (busy) return;
        boolean completed = lastResult != null;
        removePanel(); removeTranslation(); releaseReadingFrame(); lastResult = null; ball.setText("譯");
        if (completed && !prefs.resident() && !recording && !recordingBusy) stopSelf();
    }
    private void removePanel() {
        progressText = null;
        if (panel != null) { if (panel.isAttachedToWindow()) windows.removeViewImmediate(panel); panel = null; }
        if (translatedLayer != null && translatedLayer.isAttachedToWindow()) translatedLayer.requestFocus();
    }
    private void removeTranslation() {
        if (translatedLayer != null) { if (translatedLayer.isAttachedToWindow()) windows.removeViewImmediate(translatedLayer); translatedLayer = null; }
    }
    private void releaseReadingFrame() {
        if (readingFrame != null) { readingFrame.recycle(); readingFrame = null; }
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (destroyed || ball == null) return;
        if (busy) {
            ++translationJob; busy = false;
            showNotice("畫面方向已改變，請再按球翻譯；錄影會繼續。", Toast.LENGTH_LONG);
        }
        removeTranslation(); removePanel(); releaseReadingFrame(); lastResult = null;
        ball.setText("譯"); ball.setVisibility(View.VISIBLE); resetPosition(); positionRecordChip();
        if (recordChip != null) recordChip.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT < 34 && capture != null) {
            Rect bounds = windows.getMaximumWindowMetrics().getBounds(); capture.resize(bounds.width(), bounds.height());
        }
    }
    @Override public void onDestroy() {
        destroyed = true; running = false; main.removeCallbacksAndMessages(null);
        prefs.data.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        removePanel(); removeTranslation(); releaseReadingFrame();
        if (ball != null && ball.isAttachedToWindow()) windows.removeViewImmediate(ball);
        if (capture != null) capture.close(); if (engine != null) engine.close();
        resetRecording();
        lastResult = null; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
