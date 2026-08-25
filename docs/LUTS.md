# 内置 LUT 目录（luts/）

本目录收录 25 个转换自 [shenmintao/V-Log-Alchemy](https://github.com/shenmintao/V-Log-Alchemy) 的风格 LUT，可直接拷入 SD 卡 `/LUTS/` 使用。

## 来源与许可

- 原 LUT 版权：V-Log Alchemy 项目，**Apache-2.0** 许可；
- 原 LUT 输入为松下 V-Log/V-Gamut，本仓库版本已用数学构造的 **sRGB/Rec.709 → V-Log/V-Gamut 适配器** 做了前置转换并重采样到 33 点网格（改动内容：输入色彩空间适配 + 重采样，风格部分未动）；
- 跨品牌色彩模拟存在传感器光谱响应差异（原作者亦有声明），极端光线/高饱和场景下会有偏差；
- 文件名已按相机 SD 卡的 8.3 短文件名要求处理；
- 建议按需拷贝：App 首次遇到新 LUT 需机内分解（每个约 6.5–8 秒），一次拷太多会等很久。

## 使用前提

机身创意风格用**标准（Standard）**，关闭 DRO/自动 HDR——这些功能会改变 LUT 脚下的影调曲线。强度不满意可在 App 内用拨轮调 0–100%。

## 富士胶片模拟（原 F-Log2C 内核）

| 文件 | 原型 | 特点 |
|---|---|---|
| `CNEG.CUB` | Classic Neg. | 经典负片。高对比、暗部偏青、饱和克制，街头随拍最出片的一款 |
| `CCHROME.CUB` | Classic Chrome | 低饱和、冷调柔和的纪实味，像褪色的旧杂志 |
| `PROVIA.CUB` | Provia | 标准反转片，中性准确，万金油 |
| `VELVIA.CUB` | Velvia | 高饱和高对比风景反转片，蓝天绿地冲击力极强 |
| `ASTIA.CUB` | Astia | 柔和反转片，肤色讨喜，人像首选 |
| `REALAACE.CUB` | Reala Ace | 富士较新的负片模拟，准确中带柔和，比 Provia 更有"胶片感" |
| `PRONEG.CUB` | Pro Neg. Std | 影棚人像负片，影调平、肤还原准 |
| `ACROS.CUB` | Acros | 黑白。颗粒细腻、影调丰富，比普通黑白模式层次好 |
| `ETERNA.CUB` | Eterna | 富士电影胶片，低饱和、平调、大后期空间感 |
| `ETERNABB.CUB` | Eterna 漂白 | 漂白跳过（Bleach Bypass）工艺：低饱和 + 高反差 + 硬调，末世感 |

## 电影印片（原 Cineon 内核）

| 文件 | 原型 | 特点 |
|---|---|---|
| `K2383.CUB` | Kodak 2383 | 好莱坞最常用的印片胶片。黑位抬升的"胶片灰底"，暖高光、青阴影 |
| `F3513DI.CUB` | Fuji 3513 DI | 富士印片胶片，与 2383 同用途但富士色彩取向，稍清冷 |

## 哈苏（原 X2D Phocus 内核，HNCS 自然色彩科学）

| 文件 | 输出制式 | 特点 |
|---|---|---|
| `HBS709.CUB` | Rec.709 | 哈苏标准色彩，准确克制、灰阶过渡细腻 |
| `HBSRGB.CUB` | sRGB | 同上，sRGB 输出基准，照片上观感与 709 版差异细微 |
| `HBN709.CUB` | Rec.709 | 哈苏自然（Nature）色彩，比标准更柔更淡 |
| `HBNRGB.CUB` | sRGB | 同上，sRGB 输出基准 |

## 徕卡（原 L-Log 内核）

| 文件 | 原型 | 特点 |
|---|---|---|
| `LEICACLS.CUB` | Leica Classic | 微冷高对比的"德味"，暗部沉稳 |
| `LEICANAT.CUB` | Leica Natural | 徕卡自然模式，平实还原 |

## ARRI

| 文件 | 原型 | 特点 |
|---|---|---|
| `ARRI.CUB` | ARRI Classic 709 | 电影机标准渲染：著名的高光滚降和肤色处理，画面"润" |

## 尼康 / RED（原 N-Log/IPP2 内核）

| 文件 | 原型 | 特点 |
|---|---|---|
| `NREC709.CUB` | Nikon N-Log→709 | 尼康官方还原风格，中性略艳 |
| `REDACHRO.CUB` | RED Achromic | RED 的黑白渲染，反差硬朗 |
| `REDFB.CUB` | RED FilmBias | 胶片偏色基底，整体暖调 |
| `REDFBBB.CUB` | FilmBias BleachBypass | 胶片偏色 + 漂白工艺，低饱和硬调 |
| `REDFBO.CUB` | FilmBias Offset | 偏色最明显的一款，暖绿色罩（连灰阶都故意染色），风格化慎用 |
| `REDMC.CUB` | RED Rec709 Medium Contrast Soft | RED 的 709 柔和中反差渲染 |
