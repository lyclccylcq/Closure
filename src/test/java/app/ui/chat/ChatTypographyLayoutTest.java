package app.ui.chat;

import app.chat.model.AssistantReply;
import app.chat.model.ChatMessage;
import app.chat.model.MessageRole;
import app.service.ChatService;
import app.service.ConversationHistory;
import app.ui.UiFonts;
import app.voice.model.SpeechEmotion;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

/** Real font resolution and viewport measurements, without external model or voice services. */
@EnabledIfSystemProperty(named = "closure.nativeTypographyTest", matches = "true")
class ChatTypographyLayoutTest {
    private static final String SHORT = "中午好！";
    private static final String GLYPHS = "博士，午饭吃了没？返、骨、直、令、门、话、这、关、检索增强生成。\n"
            + "全角标点！？，。；：“你好” / 半角 !?,.:; RAG 123";
    private static final String LONG = "博士，RAG 是 Retrieval-Augmented Generation，中文叫检索增强生成。"
            + "先查资料，再根据检索结果回答，保留来源和边界。\n\n"
            + (("准备文档、分块与建立索引。收到问题后检索相关段落、筛选证据，再交给模型组织回答。"
            + "中文标点应当保持完整，长句应该自然换行，聊天窗口放大后要用上新增空间。").repeat(3) + "\n\n").repeat(2);
    private WorkbenchWindow window;

    @Test
    void simplifiedChineseAndBubblesStayCorrectAcrossViewportSizesAndTextUpdates() throws Exception {
        var client = new ChatWindowLifecycleTest.Client();
        var service = new ChatService(client) {
            @Override public List<ConversationHistory.Turn> completedTurns() {
                return List.of(turn(1, SHORT, GLYPHS), turn(2, "给我讲讲 RAG", LONG));
            }
        };
        try {
            swing(() -> {
                window = new WorkbenchWindow(() -> service);
                window.setSize(1000, 760);
                window.setVisible(true);
                return null;
            });
            await(() -> view().getWidth() >= 990 && view().lookup(".workbench-transcript") != null);
            fx(() -> {
                layout();
                for (var node : view().lookupAll(".label")) {
                    Labeled label = (Labeled) node;
                    if (!label.getStyleClass().contains("workbench-brand") && !label.getText().isBlank()) {
                        assertEquals(UiFonts.DEFAULT_FAMILY, label.getFont().getFamily(), label.getText());
                    }
                }
                var title = (Label) view().lookup(".workbench-conversation-title");
                assertTrue(title.getFont().getName().contains("Bold"), title.getFont().toString());
                assertEquals(UiFonts.DEFAULT_FAMILY, editor().getFont().getFamily());
                editor().setText("输入字形核对：中午好！博士，RAG 123 !?");
                assertEquals(UiFonts.DEFAULT_FAMILY, shortBubble().getFont().getFamily());
                assertTrue(((Label) shortBubble().getParent().getChildrenUnmodifiable().getFirst())
                        .getFont().getName().contains("Bold"));
                assertEquals(SHORT, shortBubble().getText());
                assertEquals(GLYPHS, firstAssistant().getText());
                assertShortBubble();
                assertUsableWidth();
                capture("chat-layout-regular");
                return null;
            });
            double regularWidth = fx(() -> longAssistant().getWidth());
            resize(1470, 880);
            fx(() -> {
                assertShortBubble();
                assertUsableWidth();
                assertTrue(longAssistant().getWidth() > regularWidth + 300,
                        "The transcript must use the newly available viewport width");
                assertBounds();
                capture("chat-layout-wide");
                // This is the same update endpoint used by the streaming renderer.
                ((MessageBubble) shortBubble().getParent().getParent()).setContent("长消息！".repeat(120));
                return null;
            });
            fx(() -> {
                layout();
                assertTrue(shortBubble().getHeight() > new Text(SHORT).getLayoutBounds().getHeight() * 2);
                assertBounds();
                ((MessageBubble) shortBubble().getParent().getParent()).setContent(SHORT);
                return null;
            });
            fx(() -> { layout(); assertShortBubble(); return null; });
            resize(900, 620);
            fx(() -> {
                assertShortBubble();
                assertUsableWidth();
                assertBounds();
                capture("chat-layout-narrow");
                ((ToggleButton) view().lookup("#workbench-toggle-files")).fire();
                return null;
            });
            fx(() -> {
                layout();
                assertBounds();
                assertTrue(editor().getWidth() > 100);
                capture("chat-layout-panels");
                System.out.println("CLOSURE_TYPOGRAPHY_NATIVE_PASS family=" + UiFonts.DEFAULT_FAMILY
                        + " regular=true bold=true punctuation=true compactBubble=true responsive=true updates=true");
                return null;
            });
        } finally {
            if (window != null) swing(() -> { window.dispose(); return null; });
            fx(() -> null);
            service.close();
        }
    }

