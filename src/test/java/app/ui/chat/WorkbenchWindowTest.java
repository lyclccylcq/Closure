package app.ui.chat;

import app.service.ChatService;
import app.character.CharacterActivityListener;
import app.ui.UiFonts;
import app.service.AssistantSpeechService;
import app.service.ChatSpeechController;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "closure.nativeWorkbenchTest", matches = "true")
class WorkbenchWindowTest {
    @TempDir Path temporary;
    private WorkbenchWindow window;
    private final List<ChatWindowLifecycleTest.Client> clients = new ArrayList<>();
    private final List<ChatService> services = new ArrayList<>();
    private String previousMascotDirectory;
    private final BlockingQueue<String> spoken = new LinkedBlockingQueue<>();
    private final AtomicInteger speechStops = new AtomicInteger();
    private ChatSpeechController speech;
    private final AtomicInteger activeRequests = new AtomicInteger();

    @AfterEach
    void dispose() throws Exception {
        if (window == null) return;
        swing(() -> {
            window.dispose();
            return null;
        });
        await(() -> clients.stream().allMatch(client -> client.closed.get()));
        if (speech != null) speech.close();
        if (previousMascotDirectory == null) System.clearProperty("closure.workbenchMascotDirectory");
        else System.setProperty("closure.workbenchMascotDirectory", previousMascotDirectory);
    }

    @Test
    void workbenchRetainsSessionsAndPreviewsOnlyTheSelectedProjectFile() throws Exception {
        previousMascotDirectory = System.getProperty("closure.workbenchMascotDirectory");
        if (previousMascotDirectory == null) {
            Path mascot = Files.createDirectory(temporary.resolve("mascot"));
            BufferedImage sheet = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
            var graphics = sheet.createGraphics();
            try {
                graphics.setColor(java.awt.Color.MAGENTA);
                graphics.fillRect(0, 0, 20, 20);
                graphics.setColor(java.awt.Color.CYAN);
                graphics.fillRect(20, 0, 20, 20);
            } finally { graphics.dispose(); }
            ImageIO.write(sheet, "png", mascot.resolve("idle-test.png").toFile());
            Files.writeString(mascot.resolve("idle.properties"),
                    "image=idle-test.png\nwidth=20\nheight=20\ncolumns=2\nframes=2\nfps=12\n");
            System.setProperty("closure.workbenchMascotDirectory", mascot.toString());
        }
        Path project = Files.createDirectory(temporary.resolve("示例项目"));
        Path sources = Files.createDirectory(project.resolve("src"));
        String source = """
                package workspace;

                import java.nio.file.Path;

                record WorkspaceTask(String title, Path source) {
                    // 工作台从这里预览项目文件。
                }
                """;
        Files.writeString(sources.resolve("WorkspaceTask.java"), source);
        Files.writeString(project.resolve("README.md"), "# 示例项目\n");
        Files.write(project.resolve("sample.bin"), new byte[] {0, 1, 2});
        Path unrelated = Files.writeString(temporary.resolve("outside.txt"), "outside the selected project");
        Files.createSymbolicLink(project.resolve("z-outside.txt"), unrelated);

        var profiles = new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic", temporary.resolve("unused.wav"), "Synthetic", "zh")),
                message -> {}, true);
        speech = new ChatSpeechController(new AssistantSpeechService() {
            @Override public CompletableFuture<Void> speak(String text, VoiceProfile profile) {
                spoken.add(text);
                return new CompletableFuture<>();
            }
            @Override public void stop() { speechStops.incrementAndGet(); }
        }, profiles, true, message -> {});

