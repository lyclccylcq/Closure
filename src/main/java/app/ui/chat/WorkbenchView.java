package app.ui.chat;

import app.service.ChatService;
import app.ui.UiFonts;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class WorkbenchView extends BorderPane {
    private final Supplier<ChatService> services;
    private final WorkbenchProjectPane project;
    private final ListView<Session> sessions = new ListView<>();
    private final BorderPane conversation = new BorderPane();
    private final Label conversationTitle = new Label();

    WorkbenchView(Supplier<ChatService> services, Consumer<Consumer<Path>> chooseProject) {
        this.services = services;
        project = new WorkbenchProjectPane(chooseProject);
        getStyleClass().add("workbench");
        setStyle(UiFonts.fxStyle(""));
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("/ui/workbench.css")).toExternalForm());

        Label brand = new Label("Closure");
        brand.getStyleClass().add("workbench-brand");
        Label title = new Label("工作台");
        title.getStyleClass().add("workbench-title");
        HBox top = new HBox(12, brand, title);
        top.getStyleClass().add("workbench-top");
        top.setAlignment(Pos.CENTER_LEFT);
        setTop(top);

        Label heading = new Label("会话");
        heading.getStyleClass().add("workbench-heading");
        Button add = new Button("＋ 新会话");
        add.setId("workbench-new-session");
        add.getStyleClass().add("workbench-action");
        add.setOnAction(event -> newSession());
        HBox navigationHeader = new HBox(8, heading, add);
        navigationHeader.setAlignment(Pos.CENTER_LEFT);
        heading.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(heading, Priority.ALWAYS);

        sessions.setId("workbench-sessions");
        sessions.setAccessibleText("工作台会话");
        sessions.getStyleClass().add("workbench-session-list");
        sessions.setCellFactory(list -> new ListCell<>() {
            {
                setMinWidth(0);
                prefWidthProperty().bind(list.widthProperty().subtract(12));
                setTextOverrun(OverrunStyle.ELLIPSIS);
            }

            @Override
            protected void updateItem(Session item, boolean empty) {
                textProperty().unbind();
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    textProperty().bind(item.title);
                }
            }
        });
        sessions.getSelectionModel().selectedItemProperty().addListener((property, oldValue, selected) -> {
            conversationTitle.textProperty().unbind();
            if (selected == null) {
                conversationTitle.setText("");
                conversation.setCenter(null);
            } else {
                conversationTitle.textProperty().bind(selected.title);
                conversation.setCenter(selected.chat);
                selected.chat.scrollToLatestMessage();
            }
        });

        VBox navigation = new VBox(12, navigationHeader, sessions);
        navigation.setMinWidth(162);
        navigation.getStyleClass().add("workbench-navigation");
        VBox.setVgrow(sessions, Priority.ALWAYS);
        conversation.getStyleClass().add("workbench-conversation");
        conversation.setMinWidth(305);
        conversationTitle.getStyleClass().add("workbench-conversation-title");
        conversationTitle.setMaxWidth(Double.MAX_VALUE);
        conversation.setTop(conversationTitle);
        project.setMinWidth(245);

        SplitPane panels = new SplitPane(navigation, conversation, project);
        panels.getStyleClass().add("workbench-panels");
        panels.setDividerPositions(0.18, 0.70);
        setCenter(panels);
        newSession();
    }

    private void newSession() {
        Session session = new Session(services.get());
        sessions.getItems().addFirst(session);
        sessions.getSelectionModel().select(session);
    }

    void openProject(Path directory) {
        project.openProject(directory);
    }

    void close() {
        project.close();
        for (Session session : sessions.getItems()) {
            session.chat.dispose();
            session.service.close();
        }
        sessions.getItems().clear();
    }

    private static final class Session {
        private final ChatService service;
        private final ChatView chat;
        private final StringProperty title = new SimpleStringProperty("新会话");
        private boolean named;

        private Session(ChatService service) {
            this.service = service;
            chat = new ChatView(service, this::nameFromMessage);
        }

        private void nameFromMessage(String message) {
            if (named) return;
            String firstLine = message.strip().lines().findFirst().orElse("").strip();
            if (firstLine.codePointCount(0, firstLine.length()) > 30) {
                firstLine = firstLine.substring(0, firstLine.offsetByCodePoints(0, 30)) + "…";
            }
            title.set(firstLine);
            named = true;
        }
    }
}
