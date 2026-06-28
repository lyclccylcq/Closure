package app.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class PetWindow extends JFrame {

    //桌宠图标大小
    private static final int PET_SIZE=120;

    //设置图片路径
    private static final String PET_IMAGE_PATH="assets/closure_logo.png";

    //聊天窗口
    private final ChatWindow chatWindow =new ChatWindow();

    //当前鼠标位置
    private Point mousePressedPoint;

    //右键菜单栏
    private JPopupMenu popupMenu;

    //构造方法
    public PetWindow(){
        initWindow();
        initPetIcon();
        initPopupMenu();
    }

    //初始化窗口
    private void initWindow(){
        //去掉边框
        setUndecorated(true);

        //保持窗口在最前面
        setAlwaysOnTop(true);

        //设置透明度
        setBackground(new Color(0,0,0,0));

        //设置窗口大小
        setSize(PET_SIZE,PET_SIZE);

        //设置初始位置
        setLocation(1000,500);

        //关闭主窗口时候默认退出程序
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    //初始化图标
    private void initPetIcon(){
        JLabel petLabel=new JLabel();

        ImageIcon icon=new ImageIcon(PET_IMAGE_PATH);

        //缩放图片
        Image scaledImage =icon.getImage().getScaledInstance(
                PET_SIZE,
                PET_SIZE,
                //让缩放平滑
                Image.SCALE_SMOOTH
        );

        petLabel.setIcon(new ImageIcon(scaledImage));

        //让图片居中
        petLabel.setHorizontalAlignment(SwingConstants.CENTER);
        petLabel.setVerticalAlignment(SwingConstants.CENTER);

        //把图片加进窗口
        add(petLabel);

        //鼠标事件解析
        MouseAdapter mouseAdapter=new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event){
                mousePressedPoint=event.getPoint();

                //判断是不是右键
                if(event.isPopupTrigger()){
                    showPopupMenu(event);
                }
            }

            @Override
            public void mouseDragged(MouseEvent event){
                Point currentLocation=getLocation();

                int newX=currentLocation.x+event.getX()-mousePressedPoint.x;
                int newY=currentLocation.y+event.getY()-mousePressedPoint.y;

                setLocation(newX,newY);
            }

            //松开后再判断一次（不同系统不同）
            @Override
            public void mouseReleased(MouseEvent event){
                if(event.isPopupTrigger()){
                    showPopupMenu(event);
                }
            }

            @Override
            public void mouseClicked(MouseEvent event){
                //双击打开聊天窗口
                if(SwingUtilities.isLeftMouseButton(event)&& event.getClickCount()==2){
                    openChatWindow();
                }
            }
        };

        //添加鼠标监听
        petLabel.addMouseListener(mouseAdapter);
        petLabel.addMouseMotionListener(mouseAdapter);

    }

    private void initPopupMenu(){
        popupMenu =new JPopupMenu();

        JMenuItem chatItem=new JMenuItem("通讯");
        JMenuItem exitItem=new JMenuItem("下线");

        chatItem.addActionListener(e -> openChatWindow());
        exitItem.addActionListener(e -> System.exit(0));

        popupMenu.add(chatItem);
        popupMenu.addSeparator();
        popupMenu.add(exitItem);
    }


    private void showPopupMenu(MouseEvent event){
        popupMenu.show(event.getComponent(), event.getX(),event.getY());
    }

    private void openChatWindow(){
        chatWindow.setVisible(true);
        chatWindow.toFront();
    }

}
