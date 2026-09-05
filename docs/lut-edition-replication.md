# 把索尼官方相机 App 改装成 LUT 选择器 —— 完整复刻指南

> 目标读者：具备逆向工程能力的人或同等水平的 AI。按本文从零复刻"Picture Effect Plus → LUT Edition"改装，
> 包括全部钩子点、三次崩溃的根因与修复、构建管线和真机调试闭环。
>
> 前作与依赖阅读：`docs/official-apps-reverse.md`（官方应用架构逆向笔记，本文的菜单/按键/变焦结论都出自它）。

## 0. 成果定义

在 Sony RX100M3（Android 2.3.7，PMCA 平台）上，把官方"Picture Effect Plus"应用的照片效果菜单
（13 个官方滤镜）整体替换为 LUT 列表（OFF + SD 卡 `/LUTS/*.CUB`），选中即经
伽马表 + RGB 矩阵写入 ISP 管线，取景与成片实时生效。**UI、按键、状态机、拍摄、退出全部保持官方行为**，
改动面收敛为：1 个资产文件 + 7 处 smali 钩子 + 自有 LUT 引擎注入。

为什么选"改官方 App"而不是自研：官方应用自带 `BaseApp→AppRoot` 状态机、FunctionTable 按键分发、
原生观感的自绘菜单、与拍摄框架（DA/ScalarA）协商好的相机生命周期——自研应用要在这些地方重新踩一遍
所有坑（退出重启、HAL 竞态、按键映射），而官方代码已经全部处理好了。

## 1. 前置知识（复刻者必须先理解的官方架构）

- **应用形态**：Activity 是 `Theme.Transparent` 透明浮层，声明 `ACCESS_SURFACE_FLINGER`，
  叠在原生取景层上；`BaseApp extends AppRoot`（`com.sony.imaging.app.fw`，平台内置框架，不在 apk 内）。
- **菜单系统**：
  - 菜单树定义在 **`assets/MenuData.xml`**——纯文本资产，运行时由 `MenuTable` 用 XmlPullParser 解析，
    **不参与资源编译**（这是本改装的关键前提）。
  - 节点属性：`ItemId / Value / TextRes / GuideRes / IconRes / SelectedIconRes / OptionStr /
    ConfigClass / ExecType / NextMenuID`。
  - 叶子项模式：`ExecType="SET_VALUE"` + `NextMenuID=""`，选中即触发 `setValue`。
  - `BaseMenuService` 按 itemId 解析文案/介绍/图标：`getMenuItemText/GuideText/Drawable(itemid)`
    → 查 MenuData 节点的资源名 → `Resources.getIdentifier(name, null, pkg)`。
  - 菜单列表经过滤生成：`getSupportedItemList()` → 逐项检查其 `Value` 是否 ∈
    `controller.getSupportedValue(tag)` 返回列表，不在则**静默剔除**。
- **效果应用链**：菜单确认 → `PictureEffectPlusController.setValue(itemId, value)`（子类做特例后调 super）
  → `PictureEffectController.setValue` → `getEmptyParameters()` → `ParametersModifier.setPictureEffect(value)`
  → `CameraSetting.setParameters(pair)`（官方增量写参：只含本次 delta，merge 进期望态后一次下发）。
- **控制器生命周期**：`AbstractController` 提供 `onCameraSet/onCameraRemoving/onGetInitParameters/
  onGetTermParameters` 回调，由 `CameraSetting` 在相机打开/关闭时驱动。
- **伽马表（Extended Gamma Table）官方用法**（来自 up209d Bible.md，7 个官方应用逆向共识）：
  `createGammaTable()` → `write()` → `setExtendedGammaTable(table)` → **立即 `table.release()`**
  （release 的是 Java 侧硬件缓冲记账，不影响绑定与后续内容改写）。RX100M3 实测：表 2048B/1024 点；
  绑定后改写内容即时生效且安全；循环"建→绑→解绑→再建绑"会把 HAL 打进挂死区。

## 2. 改装设计总览

```
官方 apk ──(apktool d -r)──> 解包树
                                ├─ assets/MenuData.xml     ← 替换效果列表（纯文本）
                                ├─ smali/…PictureEffectController.smali   ← 钩子 A/B/C
                                ├─ smali/…PictureEffectPlusController.smali ← 钩子 D
                                ├─ smali/…BaseMenuService.smali            ← 钩子 E×2
                                ├─ smali/…PictureEffectPlusOptionMenuLayout.smali ← 钩子 F
                                ├─ smali/…PictureEffectPlus.smali          ← 钩子 G
                                └─ smali/com/sonylut/bridge/*   ← LUT 引擎（javac→dx→baksmali 注入）
```

