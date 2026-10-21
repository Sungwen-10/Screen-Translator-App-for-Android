package tw.screenlingo;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadingViewportTest {
    @Test public void pinchKeepsTheSameTextUnderTheFingers() {
        ReadingViewport v = new ReadingViewport(); v.resize(1000, 2000);
        v.scaleBy(2, 400, 600);
        assertEquals(2, v.scale(), .001);
        assertEquals(400, v.contentX(400), .001); assertEquals(600, v.contentY(600), .001);
    }
    @Test public void zoomAndPanStayWithinTheFrozenScreen() {
        ReadingViewport v = new ReadingViewport(); v.resize(1000, 2000); v.scaleBy(10, 500, 1000);
        assertEquals(4, v.scale(), .001);
        v.panBy(-100000, -100000); assertEquals(-3000, v.x(), .001); assertEquals(-6000, v.y(), .001);
        v.panBy(100000, 100000); assertEquals(0, v.x(), .001); assertEquals(0, v.y(), .001);
        v.scaleBy(.01f, 500, 1000); assertEquals(1, v.scale(), .001); assertEquals(0, v.x(), .001);
    }
}
