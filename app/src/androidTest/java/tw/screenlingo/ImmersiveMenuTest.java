package tw.screenlingo;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import static org.junit.Assert.*;

/** Real capture/service regression for text inside the hidden navigation-bar strip. */
@RunWith(AndroidJUnit4.class)
public class ImmersiveMenuTest {
    @Test public void hiddenNavigationBarDoesNotExcludeBottomMenu() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.wakeUp(); device.setOrientationNatural();
        context.stopService(new Intent(context, OverlayService.class));
        new Prefs(context).data.edit().putString("mode", "offline").putString("source", "en")
            .putString("script", "latin").putString("target", "zh-Hant").putBoolean("resident", true)
            .putBoolean("wifi", false).remove("ballX").remove("ballY").commit();
        device.executeShellCommand("appops set tw.screenlingo SYSTEM_ALERT_WINDOW allow");
        device.executeShellCommand("pm grant tw.screenlingo android.permission.POST_NOTIFICATIONS");
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            UiObject2 start = device.wait(Until.findObject(By.text("啟用懸浮翻譯")), 10000); assertNotNull(start); start.click();
            UiObject2 consent = device.wait(Until.findObject(By.res("android:id/button1")), 10000); assertNotNull(consent); consent.click();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!OverlayService.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            assertTrue(OverlayService.running);
            context.startActivity(new Intent().setClassName("tw.screenlingo.test", RotationSceneActivity.class.getName())
                .putExtra("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                .putExtra("edgeLabel", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertNotNull(device.wait(Until.findObject(By.desc("IMMERSIVE MENU")), 10000)); SystemClock.sleep(1200);
            UiObject2 introduction = device.wait(Until.findObject(By.text("Got it")), 2000);
            if (introduction != null) { introduction.click(); assertTrue(device.wait(Until.gone(By.text("Viewing full screen")), 5000)); }
            device.waitForIdle();
            File originalFile = new File(context.getExternalFilesDir(null), "immersive-menu-original.png"); device.takeScreenshot(originalFile);
            Bitmap original = BitmapFactory.decodeFile(originalFile.getPath());
            TextRecognizer sourceReader = TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
            try {
                String sourceText = Tasks.await(sourceReader.process(InputImage.fromBitmap(original, 0))).getText();
                assertTrue("Fixture bottom label must be readable before translation: " + sourceText, sourceText.toLowerCase(java.util.Locale.ROOT).contains("settings"));
            } finally { sourceReader.close(); original.recycle(); }
            Point ball = TestBall.center(context); device.click(ball.x, ball.y);
            assertNotNull(device.wait(Until.findObject(By.desc("原位翻譯畫面，點譯文查看全文，點空白還原")), 150000));
            File image = new File(context.getExternalFilesDir(null), "immersive-menu-translated.png"); device.takeScreenshot(image);
            Bitmap screen = BitmapFactory.decodeFile(image.getPath()); assertNotNull(screen);
            Bitmap bottom = Bitmap.createBitmap(screen, 0, screen.getHeight() - 70, screen.getWidth(), 70);
            TextRecognizer reader = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            try {
                String translated = Tasks.await(reader.process(InputImage.fromBitmap(bottom, 0))).getText();
                assertTrue("Bottom menu should contain Chinese, not be excluded as system navigation: " + translated,
                    translated.codePoints().anyMatch(c -> TextHeuristics.inScript(c, "zh")));
            } finally { reader.close(); bottom.recycle(); screen.recycle(); }
        } finally {
            context.stopService(new Intent(context, OverlayService.class)); device.pressHome();
            device.setOrientationNatural(); device.unfreezeRotation();
        }
    }
}
