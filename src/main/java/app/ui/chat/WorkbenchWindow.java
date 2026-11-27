package app.ui.chat;

import app.service.ChatService;
import app.service.ChatSpeechController;
import app.ui.UiFonts;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class WorkbenchWindow extends JFrame {
    private volatile boolean disposed;
    private boolean choosingProject;
    private volatile boolean windowVisible;
    private WorkbenchView view;
    private final ChatSpeechController speech;
    private final boolean ownsSpeech;

    public WorkbenchWindow() {
        this(ChatService::new, ChatSpeechController.fromEnvironment(), true);
    }

    public WorkbenchWindow(ChatSpeechController speech) {
        this(ChatService::new, speech, false);
    }

    WorkbenchWindow(Supplier<ChatService> sessions) {
        this(sessions, null, false);
    }

    WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech) {
        this(sessions, speech, false);
    }

    private WorkbenchWindow(Supplier<ChatService> sessions, ChatSpeechController speech, boolean ownsSpeech) {
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

        Platform.setImplicitExit(false);
        Platform.runLater(() -> {
            if (!disposed) {
                view = new WorkbenchView(sessions, this::chooseProject, speech);
                view.setVisible(windowVisible);
                panel.setScene(new Scene(view));
            }
        });
    }

    private void updateVisibility(boolean visible) {
        windowVisible = visible;
        Platform.runLater(() -> {
            if (view != null) view.setVisible(windowVisible);
        });
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
        Platform.runLater(() -> {
            if (view != null) view.close();
            if (ownsSpeech) speech.close();
        });
        super.dispose();
    }
}
