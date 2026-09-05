# RX100M3 平台官方 PMCA 应用逆向笔记

来源：Internet Archive 社区存档 `sony-playmemories-camera-apps / Sony RX100M3`（索尼商店 2025-08-31 关停前的抓取），7 个官方应用 apk 在 `offical_apks/`，jadx 1.4.7 反编译产物在 `offical_apks_src/`。分析日期 2026-09-05。

> SkyHDR 是 2015 年底的应用，不在 RX100M3 兼容名单（存档 RX100M3 目录也没有它）。本文基于同平台的 7 个官方应用，其中 6 个共享同一套 `com.sony.imaging.app.base` 拍照框架，是本机 API 用法的**签名级 ground truth**，可与 `stubs/sony_cameraex_stubs_rx100m3.jar`（619 方法目录）交叉验证。

## 应用清单

| 应用 | 包名 | 版本 | 备注 |
|---|---|---|---|
| 数码滤镜 | com.sony.imaging.app.digitalfilter | | |
| 照片修饰 | com.sony.imaging.app.photoretouch | | |
| **照片效果+** | com.sony.imaging.app.pictureeffectplus | 1.30 | 用户关注点，原生风格 UI 代表 |
| 人像美化 | com.sony.imaging.app.portraitbeauty | | |
| 光滑倒影 | com.sony.imaging.app.smoothreflection | | |
| **智能遥控** | com.sony.imaging.app.srctrl | 4.30 | 变焦协议最完整的参考实现 |
| 间隔拍摄 | com.sony.imaging.app.timelapse | | base 框架最全的一份拷贝 |

## 总体架构：官方应用如何"长在"相机上

1. **manifest 关键点**（pictureeffectplus/srctrl 相同）：
   - `android:theme="@style/Theme.Transparent"` —— 活动是**透明浮层**，原生取景层在下面继续活着；
   - `android.permission.ACCESS_SURFACE_FLINGER` —— 直接往 SurfaceFlinger 叠图层；
   - `launchMode="singleInstance"` + 强制 landscape；无 `uses-library`（Sony 框架类在平台 boot classpath 上）。
2. **Activity 基类链**：`XxxApp extends BaseApp extends com.sony.imaging.app.fw.AppRoot`（fw = 平台内置框架，不在 apk 里）。AppRoot 持有状态机（State/ContainerState）、按键FunctionTable、`USER_KEYCODE` 枚举。
3. **按键体系**：平台按键 `com.sony.scalar.sysutil.ScalarInput.getKeyStatus(AppRoot.USER_KEYCODE.XXX)`。官方枚举全表（对照实测 scancode：Fn=520、回看=207、左右=105/106、变焦杆=610/611、控制环=648/649）：
   `FN / MENU / PLAYBACK / DISP / CENTER / UP/DOWN/LEFT/RIGHT(+斜向) / ZOOM_LEVER_TELE / ZOOM_LEVER_WIDE / RING_CLOCKWISE / RING_COUNTERCW / DIAL1_LEFT/RIGHT(_STATUS) / DIAL2_* / DIAL3_* / EV_DIAL_CHANGED / IRIS_DIAL_CHANGED / MODE_DIAL_* / FOCUS_MODE_DIAL_* / S1_ON/OFF / S2_ON/OFF / AEL / AF_MF / ISO / WB / DRIVE_MODE / EV_COMPENSATION / EXPAND_FOCUS / PEAKING / ZEBRA / SK1 / SK2 / MOVIE_REC / IR_SHUTTER / LENS_ATTACH/DETACH / WATER_HOUSING ...`
4. **模式拨盘共存**：`ModeDialDetector` 轮询 `MODE_DIAL_CHANGED`，用户把拨盘转出应用模式即自动退出——官方应用与物理拨盘的共存方式。

## "Fn 呼出原版快捷菜单（部分功能被屏蔽）"的真相

**那不是原生菜单，是应用自绘的仿原生菜单。** 证据链：

