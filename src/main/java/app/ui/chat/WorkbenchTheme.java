package app.ui.chat;

import app.chat.model.MessageRole;
import app.ui.UiFonts;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import java.util.Objects;

final class WorkbenchTheme {
    private static final String ASSETS = "/ui/workbench/";
    private static final Font DISPLAY = loadFont("closure-display.ttf", 23);
    private static final Font PIXEL = loadFont("closure-pixel.ttf", 13);
    private static final Image MASCOT = loadImage("/animations/closure/open/frame_096.png");
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
        label.setFont(PIXEL);
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

    static StackPane welcome() {
        Label name = new Label(MessageRole.CLOSURE.getDisplayName());
        name.getStyleClass().add("workbench-welcome-name");
        UiFonts.applyJavaFxFont(name, 23);
        Label hint = new Label("在下方输入消息，开始对话。");
        hint.getStyleClass().add("workbench-welcome-hint");
        hint.setWrapText(true);
        VBox text = new VBox(8, name, hint);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        ImageView mascot = new ImageView(MASCOT);
        mascot.setViewport(new Rectangle2D(56, 84, 86, 98));
        mascot.setPreserveRatio(true);
        mascot.setFitHeight(116);
        mascot.setSmooth(false);
        mascot.setMouseTransparent(true);

        HBox card = new HBox(12, text, mascot);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinWidth(0);
        card.setMaxWidth(404);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.getStyleClass().add("workbench-welcome-card");
        StackPane welcome = new StackPane(card);
        StackPane.setMargin(card, new Insets(22));
        welcome.getStyleClass().add("workbench-welcome");
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
