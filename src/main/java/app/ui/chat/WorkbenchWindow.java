package app.ui.chat;

import app.service.ChatService;
import app.billing.BalanceService;
import app.billing.BalanceException;
import app.character.CharacterActivityListener;
import app.service.ChatSpeechController;
import app.ui.UiFonts;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

public final class WorkbenchWindow extends JFrame {
    private volatile boolean disposed;
    private boolean choosingProject;
    private volatile boolean windowVisible;
    private WorkbenchView view;
    private final ChatSpeechController speech;
    private final boolean ownsSpeech;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private AnimationTimer firstFrame;
    private final AtomicLong petEpoch = new AtomicLong();
    private final AtomicReference<PetFrame> petFrame = new AtomicReference<>();
    private final AtomicBoolean petFrameQueued = new AtomicBoolean();
    private Runnable petInteraction = () -> { };
    private BiConsumer<Integer, Integer> petMenu = (x, y) -> { };
    private record PetFrame(long epoch, BufferedImage image) { }

    public WorkbenchWindow() {
        this(ChatService::new, ChatSpeechController.fromEnvironment(), true);
    }

    public WorkbenchWindow(ChatSpeechController speech) {
        this(ChatService::new, speech, false);
    }

    public WorkbenchWindow(ChatSpeechController speech, CharacterActivityListener activity) {
        this(ChatService::new, speech, false, activity);
    }

    /** Real workbench UI with no user configuration or external model/voice services. */
    public static WorkbenchWindow startupSmokeWindow() {
        return startupSmokeWindow(null);
    }

    public static WorkbenchWindow startupSmokeWindow(ChatSpeechController speech) {
        return new WorkbenchWindow(() -> new ChatService((app.ai.AiModelClient) null), speech, false,
                CharacterActivityListener.NONE,
                () -> CompletableFuture.failedFuture(new BalanceException("离线预览不查询账户余额。")));
    }

    public ChatSpeechController speechController() {
        return speech;
    }

    public CompletableFuture<Void> whenReady() {
        return ready.copy();
    }

    WorkbenchWindow(Supplier<ChatService> sessions) {
        this(sessions, null, false);
    }

    WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech) {
        this(sessions, speech, false);
    }

    WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech, CharacterActivityListener activity) {
        this(sessions, speech, false, activity);
    }

    private WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech, boolean ownsSpeech) {
        this(sessions, speech, ownsSpeech, CharacterActivityListener.NONE);
    }

    private WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech, boolean ownsSpeech,
                            CharacterActivityListener activity) {
        this(sessions, speech, ownsSpeech, activity, BalanceService.fromEnvironment());
    }

    WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech,
                    CharacterActivityListener activity, BalanceService balances) {
        this(sessions, speech, false, activity, balances);
    }

    private WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech, boolean ownsSpeech,
                            CharacterActivityListener activity, BalanceService balances) {
        super("Closure 工作台");
        this.speech = speech;
        this.ownsSpeech = ownsSpeech;
        UiFonts.applySwingDefaults();
        JFXPanel panel = new JFXPanel();
        setContentPane(panel);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(900, screen.width), Math.min(560, screen.height)));
        setSize(Math.min(1260, screen.width), Math.min(780, screen.height));
        setLocationRelativeTo(null);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent event) { updateVisibility(true); }
            @Override public void componentHidden(ComponentEvent event) { updateVisibility(false); }
        });
        addWindowStateListener(event -> updateVisibility(isVisible() && (getExtendedState() & ICONIFIED) == 0));

        Platform.setImplicitExit(false);
        Platform.runLater(() -> {
            if (!disposed) {
                try {
                    view = new WorkbenchView(sessions, this::chooseProject, speech, activity, balances);
                    view.setVisible(windowVisible);
                    panel.setScene(new Scene(view));
                    publishPetFrame();
                    firstFrame = new AnimationTimer() {
                        @Override public void handle(long now) {
                            if (ready.isCompletedExceptionally()) { stop(); return; }
                            if (!windowVisible || view.getWidth() <= 0 || view.getHeight() <= 0) return;
                            stop();
                            try {
                                view.applyCss();
                                view.layout();
                                System.out.println("CLOSURE_WORKBENCH_READY");
                                System.out.println("CLOSURE_WORKBENCH_OPENED");
                                ready.complete(null);
                            } catch (RuntimeException | LinkageError failure) {
                                ready.completeExceptionally(failure);
                            }
                        }
                    };
                    firstFrame.start();
                } catch (RuntimeException | LinkageError failure) {
                    ready.completeExceptionally(failure);
                }
            }
        });
    }

    private void updateVisibility(boolean visible) {
        windowVisible = visible && (getExtendedState() & ICONIFIED) == 0;
        Platform.runLater(() -> {
            if (view != null) view.setVisible(windowVisible);
        });
    }

    /** The owner's existing renderer supplies immutable, completed frames on the EDT. */
    public void showPetFrame(BufferedImage image) {
        if (disposed) return;
        petFrame.set(new PetFrame(petEpoch.get(), image));
        publishPetFrame();
    }

    private void publishPetFrame() {
        if (disposed || !petFrameQueued.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            petFrameQueued.set(false);
            if (disposed || view == null) return;
            PetFrame frame = petFrame.getAndSet(null);
            if (frame != null && frame.epoch() == petEpoch.get() && windowVisible) {
                view.showPetFrame(frame.image(), () -> SwingUtilities.invokeLater(() -> {
                    if (!disposed && windowVisible) petInteraction.run();
                }), (x, y) -> SwingUtilities.invokeLater(() -> {
                    if (!disposed && windowVisible) petMenu.accept(x, y);
                }));
            }
        });
    }

    public void setPetActions(Runnable interact, BiConsumer<Integer, Integer> menu) {
        petInteraction = interact; petMenu = menu;
    }

    public void clearPetFrames() {
        petEpoch.incrementAndGet(); petFrame.set(null); hidePetBubble();
    }

    public void showPetBubble(String text, javafx.util.Duration duration) {
        if (!disposed) Platform.runLater(() -> {
            if (view != null && windowVisible) view.showPetBubble(text, duration);
        });
    }

    public void hidePetBubble() {
        if (!disposed) Platform.runLater(() -> { if (view != null) view.hidePetBubble(); });
    }

    private void chooseProject(Consumer<Path> onSelected) {
        SwingUtilities.invokeLater(() -> {
            if (disposed || choosingProject || !isShowing()) return;
            choosingProject = true;
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("选择项目目录");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path selected = chooser.getSelectedFile().toPath();
                    Platform.runLater(() -> {
                        if (!disposed) onSelected.accept(selected);
                    });
                }
            } finally {
                choosingProject = false;
            }
        });
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        petEpoch.incrementAndGet(); petFrame.set(null);
        ready.completeExceptionally(new CancellationException("Workbench disposed before startup completed"));
        Platform.runLater(() -> {
            if (firstFrame != null) firstFrame.stop();
            if (view != null) view.close();
            if (ownsSpeech) speech.close();
        });
        super.dispose();
    }
}
