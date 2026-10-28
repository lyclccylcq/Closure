package app;

import app.ui.PetWindow;
import app.ui.UiFonts;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;

public class Main {
    private static final String STARTUP_SMOKE_MARKER = "CLOSURE_STARTUP_SMOKE_PASS";

    public static void main(String[] args){
        UiFonts.applySwingDefaults();
        SwingUtilities.invokeLater(()->{
            if (Boolean.getBoolean("closure.smokeTest")) {
                runStartupSmoke();
                return;
            }
            PetWindow petWindow=new PetWindow();
            petWindow.setVisible(true);
        });
    }

    private static boolean hasImage(Component component) {
        if (component instanceof JLabel label && label.getIcon() != null
                && label.getIcon().getIconWidth() > 0 && label.getIcon().getIconHeight() > 0) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasImage(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void runStartupSmoke() {
        try {
            PetWindow window = PetWindow.startupSmokeWindow();
            window.setVisible(true);
            SwingUtilities.invokeLater(() -> {
                try {
                    window.validate();
                    if (!window.isShowing() || !window.isDisplayable()
                            || !hasImage(window.getContentPane())) {
                        throw new IllegalStateException("Closure window/model did not initialize");
                    }
                    Toolkit.getDefaultToolkit().sync();
                    System.out.println(STARTUP_SMOKE_MARKER);
                    window.dispose();
                    System.exit(0);
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    window.dispose();
                    System.exit(1);
                }
            });
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
