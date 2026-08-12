package app.ui;

import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFontsTest {
    @Test
    void javafxCssDoesNotForceFontFamily() {
        String style = UiFonts.fxStyle("-fx-font-size: 14px;");

        assertEquals("-fx-font-size: 14px;", style);
        assertEquals("", UiFonts.FX_FONT_STYLE);
        assertFalse(style.contains("Yu Gothic"));
        assertFalse(style.contains("Meiryo"));
    }

    @Test
    void preferredSwingFontCanDisplayCommonChineseText() {
        Font font = UiFonts.preferredSwingFont(Font.PLAIN, 13);

        assertEquals(-1, font.canDisplayUpTo("出门提醒"));
    }
}
