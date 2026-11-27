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
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="closure.nativeMainStartupTest",matches="true")
class MainStartupWindowTest {
    @Test
    void workbenchAcceptsInputBeforeTheModelAndSurvivesThePetHandoff() throws Exception {
        var profiles=new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                new VoiceProfile("synthetic",Path.of("unused-reference.wav"),"示例","zh")),message -> {},true);
        var speech=new ChatSpeechController(new AssistantSpeechService(){
            @Override public CompletableFuture<Void> speak(String text,VoiceProfile profile){
                fail("Startup must not synthesize voice"); return null;
            }
            @Override public void stop(){}
        },profiles,true,message -> {});
        WorkbenchWindow workbench=swing(() -> WorkbenchWindow.startupSmokeWindow(speech));
        var pet=new AtomicReference<PetWindow>();
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var offUiThread=new AtomicBoolean();
        try{
            var completion=swing(() -> Main.openWorkbenchFirst(workbench,() -> {
                offUiThread.set(!SwingUtilities.isEventDispatchThread() && !Platform.isFxApplicationThread());
                started.countDown();
                try{
                    if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                }catch(InterruptedException failure){ throw new IllegalStateException(failure); }
                Main.preparePetModel();
            },() -> pet.set(Main.showCompanion(workbench,true))));
            assertTrue(started.await(15,TimeUnit.SECONDS));
            assertTrue(offUiThread.get());
            swing(() -> {
                assertTrue(workbench.isShowing());
                assertTrue(workbench.whenReady().isDone());
                assertFalse(Arrays.stream(Window.getWindows()).anyMatch(window ->
                        window instanceof PetWindow && window.isShowing()));
                return null;
            });
            fx(() -> {
                var root=((JFXPanel)workbench.getContentPane()).getScene().getRoot();
                ((Button)root.lookup("#workbench-new-session")).fire();
                assertEquals(2,((ListView<?>)root.lookup("#workbench-sessions")).getItems().size());
                TextArea input=(TextArea)root.lookup("#workbench-editor");
                input.setText("模型仍在加载时，工作台已经能输入！");
                capture(workbench,"workbench-before-pet");
                return null;
            });
            assertNull(pet.get());
            release.countDown();
            completion.get(90,TimeUnit.SECONDS);
            swing(() -> {
                assertTrue(pet.get().isShowing());
                assertFalse(pet.get().isAutoRequestFocus(),"The background companion must not request editor focus");
                var field=PetWindow.class.getDeclaredField("speechController");
                field.setAccessible(true);
                assertSame(speech,field.get(pet.get()));
                workbench.setVisible(false);
                pet.get().openWorkbenchWindow();
                assertTrue(workbench.isShowing());
                assertEquals(1,Arrays.stream(Window.getWindows()).filter(window ->
                        window instanceof WorkbenchWindow && window.isDisplayable()).count());
                return null;
            });
            fx(() -> {
                var root=((JFXPanel)workbench.getContentPane()).getScene().getRoot();
                assertEquals(2,((ListView<?>)root.lookup("#workbench-sessions")).getItems().size());
                assertEquals("模型仍在加载时，工作台已经能输入！",
                        ((TextArea)root.lookup("#workbench-editor")).getText());
                return null;
            });
            swing(() -> { pet.get().dispose(); return null; });
            fx(() -> null);
            // A disposed primary window must not be resurrected by a late model completion.
            WorkbenchWindow closed=swing(WorkbenchWindow::startupSmokeWindow);
            var waiting=new CountDownLatch(1);
            var finish=new CountDownLatch(1);
            var reopened=new AtomicBoolean();
            var late=swing(() -> Main.openWorkbenchFirst(closed,() -> {
                waiting.countDown();
                try { assertTrue(finish.await(10,TimeUnit.SECONDS)); }
                catch(InterruptedException failure){ throw new AssertionError(failure); }
            },() -> reopened.set(true)));
            try{
                assertTrue(waiting.await(10,TimeUnit.SECONDS));
                swing(() -> { closed.dispose(); return null; });
                finish.countDown();
                late.get(5,TimeUnit.SECONDS);
                assertFalse(reopened.get());
            }finally{
                finish.countDown();
                swing(() -> { closed.dispose(); return null; });
            }
            System.out.println("CLOSURE_MAIN_STARTUP_NATIVE_PASS firstWorkbench=true typingWhileLoading=true"
                    + " sharedSpeech=true sameWindow=true sessions=true disposedGuard=true focusRequest=false");
        }finally{
            release.countDown();
            swing(() -> {
                if(pet.get()!=null) pet.get().dispose();
                workbench.dispose();
                return null;
            });
            fx(() -> null);
            speech.close();
        }
    }

    private static void capture(WorkbenchWindow window,String name) throws Exception{
        var root=((JFXPanel)window.getContentPane()).getScene().getRoot();
        root.applyCss(); root.layout();
        Path directory=Path.of(System.getProperty("closure.startupEvidence","target/startup"));
        Files.createDirectories(directory);
        assertTrue(ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null,null),null),
                "png",directory.resolve(name+".png").toFile()));
    }

    private static <T> T fx(Callable<T> action) throws Exception{
        var task=new FutureTask<>(action); Platform.runLater(task); return task.get(10,TimeUnit.SECONDS);
    }

    private static <T> T swing(Callable<T> action) throws Exception{
        var task=new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(15,TimeUnit.SECONDS);
    }
}
