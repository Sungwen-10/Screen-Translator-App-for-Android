package tw.screenlingo;

import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Run with Wi-Fi and mobile data disabled on the dedicated emulator after installing Chinese. */
@RunWith(AndroidJUnit4.class)
public class CachedModelTest {
    @Test public void installedModelsWorkColdAndWarmWithoutDownloadStatus() throws Exception {
        assertTrue("Install the Chinese model before this offline regression", Tasks.await(RemoteModelManager.getInstance()
            .isModelDownloaded(new TranslateRemoteModel.Builder("zh").build())));
        TranslationEngine engine = new TranslationEngine(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            List<String> messages = new ArrayList<>();
            String first = translate(engine, messages); String second = translate(engine, messages);
            assertEquals(first, second);
            assertTrue(first.codePoints().anyMatch(c -> c >= 0x3400 && c <= 0x9fff));
            assertFalse("Cached models must not be labelled as a download", messages.stream().anyMatch(s -> s.contains("下載")));
            System.out.println("Cached cold/warm translation succeeded; no download status emitted.");
        } finally { engine.close(); }
    }
    private String translate(TranslationEngine engine, List<String> messages) throws Exception {
        Bitmap image = Bitmap.createBitmap(1080, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(52);
        canvas.drawText("Welcome to the new application.", 50, 180, paint);
        CountDownLatch done = new CountDownLatch(1); String[] value = new String[1], error = new String[1];
        engine.run(image, "latin", "en", "zh-Hant", true, new TranslationEngine.Callback() {
            public void progress(String text) { messages.add(text); }
            public void success(TranslationEngine.Result result) { value[0] = result.blocks().stream().map(b -> b.translated).reduce("", (a,b) -> a+b); done.countDown(); }
            public void failure(String text) { error[0] = text; done.countDown(); }
        });
        assertTrue("Offline translation timed out", done.await(30, TimeUnit.SECONDS)); assertNull(error[0]); return value[0];
    }
}
