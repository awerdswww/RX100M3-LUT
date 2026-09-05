package com.sonylut.bridge;

/** 编译期固化的菜单 id 全集（与 assets/MenuData.xml 同源生成）。
 *  extendList 必须完整包含它们：菜单按 Value∈supportedList 过滤放行，
 *  漏掉任何一项都会让适配器比滚轮计数少 → 越界崩溃。 */
public final class MenuIds {
    public static final String[] IDS = {
            "lut-off",
            "lut-acros",
            "lut-arri",
            "lut-astia",
            "lut-cchrome",
            "lut-cneg",
            "lut-eterna",
            "lut-eternabb",
            "lut-f3513di",
            "lut-hbnrgb",
            "lut-hbsrgb",
            "lut-k2383",
            "lut-leicacls",
            "lut-leicanat",
            "lut-proneg",
            "lut-provia",
            "lut-realaace",
            "lut-redachro",
            "lut-redfb",
            "lut-redfbbb",
            "lut-redfbo",
            "lut-redmc",
            "lut-velvia",
            "lut-gr3xneg",
            "lut-gr3xposi"
    };

    private MenuIds() {
    }
}
