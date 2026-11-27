package app.ui.pet;

import app.character.CharacterController;
import app.character.CharacterState;
import app.character.animation.AnimationType;
import app.character.animation.spine.SpineAnimationPlayer;
import app.character.animation.spine.SpineModel;
import app.character.animation.spine.SpineRenderer;
import app.service.AssistantSpeechService;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.ui.PetWindow;
import app.ui.chat.ChatWindow;
import app.voice.VoiceProfileRegistry;
import app.voice.model.SpeechEmotion;
import app.voice.model.VoiceProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Only this smoke pet and its fake-backed chat window are used; no real config/audio/services. */
@EnabledIfSystemProperty(named="closure.nativeTransitionTest",matches="true")
class PetPointerWindowTest {
    @TempDir Path temporary;
    private String previousRenderer;
    @BeforeEach void useLegacyRig() { previousRenderer = System.getProperty("closure.animation"); System.setProperty("closure.animation", "spine"); }
    private PetWindow window;
    private PetAnimationPanel panel;
    private CharacterController character;
    private JLabel label;
    private final AtomicInteger aiCalls=new AtomicInteger();
    private final AtomicInteger speechCalls=new AtomicInteger();

    @AfterEach
    void closeWindowsAndWorker() throws Exception {
        if(window!=null) edt(()->{window.dispose();return null;});
        if(panel!=null && panel.animationPlayer() instanceof SpineAnimationPlayer spine){
            assertTrue(spine.awaitClosed(1500));
        }
        if (previousRenderer == null) System.clearProperty("closure.animation");
        else System.setProperty("closure.animation", previousRenderer);
    }

