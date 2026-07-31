package app.ui;

import app.reminder.CourseScheduleEntry;
import app.reminder.CourseScheduleReminder;
import app.reminder.CourseScheduleRepository;
import app.reminder.WorkBreakReminder;
import app.service.ChatSpeechController;
import app.ui.chat.ChatWindow;
import app.ui.pet.PetAction;
import app.ui.pet.PetAnimationPanel;
import app.ui.quick.PetSpeechBubbleWindow;
import app.ui.reminder.CourseScheduleWindow;
import app.ui.reminder.ReminderSettingsWindow;
import app.util.AvatarLoader;
import app.voice.GptSovitsServerProcess;

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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PetWindow 表示桌面上的桌宠窗口。
 * 它负责：
 * 1. 显示桌宠模型
 * 2. 让桌宠窗口保持无边框、透明背景
 * 3. 让桌宠可以被鼠标拖动
 * 4. 右键显示菜单
 * 5. 双击模型打开通讯窗口
 * 注意：
 * PetWindow 是整个程序的主窗口。
 * ChatWindow 只是它打开的聊天窗口。
 */
public class PetWindow extends JFrame {

    /**
     * 动态模型显示大小。
     */
    private static final int MODEL_SIZE = 220;

    /**
     * 模型打开后默认放在屏幕右侧靠上位置。
     */
    private static final int MODEL_RIGHT_MARGIN = 96;
    private static final int MODEL_TOP_MARGIN = 80;

    /**
     * 默认工作提醒间隔。
     * 用户可以在提醒设置窗口里改成 5 分钟到 2 小时。
     */
    private static final Duration WORK_REMINDER_INTERVAL = Duration.ofHours(2);

    /**
     * 可露希尔上线时显示的短对白。
     */
    private static final String STARTUP_BUBBLE_TEXT = "工程部可露希尔在线。";

    /**
     * 单击模型时随机显示的短互动对白。
     */
    private static final String[] INTERACTION_BUBBLE_LINES = {
            "叫我？工程部刚好还没断电。",
            "别戳啦，我在呢。",
            "博士，有事就说，维修费可以之后再算。",
            "这台终端又响了？你最好不是来催工期的。",
            "我正在假装很忙，配合一下。",
            "补觉申请还没批下来，所以我还在线。"
    };

    /**
     * 桌宠闲置时偶尔自己冒出来的短对白。
     * 这些台词只显示气泡，不触发语音。
     */
    private static final String[] IDLE_CHATTER_LINES = {
            "无人机整备完毕。离出发还有多长时间？我看看能不能补个觉......",
            "博士，你这次体检的指标怎么回事？什么，觉得最近很虚弱，怕自己时日无多？！报告是假的？逗我玩？华法琳！",
            "烧烤味饼干，来一片？",
            "罗德岛干员过生日均有机会获得采购部免单券，你也不例外！来这里摇奖，免单券就在特等奖等着你！",
            "全体无人机，挂钩释放，功率解锁，钩住博士的兜帽——起飞!",
            "别急，我还在算呢。"
    };

    private static final int IDLE_CHATTER_MIN_DELAY_MILLIS = (int) Duration.ofMinutes(8).toMillis();
    private static final int IDLE_CHATTER_MAX_DELAY_MILLIS = (int) Duration.ofMinutes(18).toMillis();
    private static final long IDLE_CHATTER_MIN_IDLE_MILLIS = Duration.ofMinutes(5).toMillis();

    /**
     * 桌宠图片路径。
     * 注意：
     * 这个图片不再作为程序启动后的第一屏，
     * 只在模型帧缺失时作为兜底图显示。
     */
    private static final String PET_IMAGE_PATH = "/images/closure-logo.png";

    /**
     * 打开模型时播放的语音路径。
     */
    private static final String OPEN_AUDIO_PATH = "/audio/Japanese/open.wav";

    /**
     * 聊天窗口对象。
     * 这里不直接 new ChatWindow。
     * 而是在用户第一次双击或点击菜单时再创建。
     * 这样可以避免程序一启动就创建聊天窗口。
     */
    private ChatWindow chatWindow;

