package tw.screenlingo;

import android.content.*;
import android.graphics.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.*;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class ThaiModelsTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    @Test public void aDownloadManagerVerifiesModelAndUiBatchDownloadsFrenchAndThai() throws Exception {
        context.stopService(new Intent(context, OverlayService.class));
        OcrModels models = new OcrModels(context); models.removeThai();
        try { models.installThai(new ByteArrayInputStream(new byte[]{1, 2, 3})); fail("Corrupt OCR data must be rejected"); }
        catch (IOException expected) { assertFalse(models.thaiReady()); }
        try { models.installThai(new ByteArrayInputStream(new byte[(int) OcrModels.THAI_BYTES])); fail("Wrong SHA-256 must be rejected even at the correct length"); }
        catch (IOException expected) { assertFalse(models.thaiReady()); }
        TranslationEngine missingEngine = new TranslationEngine(context); CountDownLatch missing = new CountDownLatch(1); String[] missingError = new String[1];
        try {
            missingEngine.run(thaiImage(), "th", "th", "zh-Hant", false, new TranslationEngine.Callback() {
                public void progress(String text) { }
                public void success(TranslationEngine.Result value) { missing.countDown(); }
                public void failure(String text) { missingError[0] = text; missing.countDown(); }
            });
            assertTrue(missing.await(10, TimeUnit.SECONDS)); assertNotNull(missingError[0]); assertTrue(missingError[0].contains("選擇及下載"));
        } finally { missingEngine.close(); }
        new Prefs(context).data.edit().putBoolean("wifi", false).commit();
        context.startActivity(new Intent(context, ModelsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        UiObject2 download = device.wait(Until.findObject(By.text("下載泰文辨識模型（約 1.1 MB）")), 10000);
        assertNotNull(download); download.click();
        long deadline = SystemClock.elapsedRealtime() + 120000;
        while (!models.state().ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500);
        assertTrue("Actual HTTPS OCR download failed: " + models.state().status(), models.thaiReady());
        assertEquals(OcrModels.THAI_BYTES, models.thaiFile().length());
        // First rows are deliberately Chinese/French/Thai; use the actual multi-select page.
        UiScrollable scroll = new UiScrollable(new UiSelector().scrollable(true));
        scroll.scrollIntoView(new UiSelector().textContains("法文 /"));
        UiObject2 french = device.wait(Until.findObject(By.clazz("android.widget.CheckBox").textContains("法文 /")), 10000);
        UiObject2 thai = device.wait(Until.findObject(By.clazz("android.widget.CheckBox").textContains("泰文 /")), 10000);
        assertNotNull(french); assertNotNull(thai); french.click(); thai.click();
        device.waitForIdle();
        assertTrue("French checkbox was not selected", french.isChecked());
        assertTrue("Thai checkbox was not selected", thai.isChecked());
        device.findObject(By.text("下載選取的翻譯模型")).click();
        deadline = SystemClock.elapsedRealtime() + 10000;
        while (TranslationModelJobs.message.startsWith("勾選") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertFalse("Download click did not start a batch", TranslationModelJobs.message.startsWith("勾選"));
        deadline = SystemClock.elapsedRealtime() + 270000;
        while (TranslationModelJobs.busy && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500);
        assertFalse("Batch download timeout", TranslationModelJobs.busy);
        RemoteModelManager manager = RemoteModelManager.getInstance();
        for (String tag : List.of("fr", "th")) assertTrue(tag + " model missing: " + TranslationModelJobs.message,
            Tasks.await(manager.isModelDownloaded(new TranslateRemoteModel.Builder(tag).build())));
        // Deletion and re-download exercise the same validated install path.
        models.removeThai(); assertFalse(models.thaiReady()); models.downloadThai(false);
        deadline = SystemClock.elapsedRealtime() + 120000;
        while (!models.state().ready() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(500);
        assertTrue("OCR re-download failed", models.thaiReady());
        scroll.scrollToBeginning(20); device.waitForIdle();
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "models-overview.png"));
        scroll.scrollIntoView(new UiSelector().textContains("法文 /")); device.waitForIdle();
        device.takeScreenshot(new File(context.getExternalFilesDir(null), "models-selection.png"));
    }

    @Test public void bFrenchAndThaiScreensTranslateOfflineWithAutomaticThaiChoice() throws Exception {
        assertTrue(new OcrModels(context).thaiReady());
        try (ThaiOcr diagnostic = new ThaiOcr()) {
            for (Bitmap fixture : List.of(thaiImage(), frenchImage())) {
                ThaiOcr.Page page = diagnostic.recognize(fixture, new OcrModels(context));
                System.out.println("Thai OCR confidence=" + page.confidence() + " text=" + page.text()); fixture.recycle();
            }
        }
        device.executeShellCommand("svc wifi disable"); device.executeShellCommand("svc data disable");
        try {
            TranslationEngine engine = new TranslationEngine(context);
            try {
                TranslationEngine.Result thai = translate(engine, thaiImage(), "th", "auto");
                String original = thai.blocks().stream().map(b -> b.original).reduce("", (a,b) -> a+b);
                assertTrue("Expected recognized Thai greeting: " + original, original.contains("สวัสดี"));
                TranslationEngine.Result auto = translate(engine, thaiImage(), "auto", "auto");
                assertTrue("Auto must select Thai OCR", auto.blocks().stream().anyMatch(b -> "th".equals(b.language)));
                translate(engine, frenchImage(), "latin", "fr");
                TranslationEngine.Result frenchAuto = translate(engine, frenchImage(), "auto", "auto");
                assertTrue("Downloaded Thai OCR must not take over a French page: " + frenchAuto.blocks().stream().map(b -> b.language + ":" + b.original).toList(), frenchAuto.blocks().stream().anyMatch(b -> "fr".equals(b.language)));
            } finally { engine.close(); }
        } finally { device.executeShellCommand("svc wifi enable"); device.executeShellCommand("svc data enable"); }
    }
    private TranslationEngine.Result translate(TranslationEngine engine, Bitmap image, String script, String source) throws Exception {
        int width = image.getWidth(), height = image.getHeight();
        Bitmap snapshot = image.copy(Bitmap.Config.ARGB_8888, false);
        CountDownLatch done = new CountDownLatch(1); String[] error = new String[1];
        TranslationEngine.Result[] result = new TranslationEngine.Result[1]; List<String> progress = new ArrayList<>();
        engine.run(image, script, source, "zh-Hant", true, new TranslationEngine.Callback() {
            public void progress(String text) { progress.add(text); }
            public void success(TranslationEngine.Result value) { result[0] = value; done.countDown(); }
            public void failure(String text) { error[0] = text; done.countDown(); }
        });
        assertTrue("Offline translation timed out", done.await(90, TimeUnit.SECONDS)); assertNull(error[0]); assertNotNull(result[0]);
        assertFalse(result[0].blocks().isEmpty());
        assertFalse("Installed models should not download", progress.stream().anyMatch(s -> s.contains("下載")));
        String translated = result[0].blocks().stream().map(b -> b.translated).reduce("", (a,b) -> a+b);
        System.out.println("Offline " + script + " -> Chinese: " + translated);
        assertTrue("Expected Chinese translation: " + translated, translated.codePoints().anyMatch(c -> c >= 0x3400 && c <= 0x9fff));
        for (TranslationEngine.Block block : result[0].blocks()) assertTrue(new Rect(0, 0, width, height).contains(block.bounds));
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TranslationOverlay view = new TranslationOverlay(context, result[0], snapshot, new TranslationOverlay.Listener() {
                public void block(TranslationEngine.Block b) { }
                public void dismiss() { }
            });
            view.layout(0, 0, width, height); view.draw(new Canvas(output));
        });
        String imageName = result[0].blocks().stream().anyMatch(b -> "fr".equals(b.language)) ? "french-translated.png" : "thai-translated.png";
        try (OutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), imageName))) {
            output.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { output.recycle(); snapshot.recycle(); }
        return result[0];
    }
    static Bitmap thaiImage() { return image(new String[]{"สวัสดี ยินดีต้อนรับ", "กรุณากดปุ่มเพื่อเริ่มต้น", "ขอบคุณมาก"}); }
    static Bitmap frenchImage() { return image(new String[]{"Bonjour tout le monde.", "Bienvenue dans notre application.", "Appuyez ici pour commencer."}); }
    private static Bitmap image(String[] lines) {
        Bitmap image = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(48);
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 55, 150 + i * 150, paint);
        return image;
    }
}
