package tw.screenlingo;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    final SharedPreferences data;
    Prefs(Context context) { data = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    String target() { return data.getString("target", "zh-Hant"); }
    String source() { return data.getString("source", "auto"); }
    String script() { return data.getString("script", "auto"); }
    boolean lens() { return !"offline".equals(data.getString("mode", "lens")); }
    float opacity() { return data.getInt("opacity", 80) / 100f; }
    boolean resident() { return data.getBoolean("resident", true); }
    boolean wifiOnly() { return data.getBoolean("wifi", true); }
    int size() { return data.getInt("size", 52); }
    void put(String key, String value) { data.edit().putString(key, value).apply(); }
}
