package tw.screenlingo;

/** A single transform shared by the frozen image, masks, text, and hit testing. */
final class ReadingViewport {
    private float scale = 1, x, y, width, height;
    float scale() { return scale; }
    float x() { return x; }
    float y() { return y; }
    void resize(float width, float height) { this.width = width; this.height = height; clamp(); }
    void scaleBy(float factor, float focusX, float focusY) {
        float next = Math.max(1, Math.min(4, scale * factor));
        x = focusX - (focusX - x) * next / scale;
        y = focusY - (focusY - y) * next / scale;
        scale = next; clamp();
    }
    void panBy(float dx, float dy) { x += dx; y += dy; clamp(); }
    float contentX(float screenX) { return (screenX - x) / scale; }
    float contentY(float screenY) { return (screenY - y) / scale; }
    private void clamp() {
        x = Math.max(width * (1 - scale), Math.min(0, x));
        y = Math.max(height * (1 - scale), Math.min(0, y));
    }
}