    @Test
    void pressWaistLiftLandingAndActualDialogTransitionHavePairedLifecycles() throws Exception {
        new SpineRenderer(SpineModel.bundled());
        edt(()->{
            window=PetWindow.startupSmokeWindow();
            panel=(PetAnimationPanel)field(window,"petAnimationPanel");
            label=(JLabel)panel.getComponent(0);
            character=(CharacterController)field(window,"characterController");
            character.play(AnimationType.IDLE);
            var profiles=new VoiceProfileRegistry(Map.of(SpeechEmotion.NEUTRAL,
                    new VoiceProfile("synthetic",temporary.resolve("unused.wav"),"Synthetic","zh")),message->{},true);
            AssistantSpeechService silent=new AssistantSpeechService(){
                public CompletableFuture<Void> speak(String text,VoiceProfile profile){
                    speechCalls.incrementAndGet();return CompletableFuture.completedFuture(null);
                }
                public void stop(){}
            };
            setField(window,"speechController",new ChatSpeechController(silent,profiles,true,message->{}));
            setField(window,"chatService",new ChatService((request,listener)->{
                aiCalls.incrementAndGet();throw new AssertionError("Opening a dialog must not call AI");
            }));
            var menu=window.getClass().getDeclaredMethod("initPopupMenu");
            menu.setAccessible(true);menu.invoke(window);
            window.setVisible(true);
            return null;
        });
        awaitFrames(3);
        capture("idle.png");
        Point original=edt(()->window.getLocation());
        Point grip=new Point(original.x+110,original.y+145);
        edt(()->{
            press(grip,1);
            assertEquals(CharacterState.PRESSED,character.state());
            drag(new Point(grip.x+2,grip.y+2));
            assertEquals(original,window.getLocation());
            assertEquals(CharacterState.PRESSED,character.state());
            assertFalse(panel.isFacingLeft());
            return null;
        });
        awaitFrames(7); capture("pressed.png");
        edt(()->{
            drag(new Point(grip.x+40,grip.y+20));
            assertEquals(new Point(original.x+40,original.y+20),window.getLocation());
            assertEquals(CharacterState.DRAGGING,character.state());
            assertTrue(panel.isFacingLeft(),"Rightward drag faces left");
            assertTrue((float)field(panel.animationPlayer(),"dragMotion")<0,"Mirrored sway uses local motion");
            character.setTalking(true);
            assertEquals(CharacterState.DRAGGING,character.state());
            character.setTalking(false);
            return null;
        });
        awaitFrames(9); capture("waist-lift.png");
        capture("drag-right-facing-left.png");
        edt(()->{
            drag(new Point(grip.x+39,grip.y+20));
            assertTrue(panel.isFacingLeft(),"Small reversal must not flip facing");
            drag(new Point(grip.x+40,grip.y+35));
            assertTrue(panel.isFacingLeft(),"Vertical movement retains facing");
            drag(new Point(grip.x+20,grip.y+35));
            assertFalse(panel.isFacingLeft(),"Leftward drag faces right");
            assertTrue((float)field(panel.animationPlayer(),"dragMotion")<0,"Unmirrored sway uses screen motion");
            drag(new Point(grip.x+20,grip.y+55));
            assertFalse(panel.isFacingLeft());
            assertEquals(new Point(original.x+20,original.y+55),window.getLocation());
            return null;
        });
        awaitFrames(9); capture("drag-left-facing-right.png");
        edt(()->{
            drag(grip);
            assertEquals(original,window.getLocation());
            release(grip,1);
            assertEquals(CharacterState.LANDING,character.state());
            assertFalse(panel.isFacingLeft(),"Landing keeps the last held direction");
            click(grip,1); // A drag returning exactly to its origin still must not become a click.
            assertNull(field(window,"interactionClickTimer"));
            assertNull(field(window,"speechBubbleWindow"));
            return null;
        });
        awaitFrames(6); capture("landing.png");
        await(()->character.state()==CharacterState.IDLE,2000);
        edt(()->{
            assertFalse(panel.isFacingLeft());
            press(grip,1);
            var right=new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                    grip.x-window.getX(),grip.y-window.getY(),grip.x,grip.y,1,true,MouseEvent.BUTTON3);
            for(var listener:panel.getMouseListeners()) listener.mousePressed(right);
            assertEquals(CharacterState.IDLE,character.state());
            JPopupMenu popup=(JPopupMenu)field(window,"popupMenu");
            assertTrue(popup.isVisible());popup.setVisible(false);
            release(grip,1);
            press(grip,1);release(grip,1);click(grip,1);
            press(grip,2);release(grip,2);click(grip,2);
            assertNull(field(window,"interactionClickTimer"));
            assertTrue(((ChatWindow)field(window,"chatWindow")).isVisible());
            assertEquals(CharacterState.THINKING,character.state());
            return null;
        });
        awaitFrames(3); capture("conversation-opening.png");
        awaitFrames(10); capture("conversation-seated.png");
        edt(()->{
            assertEquals(CharacterState.THINKING,character.state());
            character.setTalking(true);
            assertEquals(CharacterState.TALKING,character.state());
            character.setTalking(false);
            assertEquals(CharacterState.THINKING,character.state());
            ((ChatWindow)field(window,"chatWindow")).setVisible(false);
            assertEquals(CharacterState.IDLE,character.state());

            JPopupMenu popup=(JPopupMenu)field(window,"popupMenu");
            var right=new MouseEvent(panel,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,
                    110,145,window.getX()+110,window.getY()+145,1,true,MouseEvent.BUTTON3);
            for(var listener:panel.getMouseListeners()) listener.mousePressed(right);
            return null;
        });
        edt(()->{
            JPopupMenu popup=(JPopupMenu)field(window,"popupMenu");
            assertTrue(popup.isShowing());
            assertEquals(new java.awt.Color(0x1b1831),popup.getBackground());
            var popupWindow=SwingUtilities.getWindowAncestor(popup);
            if(popupWindow.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                    java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)){
                assertEquals(0,popupWindow.getBackground().getAlpha());
            }
            assertEquals("工作台",((JMenuItem)popup.getComponent(0)).getText());
            captureMenu(popup,"pet-menu.png");
            JMenu actions=java.util.Arrays.stream(popup.getComponents()).filter(JMenu.class::isInstance)
                    .map(JMenu.class::cast).findFirst().orElseThrow();
            actions.setPopupMenuVisible(true);
            assertEquals(popup.getBackground(),actions.getPopupMenu().getBackground());
            JCheckBoxMenuItem autonomous=java.util.Arrays.stream(actions.getMenuComponents())
                    .filter(JCheckBoxMenuItem.class::isInstance).map(JCheckBoxMenuItem.class::cast)
                    .findFirst().orElseThrow();
            assertTrue(autonomous.isSelected());
            autonomous.setArmed(true);
            captureMenu(actions.getPopupMenu(),"pet-actions-menu.png");
            actions.setPopupMenuVisible(false);
            javax.swing.JMenuItem workbenchItem=java.util.Arrays.stream(popup.getComponents())
                    .filter(javax.swing.JMenuItem.class::isInstance).map(javax.swing.JMenuItem.class::cast)
                    .filter(item -> "工作台".equals(item.getText())).findFirst().orElseThrow();
            for (int id : new int[] {MouseEvent.MOUSE_ENTERED,MouseEvent.MOUSE_PRESSED,MouseEvent.MOUSE_RELEASED}) {
                workbenchItem.dispatchEvent(new MouseEvent(workbenchItem,id,System.currentTimeMillis(),0,
                        workbenchItem.getWidth()/2,workbenchItem.getHeight()/2,1,false,MouseEvent.BUTTON1));
            }
            var workbench=(app.ui.chat.WorkbenchWindow)field(window,"workbenchWindow");
            assertTrue(workbench.isShowing());
            assertFalse(window.isAlwaysOnTop());
            popup.setVisible(false);
            workbench.setVisible(false);
            workbenchItem.doClick();
            assertSame(workbench,field(window,"workbenchWindow"));
            assertTrue(workbench.isShowing());
            return null;
        });
        assertEquals(0,aiCalls.get());
        assertEquals(0,speechCalls.get());
        System.out.println("CLOSURE_POINTER_NATIVE_PASS pressed=true waistLift=true dragFacing=true landing=true dragClickSuppressed=true dialog=true noModelCalls=true");
    }

    private void captureMenu(JPopupMenu menu,String name) throws Exception {
        String output=System.getProperty("closure.menuEvidence");
        if(output==null) return;
        BufferedImage image=new BufferedImage(menu.getWidth(),menu.getHeight(),BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();
        try{menu.paint(graphics);}finally{graphics.dispose();}
        assertEquals(0,image.getRGB(0,0)>>>24,"Menu corners must be transparent");
        assertEquals(255,image.getRGB(menu.getWidth()/2,12)>>>24,"Menu body must remain readable");
        Path directory=Path.of(output);Files.createDirectories(directory);
        assertTrue(ImageIO.write(image,"png",directory.resolve(name).toFile()));
    }

    private void press(Point screen,int count){
        var event=mouse(MouseEvent.MOUSE_PRESSED,screen,count);
        for(var listener:panel.getMouseListeners()) listener.mousePressed(event);
    }
    private void drag(Point screen){
        var event=mouse(MouseEvent.MOUSE_DRAGGED,screen,1);
        for(var listener:panel.getMouseMotionListeners()) listener.mouseDragged(event);
    }
    private void release(Point screen,int count){
        var event=mouse(MouseEvent.MOUSE_RELEASED,screen,count);
        for(var listener:panel.getMouseListeners()) listener.mouseReleased(event);
    }
    private void click(Point screen,int count){
        var event=mouse(MouseEvent.MOUSE_CLICKED,screen,count);
        for(var listener:panel.getMouseListeners()) listener.mouseClicked(event);
    }
    private MouseEvent mouse(int id,Point screen,int count){
        return new MouseEvent(panel,id,System.currentTimeMillis(),0,screen.x-window.getX(),screen.y-window.getY(),
                screen.x,screen.y,count,false,MouseEvent.BUTTON1);
    }

    private void awaitFrames(int count) throws Exception {
        var latch=new CountDownLatch(count);
        java.beans.PropertyChangeListener listener=event->latch.countDown();
        edt(()->{label.addPropertyChangeListener("icon",listener);return null;});
        try{assertTrue(latch.await(3,TimeUnit.SECONDS),"Expected complete model frames");}
        finally{edt(()->{label.removePropertyChangeListener("icon",listener);return null;});}
    }

    private void await(BooleanSupplier condition,long timeout) throws Exception {
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
        while(System.nanoTime()<until){
            if(edt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        fail("Gesture did not settle");
    }

    private void capture(String name) throws Exception {
        String output=System.getProperty("closure.transitionEvidence");
        if(output==null) return;
        BufferedImage image=edt(()->{
            var frame=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_ARGB);
            var graphics=frame.createGraphics();
            try{panel.paint(graphics);}finally{graphics.dispose();}
            return frame;
        });
        Path directory=Path.of(output); Files.createDirectories(directory);
        ImageIO.write(image,"png",directory.resolve(name).toFile());
    }
    private static Object field(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void setField(Object object,String name,Object value) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var result=new FutureTask<>(action);SwingUtilities.invokeAndWait(result);return result.get(10,TimeUnit.SECONDS);
    }
}
