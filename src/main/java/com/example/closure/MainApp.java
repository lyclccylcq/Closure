package com.example.closure;


import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Stack;

public class MainApp extends Application {
    @Override
    public void start(Stage stage){
        Label pet=new Label("🤖");
        pet.setStyle("""
-fx-font-size: 48px;
-fx-background-color: transparent;
                """);

        //控件容器，里面只有一个pet目前
        StackPane root=new StackPane(pet);
        root.setStyle("-fx-background-color: transparent");

        Scene scene=new Scene(root,100,100);
        //实现窗口的透明无边框
        scene.setFill(Color.TRANSPARENT);
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);

        //鼠标的拖动控件
        final double[] offsetX=new double[1];
        final double[] offsetY=new double[1];

        pet.setOnMousePressed(event ->{
            offsetX[0]=event.getSceneX();
            offsetY[0]=event.getSceneY();
        });

        pet.setOnMouseDragged(mouseEvent -> {
            stage.setX(mouseEvent.getSceneX()-offsetX[0]);
            stage.setY(mouseEvent.getSceneY()-offsetY[0]);
        });

        stage.show();

    }

    public static void main(String[] args){
        launch(args);
    }
}
