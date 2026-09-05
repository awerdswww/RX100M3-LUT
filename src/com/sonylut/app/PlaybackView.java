package com.sonylut.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

/**
 * 回放视图（v0.7.1，回看键呼出）：黑底全屏显示最近照片。
 *
 * 纯绘制组件：位图与说明文字由 Provider 提供（MainActivity 在 worker 线程
 * 解码后切主线程刷新），本类不碰相机与文件。
 * JPEG 解码全图；ARW 取 TIFF 头内嵌 JPEG 缩略图（MainActivity 提取）。
 */
public class PlaybackView extends View {

    public interface Provider {
        Bitmap bitmap();
        String caption();
    }

    private final Provider provider;
    private final Paint p = new Paint();
    private final Paint tp = new Paint();

    public PlaybackView(Context c, Provider provider) {
        super(c);
        this.provider = provider;
        tp.setAntiAlias(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        p.setColor(Color.BLACK);
        canvas.drawRect(0, 0, w, h, p);

        Bitmap bmp = provider.bitmap();
        if (bmp != null && !bmp.isRecycled()) {
            float bw = bmp.getWidth();
            float bh = bmp.getHeight();
            float scale = Math.min(w / bw, h / bh);
            float dw = bw * scale;
            float dh = bh * scale;
            canvas.drawBitmap(bmp, null,
                    new Rect((int) ((w - dw) / 2f), (int) ((h - dh) / 2f),
                            (int) ((w + dw) / 2f), (int) ((h + dh) / 2f)), p);
        } else {
            tp.setColor(0xff777777);
            tp.setTextSize(h * 0.05f);
            String msg = provider.caption() != null
                    && provider.caption().endsWith("ARW") ? "RAW 无内嵌缩略图"
                    : "解码中…";
            float tw = tp.measureText(msg);
            canvas.drawText(msg, (w - tw) / 2f, h / 2f, tp);
        }

        String cap = provider.caption();
        if (cap != null && cap.length() > 0) {
            tp.setColor(0xffffffff);
            tp.setTextSize(h * 0.038f);
            canvas.drawText(cap, w * 0.02f, h - h * 0.035f, tp);
            tp.setColor(0xff777777);
            String hint = "左右:浏览  回看:返回";
            canvas.drawText(hint, w * 0.02f, h * 0.05f, tp);
        }
    }
}
