package tw.screenlingo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.*;

/** A local, offline practice page so first-time users can try their floating button. */
public final class DemoActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setDecorFitsSystemWindows(false);
        ScrollView scroll = new ScrollView(this); LinearLayout root = Ui.column(this, 28);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(Ui.dp(this, 28) + bars.left, Ui.dp(this, 28) + bars.top,
                Ui.dp(this, 28) + bars.right, Ui.dp(this, 28) + bars.bottom); return insets;
        });
        root.addView(Ui.text(this, "SCREENLINGO  /  PRACTICE", 12, Ui.TEAL)); Ui.gap(root, 28);
        root.addView(Ui.title(this, "A little curiosity.\nA bigger world.", 32)); Ui.gap(root, 18);
        root.addView(Ui.text(this, "Read something new, wherever you are.", 18, Ui.MUTED)); Ui.gap(root, 28);
        section(root, "Make yourself at home", "You do not need to know every word to discover something wonderful.");
        section(root, "One tap away", "Tap the floating button to translate the words on this screen. Tap an empty area to see the original again.");
        section(root, "Keep exploring", "Turn the page, find a new idea, and let your next adventure begin.");
        Ui.gap(root, 20); root.addView(Ui.button(this, "返回設定", false, v -> finish()));
        Ui.gap(root, 12); root.addView(Ui.text(this, "英文試譯頁 · 先啟用懸浮球，再點「譯」。", 13, Ui.MUTED));
        scroll.addView(root); setContentView(scroll);
    }
    private void section(LinearLayout root, String title, String body) {
        LinearLayout card = Ui.column(this, 20); card.setBackground(Ui.rounded(this, Color.WHITE, 22));
        card.addView(Ui.title(this, title, 21)); Ui.gap(card, 10);
        card.addView(Ui.text(this, body, 17, Ui.INK)); root.addView(card); Ui.gap(root, 16);
    }
}