数据流（每个箭头都对应一个钩子或一份资产，**三者对齐原则**见 §5）：

```
SD:/LUTS/*.CUB ──引擎扫描──> MenuIds.IDS（编译期固化）═╗
MenuData.xml 的 LUT 菜单项（OFF+N）════════════════════╣ 三者必须严格一致
sd_luts.txt（SD 实际清单，构建时生成上两者）═══════════╝
```

## 3. LUT 引擎（自有代码，全文在 `bridge-work/src/com/sonylut/bridge/`）

| 类 | 职责 |
|---|---|
| `Cube` | .cube 解析（LUT_3D_SIZE；跳过 TITLE/DOMAIN_/LUT_1D_SIZE 行） |
| `Decomposer` | 3D LUT → 伽马 1024 点 int[] + 3×3 矩阵 int[9]（×1024 定点） |
| `LutParams` | 参数容器 + v2 缓存（.LTC，8.3 短名，头部内嵌源文件 length/mtime 指纹） |
| `MenuIds` | **编译期固化的菜单 id 全集**（"lut-off" + 各 "lut-<stem>"），与 MenuData 同源生成 |
| `LutBridge` | 全部 smali 钩子的 Java 侧实现（见下） |

`LutBridge` 公开方法（与钩子一一对应）：

- `intercept(value, CameraSetting)Z` —— setValue 拦截：
  - `"lut-<stem>"` → 读缓存/分解 → `ensureBoundGamma` → `rewriteGamma` →
    一次增量参数写（`setPictureEffect("off")` + `setRGBMatrix`）；文件缺失则清管线兜底。返回 true。
  - `"lut-off"` → 清管线（恒等伽马+恒等矩阵）+ 代写 HAL 效果 off。返回 true（不能放行，原实现会把
    "lut-off" 当原生效果 id 交给 HAL）。
  - `"off"` → 清管线后放行原实现。其他 → 直接放行。
- `extendList(list)` —— **必须返回 MenuIds.IDS 全集**（去重合并进 HAL 原生列表）。
- `sanitizeBackup(v)` —— 备份效果值净化：非 `"off"`/`"lut-*"` 一律返回 `"lut-off"`。
- `bgDrawableResId(itemid)` —— lut-/退役 id 返回运行时解析的通用背景图资源 id，其余 -1 走原实现。
- `menuText/menuGuide(itemid)` —— `lut-off`→"OFF"/固定英文；`lut-<stem>`→词干大写/LUTS.TXT 介绍
  （运行时从 SD 读，**文案可改不用重打包**）；退役官方效果 id → 空串；其余 null 走原实现。
- `onTerm(camSet)` —— 相机关闭前：已绑定表写恒等内容 + 恒等矩阵。不解绑、不再 release。
- `prewarm(context)` —— 存 ApplicationContext + 后台线程把所有缺缓存的 cube 分解掉。

伽马生命周期（RX100M3 驱动脾气，实测定案）：进程内首次应用时 FIRSTBIND
（create + 恒等写 + bind + **立即 release**，官方 LVG 模式），之后一切切换只对已绑定表 rewrite
（0-2ms 热路径，菜单滚动实时预览就靠它）；退出时 onTerm 中性化，不 unbind 不 release。

## 4. 七处 smali 钩子（方法签名 + 注入点 + 语义）

> 通用手法两种：**头部注入**（在 `.prologue` 后插条件返回）与**改名+包装**（原方法改名
> `lutOrig*`，新建同名方法先调自有桥再决定是否调原实现）。改名法对多返回点方法最稳。
> 寄存器纪律：用 `.locals` 内且在原代码首次赋值前的寄存器做暂存（如 v0/v1）；
> `invoke-static` 参数类型必须与实际对象类型严格一致（p0 是控制器不是 CameraSetting——
> 必须先 `iget-object` 出 `mCamSet` 字段再传）。

**A. `PictureEffectController.setValue(Ljava/lang/String;Ljava/lang/String;)V`** —— 头部注入：
iget `mCamSet` → `LutBridge.intercept(p2, mCamSet)` → 返回 true 则 `return-void`。

**B. `PictureEffectController.getSupportedValue(Ljava/lang/String;)Ljava/util/List;`** —— 改名+包装：
包装方法调 `lutOrigGetSupportedValue(p1)` → 结果过 `LutBridge.extendList` 返回。

**C. `PictureEffectController` 新增 override `onCameraRemoving()V`** —— iget `mCamSet` →
`LutBridge.onTerm` → `invoke-super AbstractController;->onCameraRemoving()V`。

