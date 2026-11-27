package app.ui.chat;

import javafx.animation.PauseTransition;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;

/** A view of the existing desktop renderer, never a second character/player. FX thread only. */
final class WorkbenchPetDock extends StackPane {
    private final ImageView image = new ImageView();
    private final Label bubble = new Label();
    private final PauseTransition hideBubble = new PauseTransition(Duration.seconds(5));
    private WritableImage pixels;

    WorkbenchPetDock(Runnable interact, BiConsumer<Integer, Integer> menu) {
        setId("workbench-pet-dock");
        setMinSize(196, 196); setPrefSize(196, 196); setMaxSize(196, 196);
        image.setId("workbench-mascot");
        image.getProperties().put("source", "desktop-model");
        image.setFitWidth(180); image.setFitHeight(180); image.setPreserveRatio(true);
        image.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) interact.run();
        });
        image.setOnContextMenuRequested(event -> {
            menu.accept((int) Math.round(event.getScreenX()), (int) Math.round(event.getScreenY()));
            event.consume();
        });
        bubble.setId("workbench-pet-bubble");
        bubble.getStyleClass().add("workbench-pet-bubble");
        bubble.setWrapText(true); bubble.setMaxSize(192, 58); bubble.setVisible(false); bubble.setMouseTransparent(true);
        StackPane.setAlignment(image, Pos.BOTTOM_RIGHT); StackPane.setAlignment(bubble, Pos.TOP_CENTER);
        getChildren().addAll(image, bubble);
        hideBubble.setOnFinished(event -> bubble.setVisible(false));
    }

    void showFrame(BufferedImage frame) {
        pixels = SwingFXUtils.toFXImage(frame, pixels);
        image.setImage(pixels);
    }

    void showBubble(String text, Duration duration) {
        bubble.setText(text); bubble.setVisible(true);
        hideBubble.stop(); hideBubble.setDuration(duration == null ? Duration.seconds(5) : duration); hideBubble.playFromStart();
    }

    void hideBubble() { hideBubble.stop(); bubble.setVisible(false); }
    void close() { hideBubble(); image.setImage(null); pixels = null; }
}
