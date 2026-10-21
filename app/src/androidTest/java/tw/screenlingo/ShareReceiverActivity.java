package tw.screenlingo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test-APK UID verifies that the temporary URI grant really permits reading. */
public class ShareReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView result = new TextView(this);
        try {
            Uri uri = getIntent().getParcelableExtra(android.content.Intent.EXTRA_STREAM);
            try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                result.setText("Shared PNG: " + bitmap.getWidth() + "x" + bitmap.getHeight() + ":" + bitmap.getPixel(0, 0));
                bitmap.recycle();
            }
        } catch (Exception e) { result.setText("Share failed: " + e); }
        setContentView(result);
    }
}
