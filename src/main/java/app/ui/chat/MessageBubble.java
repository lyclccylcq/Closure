package app.ui.chat;

import app.chat.model.ChatMessage;
import app.chat.model.MessageRole;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URL;

final class MessageBubble extends HBox {
    MessageBubble(ChatMessage message){
        super(8);
        boolean isUser=message.getRole().isUser();

        setMaxWidth(Double.MAX_VALUE);
        setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        ImageView avatarView=createAvatarView(message.getRole());
        VBox contentColumn=createContentColumn(message,isUser);

        if(isUser){
            getChildren().addAll(contentColumn,avatarView);
        }else{
            getChildren().addAll(avatarView,contentColumn);
        }
    }

    private VBox createContentColumn(ChatMessage message,boolean isUser){
        VBox contentColumn=new VBox(4);
        contentColumn.setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel=new Label(message.getRole().getDisplayName());
        nameLabel.setStyle(ChatStyles.NAME_STYLE);

        Label bubbleLabel=new Label(message.getContent());
        // JavaFX 自己处理文本测量和换行，避免 Swing JTextArea 手算尺寸的问题。
        bubbleLabel.setWrapText(true);
        bubbleLabel.setMaxWidth(ChatStyles.BUBBLE_MAX_WIDTH);
        bubbleLabel.setStyle(ChatStyles.BUBBLE_STYLE);

        contentColumn.getChildren().addAll(nameLabel,bubbleLabel);
        return contentColumn;
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
