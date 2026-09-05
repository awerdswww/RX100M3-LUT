package com.sonylut.bridge;

/**
 * LUT 分解器：.cube → 1024 点伽马表 + 3×3 矩阵。
 * 与 PC 端 lut-decomp/decomp.py 同一算法（交替最小二乘）：
 *   前向模型 out = clip(M · g(x))，g 三通道共用。
 *   - 固定 g 解 M（3×3 正规方程闭式解）
 *   - 固定 M 解 g（bincount 逐 knot 标量 LS，三通道联合）
 *   - 阻尼 0.5 + 单调约束 + g(1)=1 归一化（消除缩放歧义）
 * 机内跑：24³ 网格（13824 样本），秒级完成。
 */
public class Decomposer {
    private static final int KNOTS = LutParams.KNOTS;
    private static final int GRID = 24;
    private static final int ITERS = 30;

    public static LutParams decompose(Cube cube) {
        int n = GRID * GRID * GRID;
        // 采样网格
        float[] X = new float[n * 3];
        int p = 0;
        for (int ri = 0; ri < GRID; ri++) {
            for (int gi = 0; gi < GRID; gi++) {
                for (int bi = 0; bi < GRID; bi++) {
                    X[p++] = ri / (float) (GRID - 1);
                    X[p++] = gi / (float) (GRID - 1);
                    X[p++] = bi / (float) (GRID - 1);
                }
            }
        }
        float[] L = new float[n * 3];
        cube.sample(X, L);

        double[] g = neutralInit(X, L);
        double[][] M = new double[3][3];
        M[0][0] = M[1][1] = M[2][2] = 1.0;

        double[] U = new double[n * 3];
        double[] num = new double[KNOTS];
        double[] den = new double[KNOTS];
        int[] knotIdx = new int[n * 3];
        for (int i = 0; i < n * 3; i++) {
            knotIdx[i] = Math.round(X[i] * (KNOTS - 1));
        }

        for (int it = 0; it < ITERS; it++) {
            // U = g(X)
            for (int i = 0; i < n * 3; i++) {
                U[i] = interp(g, X[i]);
            }
            // 解 M：W = (UᵀU)⁻¹ UᵀL；M[j][c] = W[c][j]
            double[][] A = new double[3][3];
            double[][] B = new double[3][3];
            for (int i = 0; i < n; i++) {
                double u0 = U[i * 3], u1 = U[i * 3 + 1], u2 = U[i * 3 + 2];
                A[0][0] += u0 * u0; A[0][1] += u0 * u1; A[0][2] += u0 * u2;
                A[1][0] += u1 * u0; A[1][1] += u1 * u1; A[1][2] += u1 * u2;
                A[2][0] += u2 * u0; A[2][1] += u2 * u1; A[2][2] += u2 * u2;
                B[0][0] += u0 * L[i * 3];     B[0][1] += u0 * L[i * 3 + 1]; B[0][2] += u0 * L[i * 3 + 2];
                B[1][0] += u1 * L[i * 3];     B[1][1] += u1 * L[i * 3 + 1]; B[1][2] += u1 * L[i * 3 + 2];
                B[2][0] += u2 * L[i * 3];     B[2][1] += u2 * L[i * 3 + 1]; B[2][2] += u2 * L[i * 3 + 2];
            }
            double[][] W = mul(inv3(A), B);
            for (int j = 0; j < 3; j++) {
                for (int c = 0; c < 3; c++) {
                    M[j][c] = W[c][j];
                }
            }

            // 解 g（三通道联合 bincount）
            java.util.Arrays.fill(num, 0);
            java.util.Arrays.fill(den, 0);
            for (int c = 0; c < 3; c++) {
                double m0 = M[0][c], m1 = M[1][c], m2 = M[2][c];
                double m2sq = m0 * m0 + m1 * m1 + m2 * m2;
                if (m2sq < 1e-6) {
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    // 其他通道的贡献: others_j = Σ_{c'≠c} M[j][c'] U_{ic'}
                    double o0 = 0, o1 = 0, o2 = 0;
                    for (int cc = 0; cc < 3; cc++) {
                        if (cc == c) continue;
                        double u = U[i * 3 + cc];
                        o0 += M[0][cc] * u;
                        o1 += M[1][cc] * u;
                        o2 += M[2][cc] * u;
                    }
                    double r0 = L[i * 3] - o0, r1 = L[i * 3 + 1] - o1, r2 = L[i * 3 + 2] - o2;
                    int k = knotIdx[i * 3 + c];
                    num[k] += m0 * r0 + m1 * r1 + m2 * r2;
                    den[k] += m2sq;
                }
            }
            // 每 knot 的 LS 解 + 空 knot 插值
            double[] newg = new double[KNOTS];
            int firstValid = -1, lastValid = -1;
            for (int k = 0; k < KNOTS; k++) {
                newg[k] = den[k] > 1e-12 ? num[k] / den[k] : Double.NaN;
                if (!Double.isNaN(newg[k])) {
                    if (firstValid < 0) firstValid = k;
                    lastValid = k;
                }
            }
            int prev = firstValid;
            for (int k = firstValid; k <= lastValid; k++) {
                if (Double.isNaN(newg[k])) {
                    int next = k;
                    while (next <= lastValid && Double.isNaN(newg[next])) next++;
                    double t = next > lastValid ? 0 : (k - prev) / (double) (next - prev);
                    newg[k] = next > lastValid ? newg[prev]
                            : newg[prev] * (1 - t) + newg[next] * t;
                } else {
                    prev = k;
                }
            }
            for (int k = 0; k < firstValid; k++) newg[k] = newg[firstValid];
            for (int k = lastValid + 1; k < KNOTS; k++) newg[k] = newg[lastValid];

            // 阻尼 + 单调 + 限幅
            double acc = 0;
            for (int k = 0; k < KNOTS; k++) {
                double v = 0.5 * g[k] + 0.5 * newg[k];
                if (v < acc) v = acc;
                acc = v;
                g[k] = v < 0 ? 0 : (v > 1 ? 1 : v);
            }
            // 归一化 g(1)=1，缩放并入 M
            double s = g[KNOTS - 1];
            if (s > 1e-3) {
                for (int k = 0; k < KNOTS; k++) g[k] /= s;
                for (int j = 0; j < 3; j++) {
                    for (int c = 0; c < 3; c++) M[j][c] *= s;
                }
            }
        }

        // 量化
        LutParams out = new LutParams();
        for (int k = 0; k < KNOTS; k++) {
            out.gamma[k] = (int) Math.round(g[k] * 1023);
        }
        for (int j = 0; j < 3; j++) {
            for (int c = 0; c < 3; c++) {
                out.matrix[j * 3 + c] = (int) Math.round(M[j][c] * LutParams.MATRIX_SCALE);
            }
        }
        return out;
    }

