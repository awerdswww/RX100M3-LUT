package com.sonylut.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * 官方风格 LUT 菜单（v0.7.0）。
 *
 * 视觉语言对齐索尼机内菜单（取自官方应用资源真值）：
 *   正文 #ffdddddd / 选中 #ffdd6600（索尼橙）/ 禁用 #ff777777 / 底色深黑半透明。
 * 布局：左侧 7 行选择窗（滚动指示条），右侧描述面板（名称+介绍+强度条），
 * 底部按键提示条。
 *
 * 纯状态绘制：菜单数据由 DataSource 提供，MainActivity 持有选中态并调
 * invalidate() 驱动重绘；本类不持有业务状态，不触碰相机。
 */
public class LutMenu extends View {

    /** 菜单数据源（由 MainActivity 实现）。索引约定：0=OFF，1..n=LUT，n+1=退出。 */
    public interface DataSource {
        int itemCount();
        String itemName(int i);
        String itemDesc(int i);
        boolean isApplied(int i);
        int selection();
        int intensity();
    }

    // 索尼菜单配色（官方应用 colors.xml 原值）
    private static final int COLOR_TEXT = 0xffdddddd;
    private static final int COLOR_FOCUSED = 0xffdd6600;
    private static final int COLOR_DIM = 0xff777777;
    private static final int COLOR_VALUE = 0xffdd6611;
    private static final int COLOR_BG = 0xe6000000;
    private static final int COLOR_ROW_SEL_BG = 0x30dd6600;
    private static final int COLOR_LINE = 0xff444444;

    private static final int VISIBLE_ROWS = 7;

    private final DataSource ds;
    private final Paint p = new Paint();
    private final TextPaint tpName = new TextPaint();
    private final TextPaint tpList = new TextPaint();
    private final TextPaint tpDesc = new TextPaint();
    private final TextPaint tpTitle = new TextPaint();
    private final TextPaint tpHint = new TextPaint();

    public LutMenu(Context c, DataSource dataSource) {
        super(c);
        ds = dataSource;
        tpName.setAntiAlias(true);
        tpName.setColor(COLOR_FOCUSED);
        tpName.setTypeface(Typeface.DEFAULT_BOLD);
        tpList.setAntiAlias(true);
        tpList.setColor(COLOR_TEXT);
        tpDesc.setAntiAlias(true);
        tpDesc.setColor(COLOR_TEXT);
        tpTitle.setAntiAlias(true);
        tpTitle.setColor(COLOR_TEXT);
        tpTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tpHint.setAntiAlias(true);
        tpHint.setColor(COLOR_DIM);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 背景整幅深黑半透明，压住取景画面
        p.setColor(COLOR_BG);
        canvas.drawRect(0, 0, w, h, p);

        float pad = h * 0.035f;
        float titleSize = h * 0.048f;
        float rowH = (h - pad * 2 - titleSize * 2.6f) / VISIBLE_ROWS;
        float listW = w * 0.44f;
        float descX = pad + listW + w * 0.03f;
        float descW = w - descX - pad;

        // 标题 + 分隔线
        tpTitle.setTextSize(titleSize);
        canvas.drawText("胶片模拟", pad, pad + titleSize, tpTitle);
        p.setColor(COLOR_LINE);
        canvas.drawRect(pad, pad + titleSize * 1.35f, w - pad,
                pad + titleSize * 1.35f + Math.max(1, h / 360f), p);

        // 左侧选择窗
        int total = ds.itemCount();
        int sel = ds.selection();
        int start = sel - VISIBLE_ROWS / 2;
        if (start > total - VISIBLE_ROWS) {
            start = total - VISIBLE_ROWS;
        }
        if (start < 0) {
            start = 0;
        }
        int end = Math.min(total, start + VISIBLE_ROWS);
        float listTop = pad + titleSize * 1.9f;
        tpList.setTextSize(rowH * 0.52f);
        for (int i = start; i < end; i++) {
            float rowTop = listTop + (i - start) * rowH;
            if (i == sel) {
                p.setColor(COLOR_ROW_SEL_BG);
                canvas.drawRect(pad, rowTop, pad + listW, rowTop + rowH, p);
                p.setColor(COLOR_FOCUSED);
                canvas.drawRect(pad, rowTop, pad + rowH * 0.12f,
                        rowTop + rowH, p);
                tpList.setColor(COLOR_FOCUSED);
            } else {
                tpList.setColor(COLOR_TEXT);
            }
            String name = ds.itemName(i);
            String mark = ds.isApplied(i) ? "  ●" : "";
            float textY = rowTop + rowH * 0.5f
                    - (tpList.ascent() + tpList.descent()) / 2f;
            canvas.drawText(name + mark, pad + rowH * 0.3f, textY, tpList);
        }
        // 滚动位置指示条（总条数超过可视行数才显示）
        if (total > VISIBLE_ROWS) {
            float trackX = pad + listW + w * 0.008f;
            float trackH = listTop + VISIBLE_ROWS * rowH - listTop;
            p.setColor(0x30ffffff);
            canvas.drawRect(trackX, listTop, trackX + w * 0.004f,
                    listTop + trackH, p);
            float thumbH = trackH * VISIBLE_ROWS / total;
            float thumbY = listTop + trackH * start / total;
            p.setColor(COLOR_VALUE);
            canvas.drawRect(trackX, thumbY, trackX + w * 0.004f,
                    thumbY + thumbH, p);
        }

        // 右侧描述面板
        String selName = ds.itemName(sel);
        float nameSize = h * 0.056f;
        tpName.setTextSize(nameSize);
        float descTop = listTop + rowH * 0.4f;
        canvas.drawText(selName, descX, descTop + nameSize, tpName);
        p.setColor(COLOR_LINE);
        float lineY = descTop + nameSize * 1.5f;
        canvas.drawRect(descX, lineY, w - pad, lineY + Math.max(1, h / 360f), p);

        // 介绍文本（自动换行，CJK 断行 StaticLayout 原生支持）
        String desc = ds.itemDesc(sel);
        if (desc != null && desc.length() > 0 && descW > 100) {
            tpDesc.setTextSize(h * 0.042f);
            StaticLayout sl = new StaticLayout(desc, tpDesc, (int) descW,
                    Layout.Alignment.ALIGN_NORMAL, 1.25f, 0f, false);
            canvas.save();
            canvas.translate(descX, lineY + h * 0.02f);
            sl.draw(canvas);
            canvas.restore();
        }

        // 强度条
        float barY = h - pad - titleSize * 2.2f;
        tpDesc.setTextSize(h * 0.04f);
        tpDesc.setColor(COLOR_TEXT);
        canvas.drawText("强度 " + ds.intensity() + "%", descX, barY, tpDesc);
        p.setColor(0x30ffffff);
        canvas.drawRect(descX + h * 0.24f, barY - h * 0.022f, w - pad,
                barY - h * 0.012f, p);
        p.setColor(COLOR_VALUE);
        canvas.drawRect(descX + h * 0.24f, barY - h * 0.022f,
                descX + h * 0.24f + (w - pad - descX - h * 0.24f)
                        * ds.intensity() / 100f,
                barY - h * 0.012f, p);

        // 底部按键提示
        tpHint.setTextSize(h * 0.034f);
        canvas.drawText("上下:选择   左右:强度   中央:应用   删除:关闭   MENU:退出",
                pad, h - pad * 0.6f, tpHint);
    }
}
