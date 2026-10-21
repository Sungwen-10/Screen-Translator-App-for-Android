package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.net.Uri;
import android.view.WindowManager;

/** One projection / one virtual display. Recorder is the normal consumer while recording. */
final class ScreenCapture implements AutoCloseable {
    interface Callback { void ready(Bitmap bitmap); void error(String message); }
    interface RecordingListener { void started(boolean sound); void saved(Uri uri); void error(String message); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("screen-capture");
    private final Handler worker;
    private final MediaProjection projection;
    private final int density;
    private final Runnable stopped;
    private ImageReader reader;
    private VirtualDisplay display;
    private Callback pending;
    private volatile boolean closed;
    private int width, height, sourceWidth, sourceHeight;
    private long notBefore;
    private ScreenRecorder recorder;
    private RecordingListener recordingListener;
    private final Runnable encode = new Runnable() {
        @Override public void run() {
            if (closed || recorder == null) return;
            try { recorder.pump(); worker.postDelayed(this, 10); }
            catch (Exception e) { finishRecording("錄影編碼中斷，請確認剩餘儲存空間後重試。"); }
        }
    };
    private final Runnable timeout = () -> fail("未取得畫面。請關閉後重新啟用螢幕擷取。");
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { if (!closed) main.post(stopped); }
        @Override public void onCapturedContentResize(int w, int h) { resize(w, h); }
    };