- Fn 键由平台状态机派发到当前状态的 `pushedFnKey()` handler（`base/shooting/trigger/*KeyHandler.java`）；
- handler 查 `FunctionTable`（`NormalFunctionTable.java`，72 行，直接可读）：`CustomizableFunction` 枚举 → `FunctionInfo(tag, Controller.class)`。可注册项全集：曝光模式/闪光/人脸检测/对焦模式/A F区域/创意风格/照片效果/测光/闪光补偿/DRO-HDR/白平衡/驱动模式/ISO/画质/尺寸/比例/AEL/Af-Mf/Disp/EV/快门增减/光圈增减/ISO增减/MF辅助/数字变焦/场景选择…；
- **应用只注册自己支持的项** → 菜单里其他项不出现/不可用，这就是"屏蔽了一部分功能"的原因（不是系统压制，是白名单）；
- 菜单 UI 是**标准 Android View 自绘**（`base/menu/layout/*`：LayoutInflater + `CursorableGridView` 等），视觉语言与原生一致，由 `MenuState/BaseMenuService` 管理。

对 A6000-LUT 的含义：想要"原生感 Fn 菜单"，正确路线不是劫持原生 UI，而是**仿造这套 base.menu 框架**（或继续用现有参数 HUD，把数据源换成下文定案的 API）。

## 变焦/焦距：协议定案（三方交叉验证）

1. **`CameraEx.startZoom(direction, speed)` 参数语义**：
   - `direction`：**0=TELE（望远）, 1=WIDE（广角）**。铁证：RX100M3 固件 stub 常量 `CameraEx.ZOOM_DIRECTION_TELE = 0`（ConstantValue 0x0，`cameraex_dx.txt`）、`ZOOM_DIRECTION_WIDE = 1`；官方应用 `DigitalZoomController.DIRECTION_TELE=0/DIRECTION_WIDE=1` 且只放行 `{0,1}`；srctrl 遥控 `ZOOM_DIRECTION_IN→0, OUT→1`。三方一致。
   - `speed`：`0 ≤ speed ≤ getMaxZoomSpeed()`（ParametersModifier，本机实测 max=8，与官方 guard 一致）。**负数不在协议内**——此前 `(-1,x)` 抛 RuntimeException 的原因。
   - ⚠️ v0.5.11 曾"实测对调"为 TELE=1/WIDE=0，与固件常量矛盾。当时测试传的是 `(0,±1)`（speed 位置传了负数，越出协议），方向观感可能被污染。**建议用 (0,8)/(1,8)+stopZoom 干净重测后再信谁。**
2. **官方驱动配方（srctrl `CameraOperationZoom`）**：
   - 持续（按住）：`startZoom(dir, max/8)` **一次调用**，松开才 `stopZoom()` —— HAL 会持续驱动，不需要连发脉冲；
   - 点动（短推）：`startZoom(dir, max/4)` + `Thread.sleep(100)` + `stopZoom()`。
   - 与本项目"150ms/80ms 续发脉冲"对照：官方根本不续发。若单发不持续，疑点应放在 speed 档位与 inhibition 状态，而不是加脉冲。
3. **数字变焦**：`setDigitalZoom(mag)` 单位 = ×100（100=1.0x，猜测已被官方代码证实），`resetDigitalZoom()` 复位。官方步进表 **{100,140,200,280,400,560,800}（√2 倍）**，超出后 ×1.4142 续伸至 `getMaxDigitalZoomMagnification(type)`（ParametersModifier，本机存在）。步进跳档用 `setStepZoomMagnification(STEP_ZOOM_UPPER/LOWER)`。
4. **回读（事件驱动，无需轮询）**：`setZoomChangeListener(ZoomInfo{opticalMagnification, digitalMagnification, digitalZoomType, stopped, opticalPosition, digitalPosition})`。另有 `enableSettingChangedTypes(new int[]{15})` + `setSettingChangedListener` 通道。
5. **等效焦距 mm 显示官方公式**（`CameraSetting.calcFocalLengthByZoomInfo`）：
   `mm = FocalLength.wide + (FocalLength.tele − FocalLength.wide) × opticalPosition / 100`（`getLensInfo()`，`isSupportedLensInfo()` 先查）。比本项目现在的 mag 换算更正宗。本机还有 `setFocalLengthChangeListener`（`CAMERAEX_MSG_FOCAL_LENGTH_CHANGE`）。
6. **变焦杆设备**：`ScalarProperties.getInt("device.zoom.lever") == 1` 时官方应用**主动不提供变焦菜单**（`isSupportZoomLever`），变焦全部交给原生层驱动——与本项目"610/611 原生直驱、App 不碰"的结论一致。

## 曝光补偿（EV）：通道定案

官方 `ExposureCompensationController` 用的是 **Android 标准 `Camera.Parameters`** API，不是 ParametersModifier 的 picture-control-exposure-shift：

