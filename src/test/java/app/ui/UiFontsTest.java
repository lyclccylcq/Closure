package app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFontsTest {
    @Test
    void javafxCssKeepsSizeAndUsesTheBundledSimplifiedChineseFamily() {
        String style = UiFonts.fxStyle("-fx-font-size: 14px;");

        assertTrue(style.contains("-fx-font-size: 14px;"));
        assertTrue(style.contains("Noto Sans SC"));
        assertEquals(UiFonts.FX_FONT_STYLE, UiFonts.fxStyle(null));
        assertFalse(style.contains("Yu Gothic"));
        assertFalse(style.contains("Meiryo"));
    }

    @Test
    void bundledRegularAndBoldCoverChinesePunctuationAndMixedText() {
        for (int style : new int[] {Font.PLAIN, Font.BOLD}) {
            Font font = UiFonts.preferredSwingFont(style, 14);
            assertEquals(UiFonts.DEFAULT_FAMILY, font.getFamily(Locale.ENGLISH));
            assertEquals(-1, font.canDisplayUpTo("出门提醒！博士，午饭吃了没？返、骨、直、令、门、话、这、关。RAG 123 !?"));
            assertEquals(style == Font.BOLD, font.isBold());
        }
    }
}
