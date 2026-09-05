#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Picture Effect Plus → LUT Edition 注入脚本。

步骤：
  1. 拷贝 LUT 引擎 smali（com.sonylut.bridge）进 apktool 树
  2. MenuData.xml：13 个官方滤镜 Layer2 → OFF + 25 个 LUT 项
  3. res/values/strings.xml：加 LUT 名称/介绍；改应用名（default/zh-rCN/zh-rTW）
  4. PictureEffectController.smali：setValue 头拦截 / getSupportedValue 包装 / onCameraRemoving
  5. PictureEffectPlus.smali：onCreate 预热
"""
import io, os, re, shutil, sys

PEPLUS = r"D:\pmca-tool\apktool\peplus"
BRIDGE_SMALI = os.path.join(os.path.dirname(__file__), "build", "smali-out", "com", "sonylut", "bridge")
LUTS_TXT = r"D:\reference\lut\A6000-LUT\luts\LUTS.TXT"

# LUT 清单：词干 → (显示名, 介绍)
def load_luts():
    luts = []
    with io.open(LUTS_TXT, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "|" not in line:
                continue
            fn, desc = line.split("|", 1)
            stem = fn.split(".")[0].strip().upper()
            luts.append((stem, stem, desc.strip()))
    return luts

OFFICIAL_EFFECTS = ["part-color-plus", "rough-mono", "soft-focus", "hdr-art",
                    "richtone-mono", "miniature-plus", "watercolor", "illust",
                    "toy-camera-plus", "pop-color", "posterization",
                    "retro-photo", "soft-high-key"]
CTRL = "com.sony.imaging.app.pictureeffectplus.shooting.camera.PictureEffectPlusController"
ICON = "drawable/p_16_dd_parts_pe_menu_icon_normal_pop_color"
BG = "drawable/p_16_dd_parts_pe_image_pop_color"

def item_xml(item_id, value, text_res, guide_res):
    return ('            <Layer2\n'
            '                CautionID="0"\n'
            '                ConfigClass="' + CTRL + '"\n'
            '                ExecType="SET_VALUE"\n'
            '                GuideRes="string/' + guide_res + '"\n'
            '                IconRes="' + ICON + '"\n'
            '                ItemId="' + item_id + '"\n'
            '                NextMenuID=""\n'
            '                OptionStr="' + BG + '"\n'
            '                SelectedIconRes="' + ICON + '"\n'
            '                TextRes="string/' + text_res + '"\n'
            '                Value="' + value + '" />\n')

def step1_copy_bridge():
    dst = os.path.join(PEPLUS, "smali", "com", "sonylut", "bridge")
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(BRIDGE_SMALI, dst)
    print("1. bridge smali ->", dst, os.listdir(dst))

def step2_menudata(luts):
    path = os.path.join(PEPLUS, "assets", "MenuData.xml")
    with io.open(path, encoding="utf-8") as f:
        xml = f.read()
    if 'ItemId="lut-off"' in xml:
        print("2. (already patched, skip)")
        return
    blocks = []
    for eid in OFFICIAL_EFFECTS:
        m = re.search(r'[ \t]*<Layer2[^>]*ItemId="' + re.escape(eid) + r'"[^>]*>.*?</Layer2>\n',
                      xml, re.DOTALL)
        if not m:
            raise SystemExit("effect block not found: " + eid)
        blocks.append(m.group(0))
    new_items = [item_xml("lut-off", "off", "STRID_LUT_OFF", "STRID_LUT_OFF_G")]
    for stem, name, desc in luts:
        new_items.append(item_xml("lut-" + stem.lower(), "lut-" + stem.lower(),
                                  "STRID_LUT_" + stem, "STRID_LUT_" + stem + "_G"))
    repl = "".join(new_items)
    first = blocks[0]
    xml = xml.replace(first, repl, 1)
    for b in blocks[1:]:
        xml = xml.replace(b, "", 1)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)
    print("2. MenuData.xml: %d official -> %d items" % (len(blocks), len(new_items)))

def step3_strings(luts):
    add_default = ['    <string name="STRID_LUT_OFF">OFF</string>',
                   '    <string name="STRID_LUT_OFF_G">Turn off LUT, restore native imaging.</string>']
    for stem, name, desc in luts:
        add_default.append('    <string name="STRID_LUT_%s">%s</string>' % (stem, name))
    for stem, name, desc in luts:
        add_default.append('    <string name="STRID_LUT_%s_G">%s</string>'
                            % (stem, xml_escape(desc)))
    patch_res("values", add_default)
    # 应用名：default/zh-rCN/zh-rTW
    for d, label in (("values", "Custom LUT"), ("values-zh-rCN", "自定义LUT"),
                     ("values-zh-rTW", "自訂LUT")):
        p = os.path.join(PEPLUS, "res", d, "strings.xml")
        if not os.path.isfile(p):
            continue
        with io.open(p, encoding="utf-8") as f:
            s = f.read()
        s2 = re.sub(r'(<string name="STRID_FUNC_EFFECT_MASTER">)[^<]*(</string>)',
                    r'\g<1>' + label + r'\g<2>', s)
        s2 = re.sub(r'(<string name="STRID_FUNC_EFFECT_MASTER_2L">)[^<]*(</string>)',
                    r'\g<1>' + label + r'\g<2>', s2)
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(s2)
    print("3. strings injected")

def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def patch_res(values_dir, add_lines):
    p = os.path.join(PEPLUS, "res", values_dir, "strings.xml")
    with io.open(p, encoding="utf-8") as f:
        s = f.read()
    if "STRID_LUT_OFF" in s:
        print("   (already patched, skip)")
        return
    insert = "\n".join(add_lines) + "\n"
    idx = s.rfind("</resources>")
    s = s[:idx] + insert + s[idx:]
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

CTRL_SMALI = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                          "base", "shooting", "camera", "PictureEffectController.smali")
MCAMSET = "Lcom/sony/imaging/app/base/shooting/camera/PictureEffectController;->mCamSet:Lcom/sony/imaging/app/base/shooting/camera/CameraSetting;"

def step4_controller_hooks():
    with io.open(CTRL_SMALI, encoding="utf-8") as f:
        s = f.read()
    orig = s
    # --- hook A: setValue 头部拦截 ---
    if "LutBridge;->intercept" not in s:
        sig = '.method public setValue(Ljava/lang/String;Ljava/lang/String;)V'
        if sig not in s:
            raise SystemExit("setValue not found")
        inject = ('    iget-object v1, p0, ' + MCAMSET + '\n'
                  '    invoke-static {p2, v1}, Lcom/sonylut/bridge/LutBridge;->'
                  'intercept(Ljava/lang/String;Lcom/sony/imaging/app/base/shooting/camera/CameraSetting;)Z\n'
                  '    move-result v1\n'
                  '    if-eqz v1, :lut_continue\n'
                  '    return-void\n'
                  '    :lut_continue\n')
        i = s.index(sig)
        j = s.index('.prologue', i) + len('.prologue\n')
        s = s[:j] + inject + s[j:]
    # --- hook B: getSupportedValue 包装（原方法改名）---
    if "lutOrigGetSupportedValue" not in s:
        sig2 = '.method public getSupportedValue(Ljava/lang/String;)Ljava/util/List;'
        if sig2 not in s:
            raise SystemExit("getSupportedValue not found")
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
    # --- hook C: onCameraRemoving 覆盖（追加到文件末尾）---
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
        print("4. (all hooks already present)")

def step5_prewarm():
    p = os.path.join(PEPLUS, "smali", "com", "sony", "imaging", "app",
                     "pictureeffectplus", "PictureEffectPlus.smali")
    with io.open(p, encoding="utf-8") as f:
        s = f.read()
    if "prewarm" in s:
        print("5. (already hooked, skip)")
        return
    anchor = ('    invoke-super {p0, p1}, Lcom/sony/imaging/app/base/BaseApp;'
              '->onCreate(Landroid/os/Bundle;)V')
    if anchor not in s:
        raise SystemExit("onCreate super call not found")
    s = s.replace(anchor, anchor + '\n    invoke-static {}, Lcom/sonylut/bridge/LutBridge;->prewarm()V', 1)
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    print("5. prewarm hook done")

if __name__ == "__main__":
    luts = load_luts()
    print("LUTs:", len(luts))
    step1_copy_bridge()
    step2_menudata(luts)
    step3_strings(luts)
    step4_controller_hooks()
    step5_prewarm()
    print("ALL INJECTED")