    /**
     * 桌宠和聊天窗口共用的自动朗读控制器。
     * 这样戳一戳气泡和 AI 回复不会叠在一起播放。
     */
    private ChatSpeechController speechController;

    /**
     * 如果本地配置开启了自动启动，这里保存桌宠拉起的 GPT-SoVITS 进程。
     */
    private CompletableFuture<Optional<GptSovitsServerProcess>> voiceServerProcessFuture;

    /**
     * 贴在桌宠旁边的点击互动对白气泡。
     * 它不负责聊天输入，只显示可露希尔自己的短台词。
     */
    private PetSpeechBubbleWindow speechBubbleWindow;

    /**
     * 后台工作提醒计时器。
     */
    private WorkBreakReminder workBreakReminder;

    /**
     * 课程表本地保存和后台提醒。
     */
    private CourseScheduleRepository courseScheduleRepository;
    private CourseScheduleReminder courseScheduleReminder;

    /**
     * 工作提醒时间设置窗口。
     */
    private ReminderSettingsWindow reminderSettingsWindow;

    /**
     * 课程表上传和查看窗口。
     */
    private CourseScheduleWindow courseScheduleWindow;

    /**
     * 动态桌宠组件。
     * 如果当前没有加载 PNG 帧动画，它会显示兜底图片。
     */
    private PetAnimationPanel petAnimationPanel;

    /**
     * 桌宠组件共用的鼠标监听器。
     * 模型组件使用它处理拖拽、右键菜单和双击通讯。
     */
    private MouseAdapter petMouseAdapter;

    /**
     * 单击互动延迟计时器。
     * 用于区分单击气泡和双击通讯。
     */
    private Timer interactionClickTimer;

    /**
     * 桌面闲置随机对白计时器。
     */
    private Timer idleChatterTimer;
    private long lastDesktopActivityMillis;

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
     * 2. 提醒设置
     * 3. 下线
     */
    private JPopupMenu popupMenu;

    /**
     * 构造方法：创建桌宠窗口。
     */
    public PetWindow() {
        // 初始化窗口基本属性
        initWindow();

        // 初始化桌宠鼠标事件
        initPetMouseAdapter();

        // 初始化后台工作提醒
        initWorkBreakReminder();

        // 初始化课程表提醒
        initCourseScheduleReminder();

        // 尝试自动启动本地 GPT-SoVITS API
        initVoiceServerProcess();

        // 初始化自动朗读控制器
        initSpeechController();

        // 初始化右键菜单
        initPopupMenu();

        // 初始化桌面闲置随机对白
        initIdleChatterTimer();

        // 程序启动后直接显示可露希尔模型。
        openPetModel();
        playOpenVoice();
        startWorkBreakReminder();
        startIdleChatterTimer();
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

        // 设置桌宠窗口大小，位置会在模型加载后按屏幕重新计算。
        setSize(MODEL_SIZE, MODEL_SIZE);

        /*
         * 关闭桌宠窗口时退出程序。
         *
         * 因为 PetWindow 是主窗口。
         */
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    /**
     * 初始化桌宠鼠标事件。
     */
    private void initPetMouseAdapter() {
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
                registerDesktopActivity();

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
                registerDesktopActivity();

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
                moveSpeechBubbleNextToPet();
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
                if (!SwingUtilities.isLeftMouseButton(event) || !isClickWithoutDrag(event)) {
                    return;
                }

                if (event.getClickCount() == 1) {
                    scheduleInteractionBubble();
                    return;
                }

                if (event.getClickCount() == 2) {
                    cancelInteractionBubble();
                    openChatWindow();
                }
            }
        };
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

