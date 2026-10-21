package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** The user-supplied fixture stays on the test device, never inside the application package. */
@RunWith(AndroidJUnit4.class)
public class GameRegressionTest {
    @Test public void translateProvidedGamePhoto() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir(null);
        Bitmap original = BitmapFactory.decodeFile(new File(directory, "game-original.png").getPath());
        org.junit.Assume.assumeTrue("Optional regression: push game-original.png to the app external files directory first", original != null);
        CountDownLatch latch = new CountDownLatch(1);
        TranslationEngine.Result[] result = new TranslationEngine.Result[1]; String[] failure = new String[1];
        TranslationEngine engine = new TranslationEngine(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext());
        engine.run(original.copy(Bitmap.Config.ARGB_8888, false), "auto", "auto", "zh-Hant", false,
            new Rect(0, 136, original.getWidth(), original.getHeight() - 66), new TranslationEngine.Callback() {
            public void progress(String text) { }
            public void success(TranslationEngine.Result value) { result[0] = value; latch.countDown(); }
            public void failure(String text) { failure[0] = text; latch.countDown(); }
        });
        try {
            assertTrue("Translation timeout", latch.await(240, TimeUnit.SECONDS));
            assertNull(failure[0]); assertNotNull(result[0]);
            JSONArray blocks = new JSONArray();
            for (TranslationEngine.Block b : result[0].blocks()) {
                blocks.put(new JSONObject().put("rect", b.bounds.flattenToString()).put("original", b.original)
                    .put("translated", b.translated).put("language", b.language));
            }
            try (FileWriter writer = new FileWriter(new File(directory, "game-result.json"))) { writer.write(blocks.toString(2)); }
            assertTrue("Japanese source text was not read", result[0].blocks().stream().anyMatch(b -> b.original.contains("シュナイダー")));
            assertFalse("Skill heading and character names must not be translated as one sentence",
                result[0].blocks().stream().anyMatch(b -> b.original.contains("人組") && b.original.contains("マーガス")));
            for (int i = 0; i < result[0].blocks().size(); i++) for (int j = i + 1; j < result[0].blocks().size(); j++) {
                assertFalse("OCR blocks must be merged or separated before drawing", Rect.intersects(result[0].blocks().get(i).bounds, result[0].blocks().get(j).bounds));
            }
            Bitmap rendered = original.copy(Bitmap.Config.ARGB_8888, true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                TranslationOverlay overlay = new TranslationOverlay(context, result[0], new TranslationOverlay.Listener() {
                    public void block(TranslationEngine.Block b) { }
                    public void dismiss() { }
                });
                overlay.measure(android.view.View.MeasureSpec.makeMeasureSpec(rendered.getWidth(), android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(rendered.getHeight(), android.view.View.MeasureSpec.EXACTLY));
                overlay.layout(0, 0, rendered.getWidth(), rendered.getHeight()); overlay.draw(new Canvas(rendered));
            });
            try (FileOutputStream file = new FileOutputStream(new File(directory, "game-rendered.png"))) { rendered.compress(Bitmap.CompressFormat.PNG, 100, file); }
            rendered.recycle();
        } finally { engine.close(); original.recycle(); }
    }
}
