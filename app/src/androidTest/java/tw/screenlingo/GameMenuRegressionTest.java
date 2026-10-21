package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Private fixture is pushed separately, never bundled into the APK/source archive. */
@RunWith(AndroidJUnit4.class)
public class GameMenuRegressionTest {
    @Test public void outlinedMenusRecovered() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir(null);
        String fixture = InstrumentationRegistry.getArguments().getString("fixture", "game-menu-original.png");
        Bitmap original = BitmapFactory.decodeFile(new File(directory, fixture).getPath());
        Assume.assumeNotNull(original);
        androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context.stopService(new android.content.Intent(context, OverlayService.class)); device.waitForIdle();
        device.setOrientationLandscape(); device.waitForIdle();
        context.startActivity(new android.content.Intent().setClassName("tw.screenlingo.test", RotationSceneActivity.class.getName())
            .putExtra("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("LANDSCAPE SCENE")), 10000));
        TranslationEngine engine = new TranslationEngine(context); CountDownLatch done = new CountDownLatch(1);
        TranslationEngine.Result[] result = new TranslationEngine.Result[1]; String[] failure = new String[1];
        long started = android.os.SystemClock.elapsedRealtime();
        engine.run(original.copy(Bitmap.Config.ARGB_8888, false), "auto", "auto", "zh-Hant", false, new TranslationEngine.Callback() {
            public void progress(String text) { }
            public void success(TranslationEngine.Result value) { result[0] = value; done.countDown(); }
            public void failure(String message) { failure[0] = message; done.countDown(); }
        });
        try {
            assertTrue(done.await(240, TimeUnit.SECONDS)); assertNull(failure[0]); assertNotNull(result[0]);
            JSONArray readings = new JSONArray(), regions = new JSONArray();
            for (TranslationEngine.Block block : result[0].blocks()) readings.put(new JSONObject().put("text", block.original).put("translated", block.translated)
                .put("rect", block.bounds.flattenToString()).put("language", block.language).put("confidence", block.confidence));
            for (OcrEnhancer.Region region : OcrEnhancer.regions(original)) regions.put(new JSONObject().put("rect", region.bounds().flattenToString()).put("ink", region.ink()));
            JSONObject report = new JSONObject().put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started).put("blocks", readings).put("regions", regions);
            try (FileWriter writer = new FileWriter(new File(directory, "game-menu-result.json"))) { writer.write(report.toString(2)); }
            Context overlayContext = context.createDisplayContext(context.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)).createWindowContext(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            Rect screen = overlayContext.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            Bitmap rendered = Bitmap.createBitmap(screen.width(), screen.height(), Bitmap.Config.ARGB_8888);
            new Canvas(rendered).drawBitmap(original, null, new Rect(0, 0, rendered.getWidth(), rendered.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TranslationOverlay overlay = new TranslationOverlay(overlayContext, result[0], new TranslationOverlay.Listener() {
                    public void block(TranslationEngine.Block block) { } public void dismiss() { }
                });
                overlay.measure(android.view.View.MeasureSpec.makeMeasureSpec(rendered.getWidth(), android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(rendered.getHeight(), android.view.View.MeasureSpec.EXACTLY));
                overlay.layout(0, 0, rendered.getWidth(), rendered.getHeight()); overlay.draw(new Canvas(rendered));
            });
            try (FileOutputStream output = new FileOutputStream(new File(directory, "game-menu-rendered.png"))) { rendered.compress(Bitmap.CompressFormat.PNG, 100, output); }
            rendered.recycle();
            // These labels were absent in the old overlay. Blue outlined lettering and
            // the gift caption remain documented model limitations, not claimed fixes.
            for (String word : new String[]{"シナリオ", "チーム", "ショップ", "チャット", "コレクション", "メニュー"}) {
                assertTrue("Missing OCR word: " + word + "\n" + readings, result[0].blocks().stream().anyMatch(b -> b.original.contains(word)));
                assertTrue("No translation for: " + word, result[0].blocks().stream().anyMatch(b -> b.original.contains(word) && b.changed()));
            }
            for (int i = 0; i < result[0].blocks().size(); i++) for (int j = i + 1; j < result[0].blocks().size(); j++)
                assertFalse("Overlay labels overlap", Rect.intersects(result[0].blocks().get(i).bounds, result[0].blocks().get(j).bounds));
            assertFalse("Neighbouring buttons must stay separate", result[0].blocks().stream()
                .anyMatch(b -> b.original.contains("チャット") && b.original.contains("コレクション")));
        } finally { engine.close(); original.recycle(); device.pressHome(); device.setOrientationNatural(); device.unfreezeRotation(); }
    }
}
