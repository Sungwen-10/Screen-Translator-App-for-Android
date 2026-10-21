package tw.screenlingo;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AttributionBadgeTest {
    @Test public void dragRemainsVisibleAndRemembersEachOrientationWithoutClicking() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Prefs prefs = new Prefs(context);
            String[] keys = {"attributionLandscapeX", "attributionLandscapeY", "attributionPortraitX", "attributionPortraitY"};
            Float[] saved = new Float[keys.length];
            for (int i = 0; i < keys.length; i++) if (prefs.data.contains(keys[i])) saved[i] = prefs.data.getFloat(keys[i], 0);
            for (String key : keys) prefs.data.edit().remove(key).commit();
            try {
                FrameLayout parent = new FrameLayout(context);
                ImageView badge = AttributionBadge.add(parent, prefs);
                int[] clicks = {0}; badge.setOnClickListener(v -> clicks[0]++);
                layout(parent, 1600, 900);
                assertTrue("Default attribution should leave the bottom menu clear", badge.getBottom() < 900 / 2);
                assertTrue("Default right edge: " + badge.getLeft() + "," + badge.getTop() + "," + badge.getWidth(), badge.getLeft() > 1600 / 2);
                drag(badge, -10000, 10000);
                layout(parent, 1600, 900);
                assertEquals("Dragging must not open Google", 0, clicks[0]);
                assertTrue(badge.getLeft() >= 0); assertTrue(badge.getBottom() <= 900);
                assertTrue(badge.getRight() < 1600 / 2); assertTrue(badge.getTop() > 900 / 2);
                assertEquals(0f, prefs.data.getFloat("attributionLandscapeX", -1), .001f);
                assertEquals(1f, prefs.data.getFloat("attributionLandscapeY", -1), .001f);

                FrameLayout reopened = new FrameLayout(context);
                ImageView second = AttributionBadge.add(reopened, prefs);
                layout(reopened, 1600, 900);
                assertEquals(badge.getLeft(), second.getLeft()); assertEquals(badge.getTop(), second.getTop());
                layout(reopened, 900, 1600);
                assertTrue("Portrait gets its own default", second.getTop() < 100);
                assertTrue(second.getLeft() > 300);
                drag(second, -10000, 10000); layout(reopened, 900, 1600);
                layout(reopened, 1800, 1000);
                assertTrue("Landscape position survives aspect ratio changes", second.getLeft() < 100 && second.getTop() > 500);
                assertTrue(second.getRight() <= 1800 && second.getBottom() <= 1000);

                long time = SystemClock.uptimeMillis();
                event(badge, time, MotionEvent.ACTION_DOWN, 10, 10);
                event(badge, time, MotionEvent.ACTION_UP, 10, 10);
                assertEquals("A normal tap retains attribution link behavior", 1, clicks[0]);
            } finally {
                for (int i = 0; i < keys.length; i++) {
                    if (saved[i] == null) prefs.data.edit().remove(keys[i]).commit();
                    else prefs.data.edit().putFloat(keys[i], saved[i]).commit();
                }
            }
        });
    }
    private static void layout(FrameLayout parent, int width, int height) {
        // Parent layout determines the remembered position; a second pass lays out the updated margins.
        for (int i = 0; i < 2; i++) {
            parent.forceLayout();
            for (int child = 0; child < parent.getChildCount(); child++) parent.getChildAt(child).forceLayout();
            parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            parent.layout(0, 0, width, height);
        }
    }
    private static void drag(View badge, float x, float y) {
        long time = SystemClock.uptimeMillis();
        event(badge, time, MotionEvent.ACTION_DOWN, 10, 10);
        event(badge, time, MotionEvent.ACTION_MOVE, x, y);
        event(badge, time, MotionEvent.ACTION_UP, x, y);
    }
    private static void event(View view, long time, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(time, time + 16, action, x, y, 0);
        view.dispatchTouchEvent(e); e.recycle();
    }
}
