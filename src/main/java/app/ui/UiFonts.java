package app.ui;

import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class UiFonts {
    private static final String DISPLAY_TEST_TEXT = "出门提醒";
    private static final String FONT_OVERRIDE_PROPERTY = "closure.ui.fontFamily";
    private static final String PINGFANG_FONT_FILE_NAME = "PingFang.ttc";
    private static final Path[] PINGFANG_SEARCH_ROOTS = {
            Path.of("/System/Library/AssetsV2/com_apple_MobileAsset_Font8"),
            Path.of("/System/Library/Fonts"),
            Path.of("/Library/Fonts"),
            Path.of(System.getProperty("user.home"), "Library", "Fonts")
    };
    private static final String[] JAVAFX_FALLBACK_FONT_FILES = {
            "/System/Library/Fonts/Supplemental/NISC18030.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
    };
    private static final String[] SIMPLIFIED_CHINESE_FONT_FAMILIES = {
            "PingFang SC",
            "Hiragino Sans GB",
            "Songti SC",
            "Heiti SC",
            "STHeiti",
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "SimHei",
            "Source Han Sans SC",
            "Noto Sans CJK SC",
            "Noto Sans SC",
            "WenQuanYi Micro Hei",
            "Arial Unicode MS"
    };

    public static final String FX_FONT_STYLE = "";

    private static final Map<Double, javafx.scene.text.Font> JAVA_FX_FONT_CACHE = new ConcurrentHashMap<>();
    private static volatile String javaFxFontUri;

    private UiFonts() {
    }

    public static String fxStyle(String style) {
        if (style == null || style.isBlank()) {
            return "";
        }

        return style;
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
        control.setFont(javaFxFont(size));
    }

    public static void applyJavaFxFont(javafx.scene.control.TextInputControl control, double size) {
        control.setFont(javaFxFont(size));
    }

    private static javafx.scene.text.Font javaFxFont(double size) {
        String overriddenFontFamily = configuredFontFamily();
        if (overriddenFontFamily != null) {
            return javafx.scene.text.Font.font(overriddenFontFamily, size);
        }

        String fontUri = preferredJavaFxFontUri();
        if (fontUri == null) {
            return javafx.scene.text.Font.font(size);
        }

        return JAVA_FX_FONT_CACHE.computeIfAbsent(size, ignored -> {
            javafx.scene.text.Font loadedFont = loadJavaFxFont(fontUri, size);
            return loadedFont == null ? javafx.scene.text.Font.font(size) : loadedFont;
        });
    }

    private static String preferredJavaFxFontUri() {
        String cachedFontUri = javaFxFontUri;
        if (cachedFontUri != null) {
            return cachedFontUri;
        }

        synchronized (UiFonts.class) {
            if (javaFxFontUri == null) {
                javaFxFontUri = resolveJavaFxFontUri();
            }

            return javaFxFontUri;
        }
    }

    private static String resolveJavaFxFontUri() {
        String pingFangFontUri = findPingFangFontUri();
        if (pingFangFontUri != null) {
            return pingFangFontUri;
        }

        for (String fontFile : JAVAFX_FALLBACK_FONT_FILES) {
            Path fontPath = Path.of(fontFile);
            if (Files.isRegularFile(fontPath)) {
                return fontPath.toUri().toString();
            }
        }

        return null;
    }

    private static String findPingFangFontUri() {
        for (Path searchRoot : PINGFANG_SEARCH_ROOTS) {
            Path fontPath = findFontFile(searchRoot, PINGFANG_FONT_FILE_NAME);
            if (fontPath != null) {
                return fontPath.toUri().toString();
            }
        }

        return null;
    }

    private static Path findFontFile(Path searchRoot, String fileName) {
        if (!Files.isDirectory(searchRoot)) {
            return null;
        }

        try (var paths = Files.walk(searchRoot, 4)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static javafx.scene.text.Font loadJavaFxFont(String fontUri, double size) {
        try {
            return javafx.scene.text.Font.loadFont(fontUri, size);
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    private static String preferredSwingFontFamily() {
        Set<String> availableFontFamilies = availableSwingFontFamilies();
        String overriddenFontFamily = configuredFontFamily();
        if (overriddenFontFamily != null && isAvailableDisplayFont(availableFontFamilies, overriddenFontFamily)) {
            return overriddenFontFamily;
        }

        for (String fontFamily : SIMPLIFIED_CHINESE_FONT_FAMILIES) {
            if (isAvailableDisplayFont(availableFontFamilies, fontFamily)) {
                return fontFamily;
            }
        }

        return Font.DIALOG;
    }

    private static Set<String> availableSwingFontFamilies() {
        try {
            return Arrays.stream(GraphicsEnvironment
                            .getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames(Locale.ENGLISH))
                    .collect(Collectors.toSet());
        } catch (RuntimeException exception) {
            return Set.of(Font.DIALOG);
        }
    }

    private static boolean isAvailableDisplayFont(Set<String> availableFontFamilies, String fontFamily) {
        if (fontFamily == null || fontFamily.isBlank()) {
            return false;
        }
        if (!Font.DIALOG.equals(fontFamily) && !availableFontFamilies.contains(fontFamily)) {
            return false;
        }

        return new Font(fontFamily, Font.PLAIN, 13).canDisplayUpTo(DISPLAY_TEST_TEXT) == -1;
    }

    private static String configuredFontFamily() {
        String fontFamily = System.getProperty(FONT_OVERRIDE_PROPERTY);
        if (fontFamily == null || fontFamily.isBlank()) {
            return null;
        }

        return fontFamily.trim();
    }
}
