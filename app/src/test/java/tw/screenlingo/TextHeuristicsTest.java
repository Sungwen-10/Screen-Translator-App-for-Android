package tw.screenlingo;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextHeuristicsTest {
    @Test public void completeReadingCanBeatATruncatedWordButNotAnotherLabel() {
        assertTrue(TextHeuristics.completes("チャット", "チャッ"));
        assertTrue(TextHeuristics.completes("コレクション", "ション"));
        assertTrue(TextHeuristics.completes("Welcome", "Welcom"));
        assertFalse(TextHeuristics.completes("チャットコレクション", "チャット"));
        assertFalse(TextHeuristics.completes("ショップ", "ショッブ"));
    }
    @Test public void thaiLettersDetermineLanguageButThaiDigitsAloneDoNot() {
        assertEquals("th", TextHeuristics.clearScriptLanguage("สวัสดี ยินดีต้อนรับ"));
        assertTrue(TextHeuristics.inScript('ก', "th"));
        assertFalse(TextHeuristics.inScript('é', "th"));
        assertNull(TextHeuristics.clearScriptLanguage("๑๒๓"));
    }
    @Test public void japaneseKanaOverridesAmbiguousHan() {
        assertEquals("ja", TextHeuristics.clearScriptLanguage("明日の天気"));
        assertEquals("ja", TextHeuristics.clearScriptLanguage("ｶﾀｶﾅ"));
        assertNull(TextHeuristics.clearScriptLanguage("中文文字"));
    }
    @Test public void koreanIsRecognizedInMixedText() {
        assertEquals("ko", TextHeuristics.clearScriptLanguage("Hello 안녕하세요 123"));
        assertNull(TextHeuristics.clearScriptLanguage("English 123"));
    }
    @Test public void punctuationDoesNotWinTheScriptChoice() {
        assertEquals(0, TextHeuristics.score("! ? …", "latin"));
        assertTrue(TextHeuristics.score("中文", "zh") > TextHeuristics.score("中文", "latin"));
    }
    @Test public void japaneseWithMostlyHanStillSelectsJapaneseRecognizer() {
        String text = "東京都新宿区の天気予報";
        assertTrue(TextHeuristics.score(text, "ja") > TextHeuristics.score(text, "zh"));
    }
    @Test public void shortSkillNamesUseNearbyJapaneseInsteadOfChinese() {
        assertEquals("ja", TextHeuristics.contextualLanguage("特殊効果", "ja", "zh"));
        assertEquals("ja", TextHeuristics.contextualLanguage("Schneider", "ja", "de"));
        assertEquals("zh", TextHeuristics.contextualLanguage("新增至", null, "zh"));
        assertEquals("zh", TextHeuristics.contextualLanguage("編軽", null, "ja"));
    }
    @Test public void gameStatusCodesArePreserved() {
        assertTrue(TextHeuristics.statusToken("SP 565"));
        assertTrue(TextHeuristics.statusToken("Lv.99"));
        assertFalse(TextHeuristics.statusToken("シュナイダー"));
    }
}