    private static ConversationHistory.Turn turn(long id, String user, String answer) {
        return new ConversationHistory.Turn(id, user, "", new AssistantReply(answer, "示例", SpeechEmotion.NEUTRAL));
    }

    private void resize(int width, int height) throws Exception {
        swing(() -> { window.setSize(width, height); return null; });
        await(() -> Math.abs(view().getWidth() - width) <= 2);
        fx(() -> { layout(); return null; });
    }

    private void assertShortBubble() {
        Label bubble = shortBubble();
        Text text = new Text(bubble.getText());
        text.setFont(bubble.getFont());
        double fitted = text.getLayoutBounds().getWidth() + bubble.getInsets().getLeft() + bubble.getInsets().getRight();
        assertTrue(Math.abs(bubble.getWidth() - fitted) <= 3, "short bubble=" + bubble.getWidth() + " text+padding=" + fitted);
    }

    private void assertUsableWidth() {
        double available = chat().getWidth();
        Region surface = (Region) view().lookup(".workbench-composer-surface");
        assertTrue(surface.getWidth() >= available - 55, "Composer must use the conversation width");
        assertTrue(longAssistant().getWidth() >= available - 125, "Assistant content must use the conversation width");
        assertTrue(surface.getHeight() < 150, "The composer should leave vertical space for conversation");
        System.out.printf("CLOSURE_CHAT_MEASURE window=%.0f chat=%.0f composer=%.0f assistant=%.0f short=%.0f%n",
                view().getWidth(), available, surface.getWidth(), longAssistant().getWidth(), shortBubble().getWidth());
    }

    private void assertBounds() {
        ScrollPane scroll = (ScrollPane) view().lookup(".workbench-messages");
        Region transcript = (Region) view().lookup(".workbench-transcript");
        assertTrue(transcript.getWidth() <= scroll.getViewportBounds().getWidth() + 1);
        for (var node : view().lookupAll(".label")) {
            if (!node.getStyleClass().contains("workbench-user-message")
                    && !node.getStyleClass().contains("workbench-assistant-message")) continue;
            Bounds bounds = node.localToScene(node.getBoundsInLocal());
            Bounds viewport = scroll.localToScene(scroll.getBoundsInLocal());
            assertTrue(bounds.getMinX() >= viewport.getMinX() - 1);
            assertTrue(bounds.getMaxX() <= viewport.getMaxX() + 1);
        }
    }

    private void capture(String name) throws Exception {
        ScrollPane scroll = (ScrollPane) view().lookup(".workbench-messages");
        scroll.setVvalue(scroll.getVmin());
        scroll.layout();
        Path directory = Path.of(System.getProperty("closure.typographyEvidence", "target/typography"));
        Files.createDirectories(directory);
        assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null),
                "png", directory.resolve(name + ".png").toFile()));
    }

    private void layout() { view().applyCss(); view().layout(); }

    private WorkbenchView view() { return (WorkbenchView) ((JFXPanel) window.getContentPane()).getScene().getRoot(); }
    private ChatView chat() { return (ChatView) view().lookup(".workbench-chat"); }
    private TextArea editor() { return (TextArea) view().lookup("#workbench-editor"); }
    private Label shortBubble() { return (Label) view().lookup(".workbench-user-message"); }
    private Label firstAssistant() { return (Label) view().lookup(".workbench-assistant-message"); }
    private Label longAssistant() {
        return view().lookupAll(".workbench-assistant-message").stream().map(Label.class::cast)
                .filter(label -> LONG.strip().equals(label.getText())).findFirst().orElseThrow();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (fx(condition)) return;
            TimeUnit.MILLISECONDS.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Expected typography layout did not appear");
    }
}
