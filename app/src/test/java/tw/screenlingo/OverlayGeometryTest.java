package tw.screenlingo;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OverlayGeometryTest {
    @Test public void denseGameRowsNeverExpandIntoTheNextSkill() {
        List<OverlayGeometry.Box> original = List.of(new OverlayGeometry.Box(100,100,700,180),
            new OverlayGeometry.Box(100,190,290,220), new OverlayGeometry.Box(300,190,700,220),
            new OverlayGeometry.Box(100,230,700,290));
        List<OverlayGeometry.Box> slots = OverlayGeometry.slots(original, 1080, 2340, 4, 120);
        assertDisjoint(slots);
        for (int i = 0; i < slots.size(); i++) for (int j = 0; j < original.size(); j++) {
            if (i != j) assertFalse(slots.get(i).intersects(original.get(j)));
        }
    }
    @Test public void diagonalExpansionDoesNotCreateANewCollision() {
        assertDisjoint(OverlayGeometry.slots(List.of(new OverlayGeometry.Box(0,0,100,50),
            new OverlayGeometry.Box(110,60,200,100)), 300, 200, 3, 100));
    }
    @Test public void mergedBoundingBoxesAreCheckedAgain() {
        List<OverlayGeometry.Box> boxes = List.of(new OverlayGeometry.Box(0,0,10,30),
            new OverlayGeometry.Box(5,20,30,30), new OverlayGeometry.Box(20,0,25,10));
        assertEquals(1, OverlayGeometry.overlappingGroups(boxes).size());
    }
    private void assertDisjoint(List<OverlayGeometry.Box> boxes) {
        for (int i = 0; i < boxes.size(); i++) for (int j = i+1; j < boxes.size(); j++) {
            assertFalse("Two translation slots overlap", boxes.get(i).intersects(boxes.get(j)));
        }
    }
}
