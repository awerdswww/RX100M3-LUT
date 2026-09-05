package com.sonylut.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.sony.scalar.hardware.CameraEx;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CUSTOM LUT — A6000 胶片模拟。
 *
 * SD 卡 /LUTS/*.cube 投放 LUT。启动时先检查 LUTCACHE 分解缓存：
 * 全部就绪直接进拍照界面，否则先逐个计算新增 LUT 再进。
 * 分解为 伽马表+矩阵 写入 ISP 管线，取景/成片实时生效。
 * 拍照后自动标记：JPEG 插入 COM 段，ARW 写 XMP sidecar。
 *
 * 按键（v0.7.0 官方风简洁模式，缺省；/LUTS/STYLE.TXT 首词 CLASSIC 回退旧交互）：
 *   Fn                 : 呼出/收起官方风 LUT 菜单（列表+介绍+强度+退出项）
 *   上下/拨轮1         : 菜单选择（实时预览）；菜单外按下即呼出菜单
 *   左右/拨轮2         : 强度 0-100%
 *   中央键             : 应用并收起菜单（菜单外=呼出菜单）
 *   删除键             : 关闭 LUT
 *   变焦杆/控制环      : 按官方协议驱动变焦（TELE=0/WIDE=1；按住=单发 max/8
 *                        +无位移补发，点动=max/4+100ms+stop，见官方应用逆向笔记）
 *   快门半按/全按      : 对焦 / 拍照（原生管线存储）
 *   回看键(按住)       : 临时关 LUT 对比原图，松开恢复
 *   MENU               : 退出（参数随 App 退出自动还原）
 *
 * CLASSIC 模式保留 v0.6 行为：Fn 进/出参数调节模式，控制环=焦段拨盘。
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback,
        CameraEx.ShutterListener {
    private static final String TAG = "SonyLut";
    // 会话计数（static：同进程二次进入时 >1 —— "暖启动"即老进程驻留，是
    // 第二次进入变慢的头号嫌疑，用它和 PREPLOG 时间线对账）
    private static int sSessionNo = 0;
    private static final File LUT_DIR = new File(
            Environment.getExternalStorageDirectory(), "LUTS");
    private static final File CACHE_DIR = new File(LUT_DIR, "LUTCACHE");
    private static final File DCIM_DIR = new File(
            Environment.getExternalStorageDirectory(), "DCIM");
    private static final String OFF_NAME = "OFF（关闭）";

    // 扫描码
    private static final int SCAN_MENU = 514;
    private static final int SCAN_DELETE = 595;
    private static final int SCAN_S1 = 516;
    private static final int SCAN_S1_UP = 517;  // 半按释放
    private static final int SCAN_S2 = 518;
    private static final int SCAN_DIAL1_CW = 525;
    private static final int SCAN_DIAL1_CCW = 526;
    private static final int SCAN_DIAL2_CW = 528;
    private static final int SCAN_DIAL2_CCW = 529;
    private static final int SCAN_UP = 103;
    private static final int SCAN_DOWN = 108;
    private static final int SCAN_LEFT = 105;
    private static final int SCAN_RIGHT = 106;
    // RX100M3 实测（2026-08-24）：Fn=520，回看=207，C 键无映射（系统未派发）
    private static final int SCAN_FN = 520;        // Fn：进/出参数调节模式
    private static final int SCAN_REVIEW = 207;    // 回看：临时关 LUT 对比原图
    // 变焦输入（v0.5.8 起由 App 驱动——实测 App 前台时原生 sys.camera 不再
    // 处理这些键，旧注释"原生直接接管"对前台场景不成立）。648=CW 出处
    // Bible.md 输入码表 ISV_RING_CLOCKWISE。
    private static final int SCAN_ZOOM_W = 610;    // 变焦杆 W（广角）
    private static final int SCAN_ZOOM_T = 611;    // 变焦杆 T（望远）
    private static final int SCAN_RING_CW = 648;   // 控制环顺时针
    private static final int SCAN_RING_CCW = 649;  // 控制环逆时针

    // ---- 变焦驱动（v0.5.10 定案，依据 sess_1e41cb7c 交接笔记）：
    // startZoom(ZOOM_DIRECTION_*, speed)：0=TELE、1=WIDE（非正负号！），
    // speed>=1，取 getMaxZoomSpeed() 探测值兜底 2。
    // 按住推杆仅在下压首帧发一次 startZoom（重复事件忽略——旧实现每帧重发
    // 导致马达反复启停"一卡一卡"），松开 stopZoom。 ----
    // v0.7.0 官方定案（三方一致：RX100M3 固件 stub 常量 ZOOM_DIRECTION_TELE=0
    // /WIDE=1 + 官方 DigitalZoomController.DIRECTION_* + srctrl 遥控映射）。
    // 旧"实测对调"源于越协议传过 (0,-1)（speed 位为负不在协议内），已推翻。
    // 若真机 (0,x) 仍表现为广角，再回来改这里并记 PREPLOG。
    private static final int ZDIR_TELE = 0;
    private static final int ZDIR_WIDE = 1;
    private static final int ZOOM_FALLBACK_SPEED = 2;
    private int zoomSpeed = -1;          // 探测到的最大速度（-1=未探测）
    private static final long ZOOM_HOLD_REFRESH_MS = 80;   // CLASSIC 模式续发周期
    private static final long ZOOM_REISSUE_MS = 600;       // 简洁模式：无位移补发窗口
    private static final long ZOOM_TAP_MS = 100;           // 官方 one-shot 点动时长
    private static final long ZOOM_RING_THROTTLE_MS = 150; // 简洁模式环节流
    private int zoomHoldEpoch = 0;       // 按住会话号（松开/换向即失效）
    private volatile boolean leverDriving = false; // 推杆保持中（环让位）
    private volatile int lastOptMag = 100;   // 最近一次回调的光学倍率(百分制)
    private static final int[] PRESET_MM = {24, 28, 35, 50, 70}; // 环拨预设档
    private static final int GOTO_TOL_MAG = 6;      // 到位容差(≈±1.4mm)
    private static final long GOTO_STEP_MS = 110;   // 闭环步进节奏
    private int gotoEpoch = 0;               // 预设步进会话号
    private static final long RING_THROTTLE_MS = 240;
    private volatile int zoomDriveDir = 0; // 推杆按住方向：0=空闲 ±1=W/T
    private long lastRingTickAt = 0;
    private boolean dzModeEnsured = false; // 数字变焦模式已提交
    private int dzCurrent = 100;           // 数字变焦当前值（×100）
    private volatile int pzStatus = -1;      // PowerZoomListener 最近状态（1可用/2不可用/3不适用）
    private SurfaceHolder surfaceHolder;
    private TextView topBar, lutListView, bottomHint;
    private HudView hud;

    private volatile CameraEx camera; // shutdown/kick 线程会置换句柄
    private boolean previewStarted = false;
    private boolean surfaceReady = false; // surfaceCreated/Destroyed 维护，
                                          // initCamera 靠它决定能否直接 startPreview
    private volatile boolean takingPicture = false;
    // capture 在途闩锁：从按下快门到打标收尾之间置位；期间禁止
    // stopPreview/release（驱动 drain/写盘未完，强撤会把相机服务 wedge，
    // Pro 实测 stopPreview 阻塞 2943ms、打标线程与 shutdown 并发）
    private volatile boolean captureDraining = false;
    private boolean resumed = false;
    private volatile boolean pausing = false; // onPause 置位，汇聚器立即停手
    // 最近一次 AF 状态（锁定态判断用，v0.2 可重复对焦）
    private volatile int lastAfStatus = 0;

    // RX100M3：光圈/变焦由原生控制，App 只挂监听做 HUD 显示（F 值 / 焦距位置）

    // Fn 参数调节模式（v0.5）：Fn 键进入/退出，方向键上下选参数，左右调值。
    // 参数项：曝光补偿/光圈/快门/ISO/白平衡偏移。全走 ParametersModifier 标准通道。
    private boolean paramMode = false;
    private int paramIndex = 0; // 当前选中的参数项
    private static final int PARAM_EV = 0;
    private static final int PARAM_APERTURE = 1;
    private static final int PARAM_SHUTTER = 2;
    private static final int PARAM_ISO = 3;
    private static final int PARAM_WB_LB = 4; // 白平衡 琥珀-蓝
    private static final int PARAM_WB_CC = 5; // 白平衡 绿-品红
    private static final int PARAM_COUNT = 6;
    private int paramEv = 0;      // 曝光补偿（1/3 EV 步进）
    private int paramAperture = -1; // 光圈（F 值×100，-1=未初始化）
    private String paramShutter = "--"; // 快门（getShutterSpeed 返回 Pair<分子,分母>，
                                        // 渲染为 "1/500" 样式；"--"=未知）
    private int paramIso = 0;      // ISO（感光度值）
    private int paramWbLb = 0;     // 白平衡 LB（范围以运行时 min/max 为准）
    private int paramWbCc = 0;     // 白平衡 CC（同上）

    // 参数能力/范围快照（进入参数模式时从相机探测一次；探测失败回落保守缺省）。
    // stub 已确认 isPictureControlExposureShiftSupported/isWhiteBalanceShiftModeSupported
    // 等支持位方法真实存在（.tmp_stub/api_catalog.txt）。
    private boolean paramSupEv;     // 本机是否声明支持 picture-control-exposure-shift
    private boolean paramSupWbMode; // 本机是否支持 white-balance-shift-mode（LB/CC 的前置总开关）
    private int paramWbLbMin = -9, paramWbLbMax = 9; // 探测失败时的保守缺省（固件典型量级，
                                                     // 真 LB 范围以 getMin/MaxWhiteBalanceShiftLB 为准）
    private int paramWbCcMin = -9, paramWbCcMax = 9;
    private static final int[] ISO_LEGACY_STEPS = {100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
            16000, 20000, 25600};
    private int[] isoStepsSupported = null; // 相机报告的 ISO 档位（升序）；null=未探测到，用上表兜底

    // 退出清理线程：onPause 不做任何相机/HAL 调用（2.3 dalvik + 索尼驱动上
    // UI 线程同步清理会卡死并触发系统看门狗重启拍摄框架），全部丢给它；
    // finishing 时另有 2.5s 看门狗无条件杀进程兜底
    private volatile Thread shutdownThread;

    // 退出清理完成标志：看门狗据此决定是否兜底杀进程（v0.3.3——正常退出不杀，
    // RX100M3 上杀进程与 DA 交还竞态会带崩拍摄框架导致相机重启）
    private volatile boolean cleanupDone = true;

    // RX100M3：索尼 StoreImageCompleteListener 回调时间戳（0=未收到）。
    // 文件写稳后索尼还有缩略图/数据库等收尾，此信号用于诊断真实完成点。
    private volatile long storeCompleteAt = 0;

    // 取景帧心跳看门狗：startPreview 后 2.5s 无任何帧信号（索尼 Analize 流 /
    // 一次性预览帧回调）判定黑取景，自动 stop/start 重试 → reopen 逐级自救
    private volatile boolean previewAlive = false;
    private int previewKickStage = 0;
    private int previewNotStartedTicks = 0; // 汇聚器连续「预览未起」计数，≥6 升级 reopen
    private int cameraNullTicks = 0;        // 汇聚器连续「camera 未开」计数，节流重试
    private boolean surfaceCbAdded = false; // addCallback 防重复注册

    // camera 句柄跨线程互斥（v0.3.1：退出卡几秒的事故——shutdown 线程与其它
    // 线程并发进 CameraEx HAL native 调用撞车挂起，直到 2.5s 看门狗兜底）。
    // 所有触碰 camera/HAL 的路径都走它：shutdown/kick 后台线程用 lock() 可等；
    // 主线程用 tryLock 超时/非阻塞降级（拿不到就跳过本次，绝不阻塞 UI 等锁）。
    private final ReentrantLock camLock = new ReentrantLock();

    // LUT 状态
    private final List<File> cubeFiles = new ArrayList<File>();
    private int selection = 0;       // 列表高亮（0=OFF）
    private int appliedIndex = 0;    // 当前生效
    private int intensity = 100;
    private boolean browsing = false;
    private int lutIndexBeforeReview = -1; // 回看键临时关 LUT 前的应用索引（>0=临时关闭中）
    private LutParams baseParams;    // 当前 LUT 的 100% 参数（OFF 时为 null）
    private int applySeq = 0;        // 应用请求序号（防抖）

    // ---- v0.7.0 官方风简洁模式（缺省；/LUTS/STYLE.TXT 首词 CLASSIC 回退）----
    // 平时零干扰：变焦杆/控制环按官方协议驱动，menuKey 呼出 LutMenu，
    // 其余按键不消费。参数调节模式与焦段拨盘只在 CLASSIC 模式保留。
    private boolean simpleMode = true;
    private int menuKeyScan = SCAN_FN; // 呼出菜单的扫描码（STYLE.TXT menukey= 可改）
    private boolean menuOpen = false;
    private int menuSel = 0;         // 菜单选中项：0=OFF，1..n=LUT，n+1=退出
    private LutMenu lutMenu;
    private final java.util.Map<String, String> lutDescs =
            new java.util.HashMap<String, String>(); // 文件名大写词干 → 介绍

    // ---- v0.7.1 回放（回看键）+ LUT 命名 ----
    private boolean playbackOpen = false;
    private final List<File> playFiles = new ArrayList<File>();
    private int playIdx = 0;
    private Bitmap playBmp;
    private String playCaption = "";
    private int playSeq = 0;
    private PlaybackView playView;
    private boolean renameEnabled = true; // /LUTS/RENAME.TXT=OFF 关闭 LUT 命名
    private long shotStartedAt = 0;       // 本次快门时刻（重命名窗口基准）

    // 单次绑定复用的伽马表（v0.5.4 起禁循环建绑；v0.5.7 升级为 static——
    // 暖进程二次进入时直接复用上一 Activity 的表对象，连领养都省掉）。
    // RX100M3 驱动实测脾气（两轮日志对账）：
    //   - 已绑定的表内容可反复改写且即时生效；
    //   - "createGammaTable+setExtendedGammaTable" 在经历过 release+相机重开
    //     之后再次执行必永久挂死 → 只允许在开机周期内首个相机回合做一次。
    private static CameraEx.GammaTable sBoundGamma;
    private static byte[] sIdentityBuf; // 与表同容量的恒等内容缓存（临时关用）

    // 启动预计算状态
    private boolean startupComputing = false;
    private int startupTotal = 0;
    private int startupDone = 0;

    // 已标记过的照片（防重复）
    private final Set<String> taggedFiles = new HashSet<String>();

    private Handler worker;
    private final Handler mainHandler = new Handler();

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sSessionNo++;
        Log.i(TAG, "onCreate session#" + sSessionNo
                + " pid=" + android.os.Process.myPid());
        prepLog("entry #" + sSessionNo + (sSessionNo > 1 ? " WARM(同进程!)" : " cold")
                + " pid=" + android.os.Process.myPid()
                + " " + heapStat());
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        topBar = (TextView) findViewById(R.id.topBar);
        lutListView = (TextView) findViewById(R.id.lutList);
        bottomHint = (TextView) findViewById(R.id.bottomHint);
        hud = (HudView) findViewById(R.id.hud);
        readStyle();
        loadLutDescs();
        lutMenu = new LutMenu(this, new LutMenu.DataSource() {
            public int itemCount() {
                return cubeFiles.size() + 2; // OFF + LUTs + 退出
            }
            public String itemName(int i) {
                if (i == 0) {
                    return OFF_NAME;
                }
                if (i == cubeFiles.size() + 1) {
                    return "退出应用";
                }
                return prettyName(displayName(i));
            }
            public String itemDesc(int i) {
                if (i == 0) {
                    return "关闭 LUT，还原图像原生色彩。";
                }
                if (i == cubeFiles.size() + 1) {
                    return "退出应用到原生拍摄界面。退出时自动清理 LUT 管线。";
                }
                File f = cubeFiles.get(i - 1);
                String d = lutDescs.get(stemOf(f.getName()));
                return d != null ? d : "V-Log 转换胶片模拟 LUT。";
            }
            public boolean isApplied(int i) {
                return i == appliedIndex;
            }
            public int selection() {
                return menuSel;
            }
            public int intensity() {
                return intensity;
            }
        });
        lutMenu.setVisibility(View.GONE);
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(
                lutMenu, new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        playView = new PlaybackView(this, new PlaybackView.Provider() {
            public Bitmap bitmap() {
                return playBmp;
            }
            public String caption() {
                return playCaption;
            }
        });
        playView.setVisibility(View.GONE);
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(
                playView, new android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        bottomHint.setText(simpleMode
                ? "Fn:菜单  拨杆/控制环:变焦  回看:回放  删除:关LUT  MENU:退出"
                : "拨轮1:选择  拨轮2:强度  确认:选定  删除:关闭  MENU:退出");

        HandlerThread t = new HandlerThread("lut-worker");
        t.start();
        worker = new Handler(t.getLooper());

        scanLuts();
        checkStartupCache();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume");
        resumed = true;
        pausing = false;
        notifyAppInfo();
        // 取景看门狗布防不依赖 startPreview 成功：预览「从没起来」也能被发现
        mainHandler.removeCallbacks(previewCheck);
        mainHandler.postDelayed(previewCheck, 2500);
        if (startupComputing) {
            // 预计算未完成前不进拍照界面
            return;
        }
        initCamera();
    }

    /** 打开相机并起预览（可重入）。 */
    private void initCamera() {
        // 上一次 onPause 的异步清理可能还在跑（快速重进场景）：
        // 上限 2s 等它释放完相机再开，避免 open 撞上 release
        Thread sd = shutdownThread;
        if (sd != null && sd.isAlive()) {
            Log.i(TAG, "initCamera: join shutdown thread");
            try {
                sd.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "initCamera: shutdown join done, alive=" + sd.isAlive());
        }
        if (camera != null) {
            return;
        }
        // open 前拿锁：shutdown 线程可能还在做清理（join 只等 2s 上限），
        // 拿不到就让汇聚器下轮节流重试，别阻塞 UI
        boolean locked = false;
        try {
            locked = camLock.tryLock(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            Log.w(TAG, "initCamera: camLock busy, defer to aggregator");
            prepLog("initCamera: camLock busy");
            return;
        }
        try {
            if (camera != null) {
                return;
            }
            camera = CameraEx.open(0, null);
            camera.setShutterListener(this);
            // AF 状态监听：驱动取景中央对焦框。
            // 回调线程不确定，只打 Log 并切主线程改 UI，不碰 camera native 调用
            camera.setAutoFocusStartListener(new CameraEx.AutoFocusStartListener() {
                public void onStart(CameraEx c) {
                    Log.i(TAG, "af start");
                    mainHandler.post(new Runnable() {
                        public void run() {
                            hud.setAfState(HudView.AF_WORKING);
                        }
                    });
                }
            });
            camera.setAutoFocusDoneListener(new CameraEx.AutoFocusDoneListener() {
                public void onDone(int status, int[] areas, CameraEx c) {
                    lastAfStatus = status;
                    Log.i(TAG, "af done: status=" + status);
                    final int hudState;
                    if (status == STATUS_LOCK || status == STATUS_LOCK_WARM) {
                        hudState = HudView.AF_LOCK;
                    } else if (status == STATUS_WORKING || status == STATUS_CONTINUOUS
                            || status == STATUS_LOCK_WARN) {
                        hudState = HudView.AF_WORKING;
                    } else {
                        hudState = HudView.AF_CLEAR;
                    }
                    mainHandler.post(new Runnable() {
                        public void run() {
                            hud.setAfState(hudState);
                        }
                    });
                }
            });
            // RX100M3：光圈监听（v0.5.5 起这是唯一可信的光圈真值来源——
            // modifier.getAperture() 恒返回 F1.8 不反映实时 iris）。
            // 直接回写 paramAperture，HUD 用它绘制参数模式显示。
            try {
                camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
                    public void onApertureChange(CameraEx.ApertureInfo info, CameraEx c) {
                        paramAperture = info.currentAperture;
                        final String s = "光圈 F" + (info.currentAperture / 100.0f);
                        Log.i(TAG, "aperture changed: " + s);
                        mainHandler.post(new Runnable() {
                            public void run() {
                                topBar.setText(s);
                            }
                        });
                        mainHandler.postDelayed(new Runnable() {
                            public void run() {
                                refreshTopBar();
                            }
                        }, 1500);
                    }
                });
            } catch (Throwable t) {
                Log.i(TAG, "setApertureChangeListener n/a: " + t);
            }
            // v0.5.8：PowerZoom 状态监听（变焦马达可用性信息源；旧"被 HAL 拒"
            // 结论来自未注册监听时的盲试，本次重审）
            try {
                camera.setPowerZoomListener(new CameraEx.PowerZoomListener() {
                    public void onChanged(int status, CameraEx c) {
                        if (status != pzStatus) {
                            Log.i(TAG, "powerzoom status=" + status
                                    + " (1=avail,2=unavail,3=inapplicable)");
                        }
                        pzStatus = status;
                    }
                });
            } catch (Throwable t) {
                Log.i(TAG, "setPowerZoomListener n/a: " + t);
            }
            // RX100M3：索尼"图像存储完成"权威信号（诊断 + 供护栏参考）。
            // 文件写稳 ≠ 索尼收尾完（缩略图/数据库/RAW 处理更久）。
            try {
                camera.setStoreImageCompleteListener(new CameraEx.StoreImageCompleteListener() {
                    public void onDone(int status, CameraEx.StoreImageInfo info, CameraEx c) {
                        storeCompleteAt = System.currentTimeMillis();
                        Log.i(TAG, "store image complete status=" + status);
                        prepLog("store complete status=" + status);
                    }
                });
            } catch (Throwable t) {
                Log.i(TAG, "setStoreImageCompleteListener n/a: " + t);
            }
            // RX100M3：变焦位置监听（原生控制环变焦时 HUD 显示焦距）
            try {
                camera.setZoomChangeListener(new CameraEx.ZoomChangeListener() {
                    public void onChanged(CameraEx.ZoomInfo info, CameraEx c) {
                        lastOptMag = info.opticalMagnification;
                        int fl = Math.round(24f * info.opticalMagnification / 100f);
                        if (fl < 24) fl = 24;
                        if (fl > 70) fl = 70;
                        final String s = "焦距 " + fl + "mm"
                                + (info.stopped ? "" : "...");
                        mainHandler.post(new Runnable() {
                            public void run() {
                                topBar.setText(s);
                            }
                        });
                        mainHandler.postDelayed(new Runnable() {
                            public void run() {
                                refreshTopBar();
                            }
                        }, 1500);
                    }
                });
            } catch (Throwable t) {
                Log.i(TAG, "setZoomChangeListener n/a: " + t);
            }
            // RX100M3：快门速度监听（v0.5.5：真值回写 paramShutter，
            // 与光圈同理——modifier 读回在原生联动下不可信）
            try {
                camera.setShutterSpeedChangeListener(new CameraEx.ShutterSpeedChangeListener() {
                    public void onShutterSpeedChange(CameraEx.ShutterSpeedInfo info, CameraEx c) {
                        if (info != null) {
                            paramShutter = info.currentShutterSpeed_n + "/"
                                    + info.currentShutterSpeed_d;
                            final String s = "快门 " + paramShutter;
                            mainHandler.post(new Runnable() {
                                public void run() {
                                    topBar.setText(s);
                                }
                            });
                            mainHandler.postDelayed(new Runnable() {
                                public void run() {
                                    refreshTopBar();
                                }
                            }, 1500);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.i(TAG, "setShutterSpeedChangeListener n/a: " + t);
            }
        } catch (Throwable t) {
            Log.e(TAG, "CameraEx.open failed", t);
            prepLog("CameraEx.open failed " + t);
            topBar.setText("相机打开失败: " + t);
            return;
        } finally {
            camLock.unlock();
        }
        if (!surfaceCbAdded) {
            surfaceHolder.addCallback(this);
            surfaceCbAdded = true;
        }
        maybeStartPreview("initCamera");
        refreshTopBar();
        // 恢复之前应用的 LUT（从播放界面返回等场景）
        if (appliedIndex > 0 && baseParams != null) {
            writePipeline(effectiveParams(baseParams));
        }
    }

    @Override
    protected void onPause() {
        final boolean finishing = isFinishing();
        Log.i(TAG, "onPause finishing=" + finishing);
        resumed = false;
        pausing = true; // 让汇聚器立即停手
        mainHandler.removeCallbacks(previewCheck);
        // UI 线程不做任何相机/HAL 调用——实测 UI 线程同步清理会死在 onPause
        // 里，触发索尼系统看门狗重启相机拍摄框架（按 MENU 黑屏重进拍照界面）。
        // 清管线/stopPreview/release 全部丢给独立 shutdown 线程；finishing 时
        // 另起 2.5s 看门狗：shutdown 就算卡死在 HAL 里，进程也会被无条件杀掉，
        // 内核回收 camera fd——用户看到秒退而不是黑屏。
        final boolean wasPreviewing = previewStarted;
        Thread t = new Thread("sonylut-shutdown") {
            public void run() {
                shutdownCamera(finishing, wasPreviewing);
            }
        };
        shutdownThread = t;
        t.start();
        if (finishing) {
            Thread wd = new Thread("sonylut-exit-watchdog") {
                public void run() {
                    // 顺延式看门狗：正常 shutdown ~300ms，2.5s 内都不算卡；
                    // 但 capture 在途（drain/写盘/打标收尾）期间不误杀——
                    // 闩锁置位时每 500ms 醒一次顺延 deadline，硬上限 20s 到点照样杀。
                    long start = System.currentTimeMillis();
                    long deadline = start + 2500;
                    boolean extended = false;
                    while (true) {
                        long now = System.currentTimeMillis();
                        if (now >= deadline || now >= start + 20000) {
                            break;
                        }
                        if (captureDraining) {
                            if (!extended) {
                                extended = true;
                                Log.i(TAG, "exit watchdog: capture draining, extend");
                                prepLog("watchdog extend (capture draining)");
                            }
                            deadline = now + 2500;
                        }
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    Log.w(TAG, "exit watchdog deadline reached");
                    // 清理已完成：绝不杀进程（RX100M3 上杀进程会带崩 DA 交还，
                    // 相机重启）。只有清理真卡死（HAL 挂起、fd 放不出）才杀。
                    if (cleanupDone) {
                        Log.i(TAG, "exit watchdog: cleanup done, no kill");
                        prepLog("watchdog skip (cleanup done)");
                        return;
                    }
                    Log.w(TAG, "exit watchdog fired, kill process");
                    prepLog("exit watchdog fired");
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0); // killProcess 若未生效的兜底
                }
            };
            wd.setDaemon(true);
            wd.start();
        }
        previewStarted = false;
        surfaceHolder.removeCallback(this);
        surfaceCbAdded = false;
        lastAfStatus = 0;
        hud.setAfState(HudView.AF_CLEAR);
        super.onPause();
        Log.i(TAG, "onPause done (cleanup async)");
        prepLog("pause dispatched finishing=" + finishing);
    }

    /** 相机清理（独立 shutdown 线程，可阻塞，finishing 时有看门狗兜底）：
     *  清管线 → stopPreview → release。exitAfter=true 做完杀进程。
     *  全程持 camLock，与其它线程的 HAL 调用互斥（v0.3.1 退出卡几秒事故）。 */
    private void shutdownCamera(boolean exitAfter, boolean wasPreviewing) {
        long t0 = System.currentTimeMillis();
        Log.i(TAG, "shutdown begin");
        prepLog("shutdown begin");
        cleanupDone = false;
        // capture 在途闩锁：拍照后立刻 MENU 退出时，驱动 drain/写盘/护栏可能
        // 未完（RX100M3 写盘护栏最长 ~13s），此时 stopPreview+release 会打断
        // 索尼写入 → 数据库损坏 → 重启。有界等待 ≤16s 让闩锁先清空。
        long w0 = System.currentTimeMillis();
        while (captureDraining && System.currentTimeMillis() - w0 < 16000) {
            Log.i(TAG, "waiting capture drain +" + rel(w0) + "ms");
            prepLog("waiting capture drain " + rel(w0) + "ms");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        if (captureDraining) {
            Log.e(TAG, "capture drain timeout 16s, proceed anyway");
            prepLog("capture drain TIMEOUT");
        } else if (System.currentTimeMillis() - w0 > 0) {
            Log.i(TAG, "capture drain done +" + rel(w0) + "ms");
            prepLog("capture drain done " + rel(w0) + "ms");
        }
        prepLog("shutdown lock wait begin");
        camLock.lock(); // 后台线程可等；卡死由 2.5s 看门狗/进程退出兜底
        prepLog("shutdown lock acquired +" + rel(t0) + "ms");
        try {
            // RX100M3：先拔掉所有 native→Java 回调（Zoom/Aperture/Shutter…），
            // 再清管线/释放。原生变焦工作后这些回调持续在飞；System.exit 杀掉
            // JVM 时若 native 事件线程正在回调，JNI 崩溃会带崩相机服务，
            // 表现为按 MENU 退出后相机自动重启。
            if (camera != null) {
                // clearListeners() 是 private，只能逐个置 null 注销
                try {
                    camera.setZoomChangeListener(null);
                    camera.setApertureChangeListener(null);
                    camera.setPowerZoomListener(null);
                    camera.setAutoFocusStartListener(null);
                    camera.setAutoFocusDoneListener(null);
                    camera.setShutterListener(null);
                    Log.i(TAG, "listeners cleared @shutdown");
                } catch (Throwable t) {
                    Log.i(TAG, "listener unregister n/a: " + t);
                }
                // v0.3.5：退出清理改为 SD 卡可配（/LUTS/EXITCLR.TXT）。
                // 模式：NONE=完全不清 / LINEAR=中性表+恒等矩阵 / GAMMA=仅中性表 /
                //       MATRIX=仅恒等矩阵 / NULL=停预览但保留绑定。
                // 生效缺省：无文件=NULL，解析失败=LINEAR 兜底。
                // v0.5.4：所有模式均已禁止解绑/新建伽马表（HAL 死点）。
                String clrMode = readExitClearMode();
                Log.i(TAG, "exit clear mode=" + clrMode);
                prepLog("exit clear mode=" + clrMode);
                if (!"NONE".equals(clrMode)) {
                    clearPipelineForExit(clrMode);
                }
                Log.i(TAG, "pipeline clear done @shutdown +" + rel(t0) + "ms");
                try {
                    Thread.sleep(1500); // 沉降：异步参数提交落地
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (wasPreviewing) {
                    try {
                        Log.i(TAG, "stopPreview @shutdown");
                        camera.getNormalCamera().stopPreview();
                        Log.i(TAG, "stopPreview done +" + rel(t0) + "ms");
                    } catch (Throwable t) {
                        Log.e(TAG, "stopPreview failed", t);
                    }
                }
                try {
                    Log.i(TAG, "camera release @shutdown");
                    camera.release();
                    Log.i(TAG, "camera release done +" + rel(t0) + "ms");
                } catch (Throwable t) {
                    Log.e(TAG, "camera release failed", t);
                }
                // v0.5.7：release 挪到相机句柄关闭之后（停流后摘缓冲的温和时序），
                // 防退出重启依然成立；下回合进 App 走 ADOPT 复用 HAL 旧绑定绕开 create 死区
                releaseBoundGamma("shutdown");
                camera = null;
            }
        } finally {
            camLock.unlock();
        }
        previewStarted = false;
        zoomDriveDir = 0;
        stopHoldLoop();      // 终止按住续发循环
        cleanupDone = true; // 看门狗凭此判断：清理已完成就绝不杀进程
        Log.i(TAG, "shutdown done +" + rel(t0) + "ms");
        prepLog("shutdown done " + rel(t0) + "ms");
        if (exitAfter) {
            // v0.3.3：不再 System.exit。RX100M3 上退出杀进程（System.exit/
            // killProcess）恰逢 DA 显示交还、原生界面开相机，框架把进程死亡
            // 当致命错 → 相机重启+数据库修复（实测两次清理均干净完成后仍重启）。
            // 相机已 release、fd 已还，空进程留给系统缓存回收即可。
            Log.i(TAG, "exit: cleanup complete, process left alive");
            prepLog("exit complete (alive)");
        }
    }

    private static long rel(long t0) {
        return System.currentTimeMillis() - t0;
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "onDestroy");
        mainHandler.removeCallbacks(previewCheck); // 摘汇聚器链（onPause 已摘，双保险）
        super.onDestroy();
        if (worker != null) {
            worker.getLooper().quit(); // 队列里残余任务丢弃，别拖累退出
        }
        // 注意：这里不 System.exit——进程终止由 shutdown 线程清理完成后
        // 执行（或其 2.5s 看门狗兜底），onDestroy 只是生命周期过场
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        maybeStartPreview("surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        previewStarted = false;
    }

    private boolean previewRetried = false;

    /**
     * 状态汇聚启动预览：只要（预计算完成 ∧ camera 可用）就尝试。surface 就绪
     * 不信事件接力——surfaceCreated 可能在 addCallback 注册前到达（首次启动
     * 预计算拖几秒，事件被丢弃，startPreview 被守卫静默吞掉），!surfaceReady
     * 时直接探测 surface 当前有效性补判定。
     */
    private void maybeStartPreview(String why) {
        if (startupComputing || camera == null || previewStarted) {
            return;
        }
        if (!surfaceReady) {
            try {
                android.view.Surface s = surfaceHolder.getSurface();
                if (s != null && s.isValid()) {
                    surfaceReady = true;
                    Log.i(TAG, "surface valid by probe @" + why
                            + " (surfaceCreated missed)");
                    prepLog("surface probed valid @" + why);
                }
            } catch (Throwable t) {
                Log.w(TAG, "surface probe failed: " + t);
            }
        }
        if (!surfaceReady) {
            Log.w(TAG, "maybeStartPreview(" + why + "): surface not ready");
            return;
        }
        startPreview();
    }

    private void startPreview() {
        if (camera == null || previewStarted || !surfaceReady || pausing) {
            return;
        }
        if (!camLock.tryLock()) {
            // shutdown/kick 持锁中：跳过本次，汇聚器下轮会再补
            Log.w(TAG, "startPreview: camLock busy, skip");
            return;
        }
        try {
            if (camera == null || previewStarted || pausing) {
                return;
            }
            camera.getNormalCamera().setPreviewDisplay(surfaceHolder);
            camera.getNormalCamera().startPreview();
            previewStarted = true;
            previewRetried = false;
            Log.i(TAG, "preview started");
            prepLog("preview started");
            armPreviewWatchdog();
        } catch (Throwable e) {
            // 首启（预计算后）HAL 可能还没就绪：捕全部异常，800ms 后重试一次
            Log.e(TAG, "startPreview failed", e);
            prepLog("startPreview failed " + e);
            if (!previewRetried) {
                previewRetried = true;
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        Log.i(TAG, "startPreview retry");
                        startPreview();
                    }
                }, 800);
            }
        } finally {
            camLock.unlock();
        }
    }

    // ---------------- 取景帧心跳看门狗（周期性汇聚器，时序竞争免疫） ----------------
    // 快速重进（进程退出 ~1s 后）索尼驱动偶发「open/startPreview 都成功但无
    // 画面」；首次启动预计算数秒会把 surfaceCreated 事件丢掉（addCallback 注册
    // 前到达）。所以不靠一次性事件接力：resumed 期间每 1.5s 自我重排一次，状态
    // 到哪一步补哪一步——camera null 补 open（节流），预览未起补
    // maybeStartPreview（连 6 tick 补不动升级 reopen），起了但没帧走 kick 分级
    // 自救。帧心跳确认（previewAlive）后停表；onPause/onDestroy 摘链。

    /** 帧信号 1：索尼分析数据流。只确认首帧，确认后立刻摘掉，不常驻。 */
    private final CameraEx.PreviewAnalizeListener analizeListener =
            new CameraEx.PreviewAnalizeListener() {
                public void onAnalizedData(CameraEx.AnalizedData d, CameraEx c) {
                    previewFrameSeen("analize data");
                }
            };

    /** 帧信号 2：标准一次性预览帧回调（回调线程 = open 时绑定的主线程）。 */
    private final Camera.PreviewCallback oneShotFrame = new Camera.PreviewCallback() {
        public void onPreviewFrame(byte[] data, Camera c) {
            previewFrameSeen("oneshot frame "
                    + (data == null ? 0 : data.length) + "B");
        }
    };

    private void previewFrameSeen(String via) {
        if (previewAlive) {
            return;
        }
        previewAlive = true;
        previewKickStage = 0; // 有帧即痊愈，自救级数归零
        Log.i(TAG, "preview frames confirmed via " + via);
        mainHandler.removeCallbacks(previewCheck);
        // 分析流确认完就摘掉（worker 上摘，拿不到锁就算了，留着也无害）
        worker.post(new Runnable() {
            public void run() {
                CameraEx cam = camera;
                if (cam == null || !camLock.tryLock()) {
                    return;
                }
                try {
                    cam.setPreviewAnalizeListener(null);
                } catch (Throwable t) {
                } finally {
                    camLock.unlock();
                }
            }
        });
    }

    private final Runnable previewCheck = new Runnable() {
        public void run() {
            if (!resumed || pausing) {
                return; // 停表：onResume 重新布防
            }
            if (startupComputing || takingPicture) {
                // 预计算/拍照中不判定（帧路径不同），顺延一轮再看——
                // 注意必须重排保持链存活，预计算可能跑好几秒
                mainHandler.postDelayed(this, 1500);
                return;
            }
            if (previewAlive) {
                return; // 帧心跳已确认，停表
            }
            if (camera == null) {
                // initCamera 失败/没跑到：节流重开（每 4 tick ≈ 6s 一次），
                // 12 tick（~18s）开不起来才放弃提示
                cameraNullTicks++;
                if (cameraNullTicks == 1 || cameraNullTicks % 4 == 0) {
                    Log.w(TAG, "preview tick: camera null (" + cameraNullTicks
                            + "), initCamera");
                    prepLog("tick: camera null " + cameraNullTicks);
                    initCamera();
                }
                if (cameraNullTicks >= 12) {
                    cameraNullTicks = 0;
                    Log.e(TAG, "preview tick: camera never opened, give up");
                    prepLog("tick: camera null, gave up");
                    topBar.setText("相机初始化失败，请退出重进");
                    return; // 停表
                }
                mainHandler.postDelayed(this, 1500);
                return;
            }
            cameraNullTicks = 0;
            if (!previewStarted) {
                // 预览从没起来过（无声黑屏）：周期性汇聚，每 tick 补一枪；
                // 连 6 tick（~9s）补不动升级 reopen 自救
                previewNotStartedTicks++;
                Log.w(TAG, "preview tick: not started (" + previewNotStartedTicks
                        + "), converge");
                prepLog("tick: preview not started " + previewNotStartedTicks);
                maybeStartPreview("tick");
                if (!previewStarted && previewNotStartedTicks >= 6) {
                    previewNotStartedTicks = 0;
                    kickPreview(); // kick 内部会重挂链
                    return;
                }
                mainHandler.postDelayed(this, 1500);
                return;
            }
            previewNotStartedTicks = 0;
            kickPreview(); // 已 start 但无帧：分级自救（kick 内部重挂链）
        }
    };

    /** startPreview 成功后挂上：2.5s 无帧信号 → previewCheck 触发自救。 */
    private void armPreviewWatchdog() {
        previewAlive = false;
        // 帧信号监听挂接进 HAL，走锁；startPreview 持锁调用时重入无碍，
        // kick 完成后主线程重挂时可能撞上 shutdown——拿不到就跳过，
        // 链仍然重排，下轮 tick 会再汇聚
        if (camLock.tryLock()) {
            try {
                if (camera != null && !pausing) {
                    try {
                        camera.setPreviewAnalizeListener(analizeListener);
                    } catch (Throwable t) {
                        Log.w(TAG, "analize listener failed: " + t);
                    }
                    try {
                        camera.getNormalCamera()
                                .setOneShotPreviewCallback(oneShotFrame);
                    } catch (Throwable t) {
                        Log.w(TAG, "oneshot cb failed: " + t);
                    }
                }
            } finally {
                camLock.unlock();
            }
        } else {
            Log.w(TAG, "armPreviewWatchdog: camLock busy, listeners skipped");
        }
        mainHandler.removeCallbacks(previewCheck);
        mainHandler.postDelayed(previewCheck, 2500);
        Log.i(TAG, "preview watchdog armed");
    }

    /** 黑取景自救（独立线程，不堵主线程/worker——它们可能正卡在 HAL 里）。 */
    private void kickPreview() {
        int st = ++previewKickStage;
        if (st == 1 && !previewStarted) {
            // 预览从没起来过：stop/start 无意义，直接升级整只 reopen
            st = 2;
            previewKickStage = 2;
        }
        final int stage = st;
        Log.w(TAG, "preview black/stuck, kick stage=" + stage);
        prepLog("preview kick stage=" + stage);
        if (stage > 2) {
            Log.e(TAG, "preview kick gave up");
            prepLog("preview kick gave up");
            topBar.setText("取景异常，请退出重进");
            return;
        }
        new Thread("sonylut-preview-kick") {
            public void run() {
                camLock.lock(); // 后台线程可等；与 shutdown 互斥，防并发进 HAL
                try {
                    if (camera == null || pausing) {
                        return;
                    }
                    if (stage == 1) { // 只有 previewStarted 才会走到这
                        try {
                            Log.i(TAG, "kick: stopPreview");
                            camera.getNormalCamera().stopPreview();
                            Thread.sleep(400); // 给驱动一点沉降时间
                            Log.i(TAG, "kick: startPreview");
                            camera.getNormalCamera().startPreview();
                        } catch (Throwable t) {
                            Log.e(TAG, "kick stage1 failed", t);
                        }
                    } else {
                        // stage 2：整只相机 release 后由主线程走 initCamera 重开
                        try {
                            camera.release();
                        } catch (Throwable t) {
                            Log.e(TAG, "kick release failed", t);
                        }
                        releaseBoundGamma("kick"); // 句柄换新前同样放掉缓冲区记账
                        camera = null;
                        Log.i(TAG, "kick: camera released, reopen");
                    }
                } finally {
                    camLock.unlock();
                }
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (stage == 2) {
                            initCamera(); // 内部汇聚起预览 → 重新挂看门狗
                        } else {
                            armPreviewWatchdog(); // 重挂，仍无帧则升级 stage2
                        }
                    }
                });
            }
        }.start();
    }

    /** 拍摄类应用注册（LVG 同款）：声明 CATEGORY_REC，快门才归本 App。 */
    private void notifyAppInfo() {
        android.content.Intent intent = new android.content.Intent(
                "com.android.server.DAConnectionManagerService.AppInfoReceive");
        intent.putExtra("package_name", getComponentName().getPackageName());
        intent.putExtra("class_name", getComponentName().getClassName());
        intent.putExtra("large_category", "CATEGORY_REC");
        intent.putExtra("small_category", "APP_SHOOTING");
        sendBroadcast(intent);
    }

    /** 持久化尸检日志：冻机后也能从 SD 卡 PREPLOG.TXT 读出卡在哪一步。
     *  超 64KB 时把当前文件改名保留为 PREPLOG.OLD 再起新档——
     *  此前直接整文件重写，曾把参数测试的现场记录全部抹掉。 */
    private static void prepLog(String msg) {
        try {
            CACHE_DIR.mkdirs();
            File f = new File(CACHE_DIR, "PREPLOG.TXT");
            boolean append = true;
            if (f.isFile() && f.length() > 65536) {
                File old = new File(CACHE_DIR, "PREPLOG.OLD");
                old.delete();
                f.renameTo(old);
                Log.i(TAG, "preplog rotated to PREPLOG.OLD");
                append = false;
            }
            FileOutputStream fos = new FileOutputStream(f, append);
            try {
                fos.write((System.currentTimeMillis() + " " + msg + "\n")
                        .getBytes("UTF-8"));
                fos.getFD().sync();
            } finally {
                fos.close();
            }
        } catch (Throwable t) {
        }
    }

    /** Dalvik 堆快照（KB）：暖启动变慢的 GC 压力假说直接看这两个数。 */
    private static String heapStat() {
        Runtime rt = Runtime.getRuntime();
        long free = rt.freeMemory() / 1024;
        long total = rt.totalMemory() / 1024;
        return "heap " + free + "/" + total + "KB free";
    }

    // ---------------- 启动预计算 ----------------

    /** 检查所有 cube 是否都有新鲜缓存；缺的进预计算流程，算完再进拍照界面。
     *  v0.5.1：miss 名单与原因记入 PREPLOG——"二次进入卡在计算"此前零诊断。 */
    private void checkStartupCache() {
        final List<File> missing = new ArrayList<File>();
        StringBuilder missWhy = new StringBuilder();
        for (File f : cubeFiles) {
            File cache = new File(CACHE_DIR, shortName83(f.getName()) + ".LTC");
            StringBuilder why = new StringBuilder();
            if (!LutParams.isFresh(cache, f, why)) {
                missing.add(f);
                missWhy.append(' ').append(shortName83(f.getName())).append(why);
            }
        }
        Log.i(TAG, "cache check: " + cubeFiles.size() + " cubes, "
                + missing.size() + " missing");
        prepLog("cachecheck cubes=" + cubeFiles.size()
                + " missing=" + missing.size() + missWhy);
        if (missing.isEmpty()) {
            Log.i(TAG, "all LUT caches fresh, skip precompute");
            return;
        }
        startupComputing = true;
        startupTotal = missing.size();
        startupDone = 0;
        lutListView.setVisibility(View.VISIBLE);
        bottomHint.setVisibility(View.GONE);
        showStartupProgress(null);
        worker.post(new Runnable() {
            public void run() {
                for (final File f : missing) {
                    mainHandler.post(new Runnable() {
                        public void run() {
                            showStartupProgress(displayName(cubeFiles.indexOf(f) + 1));
                        }
                    });
                    try {
                        loadOrDecompose(f);
                    } catch (Throwable t) {
                        Log.e(TAG, "precompute failed: " + f.getName(), t);
                    }
                    startupDone++;
                }
                writeLutList();
                mainHandler.post(new Runnable() {
                    public void run() {
                        prepLog("startup precompute end "
                                + startupDone + "/" + startupTotal);
                        startupComputing = false;
                        lutListView.setVisibility(View.GONE);
                        bottomHint.setVisibility(View.VISIBLE);
                        refreshTopBar();
                        if (resumed) {
                            initCamera();
                        }
                    }
                });
            }
        });
    }

    private void showStartupProgress(String name) {
        String s = "正在计算新增LUT";
        if (name != null) {
            s += "（" + name + "）";
        }
        s += "\n" + startupDone + "/" + startupTotal;
        lutListView.setText(s);
        topBar.setText("CUSTOM LUT");
    }

    /** 把已算好的 LUT 列表写进缓存目录（8.3 文件名 LUTLIST.TXT）。 */
    private void writeLutList() {
        try {
            CACHE_DIR.mkdirs();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cubeFiles.size(); i++) {
                sb.append(cubeFiles.get(i).getName());
                sb.append('\t');
                sb.append(displayName(i + 1));
                sb.append('\n');
            }
            File list = new File(CACHE_DIR, "LUTLIST.TXT");
            FileOutputStream fos = new FileOutputStream(list);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
            Log.i(TAG, "LUTLIST.TXT written, " + cubeFiles.size() + " entries");
        } catch (Throwable t) {
            Log.e(TAG, "writeLutList failed", t);
        }
    }

    // ---------------- LUT 列表 ----------------

    private void scanLuts() {
        cubeFiles.clear();
        File[] files = LUT_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".cube") || n.endsWith(".cub"))) {
                    cubeFiles.add(f);
                }
            }
        }
        Collections.sort(cubeFiles, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        Log.i(TAG, "found " + cubeFiles.size() + " cubes in " + LUT_DIR);
        prepLog("scan cubes=" + cubeFiles.size());
        refreshTopBar();
    }

    private final java.util.Map<String, String> titleCache =
            new java.util.HashMap<String, String>();

    private String displayName(int index) {
        if (index == 0) {
            return OFF_NAME;
        }
        File f = cubeFiles.get(index - 1);
        String path = f.getPath();
        String title = titleCache.get(path);
        if (title == null) {
            title = readTitle(f);
            titleCache.put(path, title);
        }
        return title;
    }

    /** 从 .cube 头部读 TITLE；没有则退回文件名（去扩展名）。 */
    private static String readTitle(File f) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 10) {
                line = line.trim();
                if (line.toUpperCase().startsWith("TITLE")) {
                    int q1 = line.indexOf('"');
                    int q2 = line.lastIndexOf('"');
                    if (q1 >= 0 && q2 > q1) {
                        return line.substring(q1 + 1, q2);
                    }
                }
            }
        } catch (Throwable ignore) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignore) {}
            }
        }
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void refreshTopBar() {
        if (startupComputing) {
            return;
        }
        String s;
        if (paramMode) {
            s = paramLabel() + "  " + paramValue();
        } else if (cubeFiles.isEmpty()) {
            s = "SD卡 /LUTS 下未发现 .cube 文件";
        } else if (appliedIndex == 0) {
            s = "未应用 LUT";
        } else {
            s = displayName(appliedIndex) + "   强度 " + intensity + "%";
        }
        topBar.setText(s);
    }

    /** Fn 参数模式：进入/退出。 */
    private void toggleParamMode() {
        paramMode = !paramMode;
        if (paramMode) {
            browsing = false; // 退出 LUT 浏览
            refreshListView();
            // 初始化参数当前值（从相机读）
            initParamValues();
        }
        refreshTopBar();
        Log.i(TAG, "param mode " + (paramMode ? "ON" : "OFF"));
        prepLog("param mode " + (paramMode ? "on" : "off"));
    }

    /** 参数模式：当前参数项名称。 */
    private String paramLabel() {
        switch (paramIndex) {
            case PARAM_EV: return "曝光补偿";
            case PARAM_APERTURE: return "光圈";
            case PARAM_SHUTTER: return "快门";
            case PARAM_ISO: return "ISO";
            case PARAM_WB_LB: return "白平衡LB";
            case PARAM_WB_CC: return "白平衡CC";
            default: return "?";
        }
    }

    /** 参数模式：当前参数项值。 */
    private String paramValue() {
        switch (paramIndex) {
            case PARAM_EV:
                int whole = paramEv / 3;
                int frac = Math.abs(paramEv % 3);
                return (paramEv >= 0 ? "+" : "") + whole + (frac == 0 ? "" : frac == 1 ? ".3" : ".7") + " EV";
            case PARAM_APERTURE:
                return paramAperture > 0 ? "F" + (paramAperture / 100.0f) : "F--";
            case PARAM_SHUTTER:
                // Pair<分子,分母> 渲染为 1/500 样式；调节走原生 increment/decrement
                return "SS " + paramShutter;
            case PARAM_ISO:
                return "ISO " + paramIso;
            case PARAM_WB_LB:
                return "LB " + (paramWbLb >= 0 ? "+" : "") + paramWbLb;
            case PARAM_WB_CC:
                return "CC " + (paramWbCc >= 0 ? "+" : "") + paramWbCc;
            default: return "";
        }
    }

    /** 从相机读取当前参数值与本机能力（进入参数模式时调用）。
     *  全部读写真实值：HUD 显示的从此是相机状态，不是本地猜测。
     *  每项独立 try/catch：某项不支持不影响其它项。 */
    private void initParamValues() {
        paramSupEv = false;
        paramSupWbMode = false;
        if (camera == null) {
            return;
        }
        if (!camLock.tryLock()) {
            prepLog("initParams busy");
            return;
        }
        try {
            Camera cam = camera.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
            try {
                paramAperture = mod.getAperture();
            } catch (Throwable t) {
                paramAperture = -1;
            }
            // 快门：Pair<分子,分母>（如 1/500）；调节走 increment/decrement 原生步进
            try {
                android.util.Pair ss = mod.getShutterSpeed();
                paramShutter = (ss != null && ss.first instanceof Integer
                        && ss.second instanceof Integer)
                        ? ((Integer) ss.first).intValue() + "/"
                          + ((Integer) ss.second).intValue()
                        : "--";
            } catch (Throwable t) {
                paramShutter = "--";
            }
            try {
                paramIso = mod.getISOSensitivity();
            } catch (Throwable t) {
                paramIso = 0;
            }
            // 曝光补偿：v0.5.5 起为本地管线变换（effectiveParams），不走 HAL——
            // picture-control-exposure-shift 实测被接受但画面零变化（摆设通道）。
            // 支持位仅探测量化留档；本地计数恒从 0 开始。
            try {
                paramSupEv = mod.isPictureControlExposureShiftSupported();
            } catch (Throwable t) {
            }
            paramEv = 0;
            // 白平衡 LB/CC：当前值 + 运行时范围（替换旧的 ±100 硬编码猜测）
            try {
                paramWbLb = mod.getWhiteBalanceShiftLB();
                paramWbLbMin = mod.getMinWhiteBalanceShiftLB();
                paramWbLbMax = mod.getMaxWhiteBalanceShiftLB();
            } catch (Throwable t) {
            }
            try {
                paramWbCc = mod.getWhiteBalanceShiftCC();
                paramWbCcMin = mod.getMinWhiteBalanceShiftCC();
                paramWbCcMax = mod.getMaxWhiteBalanceShiftCC();
            } catch (Throwable t) {
            }
            try {
                paramSupWbMode = mod.isWhiteBalanceShiftModeSupported();
            } catch (Throwable t) {
            }
            // ISO 支持档位表（List<Integer>；用 raw List 规避 stub 泛型属性瑕疵）
            List lst = null;
            try {
                lst = mod.getSupportedISOSensitivities();
            } catch (Throwable t) {
            }
            if (lst != null && !lst.isEmpty()) {
                int[] arr = new int[lst.size()];
                int n = 0;
                for (int i = 0; i < arr.length; i++) {
                    Object o = lst.get(i);
                    if (o instanceof Integer) {
                        arr[n++] = ((Integer) o).intValue();
                    }
                }
                if (n > 0) {
                    isoStepsSupported = n == arr.length ? arr : Arrays.copyOf(arr, n);
                    Arrays.sort(isoStepsSupported);
                    // 当前值若不在表中，吸附到最近档，避免 UI 显示一个相机不认的数
                    boolean in = false;
                    for (int v : isoStepsSupported) {
                        if (v == paramIso) {
                            in = true;
                            break;
                        }
                    }
                    if (!in) {
                        int best = isoStepsSupported[0];
                        for (int v : isoStepsSupported) {
                            if (Math.abs(v - paramIso) < Math.abs(best - paramIso)) {
                                best = v;
                            }
                        }
                        Log.i(TAG, "iso " + paramIso + " not in table, snap to " + best);
                        paramIso = best;
                    }
                }
            }
            Log.i(TAG, "param init ev=" + paramEv + "(sup=" + paramSupEv + ") F="
                    + (paramAperture > 0 ? (paramAperture / 100.0f) : -1)
                    + " ss=" + paramShutter + " iso=" + paramIso
                    + "/" + (isoStepsSupported != null ? isoStepsSupported.length : 0)
                    + " lb=[" + paramWbLbMin + "," + paramWbLbMax + "]@" + paramWbLb
                    + " cc=[" + paramWbCcMin + "," + paramWbCcMax + "]@" + paramWbCc
                    + " wbModeSup=" + paramSupWbMode);
            prepLog("pinit ev=" + paramEv + "/s" + (paramSupEv ? 1 : 0)
                    + " lb" + paramWbLb + "[" + paramWbLbMin + "," + paramWbLbMax + "]"
                    + " cc" + paramWbCc + "[" + paramWbCcMin + "," + paramWbCcMax + "]"
                    + " wbm=" + (paramSupWbMode ? 1 : 0));
        } catch (Throwable t) {
            Log.e(TAG, "initParamValues failed", t);
            prepLog("initParams fail " + t);
        } finally {
            camLock.unlock();
        }
    }

    /** 调整当前参数。delta=±1（步进）。
     *  v0.5.1 重构（修"只走本地数字不生效"）：
     *  - 写前：EV 查支持位、WB 夹到运行时 min/max 并先开 shift 总开关、ISO 用
     *    相机报告档位表；
     *  - 写后：重新 getParameters 读回 HAL 实际接受的值覆盖 UI——写入被丢弃/
     *    被夹紧时用户看到的就是真值而不是请求值；req/hal 差异记入 PREPLOG。 */
    private void adjustParam(int delta) {
        if (camera == null || pausing) {
            return;
        }
        if (!camLock.tryLock()) {
            Log.w(TAG, "adjustParam: camLock busy, skip");
            return;
        }
        int committedWhich = -1; // 走 Parameters 提交通道的项
        int requested = Integer.MIN_VALUE;
        try {
            Camera cam = camera.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
            switch (paramIndex) {
                case PARAM_EV: {
                    // 本地曝光变换：重算并写入伽马内容（+EV=数字过曝，-EV=欠曝）。
                    // 不碰 HAL 参数通道、不需要 setParameters/readBack。
                    paramEv = clampInt(paramEv + delta, -60, 60);
                    writePipeline(effectiveParams(
                            appliedIndex > 0 ? baseParams : null));
                    break;
                }
                case PARAM_APERTURE: {
                    if (delta > 0) {
                        camera.incrementAperture();
                    } else {
                        camera.decrementAperture();
                    }
                    scheduleNativeReadback(true, delta);
                    break;
                }
                case PARAM_SHUTTER: {
                    if (delta > 0) {
                        camera.incrementShutterSpeed();
                    } else {
                        camera.decrementShutterSpeed();
                    }
                    scheduleNativeReadback(false, delta);
                    break;
                }
                case PARAM_ISO: {
                    int[] table = isoStepsSupported != null
                            ? isoStepsSupported : ISO_LEGACY_STEPS;
                    requested = stepIso(table, paramIso, delta);
                    paramIso = requested;
                    mod.setISOSensitivity(requested);
                    committedWhich = PARAM_ISO;
                    break;
                }
                case PARAM_WB_LB: {
                    maybeEnableWbShiftMode(mod); // 官方 LVG 流程：shift 前置总开关
                    requested = clampInt(paramWbLb + delta,
                            paramWbLbMin, paramWbLbMax);
                    paramWbLb = requested;
                    mod.setWhiteBalanceShiftLB(requested);
                    committedWhich = PARAM_WB_LB;
                    break;
                }
                case PARAM_WB_CC: {
                    maybeEnableWbShiftMode(mod);
                    requested = clampInt(paramWbCc + delta,
                            paramWbCcMin, paramWbCcMax);
                    paramWbCc = requested;
                    mod.setWhiteBalanceShiftCC(requested);
                    committedWhich = PARAM_WB_CC;
                    break;
                }
            }
            if (committedWhich >= 0) {
                cam.setParameters(p);
                readBack(committedWhich, requested);
            }
            refreshTopBar();
            Log.i(TAG, "adjustParam " + paramLabel() + " delta=" + delta);
        } catch (Throwable t) {
            Log.e(TAG, "adjustParam failed", t);
            prepLog("adjustParam fail " + t);
        } finally {
            camLock.unlock();
        }
    }

    /** 光圈/快门原生步进后的诊断：只记 inhibition 位图（v0.5.5 起真值由
     *  Aperture/ShutterSpeed 监听器回写字段维护，modifier 读回不可信已弃用）。 */
    private void scheduleNativeReadback(final boolean aperture, final int delta) {
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                worker.post(new Runnable() {
                    public void run() {
                        CameraEx camRef = camera;
                        if (camRef == null || pausing || !camLock.tryLock()) {
                            return;
                        }
                        try {
                            int inhib = -1;
                            try {
                                inhib = camRef.getInhibitionInfo(); // 控制权 inhibit 位图
                            } catch (Throwable t) {
                            }
                            String tag = aperture ? "IRIS" : "SS";
                            prepLog("native " + tag + " d=" + delta
                                    + " inh=0x" + Integer.toHexString(inhib));
                            mainHandler.post(new Runnable() {
                                public void run() {
                                    refreshTopBar();
                                }
                            });
                        } finally {
                            camLock.unlock();
                        }
                    }
                });
            }
        }, 300);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 白平衡 LB/CC 的前置总开关（官方 Liveview Grading 必开；不开则改值不生效，
     *  这是此前"白平衡没效果"的头号嫌疑）。 */
    private void maybeEnableWbShiftMode(CameraEx.ParametersModifier mod) {
        if (!paramSupWbMode) {
            return;
        }
        try {
            mod.setWhiteBalanceShiftMode(true);
        } catch (Throwable t) {
            prepLog("wb mode on fail " + t);
        }
    }

    /** 在升序档位表内向 dir 方向步进；当前值不在表内时吸附到相邻档。 */
    private static int stepIso(int[] asc, int cur, int dir) {
        if (asc.length == 0) {
            return cur;
        }
        int i = 0;
        for (int k = 0; k < asc.length; k++) {
            if (asc[k] <= cur) {
                i = k;
            } else {
                break;
            }
        }
        i = clampInt(i + dir, 0, asc.length - 1);
        return asc[i];
    }

    /** Parameters 类参数的写后读回：HAL 拒收/夹紧时把真实接受值刷回 UI 与日志。
     *  这一次装机就能定量回答 EV/WB 到底吃不吃、范围几何。 */
    private void readBack(int which, int requested) {
        try {
            Camera.Parameters p2 = camera.getNormalCamera().getParameters();
            CameraEx.ParametersModifier m2 = camera.createParametersModifier(p2);
            int hal;
            String name;
            switch (which) {
                case PARAM_ISO:
                    hal = m2.getISOSensitivity();
                    name = "ISO";
                    paramIso = hal;
                    break;
                case PARAM_WB_LB:
                    hal = m2.getWhiteBalanceShiftLB();
                    name = "WBLB";
                    paramWbLb = hal;
                    break;
                default:
                    hal = m2.getWhiteBalanceShiftCC();
                    name = "WBCC";
                    paramWbCc = hal;
                    break;
            }
            Log.i(TAG, "readback " + name + " req=" + requested + " hal=" + hal);
            prepLog("rb " + name + " req=" + requested + " hal=" + hal);
        } catch (Throwable t) {
            Log.e(TAG, "readback failed", t);
            prepLog("rb fail " + t);
        }
    }

    private void refreshListView() {
        if (!browsing) {
            lutListView.setVisibility(View.GONE);
            return;
        }
        int total = cubeFiles.size() + 1;
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, Math.min(selection - 2, total - 5));
        int end = Math.min(total, start + 5);
        for (int i = start; i < end; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(i == selection ? "> " : "   ");
            sb.append(displayName(i));
            if (i == appliedIndex) {
                sb.append("  ●");
            }
        }
        lutListView.setText(sb.toString());
        lutListView.setVisibility(View.VISIBLE);
    }

    // ---------------- 参数应用 ----------------

    private void requestApply(final int index) {
        final int seq = ++applySeq;
        final long tReq = System.currentTimeMillis();
        prepLog("apply req idx=" + index + " seq=" + seq + " " + heapStat());
        if (index == 0) {
            baseParams = null;
            appliedIndex = 0;
            writePipeline(null);
            refreshTopBar();
            refreshListView();
            return;
        }
        topBar.setText(displayName(index) + "   计算中...");
        worker.post(new Runnable() {
            public void run() {
                final LutParams params;
                try {
                    params = loadOrDecompose(cubeFiles.get(index - 1));
                } catch (Throwable t) {
                    Log.e(TAG, "decompose failed", t);
                    prepLog("apply fail idx=" + index + " " + t);
                    mainHandler.post(new Runnable() {
                        public void run() {
                            topBar.setText("分解失败");
                        }
                    });
                    return;
                }
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (seq != applySeq) {
                            return; // 已被更新的选择覆盖
                        }
                        baseParams = params;
                        appliedIndex = index;
                        writePipeline(effectiveParams(params));
                        prepLog("apply done idx=" + index
                                + " total=" + (System.currentTimeMillis() - tReq) + "ms");
                        refreshTopBar();
                        refreshListView();
                    }
                });
            }
        });
    }

    /** 缓存命中则秒读，否则机内分解并写缓存。
     *  注意：A6000 的 SD 卡挂载是 8.3 短文件名，缓存目录/文件名必须 8.3 合规。 */
    private LutParams loadOrDecompose(File cubeFile) throws IOException {
        CACHE_DIR.mkdirs();
        File cache = new File(CACHE_DIR, shortName83(cubeFile.getName()) + ".LTC");
        if (LutParams.isFresh(cache, cubeFile, null)) {
            long t0 = System.currentTimeMillis();
            LutParams p = LutParams.load(cache);
            prepLog("cachehit " + shortName83(cubeFile.getName())
                    + " " + (System.currentTimeMillis() - t0) + "ms");
            return p;
        }
        long t0 = System.currentTimeMillis();
        prepLog("decompose begin " + cubeFile.getName());
        Cube cube = Cube.load(cubeFile);
        LutParams params = Decomposer.decompose(cube);
        Log.i(TAG, "decomposed " + cubeFile.getName() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        prepLog("decompose done " + shortName83(cubeFile.getName())
                + " " + (System.currentTimeMillis() - t0) + "ms");
        try {
            params.save(cache, cubeFile.length(), cubeFile.lastModified());
        } catch (IOException e) {
            Log.e(TAG, "cache write failed", e);
        }
        return params;
    }

    /** 文件名转 8.3 短名（不含扩展名部分）：去非字母数字，截 8 字符，大写。 */
    private static String shortName83(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 8; i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.length() > 0 ? sb.toString() : "LUT";
    }

    private static final int[] MATRIX_IDENTITY =
            {1024, 0, 0, 0, 1024, 0, 0, 0, 1024};

    /** 确保伽马表可用（v0.5.7 三级策略，绕开 create/bind 挂死区）：
     *  ① static 缓存还活着（暖进程快速重进）→ 直接用；
     *  ② getExtendedGammaTable() 领养相机上仍绑着的旧表（上个回合退出时
     *     绑定留在 HAL 里）→ 不创建、不绑定、不碰死区；
     *  ③ 都没有（真·开机后第一次）→ 才允许一次性的 create+write+bind。 */
    private boolean ensureBoundGamma() throws IOException {
        if (sBoundGamma != null) {
            return true;
        }
        try {
            CameraEx.GammaTable existing = camera.getExtendedGammaTable();
            if (existing != null) {
                int bufSize = existing.getSize();
                if (bufSize > 0 && bufSize <= 8192) {
                    int points = bufSize / 2;
                    byte[] buf = new byte[bufSize];
                    for (int i = 0; i < points; i++) {
                        int v = (int) ((long) i * 1023
                                / (points - 1 > 0 ? points - 1 : 1));
                        buf[2 * i] = (byte) (v & 0xff);
                        buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
                    }
                    sBoundGamma = existing;
                    sIdentityBuf = buf;
                    Log.i(TAG, "gamma ADOPT pts=" + points);
                    prepLog("gamma ADOPT pts=" + points);
                    return true;
                }
                prepLog("gamma adopt reject size=" + bufSize);
            }
        } catch (Throwable t) {
            prepLog("gamma adopt fail " + t);
        }
        CameraEx.GammaTable table = camera.createGammaTable();
        table.setPictureEffectGammaForceOff(true);
        int bufSize = table.getSize();
        int points = bufSize / 2;
        if (points <= 0 || points > 4096) {
            points = 1024;
        }
        byte[] buf = new byte[bufSize];
        for (int i = 0; i < points; i++) {
            int v = (int) ((long) i * 1023 / (points - 1 > 0 ? points - 1 : 1));
            buf[2 * i] = (byte) (v & 0xff);
            buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        table.write(new ByteArrayInputStream(buf));
        camera.setExtendedGammaTable(table); // 全开机周期仅此一次的绑定
        sBoundGamma = table;
        sIdentityBuf = buf;
        Log.i(TAG, "gamma FIRSTBIND size=" + bufSize + " pts=" + points);
        prepLog("gamma FIRSTBIND pts=" + points);
        return true;
    }

    /** 向已绑定的表重写 LUT 内容：按表容量重采样 1024 点。 */
    private void rewriteGamma(int[] gamma) throws IOException {
        int bufSize = sIdentityBuf != null ? sIdentityBuf.length : 2048;
        int points = bufSize / 2;
        byte[] buf = new byte[bufSize];
        for (int i = 0; i < points; i++) {
            int src = (int) ((long) i * 1023 / (points - 1 > 0 ? points - 1 : 1));
            int v = gamma[src];
            buf[2 * i] = (byte) (v & 0xff);
            buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        long t0 = System.currentTimeMillis();
        sBoundGamma.write(new ByteArrayInputStream(buf));
        prepLog("gamma rewrite " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 组合当前生效参数：基 LUT（或中性）→ 强度混合 → 本地 EV 变换。
     *  v0.5.5 曝光补偿新通道：picture-control-exposure-shift 在本机被 HAL
     *  接受但画面零变化（摆设），改为直改伽马内容——out[i]=g[i·2^(EV/3)]，
     *  正 EV 向高输入端取样＝数字过曝（高光提前截断，与真实加曝同形）。 */
    private LutParams effectiveParams(LutParams base) {
        LutParams p = base != null ? base.withIntensity(intensity)
                : LutParams.identity();
        if (paramEv != 0) {
            double k = Math.pow(2.0, paramEv / 3.0);
            int n = LutParams.KNOTS;
            int[] g = p.gamma;
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                long j = Math.round(i * k);
                if (j > n - 1) {
                    j = n - 1;
                } else if (j < 0) {
                    j = 0;
                }
                out[i] = g[(int) j];
            }
            p.gamma = out;
        }
        return p;
    }

    /** 交还相机前的收尾：对单次复用的绑定表补一次 DeviceBuffer.release()。
     *  v0.5.6 回归修复——"绑定后必须 release"（官方 LVG 用法）释放的是
     *  Java 侧硬件缓冲区记账，不影响运行中的绑定与内容改写；漏放行则退出
     *  交还时 HAL 挂着我们的缓冲区，原生界面接管即崩 = 当年退出重启老病根。
     *  全生命周期只在最终交还这一次（每次切换都建+释的循环正是 HAL 挂死来源，
     *  两者不冲突：循环禁止，收尾必做）。 */
    private static void releaseBoundGamma(String via) {
        if (sBoundGamma != null) {
            try {
                Log.i(TAG, "gamma release @" + via);
                prepLog("gamma release @" + via);
                sBoundGamma.release();
            } catch (Throwable t) {
                Log.e(TAG, "gamma release failed", t);
            } finally {
                sBoundGamma = null;
                sIdentityBuf = null;
            }
        }
    }

    /** 写管线。params=null 表示临时关/关闭：恒等矩阵 + 已绑定表内容归零，
     *  绝不解绑/重建/释放（v0.5.4 死点规避；旧实现的 setExtendedGammaTable(null)
     *  属于把 HAL 驱进挂死循环的操作之一，禁用）。 */
    private void writePipeline(LutParams params) {
        if (camera == null) {
            return;
        }
        // 退出中不再写新参数（shutdown 会统一清管线），尽快放锁别挡 shutdown
        if (pausing && params != null) {
            Log.i(TAG, "writePipeline skipped (pausing)");
            return;
        }
        camLock.lock();
        try {
            if (camera == null) {
                return;
            }
            try {
                ensureBoundGamma();
                if (params == null) {
                    long t0 = System.currentTimeMillis();
                    sBoundGamma.write(new ByteArrayInputStream(sIdentityBuf));
                    prepLog("gamma softclear "
                            + (System.currentTimeMillis() - t0) + "ms");
                    writeMatrix(MATRIX_IDENTITY);
                    Log.i(TAG, "pipeline neutralized");
                    return;
                }
                rewriteGamma(params.gamma);
                writeMatrix(params.matrix);
                Log.i(TAG, "pipeline written");
            } catch (Throwable t) {
                Log.e(TAG, "writePipeline failed", t);
                topBar.setText("写入失败: " + t);
            }
        } finally {
            camLock.unlock();
        }
    }

    private void writeMatrix(int[] m) {
        Camera cam = camera.getNormalCamera();
        Camera.Parameters p = cam.getParameters();
        CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
        // RX100M3：setParameters 全量回写会把 HAL 的变焦驱动状态重置，
        // 导致原生控制环变焦失灵（应用 LUT 后转环没反应）。
        // 先记住当前 zoomDriveType，写完矩阵后恢复回去。
        String savedZoomDrive = null;
        try {
            savedZoomDrive = mod.getZoomDriveType();
        } catch (Throwable t) {
            Log.i(TAG, "getZoomDriveType n/a: " + t);
        }
        mod.setRGBMatrix(m);
        cam.setParameters(p);
        if (savedZoomDrive != null) {
            try {
                Camera.Parameters p2 = cam.getParameters();
                CameraEx.ParametersModifier mod2 = camera.createParametersModifier(p2);
                String now = mod2.getZoomDriveType();
                if (!savedZoomDrive.equals(now)) {
                    mod2.setZoomDriveType(savedZoomDrive);
                    cam.setParameters(p2);
                    Log.i(TAG, "zoomDriveType restored: " + savedZoomDrive
                            + " (was " + now + ")");
                    prepLog("zoomDrive restore " + savedZoomDrive + " was " + now);
                }
            } catch (Throwable t) {
                Log.i(TAG, "zoomDriveType restore n/a: " + t);
                prepLog("zoomDrive restore fail " + t);
            }
        }
    }

    private void applyIntensity() {
        if (appliedIndex > 0 && baseParams != null) {
            writePipeline(effectiveParams(baseParams));
        }
        refreshTopBar();
    }

    /** 读 /LUTS/EXITCLR.TXT 的退出清理模式（8.3 合规文件名）。
     *  NONE / LINEAR / GAMMA / MATRIX / NULL，缺省 NULL。 */
    private static String readExitClearMode() {
        try {
            File f = new File(LUT_DIR, "EXITCLR.TXT");
            if (!f.isFile()) {
                return "NULL";
            }
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int n = in.read(b);
                String s = new String(b, 0, n > 0 ? n : 0, "UTF-8").trim().toUpperCase();
                s = s.split("\\s+")[0];
                if (s.equals("NONE") || s.equals("GAMMA") || s.equals("MATRIX")
                        || s.equals("NULL") || s.equals("LINEAR")) {
                    return s;
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
        }
        return "LINEAR"; // 解析失败的兜底（缺省路径见本方法开头的 NULL 分支说明）
    }

    /** 退出专用管线清理（模式化；v0.5.4 起解绑/新建伽马表被全面禁止——
     *  反复建绑循环是 HAL 挂死死点，任何模式下都只允许复用已绑定表）。
     *  当前行为只由 readExitClearMode 的返回值决定：
     *  无文件=NULL(停预览、保留绑定) / 解析失败=LINEAR(恒等表+恒等矩阵)。
     *  GAMMA=仅中性化表内容 / MATRIX=仅恒等矩阵 / NONE=完全不动。 */
    private void clearPipelineForExit(String clrMode) {
        if ("NULL".equals(clrMode)) {
            try {
                Log.i(TAG, "exit clear: stop preview first");
                camera.getNormalCamera().stopPreview();
            } catch (Throwable t) {
                Log.e(TAG, "exit stopPreview failed", t);
            }
            // v0.5.4：不再 setExtendedGammaTable(null) 解绑——解绑/重绑循环
            // 正是 HAL 挂死的死点路径之一。绑定保留（内容即最后应用的 LUT，
            // 与缺省 NONE 行为一致），交还原生界面。
            prepLog("exit clear NULL->keep-bind (unbind banned)");
            return; // 矩阵不动——原生界面会重新配置
        }
        boolean doGamma = "LINEAR".equals(clrMode) || "GAMMA".equals(clrMode);
        boolean doMatrix = "LINEAR".equals(clrMode) || "MATRIX".equals(clrMode);
        if (doGamma) {
            // 复用已绑定表写恒等内容（不新建、不解绑、不释放）
            try {
                ensureBoundGamma();
                long t0 = System.currentTimeMillis();
                sBoundGamma.write(new ByteArrayInputStream(sIdentityBuf));
                prepLog("exit clear gamma neutralized "
                        + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable t) {
                Log.e(TAG, "gamma neutralize failed", t);
                prepLog("exit gamma fail " + t);
            }
        } else {
            prepLog("exit clear skip gamma");
        }
        if (doMatrix) {
            // 恒等矩阵（单次 setParameters，不恢复 zoomDrive）
            try {
                Camera cam = camera.getNormalCamera();
                Camera.Parameters p = cam.getParameters();
                camera.createParametersModifier(p)
                        .setRGBMatrix(new int[]{1024, 0, 0, 0, 1024, 0, 0, 0, 1024});
                cam.setParameters(p);
                Log.i(TAG, "exit clear: identity matrix written");
                prepLog("exit clear matrix");
            } catch (Throwable t) {
                Log.e(TAG, "identity matrix clear failed", t);
            }
        } else {
            prepLog("exit clear skip matrix");
        }
    }

    // ---------------- 拍照 ----------------

    /** 闩锁兜底：onShutter 丢失（HAL 异常等）时 30s 后强制复位，
     *  否则 shutdown 会被有界等待卡满 12s、看门狗顺延到 20s 上限。 */
    private final Runnable captureDrainSafety = new Runnable() {
        public void run() {
            if (captureDraining) {
                captureDraining = false;
                Log.e(TAG, "capture drain safety clear (30s, onShutter lost?)");
                prepLog("capture drain safety clear");
            }
        }
    };

    /** 闩锁复位（幂等），在打标任务之后由 worker 串行触发。 */
    private void endCaptureDrain(String via) {
        if (captureDraining) {
            captureDraining = false;
            Log.i(TAG, "capture drain end (" + via + ")");
            prepLog("capture drain end " + via);
        }
    }

    private void shoot() {
        if (camera == null || takingPicture || pausing) {
            return;
        }
        if (!camLock.tryLock()) {
            // 锁被占（kicked/shutdown/写管线中）：跳过本次快门，绝不阻塞 UI
            Log.w(TAG, "shoot: camLock busy, skip");
            return;
        }
        try {
            if (camera == null || pausing) {
                return;
            }
            takingPicture = true;
            captureDraining = true;
            storeCompleteAt = 0; // 新一次拍照，重置存储完成信号
            shotStartedAt = System.currentTimeMillis();
            Log.i(TAG, "capture drain begin (shutter)");
            prepLog("capture drain begin");
            camera.burstableTakePicture();
            // 兜底闩锁挂在主线程：拍成功则 onShutter 链路复位，这里摘掉重挂
            mainHandler.removeCallbacks(captureDrainSafety);
            mainHandler.postDelayed(captureDrainSafety, 30000);
        } catch (Throwable t) {
            takingPicture = false;
            captureDraining = false;
            Log.e(TAG, "burstableTakePicture failed", t);
        } finally {
            camLock.unlock();
        }
    }

    @Override
    public void onShutter(int i, CameraEx cameraEx) {
        // 回调线程不确定：拿不到锁就跳过 cancel（shutdown 会整体 release）
        if (camLock.tryLock()) {
            try {
                cameraEx.cancelTakePicture();
            } catch (Throwable t) {
                Log.e(TAG, "cancelTakePicture failed", t);
            } finally {
                camLock.unlock();
            }
        } else {
            Log.w(TAG, "onShutter: camLock busy, cancel skipped");
        }
        takingPicture = false;
        scheduleTagging();
        // 1600 > 打标的 1500：worker 串行队列保证这条排在打标任务之后，
        // 即打标收尾（含等文件稳定最多 12s + 写 JPEG COM 段）完了闩锁才复位
        worker.postDelayed(new Runnable() {
            public void run() {
                endCaptureDrain("tagWorker tail");
            }
        }, 1600);
    }

    // ---------------- 成片 LUT 标记 ----------------

    // RX100M3 固件 1.20：拍照后原地重写 JPEG（插 COM 标签）与索尼媒体库
    // 写入存在竞态——文件大小稳定 ≠ 索尼数据库写完。实测导致照片损坏
    // （无法显示）+「修复数据」+ 退出交接时媒体服务崩溃整机重启。
    // A6000 时序宽可容忍；RX100M3 上必须关。LUT 效果烧在像素里，
    // 关掉只是照片里不再嵌 "CUSTOM LUT: 名称 强度%" 文字标签。
    private static final boolean PHOTO_TAGGING_ENABLED = false;

    /** 拍照后 1.5s 起：先当写盘护栏（等索尼把照片+媒体库写完，只读不碰文件），
     *  再视开关打标。护栏必须无条件执行——RX100M3 实测索尼写盘最长 ~10s，
     *  不等就退出（release）会打断其写入 → 数据库损坏 → 整机重启。 */
    private void scheduleTagging() {
        final String label = (PHOTO_TAGGING_ENABLED && appliedIndex > 0)
                ? displayName(appliedIndex) + " " + intensity + "%" : null;
        worker.postDelayed(new Runnable() {
            public void run() {
                barrierAndTag(label);
            }
        }, 1500);
    }

    /** 写盘护栏（v0.7.1 短版）+ LUT 命名。
     *  权威信号=索尼 StoreImageComplete 回调（图像落盘+机内收尾完成点），
     *  回调后 800ms 即放行；5s 没等到回调才降级为文件稳定短检查+1.5s 余量。
     *  旧版"12s 文件稳定+5s 余量"是打标时代（原地重写 JPEG）的防竞态设计，
     *  打标已禁用、gamma release 已修的前提下过保守，专门拖慢"拍完立刻退出"。
     *  闩锁（captureDraining）仍由 onShutter 的 1600ms 尾任务在本任务之后
     *  串行复位——护栏走完才放行退出清理的顺序保持不变。 */
    private void barrierAndTag(String label) {
        try {
            long t0 = System.currentTimeMillis();
            while (storeCompleteAt == 0
                    && System.currentTimeMillis() - t0 < 5000) {
                Thread.sleep(150);
            }
            File newest = findNewestPhoto(DCIM_DIR);
            if (newest == null
                    || System.currentTimeMillis() - newest.lastModified() > 60000) {
                prepLog("barrier: no fresh photo, skip");
                return;
            }
            boolean stable = waitFileStableShort(newest);
            long settleEnd = System.currentTimeMillis()
                    + (storeCompleteAt > 0 ? 800 : 1500);
            while (System.currentTimeMillis() < settleEnd) {
                Thread.sleep(100);
            }
            Log.i(TAG, "write barrier done: " + newest.getName()
                    + " stable=" + stable + " storeCb="
                    + (storeCompleteAt > 0 ? "yes" : "no")
                    + " +" + rel(t0) + "ms");
            prepLog("write barrier " + (stable ? "ok" : "short")
                    + " storeCb=" + (storeCompleteAt > 0 ? "y" : "n")
                    + " " + rel(t0) + "ms");
            // LUT 命名（v0.7.1）：应用了 LUT 且 RENAME.TXT 未关时，
            // 把本次快门写出的照片改名 DSC####_LUT名.ext
            if (renameEnabled && appliedIndex > 0 && stable) {
                String tag = shortLutTag();
                if (tag.length() > 0) {
                    renameRecentPhotos(shotStartedAt, tag);
                }
            }
        } catch (InterruptedException e) {
            // shutdown 抢占：直接放行
        } catch (Throwable t) {
            Log.e(TAG, "barrier failed", t);
        }
    }

    /** 文件稳定短检查：连续两次（间隔 400ms）长度一致且 >0 即过，上限 4s。 */
    private static boolean waitFileStableShort(File f) throws InterruptedException {
        long last = -1;
        for (int i = 0; i < 10; i++) {
            long len = f.length();
            if (len > 0 && len == last) {
                return true;
            }
            last = len;
            Thread.sleep(400);
        }
        return f.length() > 0;
    }

    /** 当前 LUT 名转文件名后缀（仅 ASCII 字母数字，大写，≤8 字符）。 */
    private String shortLutTag() {
        String s = prettyName(displayName(appliedIndex));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 8; i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /** 把 since 时刻之后写出的 JPG/ARW 原地改名 base_LUT名.ext。
     *  只改文件名不碰内容（与打标时代的原地重写是两码事）；改过的文件
     *  记入 taggedFiles 防重复。机内媒体库可能要重建后才认识新名字——
     *  /LUTS/RENAME.TXT=OFF 可整体关闭。 */
    private void renameRecentPhotos(long since, String tag) {
        File[] subs = DCIM_DIR.listFiles();
        if (subs == null) {
            return;
        }
        int renamed = 0;
        for (File sub : subs) {
            if (!sub.isDirectory()) {
                continue;
            }
            File[] files = sub.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile() || f.lastModified() < since - 2000) {
                    continue;
                }
                String path = f.getPath();
                if (taggedFiles.contains(path)) {
                    continue;
                }
                String n = f.getName();
                String up = n.toUpperCase();
                if (!up.endsWith(".JPG") && !up.endsWith(".ARW")) {
                    continue;
                }
                if (up.contains("_" + tag + ".")) {
                    continue; // 已带后缀
                }
                try {
                    if (!waitFileStableShort(f)) {
                        prepLog("rename skip (unstable) " + n);
                        continue;
                    }
                } catch (InterruptedException e) {
                    return;
                }
                int dot = n.lastIndexOf('.');
                String newName = n.substring(0, dot) + "_" + tag
                        + n.substring(dot);
                File target = new File(sub, newName);
                if (target.exists()) {
                    prepLog("rename skip (exists) " + newName);
                    continue;
                }
                if (f.renameTo(target)) {
                    taggedFiles.add(path);
                    taggedFiles.add(target.getPath());
                    renamed++;
                    Log.i(TAG, "renamed " + n + " -> " + newName);
                    prepLog("renamed " + n + " -> " + newName);
                } else {
                    prepLog("rename fail " + n);
                }
            }
        }
        if (renamed > 0) {
            topBarTextTemp("已命名 LUT:" + tag, 1500);
        }
    }

    /** 顶栏临时消息（worker 线程安全，到时刷新回常驻状态）。 */
    private void topBarTextTemp(final String msg, long ms) {
        mainHandler.post(new Runnable() {
            public void run() {
                topBar.setText(msg);
            }
        });
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                refreshTopBar();
            }
        }, ms);
    }

    /** 在 DCIM 各子目录里找最新的 JPG/ARW。 */
    private static File findNewestPhoto(File dcim) {
        File newest = null;
        File[] subs = dcim.listFiles();
        if (subs == null) {
            return null;
        }
        for (File sub : subs) {
            if (!sub.isDirectory()) {
                continue;
            }
            File[] files = sub.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                String n = f.getName().toUpperCase();
                if (!(n.endsWith(".JPG") || n.endsWith(".ARW"))) {
                    continue;
                }
                if (newest == null || f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
        }
        return newest;
    }

    /** 等文件尺寸稳定：连续两次（间隔 1s）长度一致且 >0 才算写完，最多等 12s。 */
    private static boolean waitFileStable(File f) {
        long last = -1;
        for (int i = 0; i < 12; i++) {
            long len = f.length();
            if (len > 0 && len == last) {
                return true;
            }
            last = len;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** JPEG 字节手术：在 APP0/APP1 段序列之后插 COM(FFFE) 段。
     *  机内回放的严格解码器要求 SOI 后首个段是 APP0(JFIF)/APP1(EXIF)，
     *  COM 插在最前面会被拒显（「无法显示」）。全程流式，不整读文件。 */
    private static void insertJpegComment(File jpg, String comment) throws IOException {
        // EOI 校验：文件不完整（相机可能还在写）直接放弃本轮
        if (!checkEoi(jpg)) {
            Log.w(TAG, "jpeg incomplete (no EOI), skip: " + jpg.getName());
            return;
        }
        byte[] payload = comment.getBytes("UTF-8");
        if (payload.length > 65530) {
            return;
        }
        // 第一遍：扫段头，找 APP0/APP1 段序列的结束位置（COM 插入点），顺便防重
        long insertAt = -1;
        java.io.BufferedInputStream probe = new java.io.BufferedInputStream(
                new FileInputStream(jpg), 65536);
        try {
            if (probe.read() != 0xFF || probe.read() != 0xD8) {
                Log.w(TAG, "not a jpeg: " + jpg.getName());
                return;
            }
            long pos = 2; // 已过 SOI
            while (true) {
                int m1 = probe.read(), m2 = probe.read();
                if (m1 < 0 || m2 < 0) {
                    Log.w(TAG, "jpeg header truncated: " + jpg.getName());
                    return;
                }
                if (m1 != 0xFF || (m2 != 0xE0 && m2 != 0xE1)) {
                    // 非 APP0/APP1：插入点到了。先看看是不是我们自己的 COM（防重）
                    if (m1 == 0xFF && m2 == 0xFE) {
                        int l1 = probe.read(), l2 = probe.read();
                        int len = (l1 << 8) | l2;
                        if (len >= 2 && len <= 65535) {
                            byte[] seg = new byte[len - 2];
                            int got = 0;
                            while (got < seg.length) {
                                int r = probe.read(seg, got, seg.length - got);
                                if (r < 0) {
                                    break;
                                }
                                got += r;
                            }
                            if (got == seg.length && new String(seg, "UTF-8")
                                    .startsWith("CUSTOM LUT:")) {
                                Log.i(TAG, "already tagged, skip");
                                return;
                            }
                        }
                    }
                    insertAt = pos;
                    break;
                }
                int l1 = probe.read(), l2 = probe.read();
                if (l1 < 0 || l2 < 0) {
                    Log.w(TAG, "jpeg header truncated: " + jpg.getName());
                    return;
                }
                int len = (l1 << 8) | l2; // 长度字段含自身 2 字节
                if (len < 2) {
                    Log.w(TAG, "bad segment length: " + jpg.getName());
                    return;
                }
                skipFully(probe, len - 2);
                pos += 2 + len; // 段头 2 字节 + 长度字段与数据
            }
        } finally {
            probe.close();
        }
        Log.i(TAG, "jpeg insert at " + insertAt + ": " + jpg.getName());
        // 第二遍流式重写：SOI + 原 APP0/APP1 段 + COM + 其余部分
        File tmp = new File(jpg.getParentFile(), "LUTTMP.TMP");
        FileInputStream in = new FileInputStream(jpg);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            copyFully(in, out, insertAt);
            out.write(0xFF);
            out.write(0xFE);
            int segLen = payload.length + 2; // 长度字段包含自身
            out.write((segLen >> 8) & 0xff);
            out.write(segLen & 0xff);
            out.write(payload);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
        // tmp 写完再验 EOI，不过则不动原文件
        if (!checkEoi(tmp)) {
            tmp.delete();
            Log.w(TAG, "rewritten jpeg missing EOI, keep original: "
                    + jpg.getName());
            return;
        }
        if (!jpg.delete() || !tmp.renameTo(jpg)) {
            tmp.delete();
            throw new IOException("replace failed: " + jpg.getName());
        }
    }

    /** 校验 JPEG 尾部有 EOI(FFD9)（允许尾部零填充）。 */
    private static boolean checkEoi(File f) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                long flen = raf.length();
                if (flen < 4) {
                    return false;
                }
                int tail = (int) Math.min(flen, 65536);
                byte[] buf = new byte[tail];
                raf.seek(flen - tail);
                raf.readFully(buf);
                int i = tail - 1;
                while (i >= 0 && buf[i] == 0) {
                    i--; // 跳过尾部零填充
                }
                return i >= 1 && (buf[i] & 0xff) == 0xD9
                        && (buf[i - 1] & 0xff) == 0xFF;
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 in 原样复制 len 字节到 out。 */
    private static void copyFully(java.io.InputStream in, FileOutputStream out,
            long len) throws IOException {
        byte[] buf = new byte[65536];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                throw new IOException("unexpected EOF during header copy");
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    /** 流式跳过 len 字节，不足则抛异常。 */
    private static void skipFully(java.io.InputStream in, long len) throws IOException {
        long left = len;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) {
                    throw new IOException("unexpected EOF during segment skip");
                }
                s = 1;
            }
            left -= s;
        }
    }

    /** ARW 不动本体，写同名 XMP sidecar（8.3 文件名，Lightroom 可读）。 */
    private static void writeXmpSidecar(File arw, String label) throws IOException {
        String name = arw.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File xmp = new File(arw.getParentFile(), base + ".XMP");
        String esc = xmlEscape(label);
        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description"
                + " xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmp:Label=\"" + esc + "\">\n"
                + "   <dc:description><rdf:Alt>"
                + "<rdf:li xml:lang=\"x-default\">" + esc + "</rdf:li>"
                + "</rdf:Alt></dc:description>\n"
                + "  </rdf:Description>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n";
        FileOutputStream fos = new FileOutputStream(xmp);
        fos.write(content.getBytes("UTF-8"));
        fos.close();
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        Log.i(TAG, "keyDown: code=" + code + " scan=" + scan);

        if (scan == SCAN_MENU || code == KeyEvent.KEYCODE_MENU) {
            exitProperly();
            return true;
        }
        if (startupComputing) {
            return true; // 预计算期间吞掉其余按键
        }
        // 简洁模式回放（回看键呼出）：左右/拨轮1浏览，回看/中央返回。
        // 半按/全按快门=关掉回放直接拍摄。
        if (simpleMode && playbackOpen) {
            if (scan == SCAN_REVIEW) {
                closePlayback();
                return true;
            }
            if (scan == SCAN_LEFT || scan == SCAN_DIAL1_CCW) {
                playStep(-1);
                return true;
            }
            if (scan == SCAN_RIGHT || scan == SCAN_DIAL1_CW) {
                playStep(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                closePlayback();
                return true;
            }
            if (scan == SCAN_S1 || scan == SCAN_S2) {
                closePlayback(); // 落到后面分支直接对焦/拍摄
            } else {
                return true; // 其余键回放中吞掉防误触
            }
        }
        // 简洁模式菜单按键（打开时优先消化导航/确认/强度；快门与变焦不拦，
        // 落到后面分支照常工作——官方应用同样允许菜单开着拍摄）
        if (simpleMode && menuOpen) {
            if (scan == SCAN_UP || scan == SCAN_DIAL1_CCW) {
                menuMove(-1);
                return true;
            }
            if (scan == SCAN_DOWN || scan == SCAN_DIAL1_CW) {
                menuMove(1);
                return true;
            }
            if (scan == SCAN_LEFT || scan == SCAN_DIAL2_CCW) {
                intensity = Math.max(0, intensity - 5);
                applyIntensity();
                lutMenu.invalidate();
                return true;
            }
            if (scan == SCAN_RIGHT || scan == SCAN_DIAL2_CW) {
                intensity = Math.min(100, intensity + 5);
                applyIntensity();
                lutMenu.invalidate();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                toggleMenu(); // 实时预览已生效，中央键=确认收起
                return true;
            }
            if (scan == SCAN_DELETE || code == KeyEvent.KEYCODE_DEL) {
                menuSel = 0;
                requestApply(0);
                toggleMenu();
                return true;
            }
        }
        if (scan == SCAN_DELETE || code == KeyEvent.KEYCODE_DEL) {
            selection = 0;
            browsing = false;
            requestApply(0);
            refreshListView();
            return true;
        }
        if (scan == SCAN_S2 && camera != null) {
            shoot();
            return true;
        }
        if (scan == SCAN_S1 && camera != null) {
            // AF 进 HAL 要持 camLock；拿不到（写管线/kick/shutdown 中）就跳过本次，
            // 绝不阻塞 UI 等锁（Pro 同款策略：拿不到就跳键）
            if (!camLock.tryLock()) {
                Log.w(TAG, "S1: camLock busy, skip AF");
                return true;
            }
            try {
                if (camera == null || pausing) {
                    return true;
                }
                // 实测：HAL 合焦锁定后必须 cancelAutoFocus 才能再次 autoFocus
                if (lastAfStatus == CameraEx.AutoFocusDoneListener.STATUS_LOCK
                        || lastAfStatus
                                == CameraEx.AutoFocusDoneListener.STATUS_LOCK_WARM) {
                    // 锁定态：先 cancel 解锁，200ms 后再重新对焦（立即对焦 HAL 不理）
                    try {
                        camera.getNormalCamera().cancelAutoFocus();
                    } catch (Throwable t) {
                        Log.i(TAG, "cancelAutoFocus (pre-S1) failed: " + t);
                    }
                    mainHandler.postDelayed(new Runnable() {
                        public void run() {
                            if (camera == null || pausing || !camLock.tryLock()) {
                                return;
                            }
                            try {
                                if (camera != null) {
                                    camera.getNormalCamera().autoFocus(null);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "autoFocus failed", t);
                            } finally {
                                camLock.unlock();
                            }
                        }
                    }, 200);
                } else {
                    try {
                        camera.getNormalCamera().autoFocus(null);
                    } catch (Throwable t) {
                        Log.e(TAG, "autoFocus failed", t);
                    }
                }
            } finally {
                camLock.unlock();
            }
            return true;
        }
        if (scan == SCAN_S1_UP && camera != null) {
            // 松开半按：仅锁定态才需要 cancelAutoFocus 解锁
            if (lastAfStatus == CameraEx.AutoFocusDoneListener.STATUS_LOCK
                    || lastAfStatus
                            == CameraEx.AutoFocusDoneListener.STATUS_LOCK_WARM) {
                if (!camLock.tryLock()) {
                    Log.w(TAG, "S1-up: camLock busy, skip cancel");
                    return true;
                }
                try {
                    if (camera != null) {
                        camera.getNormalCamera().cancelAutoFocus();
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "cancelAutoFocus (S1-up) failed: " + t);
                } finally {
                    camLock.unlock();
                }
            }
            return true;
        }
        // Fn 键：简洁模式=呼出/收起官方风菜单；CLASSIC=进/出参数调节模式。
        // 回看键（207）临时关 LUT 对比，两种模式共用（见下方 REVIEW 分支）
        if (simpleMode && scan == menuKeyScan) {
            toggleMenu();
            return true;
        }
        if (!simpleMode && scan == SCAN_FN) {
            toggleParamMode();
            return true;
        }
        if (scan == SCAN_REVIEW) {
            if (simpleMode) {
                // v0.7.1：回看键=照片回放（旧的"按住临时关 LUT"退役）
                togglePlayback();
            } else {
                // CLASSIC：按住临时关 LUT 对比原图（记住原索引），松开恢复。
                if (appliedIndex > 0) {
                    lutIndexBeforeReview = appliedIndex;
                    requestApply(0); // 切到 OFF
                    Log.i(TAG, "review: temp LUT off (was #" + lutIndexBeforeReview + ")");
                }
            }
            return true;
        }
        // v0.5.8：变焦杆/控制环（App 前台时马达归本 App 驱动）
        if (scan == SCAN_ZOOM_W || scan == SCAN_ZOOM_T
                || scan == SCAN_RING_CW || scan == SCAN_RING_CCW) {
            handleZoomKey(scan, event.getRepeatCount());
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (simpleMode) {
                if (!menuOpen) {
                    openMenu(); // 菜单开时的中央键已在上方分支消化
                }
                return true;
            }
            if (paramMode) {
                paramMode = false; // 中央键退出参数模式
                refreshTopBar();
                return true;
            }
            if (browsing) {
                browsing = false; // 选定，收起列表
                refreshListView();
            } else {
                browsing = true;
                selection = appliedIndex;
                refreshListView();
            }
            return true;
        }
        // Fn 参数模式：方向键上下选参数项，左右调值
        if (paramMode) {
            if (scan == SCAN_UP || scan == SCAN_DIAL1_CCW) {
                paramIndex = (paramIndex + PARAM_COUNT - 1) % PARAM_COUNT;
                refreshTopBar();
                return true;
            }
            if (scan == SCAN_DOWN || scan == SCAN_DIAL1_CW) {
                paramIndex = (paramIndex + 1) % PARAM_COUNT;
                refreshTopBar();
                return true;
            }
            // 左右调值（RX100M3 实测：左=105，右=106）
            if (scan == SCAN_DIAL2_CCW || scan == SCAN_LEFT) {
                adjustParam(-1);
                return true;
            }
            if (scan == SCAN_DIAL2_CW || scan == SCAN_RIGHT) {
                adjustParam(1);
                return true;
            }
            return true; // 参数模式下吞掉其余键，防误触 LUT 浏览
        }
        boolean prev = (scan == SCAN_DIAL1_CCW || scan == SCAN_UP);
        boolean next = (scan == SCAN_DIAL1_CW || scan == SCAN_DOWN);
        if (prev || next) {
            if (simpleMode) {
                // 简洁模式：菜单外拨轮/上下=呼出菜单并开始移动选择
                if (!menuOpen) {
                    openMenu();
                }
                menuMove(next ? 1 : -1);
                return true;
            }
            if (!browsing) {
                browsing = true;
                selection = appliedIndex;
            }
            int total = cubeFiles.size() + 1;
            if (total > 0) {
                selection = (selection + (next ? 1 : total - 1)) % total;
            }
            refreshListView();
            debouncePreview();
            return true;
        }
        boolean intDown = (scan == SCAN_DIAL2_CCW);
        boolean intUp = (scan == SCAN_DIAL2_CW);
        if (intDown || intUp) {
            intensity = Math.max(0, Math.min(100, intensity + (intUp ? 5 : -5)));
            applyIntensity();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int scan = event.getScanCode();
        int code = event.getKeyCode();
        // RX100M3：回看键松开恢复 LUT（仅 CLASSIC 模式的临时关对比功能）
        if (scan == SCAN_REVIEW) {
            if (!simpleMode && lutIndexBeforeReview > 0) {
                final int restore = lutIndexBeforeReview;
                lutIndexBeforeReview = -1;
                selection = restore;
                requestApply(restore);
                Log.i(TAG, "review: restore LUT #" + restore);
            }
            return true;
        }
        // v0.5.8：变焦杆松开必须先于通用吞掉逻辑处理（松开=停马达/停步进）
        if (scan == SCAN_ZOOM_W || scan == SCAN_ZOOM_T) {
            handleZoomUp(scan);
            return true;
        }
        if (scan == SCAN_MENU || scan == SCAN_DELETE || scan == SCAN_S1 || scan == SCAN_S2
                || scan == SCAN_S1_UP
                || scan == SCAN_DIAL1_CW || scan == SCAN_DIAL1_CCW
                || scan == SCAN_DIAL2_CW || scan == SCAN_DIAL2_CCW
                || scan == SCAN_UP || scan == SCAN_DOWN
                || scan == SCAN_FN || scan == menuKeyScan || scan == SCAN_REVIEW
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == 0) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------- 变焦（v0.5.8：自校准光学驱动 + 数字变焦兜底） ----------------
    // 历史结论勘误：旧注释"startZoom 被 HAL 拒/powerzoom status=2"来自一次
    // 未注册监听的盲试且参数语义不明，不具因果效力。本版第一次使用某方向时
    // 自动做参数校准（候选表逐组试、以 opticalPosition 位移判有效），锁定后
    // 手感与原生一致（按住连续变焦）；全败的方向降级数字变焦步进。
    // 约定假设：opticalPosition 增大=望远（HUD 变焦倍率可目测印证）。

    private boolean isZoomLeverScan(int scan) {
        return scan == SCAN_ZOOM_T || scan == SCAN_ZOOM_W;
    }

    private int zoomDirOf(int scan) {
        if (scan == SCAN_ZOOM_T || scan == SCAN_RING_CW) {
            return 1;
        }
        if (scan == SCAN_ZOOM_W || scan == SCAN_RING_CCW) {
            return -1;
        }
        return 0;
    }

    /** 键按下入口。简洁模式=官方协议（推杆单发 max/8+无位移补发，
     *  控制环点动 max/4+100ms+stop）；CLASSIC=旧 80ms 续发/焦段拨盘。 */
    private void handleZoomKey(int scan, int repeatCount) {
        int dir = zoomDirOf(scan);
        if (dir == 0) {
            return;
        }
        if (isZoomLeverScan(scan)) {
            if (repeatCount > 0 && zoomDriveDir == dir) {
                return; // 按住期间：马达已在转，别再打断
            }
            zoomDriveDir = dir;
            leverDriving = true;
            if (simpleMode) {
                leverWatchdog(dir);
            } else {
                safeStopZoom("flush"); // 冲掉上一脉冲残留，首帧即可动
                zoomDrive(dir);
                holdLoop(dir);
                // 松开由 keyUp 终止；按住用续发循环维持
            }
        } else if (simpleMode) {
            ringPulse(dir);
        } else {
            ringTick(dir);
        }
    }

    private void handleZoomUp(int scan) {
        int dir = zoomDirOf(scan);
        if (dir != 0 && zoomDriveDir == dir) {
            zoomDriveDir = 0;
            stopHoldLoop();
            leverDriving = false;
            safeStopZoom("lever-up");
        }
    }

    /** 按住期间的续发循环：HAL 是短脉冲驱动，需周期性补发维持转动。 */
    private void holdLoop(final int dir) {
        final int ep = ++zoomHoldEpoch;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (ep != zoomHoldEpoch || zoomDriveDir != dir) {
                    return;
                }
                zoomDrive(dir);
                holdLoopKeep(dir, ep);
            }
        }, ZOOM_HOLD_REFRESH_MS);
    }

    private void holdLoopKeep(final int dir, final int ep) {
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (ep != zoomHoldEpoch || zoomDriveDir != dir || pausing) {
                    return;
                }
                zoomDrive(dir);
                holdLoopKeep(dir, ep);
            }
        }, ZOOM_HOLD_REFRESH_MS);
    }

    private void stopHoldLoop() {
        zoomHoldEpoch++;
    }

    // ---- v0.7.0 官方协议变焦（依据 docs/official-apps-reverse.md 定案）----
    // srctrl 官方配方：按住=单发 startZoom(dir, max/8) + 松开 stopZoom；
    // 点动=单发 startZoom(dir, max/4) + 100ms + stopZoom。
    // 本机历史实测驱动会自行衰减，故加"无位移补发"看门狗：仅当指令发出后
    // 光学倍率回调零位移时才补发——官方语义的连续驱动不被打断。

    private int holdSpeed() {
        int s = probeZoomSpeed();
        return Math.max(1, s / 8); // 官方持续档：maxSpeed/8
    }

    private int tapSpeed() {
        int s = probeZoomSpeed();
        return Math.max(1, s / 4); // 官方点动档：maxSpeed/4
    }

    /** 按住看门狗：下发指令→600ms 后检查位移，零位移才补发，循环至松开。 */
    private void leverWatchdog(final int dir) {
        final int ep = ++zoomHoldEpoch;
        stepLever(dir, ep);
    }

    private void stepLever(final int dir, final int ep) {
        final int baseMag = lastOptMag;
        zoomDriveSp(dir, holdSpeed());
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (ep != zoomHoldEpoch || zoomDriveDir != dir || pausing) {
                    return;
                }
                if (lastOptMag != baseMag) {
                    // 有位移：驱动仍在持续，只重新锚定基线继续观察
                    mainHandler.postDelayed(new Runnable() {
                        public void run() {
                            if (ep != zoomHoldEpoch || zoomDriveDir != dir
                                    || pausing) {
                                return;
                            }
                            stepLever(dir, ep);
                        }
                    }, ZOOM_REISSUE_MS);
                } else {
                    stepLever(dir, ep); // 零位移：HAL 自行衰减了，补发维持
                }
            }
        }, ZOOM_REISSUE_MS);
    }

    /** 控制环点动脉冲：官方 one-shot 配方（max/4 + 100ms + stopZoom）。 */
    private void ringPulse(final int dir) {
        long now = System.currentTimeMillis();
        if (now - lastRingTickAt < ZOOM_RING_THROTTLE_MS || leverDriving) {
            return;
        }
        lastRingTickAt = now;
        zoomDriveSp(dir, tapSpeed());
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (!leverDriving) {
                    safeStopZoom("ring-pulse");
                }
            }
        }, ZOOM_TAP_MS);
    }

    /** 官方协议下发：方向 + 指定速度档（与 CLASSIC 的 zoomDrive(dir) 全速
     *  版本并存；ZDIR_TELE=0/WIDE=1 见常量声明处）。 */
    private void zoomDriveSp(int dir, int spd) {
        if (camera == null || pausing) {
            return;
        }
        int protoDir = dir > 0 ? ZDIR_TELE : ZDIR_WIDE;
        try {
            if (camLock.tryLock()) {
                try {
                    if (camera != null && !pausing) {
                        camera.startZoom(protoDir, spd);
                        Log.i(TAG, "zoomSp dir=" + dir + " proto=" + protoDir
                                + " spd=" + spd);
                    }
                } finally {
                    camLock.unlock();
                }
            }
        } catch (Throwable t) {
            prepLog("zoomSp ex dir=" + dir + " " + t.getMessage());
        }
    }


    /** 控制环=预设焦段拨盘：每格在 24/28/35/50/70 间步进，
     *  由倍率回做闭环把镜头脉冲驱动到目标档位后自动停。 */
    private void ringTick(int dir) {
        long now = System.currentTimeMillis();
        if (now - lastRingTickAt < RING_THROTTLE_MS || leverDriving) {
            return;
        }
        lastRingTickAt = now;
        int curIdx = nearestPresetIdx(lastOptMag);
        int tgtIdx = curIdx + dir;
        if (tgtIdx < 0 || tgtIdx >= PRESET_MM.length) {
            topBar.setTextColor(0xFFFF6666);
            topBar.setText(dir > 0 ? "已是长焦端" : "已是广角端");
            mainHandler.postDelayed(new Runnable() {
                public void run() {
                    topBar.setTextColor(0xFFFFFFFF);
                    refreshTopBar();
                }
            }, 700);
            return;
        }
        gotoPreset(tgtIdx);
    }

    private static int nearestPresetIdx(int mag) {
        int best = 0;
        int bd = Integer.MAX_VALUE;
        for (int i = 0; i < PRESET_MM.length; i++) {
            int d = Math.abs(presetMag(i) - mag);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static int presetMag(int idx) {
        return PRESET_MM[idx] * 100 / 24; // mm → 光学倍率百分制
    }

    /** 闭环驱动到指定焦段档位（epoch 防串场；推杆优先）。 */
    private void gotoPreset(final int idx) {
        stopHoldLoop();
        final int ep = ++gotoEpoch;
        final int tgtMag = presetMag(idx);
        final String name = PRESET_MM[idx] + "mm";
        topBar.setText("焦距→" + name);
        prepLog("preset goto " + name + " tgtMag=" + tgtMag);
        mainHandler.post(new Runnable() {
            public void run() {
                gotoStep(ep, tgtMag, name);
            }
        });
    }

    private void gotoStep(final int ep, final int tgtMag, final String name) {
        CameraEx camRef = camera;
        if (ep != gotoEpoch || camRef == null || pausing || leverDriving) {
            return;
        }
        int cur = lastOptMag;
        int diff = tgtMag - cur;
        if (Math.abs(diff) <= GOTO_TOL_MAG) {
            safeStopZoom("preset-arrive");
            Log.i(TAG, "preset arrived " + name + " mag=" + cur);
            prepLog("preset arrive " + name + " mag=" + cur);
            mainHandler.post(new Runnable() {
                public void run() {
                    topBar.setText("焦距 " + name);
                }
            });
            return;
        }
        final int protoDir = diff > 0 ? ZDIR_TELE : ZDIR_WIDE;
        try {
            if (camLock.tryLock()) {
                try {
                    camera.startZoom(protoDir, Math.max(2, zoomSpeed / 2));
                } finally {
                    camLock.unlock();
                }
            }
        } catch (Throwable t) {
            prepLog("goto ex " + t.getMessage());
        }
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (ep != gotoEpoch) {
                    return;
                }
                safeStopZoom("preset-pulse");
                mainHandler.postDelayed(new Runnable() {
                    public void run() {
                        gotoStep(ep, tgtMag, name);
                    }
                }, 60);
            }
        }, GOTO_STEP_MS);
    }

    /** 物理方向(+1=T/-1=W)映射为协议方向参数并下发。
     *  按 dx dump 常量与上一会话交接笔记：TELE=0、WIDE=1，速度探测上限。 */
    private void zoomDrive(int dir) {
        if (camera == null || pausing) {
            return;
        }
        int protoDir = dir > 0 ? ZDIR_TELE : ZDIR_WIDE;
        int spd = probeZoomSpeed();
        try {
            if (camLock.tryLock()) {
                try {
                    if (camera != null && !pausing) {
                        camera.startZoom(protoDir, spd);
                        Log.i(TAG, "zoom drive dir=" + dir + " proto=" + protoDir
                                + " speed=" + spd);
                    }
                } finally {
                    camLock.unlock();
                }
            }
        } catch (Throwable t) {
            prepLog("startZoom ex dir=" + dir + " " + t.getMessage());
        }
    }

    /** getMaxZoomSpeed() 探测一次并缓存；失败回落 2。 */
    private int probeZoomSpeed() {
        if (zoomSpeed > 0) {
            return zoomSpeed;
        }
        try {
            if (camLock.tryLock()) {
                try {
                    if (camera != null) {
                        Camera.Parameters p =
                                camera.getNormalCamera().getParameters();
                        CameraEx.ParametersModifier mod =
                                camera.createParametersModifier(p);
                        int mx = mod.getMaxZoomSpeed();
                        zoomSpeed = clampInt(mx, 1, 10);
                        prepLog("zoom maxSpeed=" + zoomSpeed);
                    }
                } finally {
                    camLock.unlock();
                }
            }
        } catch (Throwable t) {
            prepLog("zoomSpeed probe fail " + t);
        }
        if (zoomSpeed <= 0) {
            zoomSpeed = ZOOM_FALLBACK_SPEED;
        }
        return zoomSpeed;
    }

    private void safeStopZoom(String via) {
        if (camera == null || !camLock.tryLock()) {
            return;
        }
        try {
            if (camera != null) {
                camera.stopZoom();
            }
        } catch (Throwable t) {
            // 静默
        } finally {
            camLock.unlock();
        }
    }

    /** 数字变焦兜底步进。 */
    private int dzMax100 = 400;

    private void digitalStep(int dir) {
        if (camera == null || !camLock.tryLock()) {
            return;
        }
        try {
            if (camera == null || pausing) {
                return;
            }
            if (!dzModeEnsured) {
                dzModeEnsured = ensureDigitalZoomMode();
            }
            if (!dzModeEnsured) {
                prepLog("dz skip (mode n/a)");
                return;
            }
            int next = clampInt(dzCurrent + dir * 50, 100, dzMax100);
            if (next == dzCurrent) {
                return;
            }
            camera.setDigitalZoom(next);
            dzCurrent = next;
            prepLog("dz step dir=" + dir + " -> " + next);
        } catch (Throwable t) {
            prepLog("dz fail " + t);
        } finally {
            camLock.unlock();
        }
    }

    private boolean ensureDigitalZoomMode() {
        try {
            Camera cam = camera.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
            java.util.List types = mod.getSupportedDigitalZoomTypes();
            boolean smart = false;
            int maxMag = -1;
            if (types != null) {
                for (int i = 0; i < types.size(); i++) {
                    Object o = types.get(i);
                    if (o instanceof String
                            && ((String) o).startsWith("smart")) {
                        smart = true;
                        break;
                    }
                }
            }
            if (maxMag > 0) {
                dzMax100 = clampInt(maxMag * 100, 100, 3200);
            }
            if (!smart) {
                prepLog("dz mode: smart 不在支持表(仍尝试提交)");
            }
            try {
                maxMag = mod.getMaxDigitalZoomMagnification(
                        CameraEx.ParametersModifier.DIGITAL_ZOOM_TYPE_SMART);
            } catch (Throwable t) {
            }
            if (maxMag > 0) {
                dzMax100 = clampInt(maxMag * 100, 100, 3200);
            }
            mod.setDigitalZoomMode(
                    CameraEx.ParametersModifier.DIGITAL_ZOOM_TYPE_SMART, true);
            cam.setParameters(p);
            prepLog("dz mode committed max=" + dzMax100);
            return true;
        } catch (Throwable t) {
            prepLog("dz mode fail " + t);
            return false;
        }
    }

    /** 浏览中移动选择后 400ms 防抖预览。 */
    private void debouncePreview() {        final int seq = ++applySeq;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (seq == applySeq && browsing) {
                    requestApply(selection);
                }
            }
        }, 400);
    }

    // ---------------- v0.7.0 官方风 LUT 菜单 ----------------

    private void openMenu() {
        if (menuOpen) {
            return;
        }
        menuOpen = true;
        menuSel = Math.min(appliedIndex, cubeFiles.size()); // 退出项不作为初始选中
        lutMenu.setVisibility(View.VISIBLE);
        lutMenu.invalidate();
        Log.i(TAG, "menu open sel=" + menuSel);
        prepLog("menu open");
    }

    private void closeMenu() {
        if (!menuOpen) {
            return;
        }
        menuOpen = false;
        lutMenu.setVisibility(View.GONE);
        refreshTopBar();
        Log.i(TAG, "menu close");
    }

    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    /** 菜单选择移动 + 500ms 防抖实时预览（与旧浏览模式同策略：改写已绑定
     *  表内容是证实安全的热路径；退出项不参与预览）。 */
    private void menuMove(int delta) {
        int total = cubeFiles.size() + 2; // OFF + LUTs + 退出
        menuSel = (menuSel + delta + total) % total;
        lutMenu.invalidate();
        if (menuSel <= cubeFiles.size()) {
            debounceMenuPreview();
        }
    }

    private void debounceMenuPreview() {
        final int seq = ++applySeq;
        final int target = menuSel;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (seq == applySeq && menuOpen) {
                    requestApply(target);
                    lutMenu.invalidate();
                }
            }
        }, 500);
    }

    /** 菜单显示名：剥掉 cube TITLE 里的 "VLogAlchemy" 前缀与 "(sRGB in)" 后缀。 */
    private static String prettyName(String title) {
        String s = title.trim();
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren);
        }
        String prefix = "VLogAlchemy ";
        if (s.length() > prefix.length()
                && s.substring(0, prefix.length()).equalsIgnoreCase(prefix)) {
            s = s.substring(prefix.length());
        }
        s = s.trim();
        return s.length() > 0 ? s : title;
    }

    /** 文件名 → 大写词干（介绍清单/描述查找键）。 */
    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.toUpperCase();
    }

    /** 读 /LUTS/STYLE.TXT：首词 CLASSIC=回退旧交互（参数模式+焦段拨盘）；
     *  menukey=NNN 指定呼出菜单的扫描码（缺省 520=Fn）。无文件=简洁模式。 */
    private void readStyle() {
        simpleMode = true;
        menuKeyScan = SCAN_FN;
        try {
            File f = new File(LUT_DIR, "STYLE.TXT");
            if (f.isFile()) {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int n = in.read(b);
                    String s = new String(b, 0, n > 0 ? n : 0, "UTF-8");
                    String[] lines = s.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.length() == 0 || line.startsWith("#")) {
                            continue;
                        }
                        if (line.toUpperCase().startsWith("CLASSIC")) {
                            simpleMode = false;
                        }
                        if (line.toUpperCase().startsWith("MENUKEY")) {
                            int eq = line.indexOf('=');
                            if (eq > 0) {
                                menuKeyScan = Integer.parseInt(
                                        line.substring(eq + 1).trim());
                            }
                        }
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "readStyle failed: " + t);
        }
        Log.i(TAG, "style simple=" + simpleMode + " menukey=" + menuKeyScan);
        prepLog("style simple=" + simpleMode + " menukey=" + menuKeyScan);
        // /LUTS/RENAME.TXT 内容 OFF = 关闭"LUT 命名"（缺省开启）。
        // 机制：拍照写盘完成后把 DSC####.JPG/ARW 原地改名 DSC####_LUT名.ext。
        // 只改文件名不碰内容，但机内媒体库可能要重建后才认识新名字。
        renameEnabled = true;
        try {
            File f = new File(LUT_DIR, "RENAME.TXT");
            if (f.isFile()) {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int n = in.read(b);
                    String s = new String(b, 0, n > 0 ? n : 0, "UTF-8")
                            .trim().toUpperCase();
                    if (s.startsWith("OFF") || s.startsWith("0")) {
                        renameEnabled = false;
                    }
                } finally {
                    in.close();
                }
            }
        } catch (Throwable t) {
        }
        prepLog("rename=" + renameEnabled);
    }

    /** /LUTS/LUTS.TXT：每行 "文件名(可带扩展)|介绍"，菜单描述面板数据源。 */
    private void loadLutDescs() {
        lutDescs.clear();
        try {
            File f = new File(LUT_DIR, "LUTS.TXT");
            if (!f.isFile()) {
                return;
            }
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new FileInputStream(f),
                            "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) {
                        continue;
                    }
                    int bar = line.indexOf('|');
                    if (bar <= 0) {
                        continue;
                    }
                    String key = stemOf(line.substring(0, bar).trim());
                    String desc = line.substring(bar + 1).trim();
                    if (key.length() > 0 && desc.length() > 0) {
                        lutDescs.put(key, desc);
                    }
                }
            } finally {
                br.close();
            }
            Log.i(TAG, "lut descs loaded: " + lutDescs.size());
        } catch (Throwable t) {
            Log.i(TAG, "loadLutDescs failed: " + t);
        }
    }

    // ---------------- v0.7.1 回放（回看键） ----------------

    private void togglePlayback() {
        if (playbackOpen) {
            closePlayback();
        } else {
            openPlayback();
        }
    }

    private void openPlayback() {
        if (playbackOpen) {
            return;
        }
        if (menuOpen) {
            closeMenu();
        }
        playFiles.clear();
        listPhotos(DCIM_DIR, playFiles, 400);
        if (playFiles.isEmpty()) {
            topBarTextTemp("没有可回看的照片", 1500);
            prepLog("playback empty");
            return;
        }
        playbackOpen = true;
        playIdx = 0;
        playView.setVisibility(View.VISIBLE);
        loadPhoto();
        Log.i(TAG, "playback open " + playFiles.size() + " photos");
        prepLog("playback open n=" + playFiles.size());
    }

    private void closePlayback() {
        if (!playbackOpen) {
            return;
        }
        playbackOpen = false;
        playView.setVisibility(View.GONE);
        playSeq++; // 在途解码作废
        if (playBmp != null) {
            playBmp.recycle();
            playBmp = null;
        }
        Log.i(TAG, "playback close");
    }

    private void playStep(int delta) {
        if (playFiles.isEmpty()) {
            return;
        }
        playIdx = (playIdx + delta + playFiles.size()) % playFiles.size();
        loadPhoto();
    }

    private void loadPhoto() {
        final int seq = ++playSeq;
        final File f = playFiles.get(playIdx);
        playCaption = f.getName() + "  " + (playIdx + 1) + "/"
                + playFiles.size();
        playBmp = null;
        playView.invalidate();
        worker.post(new Runnable() {
            public void run() {
                Bitmap bmp = null;
                try {
                    String n = f.getName().toUpperCase();
                    if (n.endsWith(".JPG")) {
                        bmp = decodeScaled(f, 1024);
                    } else {
                        byte[] jpeg = extractArwThumbnail(f);
                        if (jpeg != null) {
                            bmp = BitmapFactory.decodeByteArray(jpeg, 0,
                                    jpeg.length);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "photo decode failed", t);
                }
                final Bitmap fb = bmp;
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (seq != playSeq) {
                            return; // 已切走/已关闭
                        }
                        playBmp = fb;
                        playView.invalidate();
                    }
                });
            }
        });
    }

    /** DCIM 各子目录收集 JPG/ARW，按修改时间倒序，上限 max 个。 */
    private static void listPhotos(File dcim, List<File> out, int max) {
        File[] subs = dcim.listFiles();
        if (subs != null) {
            for (File sub : subs) {
                if (!sub.isDirectory()) {
                    continue;
                }
                File[] files = sub.listFiles();
                if (files == null) {
                    continue;
                }
                for (File f : files) {
                    if (!f.isFile()) {
                        continue;
                    }
                    String n = f.getName().toUpperCase();
                    if (n.endsWith(".JPG") || n.endsWith(".ARW")) {
                        out.add(f);
                    }
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                long d = b.lastModified() - a.lastModified();
                return d > 0 ? 1 : (d < 0 ? -1 : 0);
            }
        });
        while (out.size() > max) {
            out.remove(out.size() - 1);
        }
    }

    /** 解码 JPEG 并降采样（Dalvik 堆有限，整图解码必 OOM）。 */
    private static Bitmap decodeScaled(File f, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxDim
                || o.outHeight / (sample * 2) >= maxDim) {
            sample *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o2);
    }

    /** ARW（TIFF 结构）内嵌 JPEG 缩略图提取：IFD0 找 0x0201 偏移 + 0x0202 长度。 */
    private static byte[] extractArwThumbnail(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            if (raf.length() < 16) {
                return null;
            }
            byte[] head = new byte[8];
            raf.readFully(head);
            boolean le = head[0] == 'I'; // II=小端，MM=大端
            int ifd = (int) readU32(head, 4, le);
            if (ifd <= 0 || ifd >= raf.length()) {
                return null;
            }
            raf.seek(ifd);
            byte[] cntBuf = new byte[2];
            raf.readFully(cntBuf);
            int cnt = readU16(cntBuf, 0, le);
            if (cnt < 1 || cnt > 512) {
                return null;
            }
            byte[] entries = new byte[cnt * 12];
            raf.readFully(entries);
            long jpegOff = -1;
            long jpegLen = -1;
            for (int i = 0; i < cnt; i++) {
                int off = i * 12;
                int tag = readU16(entries, off, le);
                if (tag == 0x0201) {
                    jpegOff = readU32(entries, off + 8, le);
                } else if (tag == 0x0202) {
                    jpegLen = readU32(entries, off + 8, le);
                }
            }
            if (jpegOff <= 0 || jpegLen <= 0
                    || jpegOff + jpegLen > raf.length()) {
                return null;
            }
            raf.seek(jpegOff);
            byte[] out = new byte[(int) jpegLen];
            raf.readFully(out);
            return out;
        } catch (Throwable t) {
            Log.i(TAG, "arw thumb extract failed: " + t);
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static int readU16(byte[] b, int off, boolean le) {
        return le ? (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                : ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long readU32(byte[] b, int off, boolean le) {
        long v = le ? ((long) (b[off] & 0xff))
                | (((long) (b[off + 1] & 0xff)) << 8)
                | (((long) (b[off + 2] & 0xff)) << 16)
                | (((long) (b[off + 3] & 0xff)) << 24)
                : (((long) (b[off] & 0xff)) << 24)
                | (((long) (b[off + 1] & 0xff)) << 16)
                | (((long) (b[off + 2] & 0xff)) << 8)
                | ((long) (b[off + 3] & 0xff));
        return v & 0xffffffffL;
    }

    // 退出状态：exiting 防 MENU 重复触发；displayHandedBack 保证交还显示只做一次
    private boolean exiting = false;
    private boolean displayHandedBack = false;

    /** 交还显示给系统（幂等）：DAConnectionManager.finish + finish。 */
    private void handBackDisplay() {
        if (displayHandedBack) {
            return;
        }
        displayHandedBack = true;
        try {
            Class<?> c = Class.forName("android.app.DAConnectionManager");
            Object mgr = c.getDeclaredConstructor(android.content.Context.class).newInstance(this);
            c.getMethod("finish").invoke(mgr);
        } catch (Throwable t) {
            Log.e(TAG, "DAConnectionManager.finish() failed", t);
        }
        finish();
    }

    /** LVG 的正统退出，但顺序反转：先异步清完相机（拔监听/清管线/release），
     *  再交还显示。反过来（先 DA finish 再清理）的话，系统会立刻把相机收回给
     *  原生拍摄界面，清理线程和原生抢 CameraEx，RX100M3 上驱动打架 → 相机重启。
     *  清理卡死 3s 兜底强走（后续 onPause 的看门狗会 killProcess 收尾）。 */
    private void exitProperly() {
        if (exiting) {
            return;
        }
        exiting = true;
        topBar.setText("退出中…");
        pausing = true; // 汇聚器立即停手，别在清理中途又碰相机
        mainHandler.removeCallbacks(previewCheck);
        final boolean wasPreviewing = previewStarted;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                handBackDisplay(); // 兜底：拍照后护栏最长 ~13s+清理，15s 必交还
            }
        }, 15000);
        Thread t = new Thread("sonylut-preexit") {
            public void run() {
                shutdownCamera(false, wasPreviewing); // 只清理不杀进程
                mainHandler.post(new Runnable() {
                    public void run() {
                        handBackDisplay(); // 清理完成，正常交还
                        // v0.5.2 实验：/LUTS/EXITSUICIDE.TXT=ON 时，清理+交还
                        // 完成后 1.5s 真杀进程。目的：验证"老进程驻留内存是二次
                        // 进入切换 LUT 变慢"的假说（历史教训是杀进程与 DA 交还
                        // 竞态会带崩拍摄框架，故默认关闭、且延后到交还之后）。
                        if (readExitSuicide()) {
                            mainHandler.postDelayed(new Runnable() {
                                public void run() {
                                    Log.w(TAG, "EXITSUICIDE: kill process");
                                    prepLog("exitsuicide kill " + heapStat());
                                    android.os.Process.killProcess(
                                            android.os.Process.myPid());
                                    System.exit(0);
                                }
                            }, 1500);
                        }
                    }
                });
            }
        };
        t.start();
    }

    /** 读 /LUTS/EXITSUICIDE.TXT：内容 ON = 退出完成后真杀进程（实验开关）。 */
    private static boolean readExitSuicide() {
        try {
            File f = new File(LUT_DIR, "EXITSUICIDE.TXT");
            if (!f.isFile()) {
                return false;
            }
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[(int) f.length()];
                int n = in.read(b);
                String s = new String(b, 0, n > 0 ? n : 0, "UTF-8")
                        .trim().toUpperCase();
                return s.startsWith("ON") || s.startsWith("1");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
