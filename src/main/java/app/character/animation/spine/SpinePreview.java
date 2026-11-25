package app.character.animation.spine;

import app.character.animation.AnimationType;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/** Offline evaluation exports; never constructs the assistant or starts any service. */
public final class SpinePreview {
    private SpinePreview() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SpinePreview <output-directory>");
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        SpineModel model = SpineModel.bundled();
        SpineRenderer renderer = new SpineRenderer(model);
        var rows = new ArrayList<>(model.animations().keySet());
        rows.add("TALK (procedural)");
        rows.add("THINKING (procedural)");
        rows.add("THINKING_TALK (procedural)");
        for (boolean dark : new boolean[]{false, true}) {
            BufferedImage sheet = new BufferedImage(880, rows.size() * 248, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            graphics.setColor(dark ? new Color(27, 32, 40) : new Color(243, 245, 248));
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            graphics.setColor(dark ? Color.WHITE : Color.DARK_GRAY);
            for (int row = 0; row < rows.size(); row++) {
                String name = rows.get(row);
                AnimationType variant = name.startsWith("TALK") ? AnimationType.TALK
                        : name.startsWith("THINKING_TALK") ? AnimationType.THINKING_TALK
                        : name.startsWith("THINKING") ? AnimationType.THINKING : null;
                String action = variant == null ? name : SpineRenderer.sourceAction(variant);
                float duration = model.animations().get(action);
                for (int column = 0; column < 4; column++) {
                    float time = duration * column / 4;
                    BufferedImage image = renderer.render(action, time, variant, 220, 220);
                    graphics.drawImage(image, column * 220, row * 248 + 24, null);
                    String caption = variant == AnimationType.THINKING_TALK ? "THINKING + TALK (proc.)" : name;
                    graphics.drawString(caption + "  " + String.format(Locale.ROOT, "%.2fs", time), column * 220 + 8, row * 248 + 18);
                    if (!dark) ImageIO.write(image, "png", output.resolve("pose-" + row + "-" + column + ".png").toFile());
                }
            }
            graphics.dispose();
            ImageIO.write(sheet, "png", output.resolve(dark ? "actions-dark.png" : "actions-light.png").toFile());
        }
        gif(renderer, AnimationType.TAP, output.resolve("interact.gif"), renderer.duration(AnimationType.TAP));
        gif(renderer, AnimationType.TALK, output.resolve("talk.gif"), 4);
        gif(renderer, AnimationType.THINKING, output.resolve("thinking.gif"), 4);
        gif(renderer, AnimationType.THINKING_TALK, output.resolve("thinking-talk.gif"), 4);
        gif(renderer, AnimationType.WALK, output.resolve("walk.gif"), renderer.duration(AnimationType.WALK));
        long start = System.nanoTime();
        for (int i = 0; i < 30; i++) renderer.render(AnimationType.IDLE, i / 30f, 220, 220);
        double averageMillis = (System.nanoTime() - start) / 30_000_000.0;
        var bounds = renderer.bounds();
        String record = "{\"export_version\":\"" + model.version() + "\",\"bones\":" + model.boneCount()
                + ",\"bounds_xywh\":[" + bounds.minX() + "," + bounds.minY() + "," + bounds.width() + "," + bounds.height()
                + "],\"render_220px_mean_ms\":" + averageMillis + ",\"scope\":\"offline evaluation; no AI/TTS\"}";
        Files.writeString(output.resolve("preview-metrics.json"), record + "\n");
        System.out.println(record);
        System.out.println("Preview images: " + output);
    }

    private static void gif(SpineRenderer renderer, AnimationType action, Path path, float duration) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            int count = (int) Math.ceil(duration * 15);
            for (int frame = 0; frame < count; frame++) {
                BufferedImage image = new BufferedImage(280, 280, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.setColor(new Color(27, 32, 40)); g.fillRect(0, 0, 280, 280);
                g.drawImage(renderer.render(action, frame / 15f, 280, 280), 0, 0, null);
                g.dispose();
                var metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
                var tree = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                var control = new IIOMetadataNode("GraphicControlExtension");
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", "7");
                control.setAttribute("transparentColorIndex", "0");
                tree.appendChild(control);
                if (frame == 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions");
                    var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0}); extensions.appendChild(loop); tree.appendChild(extensions);
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree);
                writer.writeToSequence(new IIOImage(image, null, metadata), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }
}
