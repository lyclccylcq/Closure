package app.ui;

import app.ui.chat.ChatWindow;
import app.util.AvatarLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * PetWindow 表示桌面上的桌宠窗口。
 * 它负责：
 * 1. 显示桌宠图标
 * 2. 让桌宠窗口保持无边框、透明背景
 * 3. 让桌宠可以被鼠标拖动
 * 4. 右键显示菜单
 * 5. 双击打开聊天窗口
 * 注意：
 * PetWindow 是整个程序的主窗口。
 * ChatWindow 只是它打开的聊天窗口。
 */
public class PetWindow extends JFrame {

    /**
     * 桌宠图标大小，
     * 这里设置成 64 像素，
     * 避免桌面图标看起来比普通 App 图标大太多。
     */
    private static final int PET_SIZE = 64;

    /**
     * 桌宠图片路径。
     * 注意：
     * 这里使用的是 resources 资源路径，
     * 不是 "src/main/resources/..." 文件路径。
     * 对应真实位置：
     * src/main/resources/images/closure_logo.png
     */
    private static final String PET_IMAGE_PATH = "/images/closure-logo.png";

    /**
     * 聊天窗口对象。
     * 这里不直接 new ChatWindow。
     * 而是在用户第一次双击或点击菜单时再创建。
     * 这样可以避免程序一启动就创建聊天窗口。
     */
    private ChatWindow chatWindow;

    /**
     * 记录鼠标按下时的位置。
     * 用于计算拖动桌宠时窗口应该移动到哪里。
     */
    private Point mousePressedPoint;

    /**
     * 右键菜单。
     * 包含：
     * 1. 通讯
     * 2. 下线
     */
    private JPopupMenu popupMenu;

    /**
     * 构造方法：创建桌宠窗口。
     */
    public PetWindow() {
        // 初始化窗口基本属性
        initWindow();

        // 初始化桌宠图标和鼠标事件
        initPetIcon();

        // 初始化右键菜单
        initPopupMenu();
    }

