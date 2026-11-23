package app.character;

import app.character.animation.*;
import app.character.animation.spine.SpinePreview;
import app.ui.pet.PetAnimationPanel;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone model evaluation: speech/AI events are simulated; no assistant services are created. */
public final class SpineEvaluation {
    private SpineEvaluation() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--export")) {
            SpinePreview.main(new String[]{args[1]});
            return;
        }
        if (args.length != 0) throw new IllegalArgumentException("Usage: SpineEvaluation [--export <directory>]");
        SwingUtilities.invokeLater(SpineEvaluation::open);
    }

    private static void open() {
        JFrame window = new JFrame("Closure · Spine evaluation");
        PetAnimationPanel pet = new PetAnimationPanel(220, 220, null);
        CharacterController character = new CharacterController(new AnimationController(pet.animationPlayer()));
        JPanel stage = new JPanel(new GridBagLayout());
        stage.setBackground(new Color(243, 245, 248));
        stage.add(pet);
        JPanel controls = new JPanel(new FlowLayout());
        JCheckBox speaking = new JCheckBox("Speech active (simulated)");
        JCheckBox thinking = new JCheckBox("Thinking (simulated)");
        AtomicReference<Runnable> finishThinking = new AtomicReference<>(() -> { });
        speaking.addActionListener(event -> character.setTalking(speaking.isSelected()));
        thinking.addActionListener(event -> {
            finishThinking.getAndSet(() -> { }).run();
            if (thinking.isSelected()) finishThinking.set(character.thinkingStarted());
        });
        JButton tap = new JButton("Tap");
        tap.addActionListener(event -> character.play(AnimationType.TAP));
        JButton sleep = new JButton("Sleep");
        sleep.addActionListener(event -> {
            speaking.setSelected(false); thinking.setSelected(false);
            character.setTalking(false); finishThinking.getAndSet(() -> { }).run();
            character.play(AnimationType.SLEEP);
        });
        JButton idle = new JButton("Idle");
        idle.addActionListener(event -> {
            speaking.setSelected(false); thinking.setSelected(false);
            character.setTalking(false); finishThinking.getAndSet(() -> { }).run();
            character.play(AnimationType.IDLE);
        });
        JButton background = new JButton("Light / dark");
        background.addActionListener(event -> stage.setBackground(stage.getBackground().getRed() > 100
                ? new Color(27, 32, 40) : new Color(243, 245, 248)));
        controls.add(tap); controls.add(idle); controls.add(sleep); controls.add(background);
        JPanel events = new JPanel(new FlowLayout());
        events.add(speaking); events.add(thinking);
        JPanel footer = new JPanel(new GridLayout(0, 1));
        JLabel status = new JLabel("Evaluation only · no AI, TTS, weather or reminders", SwingConstants.CENTER);
        footer.add(controls); footer.add(events); footer.add(status);
        Timer stateTimer = new Timer(100, event -> status.setText("State: " + character.state() + " · " + pet.rendererName()
                + " · simulated events, no services"));
        stateTimer.start();
        window.add(stage, BorderLayout.CENTER);
        window.add(footer, BorderLayout.SOUTH);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                stateTimer.stop(); character.close(); pet.dispose();
            }
        });
        window.setSize(600, 440);
        window.setMinimumSize(new Dimension(600, 440));
        window.setLocationRelativeTo(null);
        character.play(AnimationType.GREETING);
        window.setVisible(true);
    }
}
