package tw.screenlingo;

import java.util.*;

/** Collision planning in physical screen coordinates; independent of Android UI classes. */
final class OverlayGeometry {
    record Box(float left, float top, float right, float bottom) {
        float width() { return Math.max(0, right - left); }
        float height() { return Math.max(0, bottom - top); }
        boolean intersects(Box b) { return left < b.right && right > b.left && top < b.bottom && bottom > b.top; }
        Box union(Box b) { return new Box(Math.min(left, b.left), Math.min(top, b.top), Math.max(right, b.right), Math.max(bottom, b.bottom)); }
    }

    /** Every source rectangle remains reserved, including unchanged words and status numbers. */
    static List<Box> slots(List<Box> sources, float width, float height, float gap, float maxExpansion) {
        List<Box> result = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            Box b = sources.get(i);
            List<Box> obstacles = new ArrayList<>(sources); obstacles.remove(i); obstacles.addAll(result);
            float right = Math.min(width, b.right + Math.min(maxExpansion, b.width() * .35f));
            for (Box other : obstacles) {
                if (other.top < b.bottom + gap && other.bottom > b.top - gap && other.left >= b.right) {
                    right = Math.min(right, other.left - gap);
                }
            }
            right = Math.max(b.right, right);
            float bottom = Math.min(height, b.bottom + Math.min(maxExpansion, b.height() * .8f));
            for (Box other : obstacles) {
                if (other.left < right + gap && other.right > b.left - gap && other.top >= b.bottom) {
                    bottom = Math.min(bottom, other.top - gap);
                }
            }
            result.add(new Box(Math.max(0, b.left), Math.max(0, b.top), right, Math.max(b.bottom, bottom)));
        }
        return result;
    }

    /** A merged bounding rectangle can intersect a third block; repeat until the groups are stable. */
    static List<List<Integer>> overlappingGroups(List<Box> boxes) {
        List<Box> unions = new ArrayList<>(boxes); List<List<Integer>> groups = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) groups.add(new ArrayList<>(List.of(i)));
        boolean changed;
        do {
            changed = false;
            outer: for (int i = 0; i < unions.size(); i++) for (int j = i + 1; j < unions.size(); j++) {
                if (unions.get(i).intersects(unions.get(j))) {
                    unions.set(i, unions.get(i).union(unions.remove(j))); groups.get(i).addAll(groups.remove(j));
                    changed = true; break outer;
                }
            }
        } while (changed);
        return groups;
    }
}