    /**
     * 初始化桌宠窗口。
     */
    private void initWindow() {
        /*
         * 去掉窗口边框。
         *
         * JFrame 默认有标题栏和边框，
         * 桌宠不需要这些东西。
         */
        setUndecorated(true);

        /*
         * 让桌宠窗口默认保持在最前面。
         *
         * 这样桌宠不会轻易被其他窗口挡住。
         */
        setAlwaysOnTop(true);

        /*
         * 设置透明背景。
         *
         * 这样窗口本身不会显示成一个方块，
         * 只显示里面的图片。
         */
        setBackground(new Color(0, 0, 0, 0));

        // 设置桌宠窗口大小
        setSize(PET_SIZE, PET_SIZE);

        // 设置桌宠初始位置
        setLocation(1000, 500);

        /*
         * 关闭桌宠窗口时退出程序。
         *
         * 因为 PetWindow 是主窗口。
         */
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    /**
     * 初始化桌宠图标。
     */
    private void initPetIcon() {
        // 创建一个 JLabel 用来显示桌宠图片
        JLabel petLabel = new JLabel();

        /*
         * 加载并缩放桌宠图片。
         *
         * ImageLoader.loadIcon 会从 resources 中读取图片，
         * 然后缩放到 PET_SIZE x PET_SIZE。
         */
        ImageIcon icon = AvatarLoader.loadAvatar(
                PET_IMAGE_PATH,
                PET_SIZE,
                PET_SIZE
        );

        // 把图片设置到 JLabel 上
        petLabel.setIcon(icon);

        // 让图片在 JLabel 中水平居中
        petLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // 让图片在 JLabel 中垂直居中
        petLabel.setVerticalAlignment(SwingConstants.CENTER);

        // 把图标组件加入窗口
        add(petLabel);

        /*
         * 创建鼠标监听器。
         *
         * 这里同时处理：
         * 1. 鼠标按下
         * 2. 鼠标拖动
         * 3. 鼠标松开
         * 4. 鼠标点击
         */
        MouseAdapter mouseAdapter = new MouseAdapter() {

            /**
             * 鼠标按下时触发。
             */
            @Override
            public void mousePressed(MouseEvent event) {
                /*
                 * 记录鼠标按下时的位置。
                 *
                 * event.getPoint() 得到的是鼠标在桌宠窗口内部的位置，
                 * 不是屏幕上的绝对位置。
                 */
                mousePressedPoint = event.getPoint();

                /*
                 * 判断是否触发右键菜单。
                 *
                 * 不同系统触发 popup 的时机不同：
                 * 有些系统是 mousePressed，
                 * 有些系统是 mouseReleased。
                 *
                 * 所以这里判断一次，mouseReleased 里再判断一次。
                 */
                if (event.isPopupTrigger()) {
                    showPopupMenu(event);
                }
            }

            /**
             * 鼠标拖动时触发。
             */
            @Override
            public void mouseDragged(MouseEvent event) {
                // 获取窗口当前在屏幕上的位置
                Point currentLocation = getLocation();

                /*
                 * 计算新的窗口位置。
                 *
                 * event.getX() - mousePressedPoint.x：
                 * 表示鼠标相对于按下时在 X 方向移动了多少。
                 *
                 * event.getY() - mousePressedPoint.y：
                 * 表示鼠标相对于按下时在 Y 方向移动了多少。
                 */
                int newX = currentLocation.x + event.getX() - mousePressedPoint.x;
                int newY = currentLocation.y + event.getY() - mousePressedPoint.y;

                // 移动桌宠窗口
                setLocation(newX, newY);
            }

            /**
             * 鼠标松开时触发。
             */
            @Override
            public void mouseReleased(MouseEvent event) {
                // 再判断一次是否触发右键菜单，兼容不同系统
                if (event.isPopupTrigger()) {
                    showPopupMenu(event);
                }
            }

            /**
             * 鼠标点击时触发。
             */
            @Override
            public void mouseClicked(MouseEvent event) {
                /*
                 * 判断是否是鼠标左键双击。
                 *
                 * 如果是，就打开聊天窗口。
                 */
                if (SwingUtilities.isLeftMouseButton(event)
                        && event.getClickCount() == 2) {
                    openChatWindow();
                }
            }
        };

        // 给桌宠图标添加鼠标点击监听
        petLabel.addMouseListener(mouseAdapter);

        // 给桌宠图标添加鼠标拖动监听
        petLabel.addMouseMotionListener(mouseAdapter);
    }

    /**
     * 初始化右键菜单。
     */
    private void initPopupMenu() {
        // 创建右键菜单对象
        popupMenu = new JPopupMenu();

        // 创建“通讯”菜单项
        JMenuItem chatItem = new JMenuItem("通讯");

        // 创建“下线”菜单项
        JMenuItem exitItem = new JMenuItem("下线");

        // 点击“通讯”时打开聊天窗口
        chatItem.addActionListener(e -> openChatWindow());

        // 点击“下线”时退出程序
        exitItem.addActionListener(e -> System.exit(0));

        // 把“通讯”加入菜单
        popupMenu.add(chatItem);

        // 添加一条分隔线
        popupMenu.addSeparator();

        // 把“下线”加入菜单
        popupMenu.add(exitItem);
    }

    /**
     * 显示右键菜单。
     *
     * @param event 鼠标事件
     */
    private void showPopupMenu(MouseEvent event) {
        /*
         * 在鼠标点击的位置显示菜单。
         *
         * event.getComponent()：
         * 表示当前触发事件的组件，也就是 petLabel。
         *
         * event.getX(), event.getY()：
         * 表示鼠标在 petLabel 内部的位置。
         */
        popupMenu.show(
                event.getComponent(),
                event.getX(),
                event.getY()
        );
    }

    /**
     * 打开聊天窗口。
     * 触发方式：
     * 1. 双击桌宠图标
     * 2. 右键菜单点击“通讯”
     */
    private void openChatWindow() {
        /*
         * 如果聊天窗口还没有创建，就创建一个。
         *
         * 这样可以避免程序一启动就 new ChatWindow。
         */
        if (chatWindow == null) {
            chatWindow = new ChatWindow();

            /*
             * 第一次打开聊天窗口时，
             * 让聊天窗口出现在桌宠右边。
             */
            Point petLocation = getLocation();

            chatWindow.setLocation(
                    petLocation.x + getWidth() + 10,
                    petLocation.y
            );

            /*
             * 当聊天窗口被关闭/隐藏时，
             * 恢复桌宠置顶状态。
             *
             * 因为打开聊天窗口时，
             * 我们会让桌宠取消置顶，
             * 避免桌宠图标挡在聊天窗口上。
             */
            chatWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    restorePetAlwaysOnTop();
                }

                @Override
                public void windowClosed(WindowEvent event) {
                    restorePetAlwaysOnTop();
                }
            });
        }

        /*
         * 打开聊天窗口时：
         * 1. 桌宠取消置顶
         * 2. 聊天窗口置顶
         *
         * 这样可以解决：
         * 双击打开聊天后，桌宠图标挡在聊天窗口上方的问题。
         */
        setAlwaysOnTop(false);
        chatWindow.setAlwaysOnTop(true);

        // 显示聊天窗口
        chatWindow.setVisible(true);

        // 把聊天窗口移动到最前面
        chatWindow.toFront();

        // 让聊天窗口获得焦点
        chatWindow.requestFocus();
    }

    /**
     * 恢复桌宠置顶状态。
     * 当聊天窗口关闭或隐藏时调用。
     */
    private void restorePetAlwaysOnTop() {
        // 让桌宠重新保持在最前面
        setAlwaysOnTop(true);

        // 如果聊天窗口存在，就取消聊天窗口置顶
        if (chatWindow != null) {
            chatWindow.setAlwaysOnTop(false);
        }
    }
}
