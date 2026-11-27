package app.character.animation.sprite;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Synthetic test shapes only; no third-party character assets are bundled. */
public final class SpriteFixtures {
    private SpriteFixtures() { }
    public static Path create(Path root) throws Exception {
        Files.createDirectories(root);
        Properties properties = new Properties();
        properties.setProperty("format", "1"); properties.setProperty("fps", "30");
        properties.setProperty("width", "32"); properties.setProperty("height", "28");
        properties.setProperty("columns", "2"); properties.setProperty("pageFrames", "4");
        properties.setProperty("synthetic", "true"); properties.setProperty("commit", "synthetic-junit");
        for (String name : SpriteModel.CLIPS) {
            properties.setProperty(name + ".frames", "6"); properties.setProperty(name + ".pages", "2");
            for (int page = 0; page < 2; page++) {
                BufferedImage image = new BufferedImage(64, 56, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                for (int i = 0; i < 4; i++) {
                    g.setColor(new Color(150 + i * 15, 100 + page * 40, 220, 128));
                    g.fillOval(i % 2 * 32 + 6, i / 2 * 28 + 3, 20, 22);
                }
                g.dispose();
                Path file = root.resolve(name + "-" + page + ".png"); ImageIO.write(image, "png", file.toFile());
                properties.setProperty(name + ".page." + page, file.getFileName().toString());
                properties.setProperty(name + ".sha256." + page, hash(file));
            }
        }
        write(root, properties);
        return root;
    }
    public static Properties properties(Path root) throws Exception {
        Properties data = new Properties();
        try (var input = Files.newInputStream(root.resolve("model.properties"))) { data.load(input); }
        return data;
    }
    public static void write(Path root, Properties data) throws Exception {
        try (var output = Files.newOutputStream(root.resolve("model.properties"))) { data.store(output, "Synthetic test fixture"); }
    }
    public static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
