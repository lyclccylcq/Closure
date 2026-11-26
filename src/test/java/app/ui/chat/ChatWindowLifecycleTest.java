package app.ui.chat;

import app.ai.*;
import app.character.CharacterState;
import app.character.CharacterController;
import app.character.animation.AnimationController;
import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import app.service.AssistantSpeechService;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.service.SpeechLifecycleListener;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Swing/JavaFX windows, synthetic backend and silent speech spy only. */
@EnabledIfSystemProperty(named="closure.nativeChatTest",matches="true")
class ChatWindowLifecycleTest {
    @TempDir Path temp;
    private ChatWindow window;
    private JFXPanel panel;
    private CharacterController character;

    static final class MotionSpy implements AnimationPlayer {
        volatile AnimationType current;
        final List<AnimationType> played=new CopyOnWriteArrayList<>();
        @Override public void play(AnimationType value){ current=value; played.add(value); }
        @Override public void playOnce(AnimationType value,Runnable completion){ play(value); completion.run(); }
        @Override public void stop(){ current=null; }
        @Override public boolean isPlaying(){ return current!=null; }
    }

    record Call(AiChatRequest request,AiStreamListener listener,CompletableFuture<Void> cancelled){
        void finish(String text){
            String json="{\"displayText\":\""+text+"\",\"speechText\":\""+text+"\",\"dominantEmotion\":\"NEUTRAL\"}";
            listener.onToken(json); listener.onComplete();
        }
    }

    static final class Client implements AiModelClient {
        final BlockingQueue<Call> calls=new LinkedBlockingQueue<>();
        final AtomicBoolean closed=new AtomicBoolean();
        @Override public void streamChat(AiChatRequest r,AiStreamListener l){ streamChatCancellable(r,l); }
        @Override public AiStreamCall streamChatCancellable(AiChatRequest r,AiStreamListener l){
            var cancelled=new CompletableFuture<Void>();
            calls.add(new Call(r,l,cancelled));
            return () -> cancelled.complete(null);
        }
        Call take() throws Exception {
            Call c=calls.poll(5,TimeUnit.SECONDS); assertNotNull(c); return c;
        }
        @Override public void close(){ closed.set(true); }
    }

