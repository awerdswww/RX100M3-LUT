package com.sonylut.bridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * .cube 3D LUT 解析与三线性采样。
 * .cube 标准：red 变化最快，flat idx = (b*size+g)*size+r。
 */
public class Cube {
    public int size;
    public float[] data; // len = size³*3，idx(r,g,b) = ((b*size+g)*size+r)*3

    public static Cube load(File f) throws IOException {
        Cube c = new Cube();
        BufferedReader br = new BufferedReader(new FileReader(f));
        try {
            int capacity = -1;
            float[] buf = new float[64 * 64 * 64 * 3]; // 上限 64³
            int n = 0;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                String upper = line.toUpperCase();
                if (upper.startsWith("LUT_3D_SIZE")) {
                    c.size = Integer.parseInt(line.split("\\s+")[1]);
                    capacity = c.size * c.size * c.size * 3;
                    continue;
                }
                if (upper.startsWith("TITLE") || upper.startsWith("DOMAIN_")
                        || upper.startsWith("LUT_1D_SIZE")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length == 3) {
                    try {
                        for (int i = 0; i < 3; i++) {
                            buf[n++] = Float.parseFloat(parts[i]);
                        }
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            if (c.size <= 0 || n != c.size * c.size * c.size * 3) {
                throw new IOException("bad cube: size=" + c.size + " values=" + n / 3);
            }
            c.data = new float[n];
            System.arraycopy(buf, 0, c.data, 0, n);
            return c;
        } finally {
            br.close();
        }
    }

    /** 三线性采样。X: 输入点 len=N*3（0..1）；out: 输出 len=N*3。 */
    public void sample(float[] X, float[] out) {
        int s = size;
        float max = s - 1;
        for (int i = 0; i < X.length; i += 3) {
            float r = X[i] * max, g = X[i + 1] * max, b = X[i + 2] * max;
            if (r < 0) r = 0; else if (r > max) r = max;
            if (g < 0) g = 0; else if (g > max) g = max;
            if (b < 0) b = 0; else if (b > max) b = max;
            int r0 = (int) r, g0 = (int) g, b0 = (int) b;
            if (r0 >= s - 1) r0 = s - 2;
            if (g0 >= s - 1) g0 = s - 2;
            if (b0 >= s - 1) b0 = s - 2;
            float fr = r - r0, fg = g - g0, fb = b - b0;
            float acc0 = 0, acc1 = 0, acc2 = 0;
            for (int corner = 0; corner < 8; corner++) {
                int dr = (corner >> 2) & 1, dg = (corner >> 1) & 1, db = corner & 1;
                float w = (dr == 1 ? fr : 1 - fr) * (dg == 1 ? fg : 1 - fg) * (db == 1 ? fb : 1 - fb);
                int idx = (((b0 + db) * s + (g0 + dg)) * s + (r0 + dr)) * 3;
                acc0 += w * data[idx];
                acc1 += w * data[idx + 1];
                acc2 += w * data[idx + 2];
            }
            out[i] = acc0;
            out[i + 1] = acc1;
            out[i + 2] = acc2;
        }
    }
}
