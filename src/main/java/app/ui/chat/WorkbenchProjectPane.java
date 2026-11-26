package app.ui.chat;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class WorkbenchProjectPane extends VBox {
    private static final int PREVIEW_LIMIT = 120_000;
    private static final Comparator<Entry> FILE_ORDER = Comparator
            .comparing(Entry::directory).reversed()
            .thenComparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::label);

    private final ExecutorService reader = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "workbench-project-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final Label location = new Label("未选择目录");
    private final TreeView<Entry> files = new TreeView<>();
    private final Label previewTitle = new Label("文件预览");
    private final Label previewStatus = new Label();
    private final TextArea preview = new TextArea();
    private final ReadOnlyObjectWrapper<Path> root = new ReadOnlyObjectWrapper<>();
    private final Consumer<Consumer<Path>> chooseProject;
    private Future<?> previewTask;
    private long projectRevision;
    private long previewRevision;
    private boolean closed;

    WorkbenchProjectPane(Consumer<Consumer<Path>> chooseProject) {
        super(12);
        this.chooseProject = chooseProject;
        getStyleClass().add("workbench-project");
        Label caption = WorkbenchTheme.heading("项目文件");
        caption.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(caption, Priority.ALWAYS);
        Button choose = new Button("选择目录", WorkbenchTheme.icon(WorkbenchTheme.OPEN_DIRECTORY));
        choose.setId("workbench-open-project");
        choose.getStyleClass().add("workbench-action");
        choose.setOnAction(event -> chooseDirectory());
        HBox heading = new HBox(8, caption, choose);
        heading.setAlignment(Pos.CENTER_LEFT);

        location.getStyleClass().add("workbench-path");
        location.setMaxWidth(Double.MAX_VALUE);

        files.setId("workbench-project-tree");
        files.setShowRoot(false);
        files.setAccessibleText("项目文件树");
        files.getStyleClass().add("workbench-file-tree");
        files.setCellFactory(tree -> new TreeCell<>() {
            private final ImageView folder = WorkbenchTheme.icon(WorkbenchTheme.DIRECTORY);

            @Override
            protected void updateItem(Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.label());
                    setGraphic(item.directory() ? folder : null);
                }
            }
        });
        files.getSelectionModel().selectedItemProperty().addListener((property, previous, selected) ->
                display(selected == null ? null : selected.getValue()));

        previewTitle.getStyleClass().add("workbench-preview-title");
        previewTitle.setMaxWidth(Double.MAX_VALUE);
        preview.setId("workbench-file-preview");
        preview.setAccessibleText("UTF-8 文件预览");
        preview.setEditable(false);
        preview.setWrapText(false);
        preview.setPromptText("从项目中选择一个文件");
        preview.getStyleClass().add("workbench-preview");
        previewStatus.getStyleClass().add("workbench-path");
        previewStatus.visibleProperty().bind(previewStatus.textProperty().isNotEmpty());
        previewStatus.managedProperty().bind(previewStatus.visibleProperty());
        VBox.setVgrow(preview, Priority.ALWAYS);
        VBox previewPane = new VBox(8, previewTitle, preview, previewStatus);
        previewPane.getStyleClass().add("workbench-preview-pane");

        SplitPane browser = new SplitPane(files, previewPane);
        browser.setOrientation(Orientation.VERTICAL);
        browser.setDividerPositions(0.43);
        browser.getStyleClass().add("workbench-browser");
        VBox.setVgrow(browser, Priority.ALWAYS);
        getChildren().addAll(heading, location, browser);
    }

    ReadOnlyObjectProperty<Path> directory() {
        return root.getReadOnlyProperty();
    }

    void chooseDirectory() {
        chooseProject.accept(this::openProject);
    }

    void openProject(Path directory) {
        if (closed) return;
        long revision = ++projectRevision;
        root.set(null);
        files.setRoot(null);
        display(null);
        showLocation(directory, "正在读取目录…");
        reader.execute(() -> {
            try {
                Path actual = directory.toRealPath();
                List<Entry> entries = list(actual, actual);
                Platform.runLater(() -> {
                    if (closed || revision != projectRevision) return;
                    root.set(actual);
                    showLocation(actual, fileName(actual));
                    TreeItem<Entry> project = new TreeItem<>(new Entry(actual, fileName(actual), true));
                    project.getChildren().setAll(entries.stream().map(e -> item(e, actual, revision)).toList());
                    project.setExpanded(true);
                    files.setRoot(project);
                });
            } catch (IOException | SecurityException failure) {
                Platform.runLater(() -> {
                    if (!closed && revision == projectRevision) showLocation(directory, "无法读取项目目录");
                });
            }
        });
    }

    private void showLocation(Path path, String label) {
        location.setText(label);
        location.setTooltip(new Tooltip(path.toString()));
    }

    private TreeItem<Entry> item(Entry entry, Path project, long revision) {
        TreeItem<Entry> item = new TreeItem<>(entry);
        if (!entry.directory()) return item;

        TreeItem<Entry> loading = new TreeItem<>(new Entry(null, "正在读取…", false));
        item.getChildren().add(loading);
        item.expandedProperty().addListener((property, collapsed, expanded) -> {
            if (closed || revision != projectRevision || !expanded
                    || item.getChildren().isEmpty() || item.getChildren().getFirst() != loading) return;
            reader.execute(() -> {
                try {
                    List<Entry> entries = list(entry.path(), project);
                    Platform.runLater(() -> {
                        if (closed || revision != projectRevision) return;
                        item.getChildren().setAll(entries.stream().map(e -> item(e, project, revision)).toList());
                    });
                } catch (IOException | SecurityException failure) {
                    Platform.runLater(() -> {
                        if (!closed && revision == projectRevision) {
                            item.getChildren().setAll(List.of(new TreeItem<>(new Entry(null, "无法读取目录", false))));
                        }
                    });
                }
            });
        });
        return item;
    }

    private void display(Entry entry) {
        long revision = ++previewRevision;
        if (previewTask != null) previewTask.cancel(true);
        preview.clear();
        previewTitle.setText("文件预览");
        previewStatus.setText("");
        Path project = root.get();
        if (closed || project == null || entry == null || entry.path() == null || entry.directory()) return;
        previewTitle.setText(project.relativize(entry.path()).toString());
        previewStatus.setText("正在读取…");
        previewTask = reader.submit(() -> {
            try {
                Preview result = readPreview(inside(entry.path(), project));
                Platform.runLater(() -> {
                    if (closed || revision != previewRevision) return;
                    preview.setText(result.text());
                    preview.positionCaret(0);
                    previewStatus.setText(result.note());
                });
            } catch (MalformedInputException failure) {
                showPreviewFailure(revision, "此文件无法按 UTF-8 文本预览");
            } catch (IOException | SecurityException failure) {
                showPreviewFailure(revision, "无法读取此文件");
            }
        });
    }

    private void showPreviewFailure(long revision, String message) {
        Platform.runLater(() -> {
            if (!closed && revision == previewRevision) previewStatus.setText(message);
        });
    }

    private static List<Entry> list(Path directory, Path project) throws IOException {
        try (var children = Files.list(inside(directory, project))) {
            return children.filter(path -> !fileName(path).equals(".git"))
                    .map(WorkbenchProjectPane::entry).sorted(FILE_ORDER).toList();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static Entry entry(Path path) {
        return new Entry(path, fileName(path), Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static Path inside(Path path, Path project) throws IOException {
        Path actual = path.toRealPath();
        if (!actual.startsWith(project)) throw new IOException("Path outside the selected project");
        return actual;
    }

    static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static Preview readPreview(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Not a regular file");
        char[] text = new char[PREVIEW_LIMIT + 1];
        int length = 0;
        try (Reader source = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            while (length < text.length) {
                int received = source.read(text, length, text.length - length);
                if (received < 0) break;
                length += received;
            }
        }
        boolean truncated = length > PREVIEW_LIMIT;
        int shown = Math.min(length, PREVIEW_LIMIT);
        if (truncated && Character.isHighSurrogate(text[shown - 1])) shown--;
        String content = new String(text, 0, shown);
        if (content.indexOf('\0') >= 0) return new Preview("", "此文件无法按 UTF-8 文本预览");
        return new Preview(content, truncated ? "预览已截取前 " + PREVIEW_LIMIT + " 个字符" : "");
    }

    void close() {
        closed = true;
        reader.shutdownNow();
    }

    private record Entry(Path path, String label, boolean directory) {}
    private record Preview(String text, String note) {}
}
