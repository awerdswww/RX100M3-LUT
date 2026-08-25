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
| 退出 App | ⚠️ | **偶发相机重启**（ISP 状态污染，固件级缺陷） |
| 照片元数据 | ❌ | 已禁用（与索尼媒体库竞态导致重启） |

## 已知问题（RX100M3 特有）

1. **退出重启**：应用 LUT 后退出 App（或关机），相机可能重启一次。重启后正常，LUT 效果消失（ISP 复位）。这是 RX100M3 固件对 `setExtendedGammaTable` 的实现缺陷——写入后 ISP 状态被污染，原生界面接管时初始化失败。无解，只能接受。
2. **控制环光圈不可调**：HAL 硬映射到变焦马达，`incrementAperture()`/`adjustAperture()` 被忽略（A/M 档也一样）。要调光圈只能进原生菜单。
3. **退出后二次进入卡死**：App 退出后 LUT 缓存目录状态异常，再次进入会卡在"正在计算新增LUT"。**Workaround**：重启相机后再进。
4. **C 键无映射**：RX100M3 的 C 键（自定义键）系统未派发按键事件，无法使用。

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

## 技术要点（RX100M3 差异）

- **伽马表容量**：`getSize()` 实测 2048 字节（1024 点×16bit），与 A6000 相同——README 里"表深可能有差异"的警告对 RX100M3 不成立
- **GammaTable 必须 release**：`createGammaTable()` 后必须 `table.release()`，否则硬件缓冲区泄漏导致 HAL 崩溃（A6000 资源多撑得住，RX100M3 漏几次就崩）
- **退出清理**：RX100M3 上 `setExtendedGammaTable(null)` 解绑也会崩，只能写恒等内容（线性伽马+恒等矩阵）保留绑定状态
- **写盘护栏**：拍照后需等 `StoreImageCompleteListener` 回调 + 文件稳定才能退出清理，否则打断索尼写盘导致数据库损坏+重启

## 构建与安装

同上游，见 [docs/BUILD.md](docs/BUILD.md)。要点：

1. 用 [Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) 装 [OpenMemories-Tweak](https://github.com/ma1co/OpenMemories-Tweak)（开 ADB）
2. 从相机提取 `/system/framework/sony.cameraex.odex` 生成 stub（本仓库已含 RX100M3 版：`stubs/sony_cameraex_stubs_rx100m3.jar`）
3. `bash build.sh` 编译，`pmca-console.py install -f CustomLut.apk` 装机

**注意**：RX100M3 的 USB 模式必须切到 **MTP** 才能装应用。

## 致谢

- [starshine09074-ui/A6000-LUT](https://github.com/starshine09074-ui/A6000-LUT) — 上游项目，A6000 原始实现
- [ma1co/Sony-PMCA-RE](https://github.com/ma1co/Sony-PMCA-RE) — PMCA 安装通道逆向
- [up209d/open-memories-app-ai](https://github.com/up209d/open-memories-app-ai) — `Bible.md`：索尼官方应用逆向的 `CameraEx` API 目录
- [YahiaAngelo/Film-Luts](https://github.com/YahiaAngelo/Film-Luts) — 胶片模拟 LUT 素材

## 免责声明

同上游。本项目与索尼公司无任何关联，向相机安装第三方应用可能导致保修失效，操作风险自负。

## License

MIT
