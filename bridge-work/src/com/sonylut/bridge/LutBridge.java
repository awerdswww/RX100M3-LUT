package com.sonylut.bridge;

import android.os.Environment;
import android.util.Log;
import android.util.Pair;

import com.sony.imaging.app.base.shooting.camera.CameraSetting;
import com.sony.scalar.hardware.CameraEx;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LUT 桥（注入官方 Picture Effect Plus 的补丁胶水）。
 *
 * 挂接点（smali 层）：
 *  - PictureEffectController.setValue 头部 intercept()：value="lut-xxx" 时把
 *    伽马表+RGB 矩阵写进管线并置 HAL 照片效果 off，吞掉原调用；
 *  - PictureEffectController.getSupportedValue 尾部 extendList()：把 LUT id
 *    追加进支持列表，菜单过滤放行；
 *  - PictureEffectController.onCameraRemoving onTerm()：相机关闭前把管线
 *    中性化（恒等伽马+恒等矩阵）；
 *  - PictureEffectPlus.onCreate prewarm()：后台线程预热缓存分解。
 *
 * 伽马表生命周期沿用本项目在 RX100M3 上的定论：
 *  - 首绑 createGammaTable→write→setExtendedGammaTable→release（官方 LVG
 *    用法，release 的是 Java 侧缓冲记账，不影响绑定与后续改写）；
 *  - 之后一切切换只对已绑定表 rewrite 内容（安全热路径）；
 *  - 退出不解绑、不再 release（保留绑定内容中性）。
 */
public class LutBridge {

    private static final String TAG = "LutBridge";
    public static final String ID_PREFIX = "lut-";
    private static final File LUT_DIR = new File(
            Environment.getExternalStorageDirectory(), "LUTS");
    private static final File CACHE_DIR = new File(LUT_DIR, "LUTCACHE");

    private static final int[] MATRIX_IDENTITY =
            {1024, 0, 0, 0, 1024, 0, 0, 0, 1024};

    /** 扫描到的 cube（启动/预热时刷新）。 */
    private static List<File> sCubeFiles = new ArrayList<File>();
    /** lut id（小写）→ cube 文件。 */
    private static Map<String, File> sIdMap = new HashMap<String, File>();
    /** 已绑定的伽马表与同容量恒等内容。 */
    private static CameraEx.GammaTable sTable;
    private static byte[] sIdentityBuf;
    private static int sTablePoints = 1024;
    private static boolean sPrewarmDone = false;

    // ---------------- 扫描 ----------------