    static final class SpeechSpy implements AssistantSpeechService {
        final BlockingQueue<String> spoken=new LinkedBlockingQueue<>();
        @Override public CompletableFuture<Void> speak(String text,VoiceProfile profile){
            spoken.add(text); return CompletableFuture.completedFuture(null);
        }
        @Override public void stop(){ }
        String take() throws Exception {
            String value=spoken.poll(5,TimeUnit.SECONDS); assertNotNull(value); return value;
        }
    }

    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);
        Platform.runLater(task); return task.get(10,TimeUnit.SECONDS);
    }

    static <T> T swing(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);
        SwingUtilities.invokeLater(task); return task.get(15,TimeUnit.SECONDS);
    }

    private void open(ChatService service,ChatSpeechController speech) throws Exception {
        swing(() -> {
            window=new ChatWindow(speech,character,service);
            window.setVisible(true);
            assertEquals(CharacterState.THINKING,character.state());
            panel=(JFXPanel)window.getContentPane().getComponent(0);
            assertTrue(window.isShowing());
            return null;
        });
        fx(() -> { assertInstanceOf(ChatView.class,panel.getScene().getRoot()); return null; });
    }

    private ChatView view(){ return (ChatView)panel.getScene().getRoot(); }
    private void send(String text) throws Exception {
        fx(() -> {
            ChatInputBar bar=(ChatInputBar)view().getBottom();
            ((TextField)bar.getChildren().get(0)).setText(text);
            ((Button)bar.getChildren().get(1)).fire();
            return null;
        });
    }
    private void button(String name) throws Exception {
        fx(() -> {
            ChatInputBar bar=(ChatInputBar)view().getBottom();
            Button b=bar.getChildren().stream().filter(n -> n instanceof Button v && name.equals(v.getText()))
                    .map(Button.class::cast).findFirst().orElseThrow();
            b.fire(); return null;
        });
    }
    private static void labels(Node node,List<String> result){
        if(node instanceof Label label) result.add(label.getText());
        if(node instanceof Parent parent) for(Node child:parent.getChildrenUnmodifiable()) labels(child,result);
    }

    private long awaitVisibleText(String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{
            if(fx(() -> {
                List<String> values=new ArrayList<>();
                labels(view(),values);
                return values.contains(expected);
            })) return System.nanoTime();
            TimeUnit.MILLISECONDS.sleep(10);
        }while(System.nanoTime()<deadline);
        return fail("Streamed text did not appear in the window: "+expected);
    }

    @Test
    void realWindowRecreationStopAndNewConversationPreserveTheRightState() throws Exception {
        var client=new Client();
        var speechSpy=new SpeechSpy();
        Path placeholder=temp.resolve("synthetic-reference.wav");
        Files.writeString(placeholder,"RIFF");
        VoiceProfile profile=new VoiceProfile("neutral",placeholder,"合成参考文本。","zh");
        var registry=new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,profile),m -> { });
        var speech=new ChatSpeechController(speechSpy,registry,true,m -> { });
        var clock=new AtomicLong();
        var motion=new MotionSpy();
        character=new CharacterController(new AnimationController(motion),clock::get,Duration.ofMinutes(2));
        speech.setSpeechLifecycleListener(new SpeechLifecycleListener(){
            @Override public void onSpeechStarted(){ character.setTalking(true); }
            @Override public void onSpeechFinished(){ character.setTalking(false); }
        });
        var service=new ChatService(client);
        try{
            open(service,speech);
            long sent=System.nanoTime();
            send("先处理第二个文件，颜色表。");
            var first=client.take();
            first.listener().onToken("{\"displayText\":\"测");
            long firstVisible=awaitVisibleText("测");
            assertTrue(service.completedTurns().isEmpty());
            assertTrue(speechSpy.spoken.isEmpty());
            first.listener().onToken("试已接收。\",\"speechText\":\"测试已接收。\",\"dominantEmotion\":\"NEUTRAL\"}");
            first.listener().onComplete();
            assertEquals("测试已接收。",speechSpy.take());
            System.out.printf("CLOSURE_CHAT_LOCAL_TIMING firstVisibleMs=%d speechRequestMs=%d%n",
                    TimeUnit.NANOSECONDS.toMillis(firstVisible-sent),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-sent));
            fx(() -> null);
            assertTrue(motion.played.contains(AnimationType.THINKING_TALK));
            assertEquals(AnimationType.THINKING,motion.current);
            swing(() -> {
                window.setVisible(true); // Repeated show must not leak another visibility lease.
                window.dispatchEvent(new java.awt.event.WindowEvent(window,java.awt.event.WindowEvent.WINDOW_CLOSING));
                assertEquals(AnimationType.IDLE,motion.current);
                clock.addAndGet(Duration.ofMinutes(2).toNanos()); character.checkIdle();
                assertEquals(AnimationType.SLEEP,motion.current);
                window.setVisible(true);
                assertEquals(AnimationType.THINKING,motion.current);
                return null;
            });
            assertEquals(1,service.completedTurns().size());
            swing(() -> { window.dispose(); return null; });
            fx(() -> null);
            assertFalse(client.closed.get());
            assertEquals(AnimationType.IDLE,motion.current);

            open(service,speech);
            List<String> text=fx(() -> {
                List<String> values=new ArrayList<>(); labels(view(),values); return values;
            });
            assertTrue(text.contains("先处理第二个文件，颜色表。"));
            assertTrue(text.contains("测试已接收。"));
            send("刚才选了哪个文件？");
            var followup=client.take();
            assertEquals(4,followup.request().getMessages().size());
            assertEquals("先处理第二个文件，颜色表。",followup.request().getMessages().get(1).getContent());
            followup.finish("颜色表。");
            assertEquals("颜色表。",speechSpy.take());

            send("这一条准备停止。");
            var cancelled=client.take();
            send("排队的这一条也停止。");
            button("停止");
            cancelled.cancelled().get(5,TimeUnit.SECONDS);
            assertNull(client.calls.poll(100,TimeUnit.MILLISECONDS));
            cancelled.finish("迟到的旧回复。");
            button("新对话");
            send("新会话第一条。");
            var fresh=client.take();
            assertEquals(2,fresh.request().getMessages().size());
            fresh.finish("新会话已开始。");
            assertEquals("新会话已开始。",speechSpy.take());
            fx(() -> null);
            clock.addAndGet(Duration.ofHours(1).toNanos()); character.checkIdle();
            assertEquals(AnimationType.THINKING,motion.current);
            assertEquals(1,service.completedTurns().size());
            assertEquals("新会话第一条。",service.completedTurns().getFirst().userText());
            assertTrue(speechSpy.spoken.isEmpty());
            System.out.println("CLOSURE_CHAT_LOCAL_SMOKE_PASS recreated=true context=true stop=true reset=true staleSpeech=false chatPose=true idleSleep=true");
        }finally{
            if(window!=null) swing(() -> { window.dispose(); return null; });
            fx(() -> null);
            service.close(); speech.close();
            character.close();
            Platform.exit();
        }
    }
}