**D. `PictureEffectPlusController.getBackupEffectValue()Ljava/lang/String;`** —— 改名+包装：
结果过 `LutBridge.sanitizeBackup` 返回。

**E. `BaseMenuService.getMenuItemText / getMenuItemGuideText (Ljava/lang/String;)Ljava/lang/CharSequence;`**
—— 两方法头部注入：`LutBridge.menuText/menuGuide(p1)` 非空即返回。

**F. `PictureEffectPlusOptionMenuLayout.getBackgroundDrawable(Ljava/lang/String;)I`（private）** ——
改名+包装：`LutBridge.bgDrawableResId(p1)` ≥0 直接返回，否则调原实现。

**G. `PictureEffectPlus.onCreate(Landroid/os/Bundle;)V`** —— `invoke-super` 后插入
`LutBridge.prewarm(p0)`（p0=Activity，即 Context）。

**MenuData.xml 替换**：定位 `ApplicationTop` 节点下的 13 个官方效果 `<Layer2>`（正则按
`ItemId="..."` 匹配整块，含其 Layer3 子项），替换为 N+1 个叶子项。每项：

```xml
<Layer2
    CautionID="0"
    ConfigClass="com.sony.imaging.app.pictureeffectplus.shooting.camera.PictureEffectPlusController"
    ExecType="SET_VALUE"
    GuideRes="lutstr/lut-acros"
    IconRes="drawable/p_16_dd_parts_pe_menu_icon_normal_pop_color"
    ItemId="lut-acros"
    NextMenuID=""
    OptionStr="drawable/p_16_dd_parts_pe_image_pop_color"
    SelectedIconRes="drawable/p_16_dd_parts_pe_menu_icon_normal_pop_color"
    TextRes="lutstr/lut-acros"
    Value="lut-acros" />
```

要点：①`TextRes/GuideRes` 用 **`lutstr/` 假命名空间**——钩子 E 在资源解析前截获，从而完全绕开
资源编译；②图标/大图复用官方现有 drawable（新增资源才需要重编，见 §6）；③**OFF 项的
`Value` 必须等于 `ItemId`**（`"lut-off"`）：菜单高亮用 `getMenuItemList().indexOf(backupValue)`
按 ItemId 查找，官方应用里 ItemId==Value 是隐形约定；④LUT 文件名词干 ≤8 字母数字
（引擎缓存名 8.3 截断，长名不同文件会撞缓存）。

## 5. 三者对齐原则（本次最重要的教训）

菜单显示项 = MenuData 的项 ∩ getSupportedValue 放行的值。**任何一个 LUT 项若被过滤剔除，
适配器列表就会比滚轮（按 MenuData 全集计数）短，按方向键即 IndexOutOfBoundsException**。
因此：MenuData 的 LUT 项集合 == MenuIds.IDS == 构建时 SD 实际文件清单，三份产物由同一份
`sd_luts.txt` 生成（`inject2.py` + 一个小生成脚本）。SD 上后来增删文件 ⇒ 需重走构建
（运行时菜单是静态的）；SD 缺某文件 ⇒ 菜单项变"死项"，选中走清管线兜底（安全降级）。

## 6. 构建管线（`bridge-work/`，全部自有代码）

1. **解包**：`java -jar apktool.jar d -r -f -o peplus_r 原版.apk`
   —— **必须 `-r`**（不解码资源）：索尼 apk 有 432 处 `@android:` 非公开框架资源引用
   （索尼定制 framework 私有 drawable/attr），标准 framework 下 aapt 重编必炸；`-r` 模式
   resources.arsc 与 res/ 原样回填，二进制级不变。
2. **引擎**：`javac -encoding UTF-8 -source 1.6 -target 1.6`（bootclasspath=android-10 的
   android.jar，classpath=索尼 stub jar + 编译期 `CameraSetting` 占位类——只编译不打包，
   运行时由官方真类满足引用）→ `dx --dex`（只 dex 引擎包）→ 打 mini apk → `apktool d`
   反解出 smali → 拷入主树 `smali/com/sonylut/bridge/`。
   坑：javac 必须显式 `-encoding UTF-8`（Windows 默认 GBK 读中文注释炸）；
   注释里**严禁出现 `*/` 字样**（如写 "lut-*/退役" 会提前闭合 javadoc，报一堆"非法字符"假错误）。
3. **注入**：`inject2.py` 幂等执行资产替换与七钩子（每步先查标记防重复）。
4. **回编签名**：`apktool b` → `jarsigner` v1（`-sigalg SHA1withRSA -digestalg SHA1`，
   Android 2.3 只认 v1；JDK8+ 需临时放开 `jdk.jar.disabledAlgorithms=`）→ 装机。

