package tw.screenlingo;

/** Pure Java so the automatic script choice can be checked without an Android device. */
final class TextHeuristics {
    static boolean completes(String full, String fragment) {
        return fragment.codePointCount(0, fragment.length()) >= 3 && (full.startsWith(fragment) || full.endsWith(fragment))
            && full.codePointCount(0, full.length()) > fragment.codePointCount(0, fragment.length())
            && full.codePointCount(0, full.length()) <= fragment.codePointCount(0, fragment.length()) + 3;
    }
    static boolean inScript(int c, String script) {
        return switch (script) {
            case "zh" -> c >= 0x3400 && c <= 0x9fff;
            case "ja" -> (c >= 0x3040 && c <= 0x30ff) || (c >= 0xff66 && c <= 0xff9f);
            case "ko" -> (c >= 0xac00 && c <= 0xd7af) || (c >= 0x1100 && c <= 0x11ff);
            case "hi" -> c >= 0x0900 && c <= 0x097f;
            case "th" -> c >= 0x0e01 && c <= 0x0e5b;
            default -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0xc0 && c <= 0x024f);
        };
    }
    static int score(String text, String script) {
        return text.codePoints().map(c -> inScript(c, script) ? (script.equals("latin") ? 1 : script.equals("ja") ? 8 : 5)
            : script.equals("ja") && inScript(c, "zh") ? 5
            : Character.isLetterOrDigit(c) ? 1 : 0).sum();
    }
    static String clearScriptLanguage(String text) {
        if (text.codePoints().anyMatch(c -> inScript(c, "ja"))) return "ja";
        if (text.codePoints().anyMatch(c -> inScript(c, "ko"))) return "ko";
        if (text.codePoints().anyMatch(c -> inScript(c, "th") && Character.isLetter(c))) return "th";
        // Han alone can also be Japanese; let language identification decide unless it is uncertain.
        return null;
    }
    static boolean statusToken(String text) {
        String value = text.trim();
        return value.matches("[0-9\\s\\p{Punct}A-Z]+") || value.matches("(?i)lv\\.?\\s*\\d+(?:\\s*/\\s*\\d+)?");
    }
    static String contextualLanguage(String text, String nearbyLanguage, String identified) {
        String certain = clearScriptLanguage(text);
        if (certain != null) return certain;
        long letters = text.codePoints().filter(Character::isLetter).count();
        // Isolated Han skill names and Roman character names are ambiguous, so use nearby text.
        if (letters <= 24 && ("ja".equals(nearbyLanguage) || "ko".equals(nearbyLanguage))) return nearbyLanguage;
        // Han-only toolbar labels far from Japanese text are often Chinese UI around a photo.
        if (nearbyLanguage == null && "ja".equals(identified)
            && text.codePoints().anyMatch(c -> inScript(c, "zh"))) return "zh";
        return identified != null ? identified : nearbyLanguage;
    }
}
