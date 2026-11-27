package app.ui;

import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class UiFonts {
    public static final String DEFAULT_FAMILY = "Noto Sans SC";
    public static final String FX_FONT_STYLE = "-fx-font-family: '" + DEFAULT_FAMILY + "';";
    private static final String DISPLAY_TEST_TEXT = "简体中文，标点！门、话、这、关、骨、直、令。ABC 123 !?";
    private static final String FONT_OVERRIDE_PROPERTY = "closure.ui.fontFamily";
    private static final String[] FONT_RESOURCES = {
            "/ui/fonts/NotoSansSC-Regular.otf",
            "/ui/fonts/NotoSansSC-Bold.otf"
    };

    private UiFonts() {
    }

    public static String fxStyle(String style) {
        String configured = configuredFontFamily();
        String family = (configured == null ? DEFAULT_FAMILY : configured)
                .replace("\\", "\\\\").replace("'", "\\'");
        return "-fx-font-family: '" + family + "';" + (style == null ? "" : " " + style);
    }

    /** Inherit the same SC family through CSS, including controls created by skins. */
    public static String javaFxFamily() {
        JavaFxFonts.ensureLoaded();
        String configured = configuredFontFamily();
        return configured != null && javafx.scene.text.Font.getFamilies().contains(configured)
                ? configured : DEFAULT_FAMILY;
    }

    public static void applySwingDefaults() {
        String fontFamily = preferredSwingFontFamily();
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof FontUIResource font) {
                UIManager.put(key, new FontUIResource(fontFamily, font.getStyle(), font.getSize()));
            }
        }
    }

    public static Font preferredSwingFont(int style, int size) {
        return new Font(preferredSwingFontFamily(), style, size);
    }

    public static void applyJavaFxFont(javafx.scene.control.Labeled control, double size) {
        control.setFont(javafx.scene.text.Font.font(javaFxFamily(), size));
    }

    public static void applyJavaFxFont(javafx.scene.control.TextInputControl control, double size) {
        control.setFont(javafx.scene.text.Font.font(javaFxFamily(), size));
    }

    private static final class JavaFxFonts {
        static {
            for (String resource : FONT_RESOURCES) {
                var url = Objects.requireNonNull(UiFonts.class.getResource(resource), resource);
                var font = javafx.scene.text.Font.loadFont(url.toExternalForm(), 14);
                if (font == null || !DEFAULT_FAMILY.equals(font.getFamily())) {
                    throw new IllegalStateException("Cannot load the bundled Simplified Chinese font: " + resource);
                }
            }
        }

        private static void ensureLoaded() {}
    }

    private static final class SwingFonts {
        static {
            for (String resource : FONT_RESOURCES) {
                try (var input = Objects.requireNonNull(UiFonts.class.getResourceAsStream(resource), resource)) {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, input);
                    if (!DEFAULT_FAMILY.equals(font.getFamily(Locale.ENGLISH))) {
                        throw new IllegalStateException("Unexpected bundled font family: " + resource);
                    }
                    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                } catch (IOException | FontFormatException exception) {
                    throw new IllegalStateException("Cannot load the bundled Simplified Chinese font: " + resource, exception);
                }
            }
        }

        private static void ensureLoaded() {}
    }

    private static String preferredSwingFontFamily() {
        SwingFonts.ensureLoaded();
        String configured = configuredFontFamily();
        if (configured != null) {
            Set<String> available = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.ENGLISH)).collect(Collectors.toSet());
            if (available.contains(configured)
                    && new Font(configured, Font.PLAIN, 14).canDisplayUpTo(DISPLAY_TEST_TEXT) == -1) {
                return configured;
            }
        }
        return DEFAULT_FAMILY;
    }

    private static String configuredFontFamily() {
        String fontFamily = System.getProperty(FONT_OVERRIDE_PROPERTY);
        return fontFamily == null || fontFamily.isBlank() ? null : fontFamily.trim();
    }
}