## 7. 装机与真机调试闭环（WiFi adb）

- **装机**：`pmca-console.py install -d native -f xxx.apk`（MSC 模式即可；`-d native` 必带，
  否则 pyusb 无 backend 在枚举阶段崩）。或 WiFi adb 直装 `adb install -r`。
- **WiFi adb 调试闭环**（复刻崩溃类问题必备）：OpenMemories-Tweak 开 ADB →
  `adb connect <ip>:5555`。找 IP：本机开 TUN 代理时**端口扫描全假通**（代理代答 SYN），
  用 `arp -a` 找索尼 OUI（`fc-db-b3` 等）。然后：
  `logcat -c && logcat -v time > 本地文件`（后台流式落盘）→ `am start -n 包名/.PictureEffectPlus`
  远程启动 → 相机崩溃重启连接断开也不影响，**崩溃栈已在断连前落盘**。
- 坑：相机重启后 adbd 死，需重新开 Tweak；`input keyevent` 无法驱动菜单
  （索尼 KeyConverter 只认真实 scancode，合成键呈 keycode=0）——菜单导航仍需真机按键，
  但启动/日志/装包全自动。

## 8. 崩溃案例复盘（按发生顺序，全部经 logcat 定位）

官方框架对"菜单项不存在 / 值未知"**零防御**，任何替换菜单的实现都会连环踩雷。三个雷：

1. **默认备份效果值 NPE**：应用出厂默认备份 `part-color-plus`，启动时菜单 onResume 拿它查介绍文案
   → `getDisplayItem` 返回 null → 原版 `getMenuItemGuideText` 无 null 检查直接解引用 → NPE →
   LayoutUpdater 次生 `Application no longer exists` → 相机重启。
   **修复**：钩子 D 净化备份值 + 钩子 E 对退役 id 返回空串双保险。
2. **背景图 -1**：选项菜单 onResume/onItemSelected 调 `getBackgroundDrawable(itemId)`——
   官方硬编码 if 链只认 13 个效果 id，未知 id 返回 -1 → `setBackgroundResource(-1)` →
   `Resources$NotFoundException: Resource ID #0xffffffff` → 同款连环崩。
   **修复**：钩子 F。
3. **过滤列表与滚轮计数错位**：`extendList` 初版只追加"SD 上存在文件的 LUT id"，
   漏了无文件的 `lut-off`；且 MenuData 按仓库 25 个生成而 SD 只有 22 个 → 适配器 22 < 滚轮 26
   → 按下键 `IndexOutOfBoundsException: Invalid index 23, size is 22`。
   **修复**：§5 三者对齐 + MenuIds 固化全集。

通用教训：**替换菜单不是加项，是接管一条数据流**。完整走一遍
`备份值→恢复高亮(indexOf)→文案→图标→背景图→过滤放行→选中提交`，每个环节问"官方值换成我的 id 后
这段官方代码会发生什么"。

## 9. 验收基线（真机日志应看到）

```
LutBridge: scanLuts: N cubes
LutBridge: prewarm done N/N
LutBridge: gamma FIRSTBIND size=2048 pts=1024
（菜单滚动，每停一项）
LutBridge: gamma rewrite 0-2ms
（选中确认）
LutBridge: applied lut-xxx in ~400-700ms
（退出应用）
LutBridge: onTerm gamma neutralized 1ms
```
全程 `FATAL` 计数为 0；退出后 DisplayManager/DAConnectionManagerService 正常交还、相机不重启。

## 10. 已知边界与待验证

- SD 增删 LUT 文件需重走构建（菜单静态）；改名注意 8.3。
- 欧版机无中文字库：介绍文案用日文汉字（JIS X 0208 全覆盖校验：逐字符 `ch.encode('shift_jis')`
  不抛异常即安全；简体独有字→日文字形 調/飽/黒/膠/擬…，"徕卡"→"Leica"，标点 ・―）。
  LUTS.TXT 运行时读取，改文案只需拷 SD 不用重打包。
- 待验证：退出→重进（warm 进程）再选 LUT 是否触发伽马重绑挂死；成片 LUT 烧录确认。

## 11. 法律边界

- 不分发：改装成品 apk、官方 apk、官方代码的反编译摘录。
- 可分发：本改装工具链（注入脚本/引擎源码/构建脚本）、MenuData 的新增项 XML、本文。
  使用者自备官方 apk（Internet Archive 社区存档）并自行从相机提取编译 stub。
