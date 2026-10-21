package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ZoomGestureTest {
    @Test public void pinchPanAndTranslatedTapUseTheSameCoordinates() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Rect screen = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            int width = screen.width(), height = screen.height(); float cx = width / 2f, cy = height / 2f;
            Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            snapshot.eraseColor(Color.LTGRAY);
            TranslationEngine.Block block = new TranslationEngine.Block(new Rect((int)cx-150, (int)cy-50, (int)cx+150, (int)cy+50), "Hello", Color.WHITE);
            block.translated = "你好，世界";
            int[] taps = {0}, dismisses = {0};
            TranslationOverlay view = new TranslationOverlay(context, new TranslationEngine.Result(List.of(block), width, height, "zh-Hant"), snapshot,
                new TranslationOverlay.Listener() {
                    public void block(TranslationEngine.Block selected) { assertSame(block, selected); taps[0]++; }
                    public void dismiss() { dismisses[0]++; }
                });
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); view.draw(new Canvas(rendered));
            long start = SystemClock.uptimeMillis();
            event(view, start, start, MotionEvent.ACTION_DOWN, cx-100, cy);
            event(view, start, start+16, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx-100, cy, cx+100, cy);
            for (int i = 1; i <= 12; i++) event(view, start, start+16+i*16, MotionEvent.ACTION_MOVE, cx-100-i*18, cy, cx+100+i*18, cy);
            event(view, start, start+240, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx-316, cy, cx+316, cy);
            event(view, start, start+256, MotionEvent.ACTION_UP, cx-316, cy);
            assertTrue("Two fingers should magnify the translated page", view.zoomFactor() > 1.5f);
            assertEquals("Pinching must not dismiss the page", 0, dismisses[0]);
            event(view, start+400, start+400, MotionEvent.ACTION_DOWN, cx+150, cy+150);
            event(view, start+400, start+440, MotionEvent.ACTION_MOVE, cx+190, cy+170);
            event(view, start+400, start+480, MotionEvent.ACTION_UP, cx+190, cy+170);
            assertEquals("Panning must not dismiss the page", 0, dismisses[0]);
            event(view, start+600, start+600, MotionEvent.ACTION_DOWN, cx+40, cy+20);
            event(view, start+600, start+640, MotionEvent.ACTION_UP, cx+40, cy+20);
            assertEquals("The translated block must still be tappable after zoom and pan", 1, taps[0]);
            rendered.recycle(); snapshot.recycle();
        });
    }
    private static void event(View view, long down, long time, int action, float... points) {
        int count = points.length / 2;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = points[i*2]; coordinates[i].y = points[i*2+1]; coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, count, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.dispatchTouchEvent(event); event.recycle();
    }
}
