package app.ui.chat;

import app.chat.model.ChatMessage;
import app.chat.model.MessageRole;
import app.ui.UiFonts;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.net.URL;

final class MessageBubble extends HBox {
    private final Label bubbleLabel;

    MessageBubble(ChatMessage message,boolean workbench){
        super(8);
        boolean isUser=message.getRole().isUser();

        setMaxWidth(Double.MAX_VALUE);
        setMinWidth(0);
        getStyleClass().add(isUser ? "user-message-row" : "assistant-message-row");
        setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        ImageView avatarView=createAvatarView(message.getRole());
        bubbleLabel=createBubbleLabel(message.getContent(),workbench,isUser);
        if(workbench){
            // The row grows with the viewport. The label uses its natural width up to this limit.
            bubbleLabel.maxWidthProperty().bind(Bindings.max(0, widthProperty()
                    .subtract(ChatStyles.AVATAR_SIZE+getSpacing()).multiply(isUser ? 0.85 : 1)));
        }
        VBox contentColumn=createContentColumn(message,isUser);
        HBox.setHgrow(contentColumn,Priority.ALWAYS);

        if(isUser){
            getChildren().addAll(contentColumn,avatarView);
        }else{
            getChildren().addAll(avatarView,contentColumn);
        }
    }

    private VBox createContentColumn(ChatMessage message,boolean isUser){
        VBox contentColumn=new VBox(4);
        contentColumn.setMinWidth(0);
        contentColumn.setFillWidth(false);
        contentColumn.setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel=new Label(message.getRole().getDisplayName());
        nameLabel.setStyle(ChatStyles.NAME_STYLE);
        UiFonts.applyJavaFxFont(nameLabel,12);

        contentColumn.getChildren().addAll(nameLabel,bubbleLabel);
        return contentColumn;
    }

    void setContent(String content){
        bubbleLabel.setText(content);
    }

    private Label createBubbleLabel(String content,boolean workbench,boolean isUser){
        Label label=new Label(content);
        // JavaFX 自己处理文本测量和换行，避免 Swing JTextArea 手算尺寸的问题。
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(workbench ? Double.MAX_VALUE : ChatStyles.BUBBLE_MAX_WIDTH);
        if(workbench){
            label.getStyleClass().add(isUser ? "workbench-user-message" : "workbench-assistant-message");
        }else{
            label.setStyle(ChatStyles.BUBBLE_STYLE);
        }
        UiFonts.applyJavaFxFont(label,14);
        return label;
    }

    private ImageView createAvatarView(MessageRole role){
        ImageView avatarView=new ImageView();
        avatarView.setFitWidth(ChatStyles.AVATAR_SIZE);
        avatarView.setFitHeight(ChatStyles.AVATAR_SIZE);
        avatarView.setPreserveRatio(true);

        URL avatarUrl=MessageBubble.class.getResource(role.getAvatarPath());
        if(avatarUrl!=null){
            avatarView.setImage(new Image(
                    avatarUrl.toExternalForm(),
                    ChatStyles.AVATAR_SIZE,
                    ChatStyles.AVATAR_SIZE,
                    true,
                    true
            ));
        }

        return avatarView;
    }
}
