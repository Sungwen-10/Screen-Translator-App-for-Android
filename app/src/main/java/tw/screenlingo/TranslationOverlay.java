package tw.screenlingo;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.*;
import java.util.*;

/** A temporary reading layer. All masks are drawn before any translated text. */
final class TranslationOverlay extends View {
    interface Listener { void block(TranslationEngine.Block block); void dismiss(); }
    private record Label(TranslationEngine.Block block, RectF box, StaticLayout layout, float inset) { }
    private final TranslationEngine.Result result;
    private final Listener listener;
    private final Bitmap snapshot;
    private final ReadingViewport viewport = new ReadingViewport();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF snapshotRect = new RectF();
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Label> labels = new ArrayList<>();
    private final int[] origin = new int[2], previousOrigin = {Integer.MIN_VALUE, Integer.MIN_VALUE};
    private float downX, downY, lastX, lastY;
    private float pinchSpan, pinchX, pinchY;
    private int activePointer = -1, maxPointers;
    private boolean moved;
    private boolean dirty = true;

    TranslationOverlay(Context context, TranslationEngine.Result result, Listener listener) {
        this(context, result, null, listener);
    }
    TranslationOverlay(Context context, TranslationEngine.Result result, Bitmap snapshot, Listener listener) {
        super(context); this.result = result; this.snapshot = snapshot; this.listener = listener;
        setContentDescription("原位翻譯畫面，點譯文查看全文，點空白還原");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setClickable(true); setFocusable(true);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { dirty = true; viewport.resize(w, h); }
    float zoomFactor() { return viewport.scale(); }
    private void prepare() {
        labels.clear();
        Rect screen = getContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        float sx = screen.width() / (float) result.width(), sy = screen.height() / (float) result.height();
        snapshotRect.set(-origin[0], -origin[1], screen.width() - origin[0], screen.height() - origin[1]);
        List<OverlayGeometry.Box> sources = new ArrayList<>();
        for (TranslationEngine.Block block : result.blocks()) {
            Rect b = block.bounds;
            sources.add(new OverlayGeometry.Box(Math.max(0, b.left * sx - origin[0]), Math.max(0, b.top * sy - origin[1]),
                Math.min(getWidth(), b.right * sx - origin[0]), Math.min(getHeight(), b.bottom * sy - origin[1])));
        }
        float inset = Math.max(1, Ui.dp(getContext(), 1));
        List<OverlayGeometry.Box> slots = OverlayGeometry.slots(sources, getWidth(), getHeight(), inset * 2, 0);
        for (int i = 0; i < result.blocks().size(); i++) {
            TranslationEngine.Block block = result.blocks().get(i);
            if (!block.changed()) continue;
            OverlayGeometry.Box slot = slots.get(i), source = sources.get(i);
            if (slot.width() < 3 || slot.height() < 3) continue;
            float textInset = Math.min(inset, Math.max(1, source.height() * .05f));
            int width = Math.max(1, (int) (slot.width() - textInset * 2));
            int availableHeight = Math.max(1, (int) (slot.height() - textInset * 2));
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setColor(block.foreground);
            float rowHeight = source.height() / Math.max(1, block.original.split("\n").length);
            float size = Math.max(8, Math.min(Ui.dp(getContext(), 22), Math.min(block.sourceLineHeight * sy, rowHeight) * .9f));
            float minimum = Math.min(size, Math.max(9, Math.min(Ui.dp(getContext(), 10), size * .8f)));
            StaticLayout layout;
            while (true) {
                paint.setTextSize(size); layout = layout(block.translated, paint, width, Integer.MAX_VALUE);
                if (layout.getHeight() <= availableHeight || size <= minimum) break;
                size = Math.max(minimum, size - 1);
            }
            if (layout.getHeight() > availableHeight) {
                int visibleLines = 0;
                for (int line = 0; line < layout.getLineCount(); line++) if (layout.getLineBottom(line) <= availableHeight) visibleLines++;
                if (visibleLines == 0) {
                    paint.setTextSize(Math.max(4, size * availableHeight / Math.max(1, layout.getLineBottom(0)) * .95f));
                }
                layout = layout(block.translated, paint, width, Math.max(1, visibleLines));
            }
            labels.add(new Label(block, new RectF(slot.left(), slot.top(), slot.right(), slot.bottom()), layout, textInset));
        }
        previousOrigin[0] = origin[0]; previousOrigin[1] = origin[1]; dirty = false;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); getLocationOnScreen(origin);
        if (dirty || origin[0] != previousOrigin[0] || origin[1] != previousOrigin[1]) prepare();
        canvas.save(); canvas.translate(viewport.x(), viewport.y()); canvas.scale(viewport.scale(), viewport.scale());
        if (snapshot != null && !snapshot.isRecycled()) canvas.drawBitmap(snapshot, null, snapshotRect, bitmapPaint);
        for (Label label : labels) { background.setColor(label.block.background); canvas.drawRect(label.box, background); }
        for (Label label : labels) {
            canvas.save(); canvas.clipRect(label.box); canvas.translate(label.box.left + label.inset, label.box.top + label.inset);
            label.layout.draw(canvas); canvas.restore();
        }
        canvas.restore();
    }
    private static StaticLayout layout(String value, TextPaint paint, int width, int maxLines) {
        return StaticLayout.Builder.obtain(value, 0, value.length(), paint, width).setIncludePad(false)
            .setLineSpacing(0, 1.12f).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX(); downY = lastY = event.getY();
            activePointer = event.getPointerId(0); maxPointers = 1; moved = false; pinchSpan = 0; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            maxPointers = Math.max(maxPointers, event.getPointerCount());
            pinchSpan = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
            pinchX = (event.getX(0) + event.getX(1)) / 2; pinchY = (event.getY(0) + event.getY(1)) / 2;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            pinchSpan = 0;
            int lifted = event.getActionIndex();
            if (event.getPointerId(lifted) == activePointer) {
                int next = lifted == 0 ? 1 : 0; activePointer = event.getPointerId(next);
                lastX = event.getX(next); lastY = event.getY(next);
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(activePointer);
            if (index < 0) return true;
            float x = event.getX(index), y = event.getY(index);
            if (Math.hypot(x - downX, y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
            if (event.getPointerCount() >= 2 && snapshot != null) {
                float span = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                float focusX = (event.getX(0) + event.getX(1)) / 2, focusY = (event.getY(0) + event.getY(1)) / 2;
                if (pinchSpan > 1 && span > 1) {
                    viewport.scaleBy(span / pinchSpan, pinchX, pinchY); viewport.panBy(focusX - pinchX, focusY - pinchY);
                    setStateDescription(Math.round(viewport.scale() * 100) + "%"); invalidate();
                }
                pinchSpan = span; pinchX = focusX; pinchY = focusY;
            } else if (viewport.scale() > 1.001f) { viewport.panBy(x - lastX, y - lastY); invalidate(); }
            lastX = x; lastY = y; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            activePointer = -1;
            if (maxPointers > 1 || (moved && viewport.scale() > 1.001f)) return true;
            performClick();
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > Ui.dp(getContext(), 16)) { listener.dismiss(); return true; }
            float x = viewport.contentX(event.getX()), y = viewport.contentY(event.getY());
            for (int i = labels.size() - 1; i >= 0; i--) {
                if (labels.get(i).box.contains(x, y)) { listener.block(labels.get(i).block); return true; }
            }
            listener.dismiss(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) activePointer = -1;
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
