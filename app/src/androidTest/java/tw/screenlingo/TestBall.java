package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import android.view.*;

final class TestBall {
    static Point center(Context context) {
        WindowMetrics metrics = context.getSystemService(WindowManager.class).getMaximumWindowMetrics();
        Rect screen = metrics.getBounds();
        Insets safe = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Prefs prefs = new Prefs(context); int size = Ui.dp(context, prefs.size()), half = (size + 1) / 2;
        boolean right = prefs.data.getFloat("ballX", .95f) >= .5f;
        int x = right ? screen.width() - safe.right - half / 2 : safe.left + half / 2;
        int y = Math.max(safe.top, Math.min(Math.round(prefs.data.getFloat("ballY", .45f) * (screen.height() - size)), screen.height() - size - safe.bottom)) + size / 2;
        return new Point(x, y);
    }
}