    /** 中性轴初始化：对角线样本三通道均值。 */
    private static double[] neutralInit(float[] X, float[] L) {
        double[] g = new double[KNOTS];
        // 对角线点：X 中 r==g==b 的样本（GRID 个）
        double[] t = new double[GRID];
        double[] v = new double[GRID];
        int cnt = 0;
        for (int i = 0; i < X.length; i += 3) {
            if (X[i] == X[i + 1] && X[i + 1] == X[i + 2]) {
                t[cnt] = X[i];
                v[cnt] = (L[i] + L[i + 1] + L[i + 2]) / 3.0;
                cnt++;
            }
        }
        for (int k = 0; k < KNOTS; k++) {
            double x = k / (double) (KNOTS - 1);
            // 线性插值于对角样本
            int i = 0;
            while (i < cnt - 1 && t[i + 1] < x) i++;
            int j = i + 1 < cnt ? i + 1 : i;
            double f = j > i ? (x - t[i]) / (t[j] - t[i]) : 0;
            if (f < 0) f = 0; else if (f > 1) f = 1;
            g[k] = v[i] * (1 - f) + v[j] * f;
        }
        return g;
    }

    private static double interp(double[] g, double x) {
        double pos = x * (KNOTS - 1);
        int i = (int) pos;
        if (i >= KNOTS - 1) return g[KNOTS - 1];
        double f = pos - i;
        return g[i] * (1 - f) + g[i + 1] * f;
    }

    private static double[][] inv3(double[][] A) {
        double a = A[0][0], b = A[0][1], c = A[0][2];
        double d = A[1][0], e = A[1][1], f = A[1][2];
        double h = A[2][0], i2 = A[2][1], j = A[2][2];
        double det = a * (e * j - f * i2) - b * (d * j - f * h) + c * (d * i2 - e * h);
        if (Math.abs(det) < 1e-12) det = det < 0 ? -1e-12 : 1e-12;
        double[][] inv = new double[3][3];
        inv[0][0] = (e * j - f * i2) / det; inv[0][1] = (c * i2 - b * j) / det; inv[0][2] = (b * f - c * e) / det;
        inv[1][0] = (f * h - d * j) / det;   inv[1][1] = (a * j - c * h) / det;   inv[1][2] = (c * d - a * f) / det;
        inv[2][0] = (d * i2 - e * h) / det;  inv[2][1] = (b * h - a * i2) / det;  inv[2][2] = (a * e - b * d) / det;
        return inv;
    }

    private static double[][] mul(double[][] A, double[][] B) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = A[i][0] * B[0][j] + A[i][1] * B[1][j] + A[i][2] * B[2][j];
            }
        }
        return out;
    }
}
