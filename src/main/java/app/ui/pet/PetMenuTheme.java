package app.ui.pet;

import app.ui.UiFonts;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicCheckBoxMenuItemUI;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.GraphicsDevice;
import java.util.Objects;

public final class PetMenuTheme {
    private static final Color BACKGROUND = new Color(0x1b1831);
    private static final Color TEXT = new Color(0xe6dafa);
    private static final Color SELECTED = new Color(0x392e53);
    private static final Color ACCENT = new Color(0xc1b1ff);
    private static final Color BORDER = new Color(0x594775);
    private static final Color MUTED = new Color(0xaaa2c4);

    private PetMenuTheme() {}

    public static void apply(JPopupMenu popup) {
        popup.setUI(new BasicPopupMenuUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D painter = (Graphics2D) graphics.create();
                try {
                    painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    painter.setColor(BACKGROUND);
                    painter.fillRoundRect(1, 1, component.getWidth() - 2, component.getHeight() - 2, 20, 20);
                    painter.setColor(BORDER);
                    painter.drawRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 20, 20);
                } finally { painter.dispose(); }
            }
        });
        popup.setLightWeightPopupEnabled(false);
        popup.setOpaque(false);
        popup.setBackground(BACKGROUND);
        popup.setBorder(new EmptyBorder(7, 7, 7, 7));
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                SwingUtilities.invokeLater(() -> makeWindowTransparent(popup));
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        for (Component component : popup.getComponents()) {
            if (component instanceof JMenu menu) {
                menu.setUI(new BasicMenuUI() {
                    @Override protected void paintBackground(Graphics graphics, JMenuItem item, Color background) {
                        paintItem(graphics, item);
                    }
                    @Override protected void installDefaults() {
                        super.installDefaults();
                        selectionBackground = SELECTED;
                        selectionForeground = TEXT;
                        disabledForeground = MUTED;
                        arrowIcon = new MenuSymbol(false);
                    }
                });
                apply(menu.getPopupMenu());
            } else if (component instanceof JCheckBoxMenuItem checkbox) {
                checkbox.setUI(new BasicCheckBoxMenuItemUI() {
                    @Override protected void paintBackground(Graphics graphics, JMenuItem item, Color background) {
                        paintItem(graphics, item);
                    }
                    @Override protected void installDefaults() {
                        super.installDefaults();
                        selectionBackground = SELECTED;
                        selectionForeground = TEXT;
                        disabledForeground = MUTED;
                        checkIcon = new MenuSymbol(true);
                    }
                });
            } else if (component instanceof JMenuItem item) {
                item.setUI(new BasicMenuItemUI() {
                    @Override protected void paintBackground(Graphics graphics, JMenuItem item, Color background) {
                        paintItem(graphics, item);
                    }
                    @Override protected void installDefaults() {
                        super.installDefaults();
                        selectionBackground = SELECTED;
                        selectionForeground = TEXT;
                        disabledForeground = MUTED;
                    }
                });
            } else if (component instanceof JSeparator separator) {
                separator.setForeground(BORDER);
                separator.setBackground(BACKGROUND);
            }
            if (component instanceof JMenuItem item) {
                item.setOpaque(false);
                item.setBackground(BACKGROUND);
                item.setForeground(TEXT);
                item.setFont(UiFonts.preferredSwingFont(Font.PLAIN, 14));
                item.setBorder(new EmptyBorder(8, 11, 8, 11));
                item.setIconTextGap(9);
            }
        }
    }

    private static void makeWindowTransparent(JPopupMenu popup) {
        var window = SwingUtilities.getWindowAncestor(popup);
        if (!(window instanceof JWindow floating) || !floating.getGraphicsConfiguration().getDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) return;
        floating.setBackground(new Color(0, 0, 0, 0));
        floating.getRootPane().setOpaque(false);
        if (floating.getContentPane() instanceof JComponent content) content.setOpaque(false);
    }

    private static void paintItem(Graphics graphics, JMenuItem item) {
        if (!item.isArmed() && !(item instanceof JMenu && item.isSelected())) return;
        Graphics2D painter = (Graphics2D) graphics.create();
        try {
            painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            painter.setColor(SELECTED);
            painter.fillRoundRect(1, 1, item.getWidth() - 2, item.getHeight() - 2, 10, 10);
        } finally { painter.dispose(); }
    }

    public static Icon workbenchIcon() {
        var resource = Objects.requireNonNull(PetMenuTheme.class.getResource("/ui/workbench/folder-open-glow.png"));
        return new ImageIcon(new ImageIcon(resource).getImage().getScaledInstance(17, 16, Image.SCALE_SMOOTH));
    }

    private record MenuSymbol(boolean checkbox) implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(component.isEnabled() ? ACCENT : MUTED);
            if (checkbox) {
                graphics.drawRoundRect(x + 1, y + 1, 11, 11, 4, 4);
                if (((JMenuItem) component).isSelected()) {
                    graphics.drawLine(x + 3, y + 6, x + 6, y + 9);
                    graphics.drawLine(x + 6, y + 9, x + 10, y + 4);
                }
            } else {
                graphics.fillPolygon(new int[] {x + 5, x + 10, x + 5}, new int[] {y + 3, y + 7, y + 11}, 3);
            }
        }
    }
}