        getContentPane().removeAll();
        add(petAnimationPanel);
        setSize(MODEL_SIZE, MODEL_SIZE);
        setLocation(calculateDefaultModelLocation());
        revalidate();
        validate();
        repaint();
        showStartupSpeechBubble();
    }

    private void scheduleInteractionBubble() {
        cancelInteractionBubble();

        interactionClickTimer = new Timer(220, event -> showInteractionSpeechBubble());
        interactionClickTimer.setRepeats(false);
        interactionClickTimer.start();
    }

    private void cancelInteractionBubble() {
        if (interactionClickTimer != null) {
            interactionClickTimer.stop();
            interactionClickTimer = null;
        }
    }

    private void showStartupSpeechBubble() {
        showSpeechBubble(STARTUP_BUBBLE_TEXT);
    }

    private void showInteractionSpeechBubble() {
        String interactionLine = randomInteractionLine();
        showSpeechBubble(interactionLine);
        speakSpeechBubble(interactionLine);
    }

    private String randomInteractionLine() {
        int lineIndex = ThreadLocalRandom.current().nextInt(INTERACTION_BUBBLE_LINES.length);
        return INTERACTION_BUBBLE_LINES[lineIndex];
    }

    private void showSpeechBubble(String text) {
        registerDesktopActivity();

        if (speechBubbleWindow == null) {
            speechBubbleWindow = new PetSpeechBubbleWindow();
        }

        speechBubbleWindow.showNextTo(getBounds(), text);
    }

    private void speakSpeechBubble(String text) {
        if (speechController != null) {
            speechController.speakText(text);
        }
    }

    private void initWorkBreakReminder() {
        workBreakReminder = new WorkBreakReminder(
                WORK_REMINDER_INTERVAL,
                this::showWorkBreakReminder
        );
    }

    private void initCourseScheduleReminder() {
        courseScheduleRepository = CourseScheduleRepository.userDefault();
        List<CourseScheduleEntry> savedEntries = loadSavedCourseSchedule();
        courseScheduleReminder = new CourseScheduleReminder(
                savedEntries,
                this::showCourseScheduleReminder
        );
        courseScheduleReminder.start();
    }

    private void initIdleChatterTimer() {
        lastDesktopActivityMillis = System.currentTimeMillis();
        idleChatterTimer = new Timer(randomIdleChatterDelayMillis(), event -> showIdleChatterIfDesktopIsIdle());
        idleChatterTimer.setRepeats(false);
    }

    private void initSpeechController() {
        speechController = ChatSpeechController.fromEnvironment(
                message -> System.out.println("语音朗读警告：" + message)
        );
    }

    private void initVoiceServerProcess() {
        voiceServerProcessFuture = GptSovitsServerProcess.startConfiguredAsync(
                this::showVoiceServerStatus
        );
    }

    private void showVoiceServerStatus(String message) {
        System.out.println("语音服务启动：" + message);
        if (!isVoiceServerWarningVisible(message)) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            if (petAnimationPanel != null && isShowing()) {
                showSpeechBubble(message);
            }
        });
    }

    private boolean isVoiceServerWarningVisible(String message) {
        return message.contains("失败")
                || message.contains("缺少")
                || message.contains("过早退出");
    }

    private void startWorkBreakReminder() {
        workBreakReminder.start();
    }

    private void startIdleChatterTimer() {
        scheduleNextIdleChatter();
    }

    private void scheduleNextIdleChatter() {
        if (idleChatterTimer == null) {
            return;
        }

        int delayMillis = randomIdleChatterDelayMillis();
        idleChatterTimer.stop();
        idleChatterTimer.setDelay(delayMillis);
        idleChatterTimer.setInitialDelay(delayMillis);
        idleChatterTimer.start();
    }

    private int randomIdleChatterDelayMillis() {
        return ThreadLocalRandom.current().nextInt(
                IDLE_CHATTER_MIN_DELAY_MILLIS,
                IDLE_CHATTER_MAX_DELAY_MILLIS + 1
        );
    }

    private void showIdleChatterIfDesktopIsIdle() {
        try {
            if (isDesktopIdleForChatter()) {
                showSpeechBubble(randomIdleChatterLine());
            }
        } finally {
            scheduleNextIdleChatter();
        }
    }

    private boolean isDesktopIdleForChatter() {
        if (!isShowing()) {
            return false;
        }
        if (chatWindow != null && chatWindow.isVisible()) {
            return false;
        }

        long idleMillis = System.currentTimeMillis() - lastDesktopActivityMillis;
        return idleMillis >= IDLE_CHATTER_MIN_IDLE_MILLIS;
    }

    private String randomIdleChatterLine() {
        int lineIndex = ThreadLocalRandom.current().nextInt(IDLE_CHATTER_LINES.length);
        return IDLE_CHATTER_LINES[lineIndex];
    }

    private void registerDesktopActivity() {
        lastDesktopActivityMillis = System.currentTimeMillis();
    }

    private void pauseWorkBreakReminder() {
        workBreakReminder.pause();
        showSpeechBubble("工作提醒先暂停。博士，别趁机把休息也省了。");
    }

    private void restartWorkBreakReminder() {
        workBreakReminder.restart();
        showSpeechBubble("重新计时了。" + workReminderIntervalText() + " 后我再来查岗。");
    }

    private void openWorkReminderSettingsWindow() {
        if (reminderSettingsWindow == null) {
            reminderSettingsWindow = new ReminderSettingsWindow(
                    interval -> SwingUtilities.invokeLater(() -> applyWorkReminderInterval(interval)),
                    enabled -> SwingUtilities.invokeLater(() -> setWorkReminderEnabled(enabled)),
                    () -> SwingUtilities.invokeLater(this::restartWorkBreakReminder)
            );
        }

        reminderSettingsWindow.show(
                workBreakReminder.workInterval(),
                workBreakReminder.isRunning(),
                getBounds()
        );
    }

    private void applyWorkReminderInterval(Duration workInterval) {
        boolean wasRunning = workBreakReminder.isRunning();
        workBreakReminder.setWorkInterval(workInterval);

        if (wasRunning) {
            showSpeechBubble("提醒间隔改成 " + workReminderIntervalText() + "，从现在重新计时。");
        } else {
            showSpeechBubble("提醒间隔改成 " + workReminderIntervalText() + "。打开提醒后生效。");
        }
    }

    private void setWorkReminderEnabled(boolean enabled) {
        if (enabled) {
            restartWorkBreakReminder();
            return;
        }

        pauseWorkBreakReminder();
    }

    private void showWorkBreakReminder(String reminderText) {
        showSpeechBubble(reminderText);
    }

    private void showCourseScheduleReminder(String reminderText) {
        showSpeechBubble(reminderText);
        speakSpeechBubble(reminderText);
    }

    private List<CourseScheduleEntry> loadSavedCourseSchedule() {
        try {
            return courseScheduleRepository.load();
        } catch (IOException | IllegalArgumentException exception) {
            System.out.println("课程表读取失败：" + exception.getMessage());
            return List.of();
        }
    }

    private List<CourseScheduleEntry> importCourseSchedule(Path schedulePath) {
        try {
            List<CourseScheduleEntry> importedEntries = courseScheduleRepository.importAndSave(schedulePath);
            courseScheduleReminder.setEntries(importedEntries);
            return importedEntries;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private void openCourseScheduleWindow() {
        if (courseScheduleWindow == null) {
            courseScheduleWindow = new CourseScheduleWindow(
                    this::importCourseSchedule,
                    status -> SwingUtilities.invokeLater(() -> showSpeechBubble(status)),
                    () -> SwingUtilities.invokeLater(this::showTestCourseScheduleReminder)
            );
        }

        courseScheduleWindow.show(
                courseScheduleReminder.entries(),
                getBounds()
        );
    }

    private void showTestCourseScheduleReminder() {
        CourseScheduleReminder testReminder = new CourseScheduleReminder(
                List.of(new CourseScheduleEntry(
                        "提醒系统自检",
                        java.time.LocalDateTime.now().plusMinutes(1),
                        null,
                        app.reminder.CourseReminderType.CLASS,
                        Duration.ofMinutes(1),
                        "桌面终端",
                        "测试提醒"
                )),
                this::showCourseScheduleReminder
        );
        testReminder.checkNow();
        testReminder.dispose();
    }

    private String workReminderIntervalText() {
        long hours = workBreakReminder.workInterval().toHours();
        if (hours > 0 && workBreakReminder.workInterval().minusHours(hours).isZero()) {
            return hours + " 小时";
        }

        return workBreakReminder.workInterval().toMinutes() + " 分钟";
    }

    private Point calculateDefaultModelLocation() {
        GraphicsConfiguration graphicsConfiguration = getGraphicsConfiguration();
        if (graphicsConfiguration == null) {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            return new Point(
                    Math.max(0, screenSize.width - MODEL_SIZE - MODEL_RIGHT_MARGIN),
                    Math.max(0, MODEL_TOP_MARGIN)
            );
        }

        Rectangle screenBounds = graphicsConfiguration.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);
        int availableX = screenBounds.x + screenInsets.left;
        int availableY = screenBounds.y + screenInsets.top;
        int availableWidth = screenBounds.width - screenInsets.left - screenInsets.right;

        return new Point(
                availableX + Math.max(0, availableWidth - MODEL_SIZE - MODEL_RIGHT_MARGIN),
                availableY + MODEL_TOP_MARGIN
        );
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

        // 创建工作提醒设置菜单项
        JMenuItem workReminderSettingsItem = new JMenuItem("提醒设置...");

        // 创建课程表提醒菜单项
        JMenuItem courseScheduleItem = new JMenuItem("课程表提醒...");

        // 创建“下线”菜单项
        JMenuItem exitItem = new JMenuItem("下线");

        // 点击“通讯”时打开聊天窗口
        chatItem.addActionListener(e -> openChatWindow());

        // 打开工作提醒设置窗口
        workReminderSettingsItem.addActionListener(e -> openWorkReminderSettingsWindow());

        // 打开课程表提醒窗口
        courseScheduleItem.addActionListener(e -> openCourseScheduleWindow());

        // 点击“下线”时退出程序
        exitItem.addActionListener(e -> exitApplication());

        // 把“通讯”加入菜单
        popupMenu.add(chatItem);

        // 添加一条分隔线
        popupMenu.addSeparator();

        // 把工作提醒设置项加入菜单
        popupMenu.add(workReminderSettingsItem);
        popupMenu.add(courseScheduleItem);

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
         * 表示当前触发事件的模型组件。
         *
         * event.getX(), event.getY()：
         * 表示鼠标在该组件内部的位置。
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
     * 1. 双击桌宠模型
     * 2. 右键菜单点击“通讯”
     */
    private void openChatWindow() {
        hideSpeechBubble();

        /*
         * 如果聊天窗口还没有创建，就创建一个。
         *
         * 这样可以避免程序一启动就 new ChatWindow。
         */
        if (chatWindow == null) {
            chatWindow = new ChatWindow(speechController);

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
         * 双击打开聊天后，桌宠模型挡在聊天窗口上方的问题。
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

    private void moveSpeechBubbleNextToPet() {
        if (speechBubbleWindow != null) {
            speechBubbleWindow.moveNextTo(getBounds());
        }
    }

    private void hideSpeechBubble() {
        if (speechBubbleWindow != null) {
            speechBubbleWindow.hide();
        }
    }

    private void exitApplication() {
        cancelInteractionBubble();
        if (workBreakReminder != null) {
            workBreakReminder.dispose();
        }
        if (idleChatterTimer != null) {
            idleChatterTimer.stop();
        }
        if (reminderSettingsWindow != null) {
            reminderSettingsWindow.dispose();
        }
        if (courseScheduleReminder != null) {
            courseScheduleReminder.dispose();
        }
        if (courseScheduleWindow != null) {
            courseScheduleWindow.dispose();
        }
        if (petAnimationPanel != null) {
            petAnimationPanel.dispose();
        }
        if (speechBubbleWindow != null) {
            speechBubbleWindow.dispose();
        }
        if (chatWindow != null) {
            chatWindow.dispose();
        }
        if (speechController != null) {
            speechController.close();
        }
        closeVoiceServerProcess();
        System.exit(0);
    }

    private void closeVoiceServerProcess() {
        if (voiceServerProcessFuture == null) {
            return;
        }

        voiceServerProcessFuture.thenAccept(process -> process.ifPresent(GptSovitsServerProcess::close));
    }
}
