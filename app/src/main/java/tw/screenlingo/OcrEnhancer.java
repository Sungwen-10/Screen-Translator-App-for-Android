package tw.screenlingo;

import android.graphics.*;
import java.util.*;

/** Finds short runs of bright/coloured ink, independent of game labels or screen positions. */
final class OcrEnhancer {
    record Region(Rect bounds, int ink) { }
    static boolean ink(int color, int kind) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return switch (kind) {
            case 0, 8 -> Math.min(r, Math.min(g, b)) >= 210;
            case 1 -> g > 100 && g - r > 65 && g - b > 70;
            case 2 -> b > 140 && b - r > 70 && b - g > 40;
            case 3 -> r > 140 && r - g > 65 && r - b > 60;
            case 4 -> Math.min(r, g) > 140 && Math.min(r, g) - b > 85;
            case 5 -> Math.min(r, b) > 140 && Math.min(r, b) - g > 85;
            case 6 -> Math.min(g, b) > 140 && Math.min(g, b) - r > 85;
            default -> Math.min(r, Math.min(g, b)) >= 230;
        };
    }
    static List<Region> regions(Bitmap image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = new int[w * h], queue = new int[w * h]; byte[] mask = new byte[w * h];
        image.getPixels(pixels, 0, w, 0, 0, w, h);
        List<Region> regions = new ArrayList<>();
        for (int kind = 0; kind <= 6; kind++) {
            for (int i = 0; i < pixels.length; i++) mask[i] = (byte) (ink(pixels[i], kind) ? 1 : 0);
            List<Rect> parts = new ArrayList<>();
            for (int seed = 0; seed < mask.length; seed++) {
                if (mask[seed] == 0) continue;
                int head = 0, tail = 1, left = seed % w, right = left, top = seed / w, bottom = top;
                queue[0] = seed; mask[seed] = 0;
                while (head < tail) {
                    int point = queue[head++], x = point % w, y = point / w;
                    left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
                    for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++)
                        for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++) {
                            int next = yy * w + xx; if (mask[next] != 0) { mask[next] = 0; queue[tail++] = next; }
                        }
                }
                if (tail >= 4 && right - left >= 1 && bottom - top >= 2 && bottom - top < h * .13f
                    && right - left < w * .35f && parts.size() < 1000) parts.add(new Rect(left, top, right + 1, bottom + 1));
            }
            for (int round = 0; round < 2; round++) parts = join(parts);
            for (Rect word : parts) {
                if (word.height() < 9 || word.height() > Math.min(110, h * .14f) || word.width() < word.height() * 1.15f
                    || word.width() > word.height() * 22 || word.width() > w * .45f) continue;
                int margin = Math.max(5, Math.round(word.height() * .2f));
                Rect crop = new Rect(Math.max(0, word.left - margin), Math.max(0, word.top - margin),
                    Math.min(w, word.right + margin), Math.min(h, word.bottom + margin));
                regions.add(new Region(crop, kind));
            }
        }
        // Prefer the compact word-like regions; do not let artwork create unbounded OCR work.
        regions.sort(Comparator.comparingDouble((Region r) -> -Math.min(5, r.bounds.width() / (double) r.bounds.height())
            + (r.ink == 0 ? 0 : -.5)));
        List<Region> selected = new ArrayList<>(); int white = 0, colored = 0;
        for (Region region : regions) {
            if (region.ink == 0 && white >= 18 || region.ink != 0 && colored >= 14) continue;
            if (selected.stream().anyMatch(r -> r.ink == region.ink && overlap(r.bounds, region.bounds) > .65f)) continue;
            selected.add(region); if (region.ink == 0) white++; else colored++;
        }
        return selected;
    }
    private static List<Rect> join(List<Rect> parts) {
        int[] parent = new int[parts.size()]; for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < parts.size(); i++) for (int j = i + 1; j < parts.size(); j++) {
            Rect a = parts.get(i), b = parts.get(j);
            int dx = Math.max(0, Math.max(a.left - b.right, b.left - a.right));
            int dy = Math.max(0, Math.max(a.top - b.bottom, b.top - a.bottom));
            int yOverlap = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
            int xOverlap = Math.min(a.right, b.right) - Math.max(a.left, b.left);
            boolean row = yOverlap > Math.min(a.height(), b.height()) * .4f
                && dx <= Math.max(3, Math.min(a.height(), b.height()) * .65f)
                && Math.max(a.height(), b.height()) < Math.min(a.height(), b.height()) * 4;
            boolean strokes = xOverlap > Math.min(a.width(), b.width()) * .4f && dy <= 3;
            if (row || strokes) parent[root(parent, j)] = root(parent, i);
        }
        Map<Integer, Rect> groups = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            int root = root(parent, i); Rect box = groups.get(root);
            if (box == null) groups.put(root, new Rect(parts.get(i))); else box.union(parts.get(i));
        }
        return new ArrayList<>(groups.values());
    }
    private static int root(int[] parent, int i) { while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
    static float overlap(Rect a, Rect b) {
        int area = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left))
            * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        return area / (float) Math.max(1, Math.min(a.width() * a.height(), b.width() * b.height()));
    }
    static Bitmap prepare(Bitmap image, Region region, float scale) {
        Rect r = region.bounds; int[] pixels = new int[r.width() * r.height()]; image.getPixels(pixels, 0, r.width(), r.left, r.top, r.width(), r.height());
        for (int i = 0; i < pixels.length; i++) pixels[i] = ink(pixels[i], region.ink) ? Color.BLACK : Color.WHITE;
        if (region.ink >= 8) {
            int w = r.width(), h = r.height(); int[] eroded = new int[pixels.length]; Arrays.fill(eroded, Color.WHITE);
            for (int y = 0; y < h - 1; y++) for (int x = 0; x < w - 1; x++) {
                int i = y * w + x;
                if (pixels[i] == Color.BLACK && pixels[i + 1] == Color.BLACK && pixels[i + w] == Color.BLACK && pixels[i + w + 1] == Color.BLACK) eroded[i] = Color.BLACK;
            }
            Arrays.fill(pixels, Color.WHITE);
            for (int y = 0; y < h - 1; y++) for (int x = 0; x < w - 1; x++) {
                int i = y * w + x; if (eroded[i] == Color.BLACK) pixels[i] = pixels[i + 1] = pixels[i + w] = pixels[i + w + 1] = Color.BLACK;
            }
        }
        Bitmap mask = Bitmap.createBitmap(pixels, r.width(), r.height(), Bitmap.Config.ARGB_8888);
        Bitmap output = Bitmap.createBitmap(Math.round(r.width() * scale) + 64, Math.round(r.height() * scale) + 64, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output); canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(mask, null, new RectF(32, 32, output.getWidth() - 32, output.getHeight() - 32), new Paint(Paint.FILTER_BITMAP_FLAG));
        mask.recycle(); return output;
    }
    static boolean usefulWhiteTile(Bitmap image, Region region) {
        int white = 0, total = 0;
        for (int y = region.bounds.top; y < region.bounds.bottom; y += 4)
            for (int x = region.bounds.left; x < region.bounds.right; x += 4) {
                total++; if (ink(image.getPixel(x, y), 0)) white++;
            }
        // A light page background is not white lettering. Avoid turning it into
        // a solid black OCR input, and skip tiles with no usable bright ink.
        return total > 0 && white > total * .003f && white < total * .85f;
    }
    static List<Region> jobs(List<Region> regions, int width, int height) {
        List<Region> jobs = new ArrayList<>();
        for (Region region : regions) if (region.ink != 0) jobs.add(region);
        // Overlapping tiles cover text that is connected to bright artwork. Selecting
        // only isolated white components drops those labels when the background changes.
        int w = Math.min(1280, width), h = Math.min(320, height);
        int columns = Math.max(1, (int) Math.ceil((width - w) / (w * .8f)) + 1);
        int rows = Math.max(1, (int) Math.ceil((height - h) / (h * .8f)) + 1);
        for (int y = 0; y < rows; y++) for (int x = 0; x < columns; x++) {
            int left = columns == 1 ? 0 : Math.round(x * (width - w) / (float) (columns - 1));
            int top = rows == 1 ? 0 : Math.round(y * (height - h) / (float) (rows - 1));
            jobs.add(new Region(new Rect(left, top, left + w, top + h), 0));
        }
        List<Rect> bands = new ArrayList<>();
        for (Region region : regions) {
            if (region.ink != 0) continue;
            int left = Math.max(0, Math.min(width - w, region.bounds.centerX() - w / 2));
            Rect band = new Rect(left, Math.max(0, region.bounds.bottom - 120), left + w, Math.min(height, region.bounds.bottom + 24));
            if (bands.stream().anyMatch(r -> overlap(r, band) > .75f)) continue;
            bands.add(band);
            // A shallow context can separate labels which a taller tile groups with icons.
            jobs.add(new Region(band, 7)); jobs.add(new Region(band, 8));
        }
        return jobs;
    }
}
