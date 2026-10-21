package tw.screenlingo;

import android.content.*;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecordingTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    @After public void cleanup() throws Exception {
        if (OverlayService.running) {
            context.startService(new Intent(context, OverlayService.class).setAction(OverlayService.STOP));
            await(() -> !OverlayService.running, 12000, "Service did not stop");
        }
        device.setOrientationNatural(); device.unfreezeRotation();
        new Prefs(context).data.edit().putBoolean("recordSound", false).commit();
    }
    private void enable(boolean sound) throws Exception {
        device.wakeUp();
        new Prefs(context).data.edit().putString("mode", "offline").putString("source", "en").putString("target", "zh-Hant").putString("script", "latin")
            .putBoolean("resident", true).putBoolean("wifi", false).putBoolean("recordSound", sound).remove("ballX").remove("ballY").commit();
        device.executeShellCommand("appops set tw.screenlingo SYSTEM_ALERT_WINDOW allow");
        device.executeShellCommand("pm grant tw.screenlingo android.permission.POST_NOTIFICATIONS");
        if (sound) device.executeShellCommand("pm grant tw.screenlingo android.permission.RECORD_AUDIO");
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        UiObject2 start = device.wait(Until.findObject(By.text("啟用懸浮翻譯")), 10000); assertNotNull(start); start.click();
        UiObject2 consent = device.wait(Until.findObject(By.res("android:id/button1")), 10000); assertNotNull(consent); consent.click();
        await(() -> OverlayService.running, 10000, "Service did not start");
        context.startActivity(new Intent(context, DemoActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(device.wait(Until.findObject(By.text("SCREENLINGO  /  PRACTICE")), 10000));
        SystemClock.sleep(1200);
    }
    private void startRecording() throws Exception {
        // Exercise the floating menu the user will use from a game.
        Rect screen = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        int size = Ui.dp(context, new Prefs(context).size());
        Point ball = TestBall.center(context); int x = ball.x, y = ball.y;
        device.swipe(x, y, x, y, 100);
        UiObject2 record = device.wait(Until.findObject(By.text("開始錄影（可同時翻譯）")), 5000); assertNotNull(record); record.click();
        await(() -> OverlayService.recording && !OverlayService.recordingBusy, 10000, "Recording did not start");
        assertNotNull(device.wait(Until.findObject(By.desc("停止錄影並儲存")), 5000));
    }
    private Uri stopRecording() throws Exception {
        UiObject2 stop = device.wait(Until.findObject(By.desc("停止錄影並儲存")), 5000); assertNotNull(stop); stop.click();
        await(() -> !OverlayService.recording && !OverlayService.recordingBusy, 12000, "Recording did not finish");
        return Uri.parse(new Prefs(context).data.getString("lastVideo", ""));
    }
    @Test public void silentVideoContainsOriginalAndTranslatedFramesAndCanRecordAgain() throws Exception {
        enable(false); startRecording(); SystemClock.sleep(1700);
        Rect screen = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        int size = Ui.dp(context, new Prefs(context).size());
        Point ball = TestBall.center(context); device.click(ball.x, ball.y);
        assertNotNull("Translation must work during recording", device.wait(Until.findObject(By.desc("原位翻譯畫面，點譯文查看全文，點空白還原")), 150000));
        SystemClock.sleep(2000); long translatedTime = (SystemClock.elapsedRealtime() - OverlayService.recordingStartedAt - 500) * 1000;
        assertTrue("Translation must not stop recording", OverlayService.recording);
        Uri uri = stopRecording(); assertTrue("Stopping recording must preserve floating translation", OverlayService.running);
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, uri);
            assertTrue(Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 3000);
            Bitmap before = reader.getFrameAtTime(700000, MediaMetadataRetriever.OPTION_CLOSEST);
            Bitmap after = reader.getFrameAtTime(translatedTime, MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull(before); assertNotNull(after);
            TextRecognizer ocr = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try {
                String first = Tasks.await(ocr.process(InputImage.fromBitmap(before, 0))).getText();
                String second = Tasks.await(ocr.process(InputImage.fromBitmap(after, 0))).getText();
                assertTrue("Original video frame should show English: " + first, first.contains("curiosity") || first.contains("world"));
                assertFalse("Recording startup notices must not cover or become part of captured images: " + first,
                    first.contains("已開始") || first.contains("靜音錄影") || first.contains("照常按球"));
                assertTrue("Encoded video must contain Chinese overlay: " + second, second.codePoints().filter(c -> c >= 0x3400 && c <= 0x9fff).count() > 10);
                assertFalse("Translated video frame must cover the English title", second.contains("curiosity"));
                save(before, "recording-before.png"); save(after, "recording-translated.png");
            } finally { ocr.close(); before.recycle(); after.recycle(); }
        } finally { reader.release(); }
        MediaExtractor extractor = new MediaExtractor();
        try { extractor.setDataSource(context, uri, null); assertEquals("Silent recording must have one video track", 1, extractor.getTrackCount()); }
        finally { extractor.release(); }
        device.pressBack();
        startRecording(); SystemClock.sleep(1700);
        long started = OverlayService.recordingStartedAt;
        device.setOrientationLeft();
        SystemClock.sleep(1800);
        assertTrue("Rotation must keep recording", OverlayService.recording);
        assertEquals("Rotation must not restart the timer", started, OverlayService.recordingStartedAt);
        assertEquals("Rotation must not publish a partial video", uri.toString(), new Prefs(context).data.getString("lastVideo", ""));
        stopRecording();
        Uri second = Uri.parse(new Prefs(context).data.getString("lastVideo", "")); assertNotEquals(uri, second);
        reader = new MediaMetadataRetriever();
        try { reader.setDataSource(context, second); assertTrue(Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 500); }
        finally { reader.release(); }
        device.setOrientationNatural(); SystemClock.sleep(1200);
        assertTrue("Rotation must preserve the floating service", OverlayService.running);
        context.startService(new Intent(context, OverlayService.class).setAction(OverlayService.RECORD_START));
        await(() -> OverlayService.recording, 10000, "Recording must restart after rotation"); SystemClock.sleep(1200);
        context.startService(new Intent(context, OverlayService.class).setAction(OverlayService.STOP));
        await(() -> !OverlayService.running, 12000, "Stop must save and terminate service");
        assertNotEquals(second.toString(), new Prefs(context).data.getString("lastVideo", ""));
    }
    private void scene(int orientation, String label) throws Exception {
        context.startActivity(new Intent().setComponent(new ComponentName("tw.screenlingo.test", RotationSceneActivity.class.getName()))
            .putExtra("orientation", orientation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        assertNotNull(device.wait(Until.findObject(By.desc(label)), 10000)); SystemClock.sleep(1500);
    }
    @Test public void landscapePortraitLandscapeStayInOneFileWithCorrectFrames() throws Exception {
        enable(false);
        scene(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "LANDSCAPE SCENE");
        String previous = new Prefs(context).data.getString("lastVideo", "");
        startRecording(); long started = OverlayService.recordingStartedAt; SystemClock.sleep(1800);
        long firstTime = (SystemClock.elapsedRealtime() - started - 400) * 1000;
        scene(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "PORTRAIT SCENE");
        long portraitTime = (SystemClock.elapsedRealtime() - started - 400) * 1000;
        assertTrue(OverlayService.recording); assertEquals(started, OverlayService.recordingStartedAt);
        assertEquals(previous, new Prefs(context).data.getString("lastVideo", ""));
        Point ball = TestBall.center(context); device.click(ball.x, ball.y);
        assertNotNull("Capture/translation must work in portrait while the video stays landscape", device.wait(Until.findObject(By.desc("原位翻譯畫面，點譯文查看全文，點空白還原")), 150000));
        Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(screen);
        try { assertColor(RotationSceneActivity.BLUE, screen.getPixel(screen.getWidth() / 5, screen.getHeight() * 7 / 10)); }
        finally { screen.recycle(); }
        device.pressBack();
        scene(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, "LANDSCAPE SCENE");
        assertTrue(OverlayService.recording); assertEquals(started, OverlayService.recordingStartedAt);
        long lastTime = (SystemClock.elapsedRealtime() - started - 400) * 1000;
        Uri saved = stopRecording(); MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, saved);
            assertTrue(Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)) > Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
            Bitmap first = reader.getFrameAtTime(firstTime, MediaMetadataRetriever.OPTION_CLOSEST);
            Bitmap portrait = reader.getFrameAtTime(portraitTime, MediaMetadataRetriever.OPTION_CLOSEST);
            Bitmap last = reader.getFrameAtTime(lastTime, MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull(first); assertNotNull(portrait); assertNotNull(last);
            try {
                assertEquals(first.getWidth(), portrait.getWidth()); assertEquals(first.getHeight(), last.getHeight());
                assertColor(RotationSceneActivity.GREEN, first.getPixel(first.getWidth() / 5, first.getHeight() * 7 / 10));
                assertColor(RotationSceneActivity.BLUE, portrait.getPixel(portrait.getWidth() / 2, portrait.getHeight() * 7 / 10));
                assertColor(Color.BLACK, portrait.getPixel(portrait.getWidth() / 10, portrait.getHeight() * 7 / 10));
                assertColor(RotationSceneActivity.GREEN, last.getPixel(last.getWidth() / 5, last.getHeight() * 7 / 10));
                save(first, "rotation-landscape-before.png"); save(portrait, "rotation-portrait.png"); save(last, "rotation-landscape-after.png");
            } finally { first.recycle(); portrait.recycle(); last.recycle(); }
        } finally { reader.release(); }
    }
    private void assertColor(int expected, int actual) {
        assertTrue("Wrong frame color: " + Integer.toHexString(actual) + " expected " + Integer.toHexString(expected),
            Math.abs(Color.red(expected) - Color.red(actual)) < 35 && Math.abs(Color.green(expected) - Color.green(actual)) < 35 && Math.abs(Color.blue(expected) - Color.blue(actual)) < 35);
    }
    @Test public void gamePlaybackAudioIsEncodedWithoutMicrophone() throws Exception {
        enable(true);
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        short[] tone = new short[44100 * 2];
        for (int i = 0; i < 44100; i++) tone[i * 2] = tone[i * 2 + 1] = (short) (Math.sin(i * 2 * Math.PI * 440 / 44100) * 10000);
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(tone.length * 2).build();
        try {
            track.write(tone, 0, tone.length); track.setLoopPoints(0, 44100, -1); track.play();
            startRecording(); SystemClock.sleep(1500);
            Rect screen = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            int size = Ui.dp(context, new Prefs(context).size());
            Point ball = TestBall.center(context); device.click(ball.x, ball.y);
            assertNotNull("Translation must work during game audio recording", device.wait(Until.findObject(By.desc("原位翻譯畫面，點譯文查看全文，點空白還原")), 150000));
            SystemClock.sleep(1500); Uri uri = stopRecording();
            MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null;
            try {
                extractor.setDataSource(context, uri, null); int audioTrack = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) audioTrack = i;
                assertTrue("AAC game audio track is missing", audioTrack >= 0);
                MediaFormat input = extractor.getTrackFormat(audioTrack); extractor.selectTrack(audioTrack);
                decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)); decoder.configure(input, null, null, 0); decoder.start();
                boolean inputEnd = false; long loudSamples = 0; MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    int slot = inputEnd ? -1 : decoder.dequeueInputBuffer(1000);
                    if (slot >= 0) {
                        ByteBuffer data = decoder.getInputBuffer(slot); assertNotNull(data); data.clear(); int count = extractor.readSampleData(data, 0);
                        if (count < 0) { decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true; }
                        else { decoder.queueInputBuffer(slot, 0, count, extractor.getSampleTime(), 0); extractor.advance(); }
                    }
                    int output = decoder.dequeueOutputBuffer(info, 1000);
                    if (output >= 0) {
                        ByteBuffer pcm = decoder.getOutputBuffer(output); assertNotNull(pcm); pcm.order(ByteOrder.LITTLE_ENDIAN); pcm.position(info.offset); pcm.limit(info.offset + info.size);
                        while (pcm.remaining() >= 2) if (Math.abs((int) pcm.getShort()) > 500) loudSamples++;
                        decoder.releaseOutputBuffer(output, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    }
                }
                assertTrue("The audio track must contain actual game playback, not only silence", loudSamples > 1000);
            } finally { extractor.release(); if (decoder != null) { decoder.stop(); decoder.release(); } }
        } finally { track.stop(); track.release(); }
    }
    private void save(Bitmap image, String name) throws Exception {
        try (OutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    private void await(BooleanSupplier condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue(message, condition.getAsBoolean());
    }
}
