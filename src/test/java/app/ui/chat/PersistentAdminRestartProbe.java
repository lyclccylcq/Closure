package app.ui.chat;

import app.billing.OpenAiFundsFixture;
import app.character.CharacterController;
import app.service.ChatService;
import app.ui.PetWindow;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ToggleButton;
import javax.imageio.ImageIO;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static app.ui.chat.ChatWindowLifecycleTest.fx;
import static app.ui.chat.ChatWindowLifecycleTest.swing;
import static org.junit.jupiter.api.Assertions.*;

/** Separate, disposable test application. Never uses the production credential namespace or configuration. */
public final class PersistentAdminRestartProbe {
    private static WorkbenchWindow window;

    public static void main(String[] args) {
        int exit = 0;
        try { exercise(args); }
        catch (Throwable failure) { failure.printStackTrace(); exit = 1; }
        finally { Platform.exit(); }
        System.exit(exit);
    }

    private static void exercise(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("save") || args[0].equals("restore")))
            throw new IllegalArgumentException("Synthetic save/restore modes only");
        try (var fixture = new OpenAiFundsFixture(Path.of(args[2], "synthetic-restart-funds.json"))) {
            var balances = fixture.keychainService(args[1]); // Enforces a unique test-only namespace before any IO.
            var character = new AtomicReference<CharacterController>();
            PetWindow pet = swing(() -> {
                PetWindow owner = PetWindow.startupSmokeWindow(() -> {
                    window = new WorkbenchWindow(() -> new ChatService((app.ai.AiModelClient) null), null, character.get(), balances);
                    return window;
                });
                var field = PetWindow.class.getDeclaredField("characterController"); field.setAccessible(true);
                character.set((CharacterController) field.get(owner));
                owner.setVisible(true); owner.openWorkbenchWindow(); return owner;
            });
            try {
                window.whenReady().get(10, TimeUnit.SECONDS);
                fx(() -> { ((ToggleButton) view().lookup("#workbench-toggle-balance")).fire(); return null; });
                if (args[0].equals("save")) {
                    await(() -> result().getText().contains("Admin API key"));
                    fx(() -> {
                        input().setText("synthetic-admin-restart-only"); button("workbench-admin-apply").fire();
                        assertTrue(input().getText().isEmpty()); return null;
                    });
                }
                await(() -> result().getText().contains("1.2 USD") && state().getText().contains("已保存"));
                fx(() -> {
                    assertFalse(input().getParent().isVisible()); assertTrue(input().getText().isEmpty());
                    button("workbench-admin-settings").fire(); assertTrue(input().getPromptText().contains("无需重填"));
                    assertTrue(button("workbench-admin-apply").isDisabled());
                    input().setText("synthetic-replacement-check"); assertFalse(button("workbench-admin-apply").isDisabled());
                    input().setText(""); button("workbench-admin-apply").fire(); input().fireEvent(new javafx.event.ActionEvent());
                    assertTrue(result().getText().contains("1.2 USD")); assertTrue(state().getText().contains("已保存"));
                    assertTrue(fixture.requests().stream().allMatch(request -> request.headers().firstValue("Authorization")
                            .orElse("").equals("Bearer synthetic-admin-restart-only")));
                    assertTrue(input().getText().isEmpty()); view().applyCss(); view().layout();
                    ImageIO.write(SwingFXUtils.fromFXImage(view().snapshot(null, null), null), "png",
                            Path.of(args[2], "admin-restart-" + args[0] + ".png").toFile());
                    if (args[0].equals("restore")) button("workbench-admin-clear").fire();
                    return null;
                });
                if (args[0].equals("restore")) {
                    await(() -> result().getText().contains("管理密钥已清除") && state().getText().contains("尚未配置"));
                    fx(() -> { button("workbench-balance-refresh").fire(); return null; });
                    await(() -> result().getText().contains("Admin API key") && input().getParent().isVisible());
                }
            } finally { swing(() -> { pet.dispose(); return null; }); fx(() -> null); }
        }
        System.out.println("CLOSURE_ADMIN_RESTART_CHILD_PASS mode=" + args[0]);
    }

    private static WorkbenchView view() { return (WorkbenchView) ((JFXPanel) window.getContentPane()).getScene().getRoot(); }
    private static Button button(String id) { return (Button) view().lookup("#" + id); }
    private static PasswordField input() { return (PasswordField) view().lookup("#workbench-admin-key"); }
    private static Label state() { return (Label) view().lookup("#workbench-admin-state"); }
    private static Label result() { return (Label) view().lookup("#workbench-balance-result"); }
    private static void await(Checked condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!fx(condition::test) && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(fx(condition::test));
    }
    private interface Checked { boolean test() throws Exception; }
}
