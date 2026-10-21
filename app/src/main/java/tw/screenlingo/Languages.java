package tw.screenlingo;

import android.os.LocaleList;
import com.google.mlkit.nl.translate.TranslateLanguage;
import java.util.*;

final class Languages {
    record Entry(String tag, String name) {
        @Override public String toString() { return name; }
    }
    static String modelTag(String tag) {
        return tag.startsWith("zh") ? "zh" : TranslateLanguage.fromLanguageTag(tag);
    }
    static List<Entry> all(boolean includeAuto) {
        LinkedHashMap<String, Entry> items = new LinkedHashMap<>();
        if (includeAuto) items.put("auto", new Entry("auto", "自動偵測原文語言"));
        items.put("zh-Hant", new Entry("zh-Hant", "繁體中文"));
        items.put("zh-Hans", new Entry("zh-Hans", "简体中文"));
        LocaleList phone = LocaleList.getDefault();
        for (int i = 0; i < phone.size(); i++) {
            String tag = TranslateLanguage.fromLanguageTag(phone.get(i).toLanguageTag());
            if (tag != null && !tag.equals("zh")) {
                items.put(tag, new Entry(tag, label(tag) + " · 手機偏好語言"));
            }
        }
        List<String> rest = new ArrayList<>(TranslateLanguage.getAllLanguages());
        rest.remove("zh");
        rest.sort(Comparator.comparing(Languages::label));
        for (String tag : rest) items.putIfAbsent(tag, new Entry(tag, label(tag)));
        return new ArrayList<>(items.values());
    }
    static String label(String tag) {
        if (tag.equals("zh-Hant")) return "繁體中文";
        if (tag.equals("zh-Hans")) return "简体中文";
        Locale locale = Locale.forLanguageTag(tag);
        return locale.getDisplayLanguage(Locale.TAIWAN) + " / " + locale.getDisplayLanguage(locale);
    }
}
