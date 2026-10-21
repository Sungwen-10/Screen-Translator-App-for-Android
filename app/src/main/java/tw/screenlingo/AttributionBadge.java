package tw.screenlingo;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Keeps the complete attribution visible while allowing readers to move it off text. */
final class AttributionBadge {
    private AttributionBadge() { }

    static ImageView add(FrameLayout parent, Prefs prefs) {
        Context context = parent.getContext();
        ImageView badge = Ui.attribution(context);
        badge.setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6));
        badge.setBackground(Ui.rounded(context, Color.WHITE, 10));
        badge.setContentDescription("powered by Google Translate；拖曳可移動來源標示");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Ui.dp(context, 174), Ui.dp(context, 36));
        parent.addView(badge, params);
        Position position = new Position(parent, badge, params, prefs);
        parent.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> position.layout());
        badge.setOnTouchListener(position);
        return badge;
    }

    private static final class Position implements View.OnTouchListener {
        private final FrameLayout parent;
        private final ImageView badge;
        private final FrameLayout.LayoutParams params;
        private final Prefs prefs;
        private final int margin, width, height, slop;
        private String key;
        private float fractionX, fractionY, downX, downY, startX, startY;
        private boolean dragging, multiple;

        Position(FrameLayout parent, ImageView badge, FrameLayout.LayoutParams params, Prefs prefs) {
            this.parent = parent; this.badge = badge; this.params = params; this.prefs = prefs;
            margin = Ui.dp(parent.getContext(), 8); width = params.width; height = params.height;
            slop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        }
        void layout() {
            if (parent.getWidth() == 0 || parent.getHeight() == 0) return;
            String nextKey = parent.getWidth() > parent.getHeight() ? "attributionLandscape" : "attributionPortrait";
            if (!nextKey.equals(key)) {
                key = nextKey;
                fractionX = prefs.data.getFloat(key + "X", 1f);
                fractionY = prefs.data.getFloat(key + "Y", 0f);
            }
            // Adapt to small windows without cutting off or distorting the source graphic.
            float fit = Math.min(1f, Math.min(Math.max(1, parent.getWidth() - 2 * margin) / (float) width,
                Math.max(1, parent.getHeight() - 2 * margin) / (float) height));
            int w = Math.max(1, Math.round(width * fit)), h = Math.max(1, Math.round(height * fit));
            int x = margin + Math.round(fractionX * Math.max(0, parent.getWidth() - w - 2 * margin));
            int y = margin + Math.round(fractionY * Math.max(0, parent.getHeight() - h - 2 * margin));
            if (params.width != w || params.height != h || params.leftMargin != x || params.topMargin != y) {
                params.width = w; params.height = h; params.leftMargin = x; params.topMargin = y;
                badge.setLayoutParams(params);
            }
        }
        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startX = params.leftMargin; startY = params.topMargin;
                    dragging = multiple = false; return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multiple = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiple) return true;
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        fractionX = fraction(startX + dx - margin, parent.getWidth() - params.width - 2 * margin);
                        fractionY = fraction(startY + dy - margin, parent.getHeight() - params.height - 2 * margin);
                        layout();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && key != null) prefs.data.edit().putFloat(key + "X", fractionX).putFloat(key + "Y", fractionY).apply();
                    else if (!multiple) badge.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = multiple = false; return true;
                default:
                    return true;
            }
        }
        private static float fraction(float value, int span) { return span <= 0 ? 0 : Math.max(0, Math.min(1, value / span)); }
    }
}
