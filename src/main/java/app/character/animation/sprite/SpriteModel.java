package app.character.animation.sprite;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Local, paged RGBA frames. Artwork is never fetched by the application. */
public final class SpriteModel {
    public static final List<String> CLIPS = List.of("idle", "talk", "think_begin", "think_idle", "think_exit",
            "sad_begin", "sad_idle", "sad_exit", "happy", "celebrate", "interact_1", "interact_2");
    public static final int MAX_CACHED_PAGES = 4;
    private static SpriteModel prepared;
    private static Path preparedManifest;
    private static String preparedDigest;
    private final int width, height, columns, pageFrames, fps;
    private final boolean synthetic;
    private final String commit;
    private final Map<String, Clip> clips;
    private final Map<Path, BufferedImage> pages = new LinkedHashMap<>(8, .75f, true);

    private record Page(Path path, String sha256) { }
    private record Clip(int frames, List<Page> pages) { }

    public static Path localDirectory() {
        String configured = System.getProperty("closure.petAssetsDirectory", System.getenv("CLOSURE_PET_ASSETS_DIRECTORY"));
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".closure", "assets", "pet") : Path.of(configured);
    }

    /** Main warms the first page off the EDT; construction reuses that verified model. */
    public static synchronized SpriteModel local() {
        try {
            Path manifest = localDirectory().resolve("model.properties").toRealPath();
            if (Files.size(manifest) > 65_536) throw new IllegalArgumentException("Model manifest is too large");
            String digest = digest(Files.readAllBytes(manifest));
            if (prepared == null || !manifest.equals(preparedManifest) || !digest.equals(preparedDigest)) {
                SpriteModel candidate = load(manifest.getParent());
                candidate.frame("idle", 0);
                prepared = candidate;
                preparedManifest = manifest;
                preparedDigest = digest;
            }
            return prepared;
        } catch (IOException error) {
            throw new IllegalArgumentException("新版模型未安装或不可读：" + localDirectory(), error);
        }
    }

    public static SpriteModel load(Path directory) {
        try {
            Path root = directory.toRealPath();
            Path manifest = root.resolve("model.properties");
            if (Files.size(manifest) > 65_536) throw new IllegalArgumentException("Model manifest is too large");
            Properties data = new Properties();
            try (var input = Files.newInputStream(manifest)) { data.load(input); }
            return new SpriteModel(root, data);
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read local model", error);
        }
    }

    private SpriteModel(Path root, Properties data) throws IOException {
        number(data, "format", 1, 1);
        width = number(data, "width", 1, 512);
        height = number(data, "height", 1, 512);
        columns = number(data, "columns", 1, 8);
        pageFrames = number(data, "pageFrames", 1, 16);
        fps = number(data, "fps", 1, 30);
        if (pageFrames % columns != 0) throw new IllegalArgumentException("Incomplete page rows");
        synthetic = Boolean.parseBoolean(data.getProperty("synthetic", "false"));
        commit = data.getProperty("commit", "unknown");
        Map<String, Clip> parsed = new HashMap<>();
        for (String name : CLIPS) {
            int count = number(data, name + ".frames", 1, 300);
            int pageCount = number(data, name + ".pages", 1, 300);
            if (pageCount != (count + pageFrames - 1) / pageFrames) throw new IllegalArgumentException("Incomplete clip " + name);
            List<Page> paths = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                String relative = data.getProperty(name + ".page." + i, "");
                if (!relative.matches("[a-zA-Z0-9_./-]+") || Path.of(relative).isAbsolute()
                        || Arrays.asList(relative.split("/", -1)).stream().anyMatch(x -> x.isEmpty() || x.equals("..") || x.equals("."))) {
                    throw new IllegalArgumentException("Unsafe page path");
                }
                Path path = root.resolve(relative).toRealPath();
                if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.size(path) > 16_000_000) {
                    throw new IllegalArgumentException("Invalid model page");
                }
                String hash = data.getProperty(name + ".sha256." + i, "");
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Missing page checksum");
                paths.add(new Page(path, hash));
            }
            parsed.put(name, new Clip(count, List.copyOf(paths)));
        }
        clips = Map.copyOf(parsed);
    }

    public int frames(String name) { return clip(name).frames(); }
    public int fps() { return fps; }
    public boolean synthetic() { return synthetic; }
    public String commit() { return commit; }

    public synchronized BufferedImage frame(String name, int index) {
        Clip clip = clip(name);
        if (index < 0 || index >= clip.frames()) throw new IllegalArgumentException("Frame outside clip");
        Page page = clip.pages().get(index / pageFrames);
        BufferedImage sheet = pages.get(page.path());
        if (sheet == null) {
            sheet = read(page);
            pages.put(page.path(), sheet);
            if (pages.size() > MAX_CACHED_PAGES) pages.remove(pages.keySet().iterator().next());
        }
        int tile = index % pageFrames;
        return sheet.getSubimage(tile % columns * width, tile / columns * height, width, height);
    }

    private BufferedImage read(Page page) {
        try {
            byte[] bytes = Files.readAllBytes(page.path());
            if (!digest(bytes).equals(page.sha256())) throw new IllegalArgumentException("Model page checksum changed");
            try (ImageInputStream stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported model page");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream);
                    if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) != width * columns
                            || reader.getHeight(0) != height * (pageFrames / columns)) {
                        throw new IllegalArgumentException("Wrong model page dimensions");
                    }
                    BufferedImage image = reader.read(0);
                    if (!image.getColorModel().hasAlpha()) throw new IllegalArgumentException("Model page has no alpha");
                    return image;
                } finally { reader.dispose(); }
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot decode model page", error);
        }
    }

    public synchronized int cachedPages() { return pages.size(); }
    public synchronized void clearCache() { pages.clear(); }

    private Clip clip(String name) {
        Clip clip = clips.get(name);
        if (clip == null) throw new IllegalArgumentException("Unknown clip " + name);
        return clip;
    }

    private static int number(Properties data, String key, int min, int max) {
        try {
            int value = Integer.parseInt(data.getProperty(key, ""));
            if (value < min || value > max) throw new IllegalArgumentException("Model limit exceeded: " + key);
            return value;
        } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid model number: " + key, error); }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