    /** 扫描 /LUTS 下 .cube/.CUB，建 id 映射（幂等）。 */
    public static synchronized void scanLuts() {
        sCubeFiles.clear();
        sIdMap.clear();
        File[] files = LUT_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".cube") || n.endsWith(".cub"))) {
                    sCubeFiles.add(f);
                }
            }
        }
        Collections.sort(sCubeFiles, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : sCubeFiles) {
            sIdMap.put(idOf(f), f);
        }
        Log.i(TAG, "scanLuts: " + sCubeFiles.size() + " cubes");
    }

    /** 文件 → lut id："ACROS.CUB" → "lut-acros"。 */
    public static String idOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        return ID_PREFIX + stem.toLowerCase();
    }

    /** 供菜单显示的 id 列表（升序字母序）。 */
    public static synchronized List<String> lutIds() {
        if (sIdMap.isEmpty()) {
            scanLuts();
        }
        List<String> ids = new ArrayList<String>(sIdMap.keySet());
        Collections.sort(ids);
        return ids;
    }

    // ---------------- 菜单文案（lutstr/ 命名空间，免资源编译） ----------------

    /** LUTS.TXT 的介绍表（文件名大写词干 → 介绍，懒加载）。 */
    private static Map<String, String> sDescs;
    private static boolean sDescsLoaded = false;

    private static void loadDescs() {
        if (sDescsLoaded) {
            return;
        }
        sDescsLoaded = true;
        sDescs = new HashMap<String, String>();
        File f = new File(LUT_DIR, "LUTS.TXT");
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
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
                    String key = line.substring(0, bar).trim();
                    int dot = key.lastIndexOf('.');
                    if (dot > 0) {
                        key = key.substring(0, dot);
                    }
                    String desc = line.substring(bar + 1).trim();
                    if (key.length() > 0 && desc.length() > 0) {
                        sDescs.put(key.toUpperCase(), desc);
                    }
                }
            } finally {
                br.close();
            }
        } catch (Throwable t) {
            Log.i(TAG, "loadDescs failed: " + t);
        }
    }

    /** 菜单项名：lut-acros→ACROS、lut-off→OFF；退役 id→空串（防原版 NPE）；
     *  其余 null（走原实现）。 */
    public static CharSequence menuText(String itemid) {
        if (itemid == null) {
            return null;
        }
        if ("lut-off".equals(itemid)) {
            return "OFF";
        }
        if (itemid.startsWith(ID_PREFIX)) {
            return itemid.substring(ID_PREFIX.length()).toUpperCase();
        }
        if (isRetired(itemid)) {
            return "";
        }
        return null;
    }

    /** 菜单项介绍：lut-off→固定文案，lut-xxx→LUTS.TXT 介绍；退役 id→空串；
     *  其余 null。 */
    public static CharSequence menuGuide(String itemid) {
        if (itemid == null) {
            return null;
        }
        if ("lut-off".equals(itemid)) {
            return "Turn off LUT, restore native imaging.";
        }
        if (itemid.startsWith(ID_PREFIX)) {
            loadDescs();
            String stem = itemid.substring(ID_PREFIX.length()).toUpperCase();
            String d = sDescs.get(stem);
            return d != null ? d : "V-Log conversion film simulation LUT.";
        }
        if (isRetired(itemid)) {
            return "";
        }
        return null;
    }

    private static boolean isRetired(String itemid) {
        for (String r : RETIRED_EFFECTS) {
            if (r.equals(itemid)) {
                return true;
            }
        }
        return false;
    }

    /** 已退役的官方效果 id（MenuData 里已删除，备份值里可能出现）。 */
    private static final String[] RETIRED_EFFECTS = {
            "part-color-plus", "rough-mono", "soft-focus", "hdr-art",
            "richtone-mono", "miniature-plus", "watercolor", "illust",
            "toy-camera-plus", "pop-color", "posterization",
            "retro-photo", "soft-high-key"};

    // ---------------- smali 钩子实现 ----------------

    /**
     * setValue 拦截。返回 true=已处理（跳过原实现），false=走原路。
     * "off"/"lut-off" 清管线；"lut-off" 由本方法代写 HAL off（不能放行，
     * 原实现会把 "lut-off" 当原生效果 id 交给 HAL）。
     */
    public static boolean intercept(String value, CameraSetting camSet) {
        try {
            if (value == null || camSet == null) {
                return false;
            }
            if (value.equals("off")) {
                clearPipeline(camSet);
                return false; // 原生 off 放行原实现
            }
            if (value.equals("lut-off")) {
                clearPipeline(camSet);
                setHalEffectOff(camSet);
                return true;
            }
            if (!value.startsWith(ID_PREFIX)) {
                return false;
            }
            applyLut(value, camSet);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "intercept failed", t);
            return false; // 失败退回原生路径（效果 off）
        }
    }

    /** getBackupEffectValue 净化：备份里的退役效果 id / 异常值 → "lut-off"。
     *  修启动崩溃：默认备份 part-color-plus 已不在菜单，菜单 onResume 用它
     *  查文案触发原版 NPE（BaseMenuService 无 not-found 防御）。 */
    public static String sanitizeBackup(String v) {
        if (v != null && (v.equals("off") || v.startsWith(ID_PREFIX))) {
            return v;
        }
        return "lut-off";
    }

    /** getSupportedValue 尾部追加菜单 id 全集（MenuIds，与 MenuData 同源）。
     *  菜单按 Value∈supportedList 过滤放行：漏掉任何 XML 项（包括无文件的
     *  lut-off）都会让适配器列表比滚轮计数少 → 越界崩溃。SD 上多出的文件
     *  不追加（菜单项固定）；SD 缺文件的项选中时走 clearPipeline 兜底。 */
    public static List<String> extendList(List<String> list) {
        List<String> out = list != null ? list : new ArrayList<String>();
        for (String id : MenuIds.IDS) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 相机关闭前钩子：管线中性化（不动绑定，不 release）。 */
    public static void onTerm(CameraSetting camSet) {
        try {
            if (sTable != null && sIdentityBuf != null) {
                long t0 = System.currentTimeMillis();
                sTable.write(new ByteArrayInputStream(sIdentityBuf));
                Log.i(TAG, "onTerm gamma neutralized "
                        + (System.currentTimeMillis() - t0) + "ms");
            }
            if (camSet != null) {
                writeMatrix(MATRIX_IDENTITY, camSet);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onTerm failed", t);
        }
    }

    /** 应用 Context（prewarm 时捕获，供运行时资源解析）。 */
    private static android.content.Context sContext;
    /** 已缓存的通用背景图资源 id（0=未解析，-1=不可用）。 */
    private static int sBgResId;

    /** onCreate 预热：后台把所有缺缓存的 cube 分解掉（幂等，一次性）。 */
    public static void prewarm(android.content.Context ctx) {
        if (ctx != null) {
            sContext = ctx.getApplicationContext();
        }
        synchronized (LutBridge.class) {
            if (sPrewarmDone) {
                return;
            }
            sPrewarmDone = true;
        }
        new Thread("lut-prewarm") {
            public void run() {
                try {
                    scanLuts();
                    int done = 0;
                    for (File f : sCubeFiles) {
                        try {
                            loadOrDecompose(f);
                            done++;
                        } catch (Throwable t) {
                            Log.e(TAG, "prewarm fail " + f.getName(), t);
                        }
                    }
                    Log.i(TAG, "prewarm done " + done + "/" + sCubeFiles.size());
                } catch (Throwable t) {
                    Log.e(TAG, "prewarm crashed", t);
                }
            }
        }.start();
    }

    /** 菜单大图背景资源 id：lut 前缀与退役 id 返回通用背景图（运行时解析）；
     *  其余 -1 = 走原实现（原版 if 链不认的 id 也返回 -1，喂给
     *  setBackgroundResource 会 NotFoundException 崩溃——本钩子兜住）。 */
    public static int bgDrawableResId(String itemid) {
        if (itemid == null || sContext == null) {
            return -1;
        }
        boolean ours = itemid.startsWith(ID_PREFIX) || isRetired(itemid);
        if (!ours) {
            return -1;
        }
        if (sBgResId == 0) {
            try {
                sBgResId = sContext.getResources().getIdentifier(
                        "p_16_dd_parts_pe_image_pop_color", "drawable",
                        sContext.getPackageName());
            } catch (Throwable t) {
                sBgResId = -1;
            }
            if (sBgResId <= 0) {
                sBgResId = -1;
                Log.w(TAG, "bg drawable resolve failed");
            }
        }
        return sBgResId;
    }

    // ---------------- 应用管线 ----------------

    private static void applyLut(String id, CameraSetting camSet)
            throws IOException {
        if (sIdMap.isEmpty()) {
            scanLuts();
        }
        File cube = sIdMap.get(id);
        if (cube == null) {
            Log.w(TAG, "unknown lut id " + id + " (removed from card?)");
            clearPipeline(camSet);
            return;
        }
        long t0 = System.currentTimeMillis();
        LutParams p = loadOrDecompose(cube);
        CameraEx cam = camSet.getCamera();
        if (cam == null) {
            Log.w(TAG, "camera null, cannot apply");
            return;
        }
        ensureBoundGamma(cam);
        rewriteGamma(p.gamma, cam);
        // 一次参数写：HAL 照片效果 off + RGB 矩阵（官方增量写参模式，
        // 只含本次 delta，不会冲掉 HAL 其他状态——zoomDriveType 教训）
        Pair<android.hardware.Camera.Parameters,
                CameraEx.ParametersModifier> params =
                camSet.getEmptyParameters();
        params.second.setPictureEffect("off");
        params.second.setRGBMatrix(p.matrix);
        camSet.setParameters(params);
        Log.i(TAG, "applied " + id + " in "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    private static void clearPipeline(CameraSetting camSet) {
        try {
            if (sTable != null && sIdentityBuf != null) {
                sTable.write(new ByteArrayInputStream(sIdentityBuf));
            }
            if (camSet != null && camSet.getCamera() != null) {
                writeMatrix(MATRIX_IDENTITY, camSet);
            }
            Log.i(TAG, "pipeline cleared");
        } catch (Throwable t) {
            Log.e(TAG, "clearPipeline failed", t);
        }
    }

    private static void writeMatrix(int[] m, CameraSetting camSet) {
        Pair<android.hardware.Camera.Parameters,
                CameraEx.ParametersModifier> params =
                camSet.getEmptyParameters();
        params.second.setRGBMatrix(m);
        camSet.setParameters(params);
    }

    /** HAL 照片效果置 off（lut-off 路径代写，单次增量参数提交）。 */
    private static void setHalEffectOff(CameraSetting camSet) {
        Pair<android.hardware.Camera.Parameters,
                CameraEx.ParametersModifier> params =
                camSet.getEmptyParameters();
        params.second.setPictureEffect("off");
        camSet.setParameters(params);
    }

    /** 首绑（进程一次）：create→恒等写→bind→release（官方 LVG 用法）。 */
    private static synchronized void ensureBoundGamma(CameraEx cam)
            throws IOException {
        if (sTable != null) {
            return;
        }
        CameraEx.GammaTable table = cam.createGammaTable();
        try {
            table.setPictureEffectGammaForceOff(true);
        } catch (Throwable t) {
            Log.i(TAG, "forceOff n/a: " + t);
        }
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
        cam.setExtendedGammaTable(table);
        try {
            table.release(); // Java 侧缓冲记账，绑定不受影响
        } catch (Throwable t) {
            Log.i(TAG, "release n/a: " + t);
        }
        sTable = table;
        sIdentityBuf = buf;
        sTablePoints = points;
        Log.i(TAG, "gamma FIRSTBIND size=" + bufSize + " pts=" + points);
    }

    /** 向已绑定表重写伽马（1024 点 → 表容量重采样）。 */
    private static void rewriteGamma(int[] gamma, CameraEx cam)
            throws IOException {
        if (sTable == null || sIdentityBuf == null) {
            return;
        }
        int points = sTablePoints;
        byte[] buf = new byte[sIdentityBuf.length];
        for (int i = 0; i < points; i++) {
            int src = (int) ((long) i * 1023 / (points - 1 > 0 ? points - 1 : 1));
            int v = gamma[src];
            buf[2 * i] = (byte) (v & 0xff);
            buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        long t0 = System.currentTimeMillis();
        sTable.write(new ByteArrayInputStream(buf));
        Log.i(TAG, "gamma rewrite "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    // ---------------- 缓存（与 CustomLut 应用同格式） ----------------

    private static LutParams loadOrDecompose(File cubeFile) throws IOException {
        CACHE_DIR.mkdirs();
        File cache = new File(CACHE_DIR, shortName83(cubeFile.getName()) + ".LTC");
        if (LutParams.isFresh(cache, cubeFile, null)) {
            return LutParams.load(cache);
        }
        long t0 = System.currentTimeMillis();
        Cube cube = Cube.load(cubeFile);
        LutParams params = Decomposer.decompose(cube);
        Log.i(TAG, "decomposed " + cubeFile.getName() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        try {
            params.save(cache, cubeFile.length(), cubeFile.lastModified());
        } catch (IOException e) {
            Log.e(TAG, "cache write failed", e);
        }
        return params;
    }

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
}
