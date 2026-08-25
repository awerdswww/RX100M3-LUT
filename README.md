# CUSTOM LUT — 索尼 RX100M3 自定义 LUT 胶片模拟

> 把标准 `.cube` 3D LUT 文件丢进 SD 卡，RX100M3 取景器实时预览影调，JPEG 直出带 LUT——给这台 2014 年的卡片机装上可无限扩展的「胶片模拟」。

**Fork 自 [starshine09074-ui/A6000-LUT](https://github.com/starshine09074-ui/A6000-LUT)**（原项目，A6000 专用），本仓库适配 **Sony DSC-RX100M3（RX100 III）**。

## RX100M3 适配状态

**固件 1.20 真机验证**（2026-08-24）：

| 功能 | 状态 | 说明 |
|---|---|---|
| LUT 应用/预览 | ✅ | 正常，伽马表 2048B/1024pt 与 A6000 相同 |
| LUT 拍照 | ✅ | 成片带 LUT 效果 |
| 控制环变焦 | ✅ | 原生逻辑（App 不干预） |
| 变焦杆 | ✅ | 原生逻辑（App 不干预） |
| **Fn 键参数调节** | ✅ | 曝光补偿/光圈/快门/ISO/白平衡 |
| **回看键对比** | ✅ | 按住临时关 LUT，松开恢复 |
| 退出 App | ✅ | 正常（见下方「退出重启」的修复说明） |
| 照片元数据 | ❌ | 已禁用（JPEG 打标会写坏照片，见下） |

## 已知问题（RX100M3 特有）

1. **二次进入卡在 LUT 计算**：App 退出后不重启相机，再次进入会卡在「正在计算新增LUT」。**Workaround**：用完一次想再进，先重启相机（关机再开）再打开 App。
2. **控制环光圈不可调**：控制环在 HAL 层被硬映射到变焦，`incrementAperture()`/`adjustAperture()` 调用被忽略（A/M 档也一样）。光圈只能在原生界面调。Fn 参数模式里的光圈/快门项对 RX100M3 实际不生效，保留仅为其他机型参考。
3. **C 键无映射**：C 键（自定义键）系统不向 App 派发按键事件，无法使用。
4. **曝光补偿项未实测生效**：Fn 模式走 `setPictureControlExposureShift`，写入无报错但实际曝光变化未确认，待进一步验证。

## 修复记录（相对上游 v0.3.2）

- **退出重启（已修复）**：病根是 `GammaTable` 泄漏——它是 `DeviceBuffer`（硬件缓冲区），官方用法要求 `setExtendedGammaTable(table)` 之后立刻 `table.release()`，上游漏调了。A6000 资源池大没暴露，RX100M3 上应用几次 LUT 后缓冲区耗尽，退出时原生界面接管即崩。补上后退出正常。参考资料：[up209d/Bible.md](https://github.com/up209d/open-memories-app-ai)（索尼官方应用逆向文档）。
- **照片打标已禁用**：原版拍照后原地重写 JPEG 插 COM 标签，RX100M3 上与机内写盘竞态会产出无法显示的损坏照片，故 `PHOTO_TAGGING_ENABLED=false` 关闭。LUT 效果烧在像素里，关闭不影响成片色彩，只是照片不再内嵌 LUT 名称标签。
- **应用 LUT 后变焦失灵（已修复）**：`writeMatrix` 的 `setParameters` 全量回写会把 HAL 的变焦驱动状态重置，修复为写前保存 `zoomDriveType`、写后恢复。
- **按键表适配**：RX100M3 实测 scancode：Fn=520、回看=207、左右=105/106；变焦杆 610/611、控制环 648/649 不消费（原生变焦依赖它们）。

## 按键映射（RX100M3）

| 按键 | 功能 |
|---|---|
| **Fn**（scan=520） | 进/出参数调节模式 |
| 方向键 **上下** | 参数模式下：切换参数项（曝光补偿/光圈/快门/ISO/白平衡LB/CC） |
| 方向键 **左右**（105/106） | 参数模式下：调当前参数值 |
| **中央键** | 参数模式下：退出参数模式；正常模式：选定/收起 LUT 列表 |
| **回看**（scan=207） | 按住：临时关 LUT 对比原图；松开：恢复 |
| 方向键 **上下** | 正常模式：浏览 LUT 列表 |
| 拨轮 2 | 正常模式：LUT 强度 0-100% |
| **删除键** | 关闭 LUT |
| **快门半按/全按** | 对焦/拍照 |
| **MENU** | 退出 App |
| 控制环/变焦杆 | 原生变焦（App 不干预） |

## 参数调节模式

按 **Fn** 进入，方向键上下切换，左右调值：

| 参数 | 调节范围 | 说明 |
|---|---|---|
| 曝光补偿 | ±1/3 EV 步进 | `setPictureControlExposureShift` |
| 光圈 | 步进 | `incrementAperture`/`decrementAperture`，仅 A/M 档 |
| 快门 | 步进 | `incrementShutterSpeed`/`decrementShutterSpeed`，仅 M 档 |
| ISO | 100-25600 | 标准档位 |
| 白平衡 LB | -100~+100 | 琥珀-蓝偏移 |
| 白平衡 CC | -100~+100 | 绿-品红偏移 |

## 技术要点（RX100M3 实测结论）

- **伽马表容量**：`getSize()` 实测 2048 字节（1024 点×16bit），与 A6000 相同——上游 README 里"表深可能有差异"的警告对 RX100M3 不成立
- **GammaTable 必须 release**：`createGammaTable()` 后必须 `table.release()`（官方 Liveview Grading 用法），否则硬件缓冲区泄漏——这是 RX100M3 上退出/关机时相机重启的根因，补上后正常
- **写盘护栏**：拍照后等 `StoreImageCompleteListener` 回调 + 文件大小稳定后再允许退出清理，避免打断机内写盘
- **变焦/光圈控制**：`startZoom`/`adjustAperture` 均被 HAL 拒绝（`PowerZoomListener` 报 STATUS_UNAVAILABLE），马达控制权在系统侧，App 只能让给原生逻辑

## 构建与安装

同上游，见 [docs/BUILD.md](docs/BUILD.md)。要点：

1. 用 [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) 装 [OpenMemories-Tweak](https://github.com/ma1co/OpenMemories-Tweak)（开 ADB）
2. 从相机提取 `/system/framework/sony.cameraex.odex` 生成 stub（本仓库已含 RX100M3 版：`stubs/sony_cameraex_stubs_rx100m3.jar`）
3. `bash build.sh` 编译，`pmca-console.py install -f CustomLut.apk` 装机

**注意**：RX100M3 的 USB 模式必须切到 **MTP** 才能装应用。

## LUT 素材

本仓库 `luts/` 目录内置 25 个转换自 [shenmintao/V-Log-Alchemy](https://github.com/shenmintao/V-Log-Alchemy)（Apache-2.0）的风格 LUT——富士胶片模拟、柯达/富士电影印片、哈苏、徕卡、ARRI、RED 全系，已做 sRGB→V-Log 输入适配，可直接拷入 SD 卡 `/LUTS/` 使用。各款特点详见 [docs/LUTS.md](docs/LUTS.md)。

## 致谢

- [starshine09074-ui/A6000-LUT](https://github.com/starshine09074-ui/A6000-LUT) — 上游项目，A6000 原始实现
- [shenmintao/V-Log-Alchemy](https://github.com/shenmintao/V-Log-Alchemy) — 跨品牌色彩科学 LUT（本仓库 `luts/` 素材来源，Apache-2.0）
- [ma1co/Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) — PMCA 安装通道逆向
- [up209d/open-memories-app-ai](https://github.com/up209d/open-memories-app-ai) — `Bible.md`：索尼官方应用逆向的 `CameraEx` API 目录
- [YahiaAngelo/Film-Luts](https://github.com/YahiaAngelo/Film-Luts) — 胶片模拟 LUT 素材

## 免责声明

同上游。本项目与索尼公司无任何关联，向相机安装第三方应用可能导致保修失效，操作风险自负。

## License

MIT