- 写：`params.first.setExposureCompensation(index)`（index 为步数，非 EV 值）
- 范围/步长：`getMaxExposureCompensation() / getMinExposureCompensation() / getExposureCompensationStep()`（全标准 API）
- 读回：`getExposureCompensation()`；显示值 = index × step
- 增减：`incrementExposureCompensation()/decrementExposureCompensation()`（min/max 夹紧后再写）

本项目 v0.5.5 起的"本地伽马变换模拟 EV"可以退休了——**标准通道在 RX100M3 上是活的**（官方应用就用它）。此前"EV 无效"的结论是用错了通道（picture-control-exposure-shift）。

## 官方"增量写参"机制（修 zoomDriveType 重置问题的正解）

`CameraSetting` 写参流程（`setParameters(Pair)`）：

1. 应用每次改动从 `getEmptyParameters()` 起步（内部有 `IS_EMPTY_PARAMETERS` 标记键，只装本次改动）；
2. `write(new, backup)`：把改动 merge 进应用维护的 **backup（期望状态累积器，源自初始 HAL 参数）**；
3. `form(...)` 后**一次** `Camera.setParameters(合并态)` 下发；未碰过的键保持 HAL 初始值；
4. 立刻 `getCameraParameterPair()` 回读 HAL 真值，用 `ParameterComparator` 列表 diff 出变化项分发通知（DRIVE_MODE/ExposureCompensation/DRO/WB/创意风格/图片尺寸…每项一个 comparator）。

本项目 writeMatrix 的"全量回写冲掉 zoomDriveType"问题，官方答案是**维护期望态累积器**而不是"写前保存写后恢复"。

## 白平衡 / 光圈 / 快门 / ISO 官方通道

- WB：官方用 `setLightBalanceForWhiteBalance / setColorCompensationForWhiteBalance / setColorTemperatureForWhiteBalance`（本机 stub 均存在）。本项目用的 `setWhiteBalanceShiftLB/CC` 也在 stub 里（4 处）——两条通道并存，官方菜单走前者。
- 光圈/快门：`incrementAperture/decrementAperture/incrementShutterSpeed/decrementShutterSpeed` + `setApertureChangeListener/setShutterSpeedChangeListener` 回读 + `getShutterSpeedInfo`。与本项目现用一致（用法已对）。
- ISO：`setISOSensitivity` + `getISOSensitivityAuto`（判断 AUTO 档）。

## 对 A6000-LUT 的落地建议

1. 变焦协议改为官方定案：`startZoom(0|1, speed)`，按住=单发 `max/8`，松开 `stopZoom()`；点动=`max/4`+100ms+stop。先做一次 (0,8)/(1,8) 方向验证实验，再决定是否撤销 v0.5.11 的方向对调。
2. EV 改走标准 `Camera.Parameters.setExposureCompensation`，删本地伽马变换（真通道优先，本地变换只在标准通道也死时兜底）。
3. 焦距 HUD 改 `LensInfo` 插值公式，回读改事件驱动（ZoomChangeListener 已在用，补 `stopped` 字段判断到位）。
4. 焦段拨盘若要"24/28/35/50/70 手感"，官方等价物是数字变焦 √2 步进表（光学行程到头后接着跳），`setDigitalZoom` ×100。
5. 写参引入期望态累积器（EmptyParameters→merge→一次下发→回读 diff），替代"写前保存写后恢复"。
6. Fn 参数模式的 UI 数据绑定可参考 `NormalFunctionTable`+Controller 模式：每参数一个 Controller（supported/available/current/set 四件套）。
7. `device.zoom.lever==1` 时学官方：不跟变焦杆抢事件。

## 提取渠道备注

- 索尼 PMCA 商店 2025-08-31 关停；`pmca market` 路线已死（需 portalid 且商店下线）。
- 存档：archive.org `sony-playmemories-camera-apps`（按机型目录，RX100M3 有 7 个）。
- 从相机提取已装应用（如需验证本机版本差异）：Tweak 的 WiFi ADB（`adb pull /data/app/...`）或 pmca `updatershell`（MSC 模式免驱动，可挂 /data 拷 apk 到 SD）。本次未走这两条（存档已覆盖需求）。
- 反编译：`D:\pmca-tool\jadx-1.4.7`（JDK8 可跑），命令样例见会话记录；srctrl 有 2 个方法反编译失败，不影响分析。
