package app.ui.chat;

import app.chat.model.MessageRole;
import app.ui.UiFonts;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.Objects;

final class WorkbenchTheme {
    private static final String ASSETS = "/ui/workbench/";
    private static final Font DISPLAY = loadFont("closure-display.ttf", 20);
    static final Image NEW_SESSION = loadImage(ASSETS + "plus-pixel.png");
    static final Image OPEN_DIRECTORY = loadImage(ASSETS + "folder-open-glow.png");
    static final Image DIRECTORY = loadImage(ASSETS + "folder-closed-glow.png");

    private WorkbenchTheme() {}

    static Label wordmark() {
        Label label = new Label("CLOSURE");
        label.setFont(DISPLAY);
        label.getStyleClass().add("workbench-brand");
        return label;
    }

    static Label heading(String text) {
        Label label = new Label(text);
        UiFonts.applyJavaFxFont(label, 13);
        label.getStyleClass().add("workbench-heading");
        return label;
    }

    static ImageView icon(Image image) {
        ImageView icon = new ImageView(image);
        icon.setFitWidth(16);
        icon.setFitHeight(16);
        icon.setPreserveRatio(true);
        icon.setSmooth(false);
        return icon;
    }

    static VBox welcome() {
        Label name = new Label(MessageRole.CLOSURE.getDisplayName());
        name.getStyleClass().add("workbench-welcome-name");
        UiFonts.applyJavaFxFont(name, 23);

        VBox welcome = new VBox(9, new WorkbenchMascot(), name);
        welcome.setAlignment(Pos.CENTER);
        return welcome;
    }

    private static Image loadImage(String path) {
        return new Image(Objects.requireNonNull(WorkbenchTheme.class.getResource(path)).toExternalForm());
    }

    private static Font loadFont(String file, double size) {
        var resource = WorkbenchTheme.class.getResource(ASSETS + file);
        Font font = resource == null ? null : Font.loadFont(resource.toExternalForm(), size);
        return font != null ? font : Font.font(size);
    }
}
