package tw.screenlingo;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** The visible half stays wholly inside its touchable window; no off-screen overlay. */
final class HalfBallDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean right;
    HalfBallDrawable(boolean right) { this.right = right; paint.setColor(Ui.TEAL); }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawCircle(right ? bounds.right : bounds.left, bounds.exactCenterY(), bounds.height() / 2f, paint);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