    ScreenCapture(Context context, MediaProjection projection, Runnable stopped, Runnable ready) {
        this.context = context.getApplicationContext();
        this.projection = projection; this.stopped = stopped;
        density = context.getResources().getConfiguration().densityDpi;
        Rect bounds = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        width = sourceWidth = bounds.width(); height = sourceHeight = bounds.height();
        thread.start(); worker = new Handler(thread.getLooper());
        projection.registerCallback(projectionCallback, main);
        worker.post(() -> {
            try {
                if (closed) return;
                makeReader();
                display = projection.createVirtualDisplay("ScreenLingo", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
                display.setSurface(null);
                main.post(() -> { if (!closed) ready.run(); });
            } catch (Exception e) {
                main.post(() -> { if (!closed) stopped.run(); });
            }
        });
    }
    void resize(int w, int h) {
        worker.post(() -> {
            if (closed || display == null || w <= 0 || h <= 0 || (w == sourceWidth && h == sourceHeight)) return;
            fail("畫面尺寸已改變，請再按一次翻譯。");
            sourceWidth = w; sourceHeight = h;
            // Keep the encoder surface AND virtual display dimensions fixed until stop.
            // Android 12L+ fits the rotated source inside this surface with letterboxing.
            if (recorder == null) configureOutput(w, h);
        });
    }
    private void makeReader() {
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(current -> {
            if (closed || current != reader) return;
            try (Image image = current.acquireLatestImage()) {
                if (image == null || pending == null || SystemClock.elapsedRealtime() < notBefore) return;
                Image.Plane plane = image.getPlanes()[0];
                int strideWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888);
                java.nio.ByteBuffer buffer = plane.getBuffer();
                if (buffer.remaining() < padded.getByteCount()) {
                    java.nio.ByteBuffer full = java.nio.ByteBuffer.allocateDirect(padded.getByteCount());
                    full.put(buffer); full.rewind(); padded.copyPixelsFromBuffer(full);
                } else padded.copyPixelsFromBuffer(buffer);
                Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, width, height);
                if (bitmap != padded) padded.recycle();
                // Screenshots are for the current display, not the fixed recording canvas.
                // Remove Android's centered letterbox, then restore display coordinates.
                double scale = Math.min(width / (double) sourceWidth, height / (double) sourceHeight);
                int contentWidth = Math.min(width, Math.max(1, (int) Math.round(sourceWidth * scale)));
                int contentHeight = Math.min(height, Math.max(1, (int) Math.round(sourceHeight * scale)));
                Bitmap content = Bitmap.createBitmap(bitmap, (width - contentWidth) / 2, (height - contentHeight) / 2, contentWidth, contentHeight);
                if (content != bitmap) bitmap.recycle();
                Bitmap normalized = Bitmap.createScaledBitmap(content, sourceWidth, sourceHeight, true);
                if (normalized != content) content.recycle();
                Callback callback = pending; pending = null;
                worker.removeCallbacks(timeout); restoreConsumer();
                main.post(() -> { if (closed) normalized.recycle(); else callback.ready(normalized); });
            } catch (Exception e) { fail("畫面擷取失敗，請重新啟用。"); }
        }, worker);
    }
    void capture(Callback callback) {
        Rect bounds = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        worker.post(() -> {
            if (closed || display == null) { main.post(() -> callback.error("擷取尚未就緒，請稍候再試。")); return; }
            // Idle capture has no attached surface, so its resize callback can arrive late.
            sourceWidth = bounds.width(); sourceHeight = bounds.height();
            if (recorder == null && (width != sourceWidth || height != sourceHeight)) configureOutput(sourceWidth, sourceHeight);
            // Discard anything buffered before this click; attach only after overlays have disappeared.
            try (Image stale = reader.acquireLatestImage()) { /* release stale frame */ }
            pending = callback; notBefore = SystemClock.elapsedRealtime();
            display.setSurface(reader.getSurface());
            worker.removeCallbacks(timeout); worker.postDelayed(timeout, 4000);
        });
    }
    private void fail(String message) {
        if (pending == null) return;
        Callback callback = pending; pending = null; worker.removeCallbacks(timeout);
        restoreConsumer();
        main.post(() -> { if (!closed) callback.error(message); });
    }
    private void restoreConsumer() {
        if (display != null) display.setSurface(recorder == null ? null : recorder.surface());
    }
    private void configureOutput(int w, int h) {
        display.setSurface(null);
        if (reader != null) reader.close();
        width = w; height = h; makeReader(); display.resize(width, height, density); restoreConsumer();
    }
    void startRecording(boolean sound, RecordingListener listener) {
        Rect bounds = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        worker.post(() -> {
            if (closed || display == null || recorder != null) { main.post(() -> listener.error("擷取尚未就緒，或已有錄影進行中。")); return; }
            recordingListener = listener;
            try {
                sourceWidth = bounds.width(); sourceHeight = bounds.height();
                recorder = new ScreenRecorder(context); recorder.start(projection, sourceWidth, sourceHeight, sound);
                // Match ImageReader and encoder buffer sizes. Surface switches must not reuse a
                // smaller producer size inside a larger screenshot (which misaligns the overlay).
                if (pending != null) fail("錄影已開始，請再按一次翻譯。");
                configureOutput(recorder.width(), recorder.height()); worker.post(encode);
                main.post(() -> { if (!closed) listener.started(sound); });
            } catch (Exception e) {
                if (recorder != null) recorder.abort(); recorder = null; recordingListener = null;
                try { configureOutput(sourceWidth, sourceHeight); } catch (RuntimeException ignored) { }
                String message = e instanceof java.io.IOException ? e.getMessage() : "錄影無法啟動，請確認儲存空間及音訊權限後重試。";
                main.post(() -> listener.error(message));
            }
        });
    }
    void stopRecording() { worker.post(() -> finishRecording(null)); }
    private void finishRecording(String failure) {
        worker.removeCallbacks(encode);
        ScreenRecorder current = recorder; recorder = null;
        RecordingListener listener = recordingListener; recordingListener = null;
        if (current == null) return;
        if (pending != null) fail("錄影已停止，請再按一次翻譯。");
        try { if (display != null) display.setSurface(pending == null ? null : reader.getSurface()); } catch (Exception ignored) { }
        try {
            Uri saved = current.finish();
            if (listener != null) main.post(() -> { if (saved != null) listener.saved(saved); else listener.error("沒有可儲存的影片。"); });
        } catch (Exception e) {
            if (listener != null) main.post(() -> listener.error(failure != null ? failure : "影片未能儲存，請確認錄影時間及剩餘儲存空間。"));
        } finally { if (!closed && display != null) try { configureOutput(sourceWidth, sourceHeight); } catch (RuntimeException ignored) { } }
    }
    @Override public void close() {
        if (closed) return; closed = true;
        worker.post(() -> {
            finishRecording(null);
            pending = null; worker.removeCallbacks(timeout);
            if (display != null) { display.release(); display = null; }
            if (reader != null) { reader.close(); reader = null; }
            projection.unregisterCallback(projectionCallback); projection.stop(); thread.quitSafely();
        });
    }
}
