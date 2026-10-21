package tw.screenlingo;

import com.ibm.icu.text.Transliterator;
import org.junit.Test;
import static org.junit.Assert.*;

public class TraditionalChineseTest {
    @Test public void translationOutputUsesTraditionalGlyphs() {
        Transliterator converter = Transliterator.getInstance("Simplified-Traditional");
        assertEquals("這個應用程序可以翻譯屏幕上的文字。", converter.transliterate("这个应用程序可以翻译屏幕上的文字。"));
        assertEquals("繁體中文 English 123", converter.transliterate("繁體中文 English 123"));
    }
}
