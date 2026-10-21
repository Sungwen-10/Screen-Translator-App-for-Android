package tw.screenlingo;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;

public class RotationSceneActivity extends Activity {
    static final int GREEN = Color.rgb(20, 180, 80), BLUE = Color.rgb(30, 80, 220);
    @Override public void onCreate(Bundle state) { super.onCreate(state); scene(); setRequestedOrientation(getIntent().getIntExtra("orientation", -1)); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); setRequestedOrientation(intent.getIntExtra("orientation", -1)); scene(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); scene(); }
    private void scene() {
        boolean edge = getIntent().getBooleanExtra("edgeLabel", false);
        if (edge) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        View view = new View(this) {
            @Override public void onDraw(Canvas canvas) {
                canvas.drawColor(landscape ? GREEN : BLUE);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); paint.setTextSize(52);
                canvas.drawText("Welcome to the game", 80, getHeight() / 2f, paint);
                if (edge) canvas.drawText("Open settings", 120, getHeight() - 30, paint);
            }
        };
        view.setContentDescription(edge ? "IMMERSIVE MENU" : landscape ? "LANDSCAPE SCENE" : "PORTRAIT SCENE"); setContentView(view);
        if (edge) getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
    }
}