        swing(() -> {
            window = new WorkbenchWindow(() -> {
                var client = new ChatWindowLifecycleTest.Client();
                var service = new ChatService(client);
                clients.add(client);
                services.add(service);
                return service;
            }, speech, new CharacterActivityListener() {
                @Override public Runnable thinkingStarted() {
                    activeRequests.incrementAndGet();
                    return activeRequests::decrementAndGet;
                }
            });
            window.setVisible(true);
            assertTrue(window.isShowing());
            return null;
        });
        int initialWidth = swing(() -> window.getContentPane().getWidth());
        await(() -> Math.abs(view().getWidth() - initialWidth) <= 1);
        WorkbenchMascot mascot = fx(() -> (WorkbenchMascot) view().lookup("#workbench-mascot"));
        assertEquals("dsh-closure-pet", fx(() -> mascot.getProperties().get("source")));
        await(mascot::isPlaying);
        Rectangle2D firstFrame = fx(mascot::getViewport);
        await(() -> !firstFrame.equals(mascot.getViewport()));
        swing(() -> { window.setVisible(false); return null; });
        await(() -> !mascot.isPlaying());
        Rectangle2D pausedFrame = fx(mascot::getViewport);
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(pausedFrame, fx(mascot::getViewport));
        swing(() -> { window.setVisible(true); return null; });
        await(mascot::isPlaying);
        fx(() -> {
            var brand = (Label) view().lookup(".workbench-brand");
            assertEquals("Closure Display", brand.getFont().getFamily());
            var section = (Label) view().lookup(".workbench-title");
            assertEquals(UiFonts.DEFAULT_FAMILY, section.getFont().getFamily());
            assertNotNull(view().lookup(".workbench-welcome"));
            assertButtonGraphic("workbench-new-session");
            assertButtonGraphic("workbench-project-entry");
            assertFalse(filesToggle().isSelected());
            assertNull(files());
            capture("workbench-welcome");
            ToggleButton sidebar = (ToggleButton) view().lookup("#workbench-toggle-navigation");
            double widthWithSidebar = conversationWidth();
            sidebar.fire();
            assertNull(sessions());
            assertTrue(conversationWidth() > widthWithSidebar);
            sidebar.fire();
            assertEquals(1, sessions().getItems().size());
            return null;
        });

        String firstPrompt = "工作台启动检查\n帮我核对桌宠入口和项目文件预览。";
        fx(() -> {
            TextArea input = editor();
            input.setText("工作台启动检查");
            input.end();
            pressEnter(input, true);
            assertEquals("工作台启动检查\n", input.getText());
            input.appendText("帮我核对桌宠入口和项目文件预览。");
            pressEnter(input, false);
            assertSame(input, editor());
            ChatView chat = (ChatView) view().lookup(".workbench-chat");
            assertSame(composer(), chat.getBottom());
            return null;
        });
        var first = fx(clients::getFirst).take();
        await(() -> activeRequests.get() == 1);
        assertEquals(firstPrompt, first.request().getMessages().getLast().getContent());

        fx(() -> {
            assertNull(view().lookup(".workbench-welcome"));
            ((Button) view().lookup("#workbench-new-session")).fire();
            assertEquals(2, sessions().getItems().size());
            assertNotNull(view().lookup(".workbench-welcome"));
            return null;
        });
        send("这条写给另一段会话。");
        var other = fx(clients::getLast).take();
        assertEquals(2, other.request().getMessages().size());
        fx(() -> {
            button("workbench-stop").fire();
            return null;
        });
        other.cancelled().get(5, TimeUnit.SECONDS);
        await(() -> activeRequests.get() == 1);
        assertFalse(first.cancelled().isDone());

        String answer = "先从桌宠右键菜单打开工作台，再选择示例项目。打开 src/WorkspaceTask.java，确认能看到中文注释。";
        first.finish(answer);
        await(() -> services.getFirst().completedTurns().size() == 1);
        await(() -> activeRequests.get() == 0);
        fx(() -> {
            assertFalse(shows(answer));
            assertTrue(spoken.isEmpty(),"An off-screen conversation must retain text without speaking");
            sessions().getSelectionModel().select(1);
            return null;
        });
        await(() -> shows(answer));

        fx(() -> {
            capture("workbench-focus");
            double focusedWidth = conversationWidth();
            filesToggle().fire();
            assertTrue(conversationWidth() < focusedWidth);
            assertNotNull(files());
            assertButtonGraphic("workbench-open-project");
            view().openProject(project);
            return null;
        });
        await(() -> files().getRoot() != null && files().getRoot().getChildren().size() == 4);
        fx(() -> {
            assertEquals("示例项目", button("workbench-project-entry").getText());
            files().getRoot().getChildren().getFirst().setExpanded(true);
            return null;
        });
        await(() -> files().getExpandedItemCount() == 5
                && files().getTreeItem(1).getValue().toString().contains("WorkspaceTask.java"));
        fx(() -> {
            files().getSelectionModel().select(4);
            return null;
        });
        await(() -> shows("无法读取此文件"));
        fx(() -> {
            files().getSelectionModel().select(3);
            return null;
        });
        await(() -> shows("此文件无法按 UTF-8 文本预览"));
        fx(() -> {
            files().getSelectionModel().select(2);
            files().getSelectionModel().select(1);
            return null;
        });
        await(() -> source.equals(preview().getText()));

