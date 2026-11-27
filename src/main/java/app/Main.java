package app;

import app.ui.PetWindow;
import app.ui.UiFonts;
import app.ui.chat.WorkbenchWindow;
import app.character.animation.spine.SpineModel;
import app.character.animation.spine.SpineRenderer;
import app.character.animation.sprite.SpriteModel;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class Main {
    private static final String STARTUP_SMOKE_MARKER = "CLOSURE_STARTUP_SMOKE_PASS";

    public static void main(String[] args){
        UiFonts.applySwingDefaults();
        CompletableFuture.runAsync(Main::preparePetModel).handle((ready,error) -> {
            SwingUtilities.invokeLater(() -> {
                PetWindow pet=null;
                try{
                    boolean smoke=Boolean.getBoolean("closure.smokeTest");
                    pet=smoke ? PetWindow.startupSmokeWindow() : new PetWindow();
                    pet.setVisible(true);
                    if(smoke) verifyStartupSmoke(pet);
                }catch(Throwable failure){
                    failure.printStackTrace();
                    if(Boolean.getBoolean("closure.smokeTest")){
                        if(pet!=null) pet.dispose();
                        System.exit(1);
                    }
                }
            });
            return null;
        }).join();
    }

    static void preparePetModel(){
        if("sprite".equals(System.getProperty("closure.animation","sprite"))){
            SpriteModel.local();
        }else if("spine".equals(System.getProperty("closure.animation","sprite"))){
            new SpineRenderer(SpineModel.bundled());
        }
    }

    private static boolean hasImage(Component component) {
        if(component instanceof JLabel label && label.getIcon()!=null
                && label.getIcon().getIconWidth()>0 && label.getIcon().getIconHeight()>0){
            return true;
        }
        if(component instanceof Container container){
            for(Component child:container.getComponents()){
                if(hasImage(child)) return true;
            }
        }
        return false;
    }

    private static void verifyStartupSmoke(PetWindow pet){
        SwingUtilities.invokeLater(() -> {
            try{
                pet.validate();
                if(!pet.isShowing() || !pet.isDisplayable() || !hasImage(pet.getContentPane())){
                    throw new IllegalStateException("Closure window/model did not initialize");
                }
                if(Arrays.stream(Window.getWindows()).anyMatch(WorkbenchWindow.class::isInstance)){
                    throw new IllegalStateException("Workbench must only be created from the pet menu");
                }
                Toolkit.getDefaultToolkit().sync();
                System.out.println("CLOSURE_PET_STARTUP_ONLY_PASS");
                System.out.println(STARTUP_SMOKE_MARKER);
                pet.dispose();
                System.exit(0);
            }catch(Throwable failure){
                failure.printStackTrace();
                pet.dispose();
                System.exit(1);
            }
        });
    }
}
