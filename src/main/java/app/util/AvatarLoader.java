package app.util;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

public class AvatarLoader {

    private AvatarLoader(){}

    public static ImageIcon loadAvatar(String path,int width,int height){
        URL url=AvatarLoader.class.getResource(path);

        if(url==null){
            System.out.println("图片路径不存在："+path);
            return new ImageIcon();
        }

        ImageIcon originalIcon=new ImageIcon(url);

        Image scaledImage=originalIcon.getImage().getScaledInstance(
                width,
                height,
                Image.SCALE_SMOOTH
        );

        return new ImageIcon(scaledImage);
    }
}