        fx(() -> {
            var selected = files().getSelectionModel().getSelectedItem();
            filesToggle().fire();
            conversationWidth();
            assertNull(files());
            assertTrue(shows(answer));
            button("workbench-project-entry").fire();
            assertTrue(filesToggle().isSelected());
            conversationWidth();
            assertSame(selected, files().getSelectionModel().getSelectedItem());
            assertEquals(source, preview().getText());
            return null;
        });

        swing(() -> {
            window.setVisible(false);
            window.setVisible(true);
            assertTrue(window.isShowing());
            return null;
        });
        fx(() -> {
            assertEquals(2, sessions().getItems().size());
            assertTrue(shows(answer));
            assertEquals(source, preview().getText());
            capture("workbench-preview");
            return null;
        });

        swing(() -> {
            window.setSize(window.getMinimumSize());
            return null;
        });
        await(() -> view().getWidth() <= window.getMinimumSize().width);
        fx(() -> {
            view().layout();
            for (String id : List.of("workbench-send", "workbench-stop", "workbench-open-project")) {
                Button action = button(id);
                assertTrue(action.getWidth() >= action.prefWidth(-1) - 1);
            }
            capture("workbench-narrow");
            return null;
        });

        send("刚才先检查什么？");
        var next = fx(clients::getFirst).take();
        assertEquals(4, next.request().getMessages().size());
        assertEquals(answer, next.request().getMessages().get(2).getContent());
        next.finish("先确认工作台能正常打开。");
        await(() -> services.getFirst().completedTurns().size() == 2);
        assertEquals("先确认工作台能正常打开。", spoken.poll(5, TimeUnit.SECONDS));
        int previousStops = speechStops.get();
        swing(() -> { window.setVisible(false); return null; });
        await(() -> speechStops.get() > previousStops);
        System.out.println("CLOSURE_WORKBENCH_NATIVE_PASS speech=true mascot=true layout=true theme=true sessions=true multiline=true restore=true preview=true");
    }

    private void assertButtonGraphic(String id) {
        var graphic = assertInstanceOf(ImageView.class, button(id).getGraphic());
        assertFalse(graphic.getImage().isError());
        assertTrue(graphic.getImage().getWidth() > 0);
    }

    private void capture(String name) throws IOException {
        if (!Boolean.getBoolean("closure.workbenchPreview")) return;
        view().applyCss();
        view().layout();
        Path screenshot = Path.of("target", name + ".png").toAbsolutePath();
        Files.createDirectories(screenshot.getParent());
        assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null),
                "png", screenshot.toFile()));
        System.out.println("Workbench screenshot " + screenshot);
    }

    private WorkbenchView view() {
        JFXPanel panel = (JFXPanel) window.getContentPane();
        return (WorkbenchView) panel.getScene().getRoot();
    }

    private ListView<?> sessions() {
        return (ListView<?>) view().lookup("#workbench-sessions");
    }

    private TreeView<?> files() {
        return (TreeView<?>) view().lookup("#workbench-project-tree");
    }

    private TextArea preview() {
        return (TextArea) view().lookup("#workbench-file-preview");
    }

    private TextArea editor() {
        return (TextArea) view().lookup("#workbench-editor");
    }

    private Button button(String id) {
        return (Button) view().lookup("#" + id);
    }

    private ToggleButton filesToggle() {
        return (ToggleButton) view().lookup("#workbench-toggle-files");
    }

    private double conversationWidth() {
        view().applyCss();
        view().layout();
        return ((Region) view().lookup(".workbench-conversation")).getWidth();
    }

    private ChatInputBar composer() {
        return (ChatInputBar) view().lookup(".workbench-composer");
    }

    private void send(String text) throws Exception {
        fx(() -> {
            editor().setText(text);
            button("workbench-send").fire();
            return null;
        });
    }

    private static void pressEnter(TextArea input, boolean shift) {
        input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, shift, false, false, false));
    }

    private boolean shows(String text) {
        return view().lookupAll(".label").stream().anyMatch(node ->
                node instanceof Label label && text.equals(label.getText()) && label.isVisible());
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (fx(condition)) return;
            TimeUnit.MILLISECONDS.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Expected workbench state did not appear");
    }
}
