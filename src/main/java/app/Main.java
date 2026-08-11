package app;

import app.ui.PetWindow;
import app.ui.UiFonts;

import javax.swing.*;

public class Main {
    public static void main(String[] args){
        UiFonts.applySwingDefaults();
        SwingUtilities.invokeLater(()->{
            PetWindow petWindow=new PetWindow();
            petWindow.setVisible(true);
        });
    }
}
