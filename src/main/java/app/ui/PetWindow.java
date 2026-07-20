package app.ui;

import app.ui.chat.ChatWindow;
import app.ui.pet.PetAction;
import app.ui.pet.PetAnimationPanel;
import app.ui.quick.QuickChatWindow;
import app.util.AvatarLoader;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * PetWindow 表示桌面上的桌宠窗口。
 * 它负责：
 * 1. 显示桌宠图标
 * 2. 让桌宠窗口保持无边框、透明背景
 * 3. 让桌宠可以被鼠标拖动
 * 4. 右键显示菜单
 * 5. 双击 logo 打开动态模型
 * 注意：
 * PetWindow 是整个程序的主窗口。
 * ChatWindow 只是它打开的聊天窗口。
 */
public class PetWindow extends JFrame {

    /**
     * 启动 logo 大小，
     * 这里设置成 64 像素，
     * 避免桌面图标看起来比普通 App 图标大太多。
     */
    private static final int LOGO_SIZE = 64;

    /**
     * 动态模型显示大小。
     * 点击 logo 之后，窗口会从 LOGO_SIZE 放大到这个尺寸。
     */
    private static final int MODEL_SIZE = 280;

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
     * 打开模型时播放的语音路径。
     */
    private static final String OPEN_AUDIO_PATH = "/audio/open.wav";

    /**
     * 聊天窗口对象。
     * 这里不直接 new ChatWindow。
     * 而是在用户第一次双击或点击菜单时再创建。
     * 这样可以避免程序一启动就创建聊天窗口。
     */
    private ChatWindow chatWindow;

    /**
     * 贴在桌宠旁边的轻量问答窗口。
     * 它和完整聊天窗口分开，适合临时问一句、看一句回复。
     */
    private QuickChatWindow quickChatWindow;

    /**
     * 动态桌宠组件。
     * 如果当前没有加载 PNG 帧动画，它会保持为 null。
     */
    private PetAnimationPanel petAnimationPanel;

    /**
     * 桌宠组件共用的鼠标监听器。
     * logo 和动态模型都使用它，所以切换组件后拖拽、右键菜单仍然可用。
     */
    private MouseAdapter petMouseAdapter;

    /**
     * 记录鼠标按下时的位置。
     * 用于计算拖动桌宠时窗口应该移动到哪里。
     */
    private Point mousePressedPoint;

    /**
     * 记录鼠标按下时的屏幕位置。
     * 用来区分“点击打开模型”和“拖动移动窗口”。
     */
    private Point mousePressedScreenPoint;

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
        setSize(LOGO_SIZE, LOGO_SIZE);

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
        // 程序启动时固定先显示 logo；点击 logo 后再打开动态模型。
        ImageIcon icon = AvatarLoader.loadAvatar(
                PET_IMAGE_PATH,
                LOGO_SIZE,
                LOGO_SIZE
        );

        JLabel petLabel = new JLabel(icon);
        petLabel.setHorizontalAlignment(SwingConstants.CENTER);
        petLabel.setVerticalAlignment(SwingConstants.CENTER);

        // 把桌宠组件加入窗口
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
        petMouseAdapter = new MouseAdapter() {

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
                mousePressedScreenPoint = event.getLocationOnScreen();

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
                moveQuickChatWindowNextToPet();
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
                 * logo 阶段左键双击打开动态模型，并同步播放 open 语音。
                 * 模型已经打开后，左键双击继续保留为打开聊天窗口。
                 */
                if (SwingUtilities.isLeftMouseButton(event)
                        && event.getClickCount() == 2) {
                    if (petAnimationPanel == null && isClickWithoutDrag(event)) {
                        playOpenVoice();
                        openPetModel();
                        return;
                    }

                    openChatWindow();
                }
            }
        };

        // 给桌宠组件添加鼠标点击监听
        addPetMouseListeners(petLabel);

    }

    private void addPetMouseListeners(Component component) {
        component.addMouseListener(petMouseAdapter);
        component.addMouseMotionListener(petMouseAdapter);

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                addPetMouseListeners(child);
            }
        }
    }

    private void openPetModel() {
        if (!PetAction.OPEN.hasFrames()) {
            System.out.println("模型 PNG 帧不存在：" + PetAction.OPEN.frameDirectory());
            return;
        }

        ImageIcon fallbackIcon = AvatarLoader.loadAvatar(
                PET_IMAGE_PATH,
                MODEL_SIZE,
                MODEL_SIZE
        );

        petAnimationPanel = new PetAnimationPanel(
                PetAction.OPEN,
                MODEL_SIZE,
                MODEL_SIZE,
                fallbackIcon
        );
        addPetMouseListeners(petAnimationPanel);

        Point currentLocation = getLocation();
        int oldHeight = getHeight();

        getContentPane().removeAll();
        add(petAnimationPanel);
        setSize(MODEL_SIZE, MODEL_SIZE);
        setLocation(currentLocation.x, currentLocation.y - MODEL_SIZE + oldHeight);
        revalidate();
        validate();
        repaint();
        showQuickChatWindow();
    }

    private boolean isClickWithoutDrag(MouseEvent event) {
        if (mousePressedScreenPoint == null) {
            return true;
        }

        Point releasedPoint = event.getLocationOnScreen();
        int moveX = Math.abs(releasedPoint.x - mousePressedScreenPoint.x);
        int moveY = Math.abs(releasedPoint.y - mousePressedScreenPoint.y);
        return moveX <= 3 && moveY <= 3;
    }

    private void playOpenVoice() {
        Thread soundThread = new Thread(() -> {
            try (InputStream inputStream = PetWindow.class.getResourceAsStream(OPEN_AUDIO_PATH)) {
                if (inputStream == null) {
                    System.out.println("语音路径不存在：" + OPEN_AUDIO_PATH);
                    return;
                }

                try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream)) {
                    Clip clip = AudioSystem.getClip();
                    clip.open(AudioSystem.getAudioInputStream(bufferedInputStream));
                    clip.addLineListener(event -> {
                        if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                            clip.close();
                        }
                    });
                    clip.start();
                }
            } catch (IOException | LineUnavailableException | UnsupportedAudioFileException exception) {
                System.out.println("open 语音播放失败：" + exception.getMessage());
            }
        }, "open-voice-player");

        soundThread.setDaemon(true);
        soundThread.start();
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
        exitItem.addActionListener(e -> exitApplication());

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

    private void showQuickChatWindow() {
        if (quickChatWindow == null) {
            quickChatWindow = new QuickChatWindow();
        }

        quickChatWindow.showNextTo(getBounds());
    }

    private void moveQuickChatWindowNextToPet() {
        if (quickChatWindow != null) {
            quickChatWindow.moveNextTo(getBounds());
        }
    }

    private void exitApplication() {
        if (petAnimationPanel != null) {
            petAnimationPanel.dispose();
        }
        if (quickChatWindow != null) {
            quickChatWindow.dispose();
        }
        System.exit(0);
    }
}
