package com.sonylut.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 一组管线参数：1024 点伽马表（10bit int）+ 3×3 定点矩阵（×1024）。
 *
 * v2 缓存格式头部内嵌源 cube 的 (length, lastModified) 指纹：
 * 相机时钟若掉到纪元附近（RX100M3 实测日期未设时时间戳只有 6 位），
 * "缓存 mtime ≥ 源 mtime" 式比较跨冷启动永远失败 → 每次开机全部重算。
 * 指纹记录的是"计算那一刻读到的源文件 stat"，与时钟无关。
 */
public class LutParams {
    public static final int KNOTS = 1024;
    public static final int MATRIX_SCALE = 1024;
    private static final int VERSION = 2;

    public int[] gamma = new int[KNOTS];   // 0..1023
    public int[] matrix = new int[9];      // 定点 ×1024
    public long srcLen = -1;               // v2：计算时的源文件长度
    public long srcMtime = -1;             // v2：计算时的源文件 mtime（仅作指纹，不比大小）

    public static LutParams identity() {
        LutParams p = new LutParams();
        for (int i = 0; i < KNOTS; i++) {
            p.gamma[i] = i;
        }
        p.matrix[0] = p.matrix[4] = p.matrix[8] = MATRIX_SCALE;
        return p;
    }

    /** 强度插值：percent=0 → 恒等；100 → 本参数。 */
    public LutParams withIntensity(int percent) {
        if (percent >= 100) {
            return this;
        }
        if (percent <= 0) {
            return identity();
        }
        double a = percent / 100.0;
        LutParams out = new LutParams();
        for (int i = 0; i < KNOTS; i++) {
            out.gamma[i] = (int) Math.round((1 - a) * i + a * gamma[i]);
        }
        for (int i = 0; i < 9; i++) {
            int id = (i == 0 || i == 4 || i == 8) ? MATRIX_SCALE : 0;
            out.matrix[i] = (int) Math.round((1 - a) * id + a * matrix[i]);
        }
        return out;
    }

    // ---------------- 缓存 ----------------

    /** 写缓存（v2 头部带源文件指纹）。 */
    public void save(File f, long srcLength, long srcMtime) throws IOException {
        this.srcLen = srcLength;
        this.srcMtime = srcMtime;
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
        try {
            dos.writeInt(VERSION);
            dos.writeLong(srcLength);
            dos.writeLong(srcMtime);
            for (int i = 0; i < KNOTS; i++) {
                dos.writeInt(gamma[i]);
            }
            for (int i = 0; i < 9; i++) {
                dos.writeInt(matrix[i]);
            }
        } finally {
            dos.close();
        }
    }

    /** 读缓存。v1 文件（无指纹字段）也能读，此时 srcLen/srcMtime=-1。 */
    public static LutParams load(File f) throws IOException {
        DataInputStream dis = new DataInputStream(new FileInputStream(f));
        try {
            int ver = dis.readInt();
            if (ver != 1 && ver != 2) {
                throw new IOException("bad cache version " + ver);
            }
            LutParams p = new LutParams();
            if (ver >= 2) {
                p.srcLen = dis.readLong();
                p.srcMtime = dis.readLong();
            }
            for (int i = 0; i < KNOTS; i++) {
                p.gamma[i] = dis.readInt();
            }
            for (int i = 0; i < 9; i++) {
                p.matrix[i] = dis.readInt();
            }
            return p;
        } finally {
            dis.close();
        }
    }

    /** 只读缓存头部的指纹（不加载 1033 个 int）：返回 {version, srcLen, srcMtime}，
     *  文件过短/损坏返回 null。 */
    public static long[] peekFingerprint(File f) {
        DataInputStream dis = null;
        try {
            dis = new DataInputStream(new FileInputStream(f));
            int ver = dis.readInt();
            long len = ver >= 2 ? dis.readLong() : -1;
            long mtime = ver >= 2 ? dis.readLong() : -1;
            return new long[]{ver, len, mtime};
        } catch (Throwable t) {
            return null;
        } finally {
            if (dis != null) {
                try { dis.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 缓存是否对应当前源文件：
     *  v2：指纹完全相等即新鲜（时钟无关）；v1：回退旧 mtime 比较（历史行为）。 */
    public static boolean isFresh(File cache, File cube, StringBuilder whyNot) {
        if (!cache.isFile()) {
            if (whyNot != null) whyNot.append(":no-cache");
            return false;
        }
        long[] fp = peekFingerprint(cache);
        if (fp != null && fp[0] >= 2) {
            if (fp[1] == cube.length() && fp[2] == cube.lastModified()) {
                return true;
            }
            if (whyNot != null) {
                whyNot.append(":fp-mismatch(len ").append(fp[1]).append("vs")
                        .append(cube.length()).append(", mt ").append(fp[2]).append("vs")
                        .append(cube.lastModified()).append(')');
            }
            return false;
        }
        if (cache.lastModified() >= cube.lastModified()) {
            return true;
        }
        if (whyNot != null) {
            whyNot.append(":stale-legacy(").append(cache.lastModified())
                    .append('<').append(cube.lastModified()).append(')');
        }
        return false;
    }
}
