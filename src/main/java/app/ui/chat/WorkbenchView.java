package app.ui.chat;

import app.service.ChatService;
import app.service.ChatSpeechController;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
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
    private final ChatSpeechController speech;
    private final WorkbenchProjectPane project;
    private final ListView<Session> sessions = new ListView<>();
    private final BorderPane conversation = new BorderPane();
    private final Label conversationTitle = new Label();
    private final SplitPane content = new SplitPane();
    private final ToggleButton showFiles = new ToggleButton("项目文件", WorkbenchTheme.icon(WorkbenchTheme.DIRECTORY));
    private double projectWidth = 306;

    WorkbenchView(Supplier<ChatService> services, Consumer<Consumer<Path>> chooseProject,
                  ChatSpeechController speech) {
        this.services = services;
        this.speech = speech;
        project = new WorkbenchProjectPane(chooseProject);
        getStyleClass().add("workbench");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("/ui/workbench.css")).toExternalForm());

        Label title = WorkbenchTheme.heading("工作台");
        title.getStyleClass().add("workbench-title");
        HBox identity = new HBox(9, WorkbenchTheme.wordmark(), title);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.getStyleClass().add("workbench-identity");

        Button add = new Button("新会话", WorkbenchTheme.icon(WorkbenchTheme.NEW_SESSION));
        add.setId("workbench-new-session");
        add.getStyleClass().add("workbench-action");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> newSession());

        Button folder = new Button("", WorkbenchTheme.icon(WorkbenchTheme.OPEN_DIRECTORY));
        folder.setId("workbench-project-entry");
        folder.getStyleClass().add("workbench-project-entry");
        folder.setMinWidth(0);
        folder.setMaxWidth(Double.MAX_VALUE);
        folder.textProperty().bind(project.directory().map(WorkbenchProjectPane::fileName).orElse("打开项目"));
        folder.setOnAction(event -> {
            showFiles.setSelected(true);
            if (project.directory().get() == null) project.chooseDirectory();
        });

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
            if (oldValue != null) oldValue.chat.setSpeechActive(false);
            conversationTitle.textProperty().unbind();
            if (selected == null) {
                conversationTitle.setText("");
                conversation.setCenter(null);
            } else {
                conversationTitle.textProperty().bind(selected.title);
                conversation.setCenter(selected.chat);
                selected.chat.setSpeechActive(isVisible());
                selected.chat.scrollToLatestMessage();
            }
        });
        visibleProperty().addListener((property, oldValue, visible) -> {
            Session selected = sessions.getSelectionModel().getSelectedItem();
            if (selected != null) selected.chat.setSpeechActive(visible);
        });

        VBox navigation = new VBox(10, identity, add, WorkbenchTheme.heading("项目"), folder,
                WorkbenchTheme.heading("会话"), sessions);
        navigation.setId("workbench-navigation");
        navigation.setPrefWidth(222);
        navigation.setMinWidth(0);
        navigation.getStyleClass().add("workbench-navigation");
        VBox.setVgrow(sessions, Priority.ALWAYS);
        setLeft(navigation);

        conversation.getStyleClass().add("workbench-conversation");
        conversation.setMinWidth(305);
        conversationTitle.getStyleClass().add("workbench-conversation-title");
        conversationTitle.setMinWidth(0);
        conversationTitle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(conversationTitle, Priority.ALWAYS);

        ToggleButton showNavigation = new ToggleButton("侧栏");
        showNavigation.setId("workbench-toggle-navigation");
        showNavigation.getStyleClass().add("workbench-panel-toggle");
        showNavigation.setTooltip(new Tooltip("显示或隐藏会话侧栏"));
        showNavigation.setSelected(true);
        showNavigation.selectedProperty().addListener((property, oldValue, visible) ->
                setLeft(visible ? navigation : null));

        showFiles.setId("workbench-toggle-files");
        showFiles.getStyleClass().add("workbench-panel-toggle");
        showFiles.setTooltip(new Tooltip("显示或隐藏项目文件"));
        showFiles.selectedProperty().addListener((property, oldValue, visible) -> setFilesVisible(visible));
        HBox toolbar = new HBox(11, showNavigation, conversationTitle, showFiles);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("workbench-toolbar");
        conversation.setTop(toolbar);

        project.setId("workbench-project-pane");
        project.setMinWidth(245);
        SplitPane.setResizableWithParent(project, false);
        content.getStyleClass().add("workbench-panels");
        content.getItems().add(conversation);
        setCenter(content);
        newSession();
    }

    private void setFilesVisible(boolean visible) {
        if (visible) {
            content.getItems().add(project);
            double available = content.getWidth();
            content.setDividerPositions(available > 0 ? 1 - projectWidth / available : 0.65);
        } else {
            if (project.getWidth() > 0) projectWidth = project.getWidth();
            content.getItems().remove(project);
        }
    }

    private void newSession() {
        Session session = new Session(services.get(), speech);
        sessions.getItems().addFirst(session);
        sessions.getSelectionModel().select(session);
    }

    void openProject(Path directory) {
        showFiles.setSelected(true);
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

        private Session(ChatService service, ChatSpeechController speech) {
            this.service = service;
            chat = new ChatView(service, this::nameFromMessage, speech);
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
