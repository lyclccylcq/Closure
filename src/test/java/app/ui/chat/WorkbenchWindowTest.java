package app.ui.chat;

import app.service.ChatService;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "closure.nativeWorkbenchTest", matches = "true")
class WorkbenchWindowTest {
    @TempDir Path temporary;
    private WorkbenchWindow window;
    private final List<ChatWindowLifecycleTest.Client> clients = new ArrayList<>();
    private final List<ChatService> services = new ArrayList<>();

    @AfterEach
    void dispose() throws Exception {
        if (window == null) return;
        swing(() -> {
            window.dispose();
            return null;
        });
        await(() -> clients.stream().allMatch(client -> client.closed.get()));
    }

    @Test
    void workbenchRetainsSessionsAndPreviewsOnlyTheSelectedProjectFile() throws Exception {
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

        swing(() -> {
            window = new WorkbenchWindow(() -> {
                var client = new ChatWindowLifecycleTest.Client();
                var service = new ChatService(client);
                clients.add(client);
                services.add(service);
                return service;
            });
            window.setVisible(true);
            assertTrue(window.isShowing());
            return null;
        });
        fx(() -> {
            var brand = (Label) view().lookup(".workbench-brand");
            assertEquals("Closure Display", brand.getFont().getFamily());
            var section = (Label) view().lookup(".workbench-title");
            assertEquals("Closure Pixel", section.getFont().getFamily());
            assertNotNull(view().lookup(".workbench-welcome"));
            assertButtonGraphic("workbench-new-session");
            assertButtonGraphic("workbench-open-project");
            capture("workbench-welcome");
            view().openProject(project);
            return null;
        });

        String firstPrompt = "工作台启动检查\n帮我核对桌宠入口和项目文件预览。";
        send(firstPrompt);
        var first = fx(clients::getFirst).take();
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
            ((Button) composer().getChildren().get(2)).fire();
            return null;
        });
        other.cancelled().get(5, TimeUnit.SECONDS);
        assertFalse(first.cancelled().isDone());

        String answer = "先从桌宠右键菜单打开工作台，再选择示例项目。打开 src/WorkspaceTask.java，确认能看到中文注释。";
        first.finish(answer);
        await(() -> services.getFirst().completedTurns().size() == 1);
        fx(() -> {
            assertFalse(shows(answer));
            sessions().getSelectionModel().select(1);
            return null;
        });
        await(() -> shows(answer));

        await(() -> files().getRoot() != null && files().getRoot().getChildren().size() == 4);
        fx(() -> {
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
        await(() -> view().lookup(".workbench-composer").getBoundsInParent().getWidth() < 600);
        fx(() -> {
            ChatInputBar bar = composer();
            for (int index = 1; index <= 2; index++) {
                Button button = (Button) bar.getChildren().get(index);
                assertTrue(button.getWidth() >= button.prefWidth(-1) - 1);
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
        System.out.println("CLOSURE_WORKBENCH_NATIVE_PASS theme=true sessions=true multiline=true restore=true preview=true");
    }

    private void assertButtonGraphic(String id) {
        Button button = (Button) view().lookup("#" + id);
        var graphic = assertInstanceOf(ImageView.class, button.getGraphic());
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

    private ChatInputBar composer() {
        return (ChatInputBar) view().lookup(".workbench-composer");
    }

    private void send(String text) throws Exception {
        fx(() -> {
            ChatInputBar composer = composer();
            ((TextArea) composer.getChildren().getFirst()).setText(text);
            ((Button) composer.getChildren().get(1)).fire();
            return null;
        });
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
