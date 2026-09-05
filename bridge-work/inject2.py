#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Picture Effect Plus → LUT Edition 注入（-r 模式树，免资源重编）。

产物树：D:\\pmca-tool\\apktool\\peplus_r（resources.arsc 原样保留）
步骤：
  1. 拷贝 LUT 引擎 smali（com.sonylut.bridge）
  2. assets/MenuData.xml：13 官方滤镜 → OFF + 25 LUT（文案走 lutstr/ 命名空间）
  3. BaseMenuService：getMenuItemText/getMenuItemGuideText 头部接 LutBridge
  4. PictureEffectController：setValue 拦截 / getSupportedValue 放行 / onCameraRemoving
  5. PictureEffectPlus：onCreate 预热
"""
import io, os, re, shutil

PEPLUS = r"D:\pmca-tool\apktool\peplus_r"
BRIDGE_SMALI = os.path.join(os.path.dirname(__file__), "build", "smali-out", "com", "sonylut", "bridge")
LUTS_TXT = r"D:\reference\lut\A6000-LUT\luts\LUTS.TXT"
SD_LUTS = os.path.join(os.path.dirname(__file__), "sd_luts.txt")

def load_luts():
    # 菜单项来源 = sd_luts.txt（SD 卡实际清单，词干每行一项 "STEM|"），
    # 与 MenuIds.java 同批生成，保证 MenuData/过滤列表/SD 三者一致
    luts = []
    with io.open(SD_LUTS, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            stem = line.split("|", 1)[0].strip().upper()
            if stem:
                luts.append((stem, ""))
    return luts

OFFICIAL_EFFECTS = ["part-color-plus", "rough-mono", "soft-focus", "hdr-art",
                    "richtone-mono", "miniature-plus", "watercolor", "illust",
                    "toy-camera-plus", "pop-color", "posterization",
                    "retro-photo", "soft-high-key"]
CTRL = "com.sony.imaging.app.pictureeffectplus.shooting.camera.PictureEffectPlusController"
ICON = "drawable/p_16_dd_parts_pe_menu_icon_normal_pop_color"
BG = "drawable/p_16_dd_parts_pe_image_pop_color"

def item_xml(item_id, value):
    # TextRes/GuideRes 用 lutstr/ 命名空间：BaseMenuService 钩子识别并返回文案，
    # 不解析资源（避免资源重编）。OFF 项 Value=itemId（lut-off），保证备份值
    # 与菜单 ItemId 一致（onResume 的 indexOf 查找）。
    return ('            <Layer2\n'
            '                CautionID="0"\n'
            '                ConfigClass="' + CTRL + '"\n'
            '                ExecType="SET_VALUE"\n'
            '                GuideRes="lutstr/' + item_id + '"\n'
            '                IconRes="' + ICON + '"\n'
            '                ItemId="' + item_id + '"\n'
            '                NextMenuID=""\n'
            '                OptionStr="' + BG + '"\n'
            '                SelectedIconRes="' + ICON + '"\n'
            '                TextRes="lutstr/' + item_id + '"\n'
            '                Value="' + value + '" />\n')

def step1_copy_bridge():
    dst = os.path.join(PEPLUS, "smali", "com", "sonylut", "bridge")
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(BRIDGE_SMALI, dst)
    print("1. bridge smali ->", len(os.listdir(dst)), "files")

def step2_menudata(luts):
    path = os.path.join(PEPLUS, "assets", "MenuData.xml")
    with io.open(path, encoding="utf-8") as f:
        xml = f.read()
    if 'ItemId="lut-off"' in xml:
        print("2. (already patched)")
        return
    blocks = []
    for eid in OFFICIAL_EFFECTS:
        m = re.search(r'[ \t]*<Layer2[^>]*ItemId="' + re.escape(eid) + r'"[^>]*>.*?</Layer2>\n',
                      xml, re.DOTALL)
        if not m:
            raise SystemExit("effect block not found: " + eid)
        blocks.append(m.group(0))
    new_items = [item_xml("lut-off", "off")]
    for stem, desc in luts:
        new_items.append(item_xml("lut-" + stem.lower(), "lut-" + stem.lower()))
    xml = xml.replace(blocks[0], "".join(new_items), 1)
    for b in blocks[1:]:
        xml = xml.replace(b, "", 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)
    print("2. MenuData.xml: %d official -> %d items" % (len(blocks), len(new_items)))

def patch_text_methods():
    p = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app", "base",
                     "menu", "BaseMenuService.smali")
    with io.open(p, encoding="utf-8") as f:
        s = f.read()
    changed = False
    for sig, bridge_method in (
            ('.method public getMenuItemText(Ljava/lang/String;)Ljava/lang/CharSequence;', 'menuText'),
            ('.method public getMenuItemGuideText(Ljava/lang/String;)Ljava/lang/CharSequence;', 'menuGuide')):
        marker = 'LutBridge;->' + bridge_method
        if marker in s:
            continue
        inject = ('    invoke-static {p1}, Lcom/sonylut/bridge/LutBridge;->'
                  + bridge_method + '(Ljava/lang/String;)Ljava/lang/CharSequence;\n'
                  '    move-result-object v0\n'
                  '    if-eqz v0, :lut_orig\n'
                  '    return-object v0\n'
                  '    :lut_orig\n')
        i = s.index(sig)
        j = s.index('.prologue', i) + len('.prologue\n')
        s = s[:j] + inject + s[j:]
        changed = True
    if changed:
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(s)
    print("3. BaseMenuService text hooks", "(done)" if changed else "(already)")

CTRL_SMALI = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                          "base", "shooting", "camera", "PictureEffectController.smali")
MCAMSET = "Lcom/sony/imaging/app/base/shooting/camera/PictureEffectController;->mCamSet:Lcom/sony/imaging/app/base/shooting/camera/CameraSetting;"

def step4_controller_hooks():
    with io.open(CTRL_SMALI, encoding="utf-8") as f:
        s = f.read()
    orig = s
    if "LutBridge;->intercept" not in s:
        sig = '.method public setValue(Ljava/lang/String;Ljava/lang/String;)V'
        i = s.index(sig)
        j = s.index('.prologue', i) + len('.prologue\n')
        inject = ('    iget-object v1, p0, ' + MCAMSET + '\n'
                  '    invoke-static {p2, v1}, Lcom/sonylut/bridge/LutBridge;->intercept(Ljava/lang/String;Lcom/sony/imaging/app/base/shooting/camera/CameraSetting;)Z\n'
                  '    move-result v1\n'
                  '    if-eqz v1, :lut_continue\n'
                  '    return-void\n'
                  '    :lut_continue\n')
        s = s[:j] + inject + s[j:]
    if "lutOrigGetSupportedValue" not in s:
        sig2 = '.method public getSupportedValue(Ljava/lang/String;)Ljava/util/List;'
        s = s.replace(sig2, '.method public lutOrigGetSupportedValue(Ljava/lang/String;)Ljava/util/List;', 1)
        k = s.index('.method public lutOrigGetSupportedValue')
        e = s.index('.end method', k) + len('.end method\n')
        wrapper = (sig2 + '\n'
                   '    .locals 1\n\n'
                   '    invoke-virtual {p0, p1}, Lcom/sony/imaging/app/base/shooting/camera/PictureEffectController;->lutOrigGetSupportedValue(Ljava/lang/String;)Ljava/util/List;\n'
                   '    move-result-object v0\n\n'
                   '    invoke-static {v0}, Lcom/sonylut/bridge/LutBridge;->extendList(Ljava/util/List;)Ljava/util/List;\n'
                   '    move-result-object v0\n\n'
                   '    return-object v0\n'
                   '.end method\n')
        s = s[:e] + '\n' + wrapper + s[e:]
    if "onCameraRemoving" not in s:
        hook_c = ('\n.method public onCameraRemoving()V\n'
                  '    .locals 1\n\n'
                  '    iget-object v0, p0, ' + MCAMSET + '\n'
                  '    invoke-static {v0}, Lcom/sonylut/bridge/LutBridge;->onTerm(Lcom/sony/imaging/app/base/shooting/camera/CameraSetting;)V\n\n'
                  '    invoke-super {p0}, Lcom/sony/imaging/app/base/shooting/camera/AbstractController;->onCameraRemoving()V\n'
                  '    return-void\n'
                  '.end method\n')
        s = s.rstrip() + '\n' + hook_c
    if s != orig:
        with io.open(CTRL_SMALI, "w", encoding="utf-8", newline="\n") as f:
            f.write(s)
        print("4. controller hooks done")
    else:
        print("4. (already hooked)")

def step5_prewarm():
    p = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                     "pictureeffectplus", "PictureEffectPlus.smali")
    with io.open(p, encoding="utf-8") as f:
        s = f.read()
    old_noctx = ('    invoke-static {}, Lcom/sonylut/bridge/LutBridge;'
                 '->prewarm()V')
    new_ctx = ('    invoke-static {p0}, Lcom/sonylut/bridge/LutBridge;'
               '->prewarm(Landroid/content/Context;)V')
    if new_ctx in s:
        print("5. (already)")
        return
    if old_noctx in s:  # 旧版无参钩子升级为带 Context
        s = s.replace(old_noctx, new_ctx, 1)
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(s)
        print("5. prewarm hook upgraded (ctx)")
        return
    anchor = ('    invoke-super {p0, p1}, Lcom/sony/imaging/app/base/BaseApp;'
              '->onCreate(Landroid/os/Bundle;)V')
    if anchor not in s:
        raise SystemExit("onCreate anchor not found")
    s = s.replace(anchor, anchor + '\n' + new_ctx, 1)
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    print("5. prewarm hook done")

OPT_MENU_SMALI = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                              "pictureeffectplus", "shooting", "layout",
                              "PictureEffectPlusOptionMenuLayout.smali")

def step8_bg_hook():
    """getBackgroundDrawable 包装：lut-/退役 id 返回通用背景图，修 -1 崩溃。"""
    with io.open(OPT_MENU_SMALI, encoding="utf-8") as f:
        s = f.read()
    if "bgDrawableResId" in s:
        print("8. (already)")
        return
    sig = '.method private getBackgroundDrawable(Ljava/lang/String;)I'
    if sig not in s:
        raise SystemExit("getBackgroundDrawable not found")
    s = s.replace(sig, '.method private lutOrigGetBackgroundDrawable(Ljava/lang/String;)I', 1)
    wrapper = (sig + '\n'
               '    .locals 1\n\n'
               '    invoke-static {p1}, Lcom/sonylut/bridge/LutBridge;->bgDrawableResId(Ljava/lang/String;)I\n'
               '    move-result v0\n\n'
               '    if-gez v0, :lut_have\n\n'
               '    invoke-direct {p0, p1}, Lcom/sony/imaging/app/pictureeffectplus/shooting/layout/PictureEffectPlusOptionMenuLayout;->lutOrigGetBackgroundDrawable(Ljava/lang/String;)I\n'
               '    move-result v0\n\n'
               '    :lut_have\n'
               '    return v0\n'
               '.end method\n')
    # 插到改名方法之后
    k = s.index('.method private lutOrigGetBackgroundDrawable')
    e = s.index('.end method', k) + len('.end method\n')
    s = s[:e] + '\n' + wrapper + s[e:]
    with io.open(OPT_MENU_SMALI, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    print("8. bg drawable hook done")

PLUS_CTRL_SMALI = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                               "pictureeffectplus", "shooting", "camera",
                               "PictureEffectPlusController.smali")

def step6_backup_sanitize():
    """getBackupEffectValue 包装：退役效果 id → lut-off（修启动 NPE 崩溃）。"""
    with io.open(PLUS_CTRL_SMALI, encoding="utf-8") as f:
        s = f.read()
    if "sanitizeBackup" in s:
        print("6. (already)")
        return
    sig = '.method public getBackupEffectValue()Ljava/lang/String;'
    if sig not in s:
        raise SystemExit("getBackupEffectValue not found")
    s = s.replace(sig, '.method public lutOrigGetBackupEffectValue()Ljava/lang/String;', 1)
    k = s.index('.method public lutOrigGetBackupEffectValue')
    e = s.index('.end method', k) + len('.end method\n')
    wrapper = (sig + '\n'
               '    .locals 1\n\n'
               '    invoke-virtual {p0}, Lcom/sony/imaging/app/pictureeffectplus/shooting/camera/PictureEffectPlusController;->lutOrigGetBackupEffectValue()Ljava/lang/String;\n'
               '    move-result-object v0\n\n'
               '    invoke-static {v0}, Lcom/sonylut/bridge/LutBridge;->sanitizeBackup(Ljava/lang/String;)Ljava/lang/String;\n'
               '    move-result-object v0\n\n'
               '    return-object v0\n'
               '.end method\n')
    s = s[:e] + '\n' + wrapper + s[e:]
    with io.open(PLUS_CTRL_SMALI, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    print("6. backup sanitize hook done")

def step7_off_value_fix():
    """OFF 项 Value：off → lut-off（备份值/ItemId 一致，菜单高亮 indexOf 才能命中）。"""
    path = os.path.join(PEPLUS, "assets", "MenuData.xml")
    with io.open(path, encoding="utf-8") as f:
        xml = f.read()
    bad = 'ItemId="lut-off"\n                NextMenuID=""\n                OptionStr="' + BG + '"\n                SelectedIconRes="' + ICON + '"\n                TextRes="lutstr/lut-off"\n                Value="off"'
    good = bad[:-4] + 'lut-off"'
    if bad in xml:
        xml = xml.replace(bad, good, 1)
        with io.open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(xml)
        print("7. OFF item Value off -> lut-off")
    elif good in xml:
        print("7. (already)")
    else:
        raise SystemExit("lut-off item block not found for Value fix")

if __name__ == "__main__":
    luts = load_luts()
    print("LUTs:", len(luts))
    step1_copy_bridge()
    step2_menudata(luts)
    patch_text_methods()
    step4_controller_hooks()
    step5_prewarm()
    step6_backup_sanitize()
    step7_off_value_fix()
    step8_bg_hook()
    print("ALL INJECTED")
