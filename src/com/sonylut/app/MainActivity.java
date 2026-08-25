package com.sonylut.app;

import android.app.Activity;
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
 * 按键（对齐机内习惯）：
 *   拨轮1 / 方向键上下 : 浏览 LUT 列表（实时预览）
 *   拨轮2              : 强度 0-100%
 *   中央键             : 选定 / 收起列表
 *   删除键             : 关闭 LUT
 *   快门半按/全按      : 对焦 / 拍照（原生管线存储）；
 *                      对焦锁定后再次半按先解锁再重新对焦，取景中央显示对焦框
 *   MENU               : 退出（参数随 App 退出自动还原）
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback,
        CameraEx.ShutterListener {
    private static final String TAG = "SonyLut";
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
    // RX100M3 的变焦杆(610/611)与控制环(648/649)不在此处理：
    // 索尼原生 sys.camera 直接响应这些键驱动变焦马达，App 消费与否不影响它。
    // 我们曾尝试 startZoom/adjustAperture 均被 HAL 拒（powerzoom status=2
    // UNAVAILABLE，马达控制权在系统侧），故全部让给原生逻辑。

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
    private int paramShutter = 0;  // 快门（编码值）
    private int paramIso = 0;      // ISO（感光度值）
    private int paramWbLb = 0;     // 白平衡 LB（-100~+100）
    private int paramWbCc = 0;     // 白平衡 CC（-100~+100）

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
    private LutParams baseParams;    // 当前 LUT 的 100% 参数（OFF 时为 null）
    private int applySeq = 0;        // 应用请求序号（防抖）

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
        Log.i(TAG, "onCreate");
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
        bottomHint.setText("拨轮1:选择  拨轮2:强度  确认:选定  删除:关闭  MENU:退出");

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
            // RX100M3：光圈监听（原生变焦时 F 值会变，HUD 显示回执）
            try {
                camera.setApertureChangeListener(new CameraEx.ApertureChangeListener() {
                    public void onApertureChange(CameraEx.ApertureInfo info, CameraEx c) {
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
                        final String s = "变焦 " + info.opticalPosition
                                + "/" + info.opticalMagnification
                                + (info.stopped ? " 停" : "");
                        Log.i(TAG, "zoom changed: " + s);
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
            writePipeline(baseParams.withIntensity(intensity));
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
                // 模式：NONE=完全不清 / LINEAR=线性表+恒等矩阵 / GAMMA=仅线性表 /
                //       MATRIX=仅恒等矩阵 / NULL=先停预览后 setGamma(null) 解绑。
                // 缺省 NULL（实测：NONE/线性清都崩——毒药在应用时埋下，
                // 残留绑定态在原生界面接管时爆雷；正确做法是解绑）。
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
                camera = null;
            }
        } finally {
            camLock.unlock();
        }
        previewStarted = false;
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

    /** 持久化尸检日志：冻机后也能从 SD 卡 PREPLOG.TXT 读出卡在哪一步。 */
    private static void prepLog(String msg) {
        try {
            CACHE_DIR.mkdirs();
            File f = new File(CACHE_DIR, "PREPLOG.TXT");
            boolean append = f.isFile() && f.length() <= 65536; // 超 64KB 覆盖重写
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

    // ---------------- 启动预计算 ----------------

    /** 检查所有 cube 是否都有新鲜缓存；缺的进预计算流程，算完再进拍照界面。 */
    private void checkStartupCache() {
        final List<File> missing = new ArrayList<File>();
        for (File f : cubeFiles) {
            File cache = new File(CACHE_DIR, shortName83(f.getName()) + ".LTC");
            if (!(cache.isFile() && cache.lastModified() >= f.lastModified())) {
                missing.add(f);
            }
        }
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

    /** 从相机读取当前参数值。 */
    private void initParamValues() {
        if (camera == null) {
            return;
        }
        if (!camLock.tryLock()) {
            return;
        }
        try {
            Camera cam = camera.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
            try {
                paramAperture = mod.getAperture();
            } catch (Throwable t) { paramAperture = -1; }
            try {
                paramIso = mod.getISOSensitivity();
            } catch (Throwable t) { paramIso = 0; }
            // 快门/EV/WB 没有 getter，用默认值
        } catch (Throwable t) {
            Log.e(TAG, "initParamValues failed", t);
        } finally {
            camLock.unlock();
        }
    }

    /** 调整当前参数。delta=±1（步进）。
     *  注意：RX100M3 的 CameraEx 上光圈/快门用 increment/decrement（步进），
     *  ParametersModifier 上只有 setISOSensitivity/setWhiteBalanceShift 可用。 */
    private void adjustParam(int delta) {
        if (camera == null || pausing) {
            return;
        }
        if (!camLock.tryLock()) {
            Log.w(TAG, "adjustParam: camLock busy, skip");
            return;
        }
        try {
            Camera cam = camera.getNormalCamera();
            Camera.Parameters p = cam.getParameters();
            CameraEx.ParametersModifier mod = camera.createParametersModifier(p);
            switch (paramIndex) {
                case PARAM_EV:
                    paramEv += delta;
                    // RX100M3 无 setExposureCompensation，用 PictureControlExposureShift
                    mod.setPictureControlExposureShift(paramEv);
                    break;
                case PARAM_APERTURE:
                    // CameraEx 直接调 increment/decrement（步进）
                    if (delta > 0) {
                        camera.incrementAperture();
                    } else {
                        camera.decrementAperture();
                    }
                    // 回读当前值（ApertureChangeListener 会更新 paramAperture）
                    break;
                case PARAM_SHUTTER:
                    if (delta > 0) {
                        camera.incrementShutterSpeed();
                    } else {
                        camera.decrementShutterSpeed();
                    }
                    break;
                case PARAM_ISO:
                    // ISO 步进：100→125→160→200→250→320→400→500→640→800→1000→1250→1600→2000→2500→3200→4000→5000→6400→8000→10000→12800→16000→20000→25600
                    int[] isoSteps = {100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600};
                    int idx = 0;
                    for (int i = 0; i < isoSteps.length; i++) {
                        if (isoSteps[i] == paramIso) { idx = i; break; }
                    }
                    idx = Math.max(0, Math.min(isoSteps.length - 1, idx + delta));
                    paramIso = isoSteps[idx];
                    mod.setISOSensitivity(paramIso);
                    break;
                case PARAM_WB_LB:
                    paramWbLb = Math.max(-100, Math.min(100, paramWbLb + delta));
                    mod.setWhiteBalanceShiftLB(paramWbLb);
                    break;
                case PARAM_WB_CC:
                    paramWbCc = Math.max(-100, Math.min(100, paramWbCc + delta));
                    mod.setWhiteBalanceShiftCC(paramWbCc);
                    break;
            }
            // 光圈/快门走 CameraEx 直接调，不需要 setParameters
            if (paramIndex != PARAM_APERTURE && paramIndex != PARAM_SHUTTER) {
                cam.setParameters(p);
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
                        writePipeline(params.withIntensity(intensity));
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
        if (cache.isFile() && cache.lastModified() >= cubeFile.lastModified()) {
            Log.i(TAG, "cache hit: " + cache.getName());
            return LutParams.load(cache);
        }
        long t0 = System.currentTimeMillis();
        Cube cube = Cube.load(cubeFile);
        LutParams params = Decomposer.decompose(cube);
        Log.i(TAG, "decomposed " + cubeFile.getName() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        try {
            params.save(cache);
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

    /** 写管线（主线程）。params=null 表示关闭。持 camLock 与 shutdown/kick 互斥。 */
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
                if (params == null) {
                    camera.setExtendedGammaTable(null);
                    writeMatrix(new int[]{1024, 0, 0, 0, 1024, 0, 0, 0, 1024});
                    Log.i(TAG, "pipeline cleared");
                    return;
                }
                CameraEx.GammaTable table = camera.createGammaTable();
                table.setPictureEffectGammaForceOff(true);
                // RX100M3 表深探测：伽马表实际字节容量（A6000=2048=1024点×2B，
                // RX100M3 可能不同——getSize() 只在绑定后的表上有效）。
                int bufSize = table.getSize();
                int points = bufSize / 2; // 每点 2 字节
                if (points <= 0 || points > 4096) {
                    points = 1024; // 探测异常兜底
                }
                Log.i(TAG, "gamma table size=" + bufSize + "B points=" + points);
                prepLog("gamma table size=" + bufSize + " points=" + points);
                // 按真实容量写入：1024 点源数据按比例重采样/截断到表深。
                // 写超出表容量会写脏 HAL 邻接内存——RX100M3 退出重启的病根。
                byte[] buf = new byte[bufSize];
                for (int i = 0; i < points; i++) {
                    int src = (int) ((long) i * 1023 / (points - 1 > 0 ? points - 1 : 1));
                    int v = params.gamma[src];
                    buf[2 * i] = (byte) (v & 0xff);
                    buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
                }
                table.write(new ByteArrayInputStream(buf));
                camera.setExtendedGammaTable(table);
                table.release(); // DeviceBuffer 硬件缓冲区必须显式释放——
                                  // 官方 Liveview Grading 的用法（Bible.md）。
                                  // 不 release 会泄漏硬件缓冲区，RX100M3 资源少，
                                  // 泄漏几次后 HAL 状态脏 → 退出时原生界面接管崩。
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
            writePipeline(baseParams.withIntensity(intensity));
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
        return "LINEAR"; // 缺省：内容恒等（实测 NULL/NONE 都崩）
    }

    /** 退出专用管线清理（v0.3.5，模式化）：按 clrMode 决定清法。
     *  LINEAR（缺省）：内容写恒等直通（伽马 0..1023 线性 + 矩阵恒等），
     *      绑定保留，不 setGamma(null)——原生界面接手时看到的是"已绑定但
     *      内容中性"的状态，它可安全重新配置。
     *  NULL=先停预览再 setGamma(null) 解绑（实测崩）/
     *  NONE=完全不清（实测也崩，残留状态毒）/ GAMMA=仅线性伽马 / MATRIX=仅恒等矩阵。 */
    private void clearPipelineForExit(String clrMode) {
        if ("NULL".equals(clrMode)) {
            try {
                Log.i(TAG, "exit clear: stop preview first");
                camera.getNormalCamera().stopPreview();
            } catch (Throwable t) {
                Log.e(TAG, "exit stopPreview failed", t);
            }
            try {
                camera.setExtendedGammaTable(null); // 解绑（内容无关紧要，状态复位）
                Log.i(TAG, "exit clear: gamma unbound (null)");
                prepLog("exit clear unbind");
            } catch (Throwable t) {
                Log.e(TAG, "gamma unbind failed", t);
            }
            return; // 矩阵不动——原生界面会重新配置
        }
        boolean doGamma = "LINEAR".equals(clrMode) || "GAMMA".equals(clrMode);
        boolean doMatrix = "LINEAR".equals(clrMode) || "MATRIX".equals(clrMode);
        if (doGamma) {
            // 恒等伽马表：按表实际容量写直通值（RX100M3 表深可能与 A6000 不同，
            // 写超容量会写脏 HAL 内存）
            try {
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
                camera.setExtendedGammaTable(table);
                table.release(); // DeviceBuffer 必须释放，见 writePipeline 注释
                Log.i(TAG, "exit clear: linear gamma written (" + points + "pts)");
                prepLog("exit clear linear gamma pts=" + points);
            } catch (Throwable t) {
                Log.e(TAG, "linear gamma clear failed", t);
                prepLog("linear clear fail " + t);
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

    /** 写盘护栏 + 可选打标。闩锁（captureDraining）由 onShutter 的 1600ms
     *  尾任务在本任务之后串行复位，即护栏走完才放行退出清理。 */
    private void barrierAndTag(String label) {
        try {
            File newest = findNewestPhoto(DCIM_DIR);
            if (newest == null) {
                return;
            }
            // 只关注 30 秒内的新文件；太旧说明索尼早就写完了
            if (System.currentTimeMillis() - newest.lastModified() > 30000) {
                Log.i(TAG, "newest photo old, barrier skip");
                return;
            }
            boolean stable = waitFileStable(newest);
            // 文件写稳后索尼还有内部收尾（缩略图/媒体库/RAW 处理），
            // 实测仅等文件稳定仍会在退出时打断它 → 重启。追加固定余量，
            // 若索尼的 store-complete 回调已到则可提前结束。
            long graceEnd = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < graceEnd) {
                if (storeCompleteAt > 0
                        && System.currentTimeMillis() - storeCompleteAt > 1500) {
                    break; // 回调到达且过了 1.5s，索尼收尾完
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
            Log.i(TAG, "write barrier done: " + newest.getName()
                    + " stable=" + stable
                    + " storeCb=" + (storeCompleteAt > 0 ? "yes" : "no"));
            prepLog("write barrier " + (stable ? "ok" : "timeout")
                    + " storeCb=" + (storeCompleteAt > 0 ? "y" : "n"));
            if (label == null || !stable || taggedFiles.contains(newest.getPath())) {
                return; // 护栏模式下或未稳定：到此为止，不动文件
            }
            String n = newest.getName().toUpperCase();
            if (n.endsWith(".JPG")) {
                insertJpegComment(newest, "CUSTOM LUT: " + label);
            } else {
                writeXmpSidecar(newest, "CUSTOM LUT: " + label);
            }
            taggedFiles.add(newest.getPath());
            Log.i(TAG, "tagged " + newest.getName() + " : " + label);
        } catch (Throwable t) {
            Log.e(TAG, "tagNewestPhoto failed", t);
        }
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
        // RX100M3：Fn 键（520）进/出参数调节模式，回看键（207）临时关 LUT 对比
        if (scan == SCAN_FN) {
            toggleParamMode();
            return true;
        }
        if (scan == SCAN_REVIEW) {
            // 回看键：临时关 LUT（应用 0% 强度），松开恢复
            if (appliedIndex > 0) {
                requestApply(0); // 切到 OFF
                Log.i(TAG, "review: temp LUT off");
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
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
        // RX100M3：回看键松开恢复 LUT（如果之前被临时关了）
        if (scan == SCAN_REVIEW) {
            if (appliedIndex == 0 && browsing) {
                // 当前是 OFF 但 browsing 状态还在（被临时关的），恢复之前的选择
                // 简单处理：重新应用当前 selection
                requestApply(selection);
                Log.i(TAG, "review: restore LUT");
            }
            return true;
        }
        if (scan == SCAN_MENU || scan == SCAN_DELETE || scan == SCAN_S1 || scan == SCAN_S2
                || scan == SCAN_S1_UP
                || scan == SCAN_DIAL1_CW || scan == SCAN_DIAL1_CCW
                || scan == SCAN_DIAL2_CW || scan == SCAN_DIAL2_CCW
                || scan == SCAN_UP || scan == SCAN_DOWN
                || scan == SCAN_FN || scan == SCAN_REVIEW
                || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DEL
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == 0) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // RX100M3 变焦杆(610/611)与控制环(648/649)的处理已全部移除：
    // 索尼原生 sys.camera 直接消费这些键驱动变焦，App 侧 startZoom/adjustAperture
    // 均被 HAL 拒绝（powerzoom status=2 UNAVAILABLE），原生接管反而是唯一能用的路径。
    // 控制环（648/649）的 keyUp 也不消费，让原生逻辑处理变焦停止。

    /** 浏览中移动选择后 400ms 防抖预览。 */
    private void debouncePreview() {
        final int seq = ++applySeq;
        mainHandler.postDelayed(new Runnable() {
            public void run() {
                if (seq == applySeq && browsing) {
                    requestApply(selection);
                }
            }
        }, 400);
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
                    }
                });
            }
        };
        t.start();
    }
}
