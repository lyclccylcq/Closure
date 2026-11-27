package app;

import app.service.AssistantSpeechService;
import app.service.ChatSpeechController;
import app.ui.PetWindow;
import app.ui.chat.WorkbenchWindow;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeMainStartupTest",matches="true")
class MainStartupWindowTest {
    @Test
    void startupCreatesOnlyThePetAndTheContextMenuCreatesAndRetainsTheWorkbench() throws Exception {
        var profiles=new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic",Path.of("unused-reference.wav"),"示例","zh")),message -> {},true);
        var spoken=new AtomicInteger();
        var speech=new ChatSpeechController(new AssistantSpeechService(){
            @Override public CompletableFuture<Void> speak(String text,VoiceProfile profile){
                spoken.incrementAndGet(); return CompletableFuture.completedFuture(null);
            }
            @Override public void stop(){}
        },profiles,true,message -> {});
        var created=new AtomicInteger();
        Main.preparePetModel();
        PetWindow pet=swing(() -> {
            PetWindow window=PetWindow.startupSmokeWindow(() -> {
                created.incrementAndGet();
                return WorkbenchWindow.startupSmokeWindow(speech);
            });
            var field=PetWindow.class.getDeclaredField("speechController");
            field.setAccessible(true);
            field.set(window,speech);
            window.setVisible(true);
            return window;
        });
        try{
            swing(() -> {
                assertTrue(pet.isShowing());
                assertEquals(0,created.get());
                assertFalse(Arrays.stream(Window.getWindows()).anyMatch(WorkbenchWindow.class::isInstance),
                        "Startup must not even construct a hidden workbench");
                assertNull(field(pet,"workbenchWindow"));
                openContextMenu(pet);
                assertEquals(0,created.get(),"Showing the pet menu must not create a workbench");
                JPopupMenu menu=(JPopupMenu)field(pet,"popupMenu");
                assertTrue(menu.isShowing());
                workbenchItem(menu).doClick();
                menu.setVisible(false);
                return null;
            });
            WorkbenchWindow workbench=swing(() -> (WorkbenchWindow)field(pet,"workbenchWindow"));
            assertNotNull(workbench);
            workbench.whenReady().get(10,TimeUnit.SECONDS);
            swing(() -> {
                assertEquals(1,created.get());
                assertTrue(workbench.isShowing());
                assertFalse(pet.isAlwaysOnTop());
                assertSame(speech,workbench.speechController());
                return null;
            });
            fx(() -> {
                var root=((JFXPanel)workbench.getContentPane()).getScene().getRoot();
                ((Button)root.lookup("#workbench-new-session")).fire();
                assertEquals(2,((ListView<?>)root.lookup("#workbench-sessions")).getItems().size());
                ((TextArea)root.lookup("#workbench-editor")).setText("只从模型右键菜单打开，重新打开保留输入。");
                root.applyCss(); root.layout();
                Path directory=Path.of(System.getProperty("closure.startupEvidence","target/startup"));
                Files.createDirectories(directory);
                assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),
                        "png",directory.resolve("workbench-from-pet-menu.png").toFile()));
                return null;
            });
            swing(() -> {
                workbench.dispatchEvent(new WindowEvent(workbench,WindowEvent.WINDOW_CLOSING));
                assertFalse(workbench.isVisible());
                return null;
            });
            swing(() -> {
                openContextMenu(pet);
                JPopupMenu menu=(JPopupMenu)field(pet,"popupMenu");
                assertTrue(menu.isShowing());
                workbenchItem(menu).doClick();
                menu.setVisible(false);
                assertSame(workbench,field(pet,"workbenchWindow"));
                assertTrue(workbench.isShowing());
                assertEquals(1,created.get());
                return null;
            });
            fx(() -> {
                var root=((JFXPanel)workbench.getContentPane()).getScene().getRoot();
                assertEquals(2,((ListView<?>)root.lookup("#workbench-sessions")).getItems().size());
                assertEquals("只从模型右键菜单打开，重新打开保留输入。",
                        ((TextArea)root.lookup("#workbench-editor")).getText());
                return null;
            });
            assertEquals(0,spoken.get());
            System.out.println("CLOSURE_PET_MENU_STARTUP_NATIVE_PASS petOnly=true noHiddenWorkbench=true"
                    + " menuCreates=true sameWindow=true sessions=true sharedSpeech=true noGeneration=true");
        }finally{
            swing(() -> { pet.dispose(); return null; });
            fx(() -> null);
            speech.close();
        }
    }

    private static void openContextMenu(PetWindow pet) throws Exception{
        Component panel=(Component)field(pet,"petAnimationPanel");
        panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                90,100,pet.getX()+90,pet.getY()+100,1,true,MouseEvent.BUTTON3));
    }

    private static JMenuItem workbenchItem(JPopupMenu menu){
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .filter(item -> "工作台".equals(item.getText())).findFirst().orElseThrow();
    }

    private static Object field(Object object,String name) throws Exception{
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }

    private static <T> T fx(Callable<T> action) throws Exception{
        var task=new FutureTask<>(action);Platform.runLater(task);return task.get(10,TimeUnit.SECONDS);
    }

    private static <T> T swing(Callable<T> action) throws Exception{
        var task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(15,TimeUnit.SECONDS);
    }
}
