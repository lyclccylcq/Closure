package app;

import app.ui.PetWindow;
import app.ui.UiFonts;
import app.ui.chat.WorkbenchWindow;
import app.character.animation.spine.SpineModel;
import app.character.animation.spine.SpineRenderer;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.util.concurrent.CompletableFuture;

public class Main {
    private static final String STARTUP_SMOKE_MARKER = "CLOSURE_STARTUP_SMOKE_PASS";

    public static void main(String[] args){
        UiFonts.applySwingDefaults();
        boolean smoke=Boolean.getBoolean("closure.smokeTest");
        if(Boolean.getBoolean("closure.openWorkbench")){
            SwingUtilities.invokeLater(() -> {
                try{
                    WorkbenchWindow workbench=smoke ? WorkbenchWindow.startupSmokeWindow() : new WorkbenchWindow();
                    openWorkbenchFirst(workbench,Main::preparePetModel,() -> {
                        PetWindow pet=smoke ? PetWindow.startupSmokeWindow(workbench) : new PetWindow(workbench);
                        pet.setAlwaysOnTop(!workbench.isVisible());
                        pet.setVisible(true);
                        if(smoke) verifyStartupSmoke(pet,workbench);
                    }).exceptionally(failure -> {
                        if(smoke) failStartupSmoke(null,workbench,failure);
                        else failure.printStackTrace();
                        return null;
                    });
                }catch(Throwable failure){
                    if(smoke) failStartupSmoke(null,null,failure);
                    else failure.printStackTrace();
                }
            });
            return;
        }
        // The explicit pet-only path retains its original preparation before showing the model.
        CompletableFuture.runAsync(Main::preparePetModel).handle((ready,error) -> {
            SwingUtilities.invokeLater(() -> {
                try{
                    PetWindow pet=smoke ? PetWindow.startupSmokeWindow() : new PetWindow();
                    pet.setVisible(true);
                    if(smoke) verifyStartupSmoke(pet,null);
                }catch(Throwable failure){
                    if(smoke) failStartupSmoke(null,null,failure);
                    else failure.printStackTrace();
                }
            });
            return null;
        }).join();
    }

    static CompletableFuture<Void> openWorkbenchFirst(WorkbenchWindow workbench,Runnable preparePet,Runnable showPet){
        workbench.setVisible(true);
        workbench.toFront();
        return afterWorkbenchReady(workbench.whenReady(),preparePet,() -> {
            if(workbench.isDisplayable()) showPet.run();
        });
    }

    static CompletableFuture<Void> afterWorkbenchReady(CompletableFuture<Void> ready,Runnable preparePet,Runnable showPet){
        return ready.thenRunAsync(() -> {
            System.out.println("CLOSURE_PET_PREPARATION_STARTED");
            try{
                preparePet.run();
            }catch(RuntimeException | LinkageError failure){
                // PetAnimationPanel retains its PNG fallback; the existing workbench stays usable.
                System.err.println("桌宠模型预加载失败，继续使用动画回退：" + failure.getClass().getSimpleName());
            }
            System.out.println("CLOSURE_PET_PREPARATION_FINISHED");
        }).thenRunAsync(showPet,SwingUtilities::invokeLater);
    }

    static void preparePetModel(){
        if(!"frames".equals(System.getProperty("closure.animation","spine"))){
            new SpineRenderer(SpineModel.bundled());
        }
    }

    private static boolean hasImage(Component component) {
        if (component instanceof JLabel label && label.getIcon() != null
                && label.getIcon().getIconWidth() > 0 && label.getIcon().getIconHeight() > 0) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasImage(child)) return true;
            }
        }
        return false;
    }

    private static void verifyStartupSmoke(PetWindow pet,WorkbenchWindow workbench){
        SwingUtilities.invokeLater(() -> {
            try{
                pet.validate();
                if(!pet.isShowing() || !pet.isDisplayable() || !hasImage(pet.getContentPane())){
                    throw new IllegalStateException("Closure window/model did not initialize");
                }
                if(workbench!=null){
                    if(!workbench.isShowing() || !workbench.whenReady().isDone()
                            || workbench.whenReady().isCompletedExceptionally()){
                        throw new IllegalStateException("Workbench did not become ready before the pet");
                    }
                    System.out.println("CLOSURE_WORKBENCH_STARTUP_PASS");
                }
                Toolkit.getDefaultToolkit().sync();
                System.out.println(STARTUP_SMOKE_MARKER);
                pet.dispose();
                if(workbench!=null) workbench.dispose();
                System.exit(0);
            }catch(Throwable failure){
                failStartupSmoke(pet,workbench,failure);
            }
        });
    }

    private static void failStartupSmoke(PetWindow pet,WorkbenchWindow workbench,Throwable failure){
        failure.printStackTrace();
        if(pet!=null) pet.dispose();
        if(workbench!=null) workbench.dispose();
        System.exit(1);
    }
}
