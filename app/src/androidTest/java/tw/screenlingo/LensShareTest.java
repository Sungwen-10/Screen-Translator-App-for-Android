package tw.screenlingo;

import android.content.*;
import android.graphics.*;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LensShareTest {
    @Test public void sharesPngAcrossAppUidWithTemporaryReadOnlyPermission() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap bitmap = Bitmap.createBitmap(17, 23, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.MAGENTA);
        Intent share;
        try { share = LensShare.imageIntent(context, bitmap); } finally { bitmap.recycle(); }
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("image/png", share.getType());
        assertEquals(0, share.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertEquals("content", share.getClipData().getItemAt(0).getUri().getScheme());
        share.setComponent(new ComponentName("tw.screenlingo.test", ShareReceiverActivity.class.getName()));
        context.startActivity(share);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertNotNull("Recipient with another UID must read the PNG", device.wait(Until.findObject(By.text("Shared PNG: 17x23:" + Color.MAGENTA)), 10000));
        device.pressBack();
    }
    @Test public void providerDoesNotExposeOtherCacheOrPrivateFiles() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context,
            context.getPackageName() + ".files", new java.io.File(context.getCacheDir(), "other-private.png")));
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context,
            context.getPackageName() + ".files", new java.io.File(context.getFilesDir(), "private.txt")));
    }
}
