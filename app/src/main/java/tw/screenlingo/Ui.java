package tw.screenlingo;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int INK = Color.rgb(24, 49, 44), MUTED = Color.rgb(88, 108, 101);
    static final int TEAL = Color.rgb(13, 121, 109), BG = Color.rgb(245, 247, 244);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable rounded(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius)); return d;
    }
    static TextView text(Context c, String text, int size, int color) {
        TextView v = new TextView(c); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setLineSpacing(dp(c, 3), 1); return v;
    }
    static TextView title(Context c, String text, int size) {
        TextView v = text(c, text, size, INK); v.setTypeface(null, Typeface.BOLD); return v;
    }
    static Button button(Context c, String text, boolean primary, View.OnClickListener action) {
        Button b = new Button(c); b.setText(text); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : TEAL);
        b.setBackground(rounded(c, primary ? TEAL : Color.rgb(230, 240, 234), 16));
        b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48)); b.setOnClickListener(action);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6)); return b;
    }
    static LinearLayout column(Context c, int padding) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, padding); l.setPadding(p, p, p, p); return l;
    }
    static void gap(LinearLayout parent, int dp) {
        View v = new View(parent.getContext()); parent.addView(v, new LinearLayout.LayoutParams(1, Ui.dp(parent.getContext(), dp)));
    }
    static ImageView attribution(Context c) {
        ImageView image = new ImageView(c);
        image.setImageResource(R.drawable.google_translate);
        image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("powered by Google Translate");
        image.setOnClickListener(v -> c.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://translate.google.com")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
        return image;
    }
}
