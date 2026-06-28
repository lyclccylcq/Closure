package app;

import app.ui.PetWindow;

import javax.swing.*;

public class Main {
    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            PetWindow petWindow=new PetWindow();
            petWindow.setVisible(true);
        });
    }
}
