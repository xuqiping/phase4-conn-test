# ART-ASSET-0003 最终生成提示词与像素清单（现代化高山流水版）

> 状态：待用户生成回填 ｜ 取代 ART-ASSET-0002（用户裁定 2026-08-28：弃水墨风，全部转现代化缥缈空灵表达）
> 设计语言：新中式现代极简——柔和渐变、雾感层次、极简山形剪影、光影氛围；**禁止**宣纸纹理/泼墨/枯笔/晕染颗粒等传统绘画肌理
> 文化锚点保留：高山、流水、云雾、知音、印章（全部现代化转译）
> 像素与体积：UI-0001 冻结值 ｜ 目录：FILE-STRUCTURE-0001（落盘到 `frontupdate/src/assets/art/`）

## 通用规则

- **统一负面提示词**（每张必加）：
  `水墨画, 国画, 宣纸纹理, 泼墨, 枯笔, 笔触, 书法字, 文字, 水印, logo, 具象人物, 人脸, 现代建筑, 高饱和, 霓虹, 赛博朋克, 卡通, 动漫, 3D渲染, 照片写实, 油画, 画框`
  EN: `ink wash painting, traditional Chinese painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame`
- **统一风格锚**：现代数字插画 / 新中式极简 / 大气透视（atmospheric perspective）/ 柔和渐变色层 / 雾中远山剪影 / 缥缈空灵的光线。
- **出图后处理**：压缩转 AVIF + WebP（squoosh.app / cwebp），验收对照下表体积上限。

---

## 任务 1 ｜ 登录页大背景（ART-ASSET-0001）

- **像素**：2560×1440（16:9）｜ ≤200KB/张 ｜ 落盘 `src/assets/art/login/splash-ink.{avif,webp}`（文件名保留 splash-ink 不变，免改代码）
- **构图铁律**：右 40% 低细节低对比（登录卡片位）；视觉重心在左侧/底部的层叠远山与云雾。
- **中文提示词**：
  `现代极简东方风格数字插画，高山流水意境，深夜黛蓝色调，左侧群山以柔和渐变的色层层叠渐远，山间缥缈云雾如轻纱流动，一道细长的溪流反光蜿蜒而下，右侧大面积空灵留白，蓝调时刻的柔和光线，大气透视，宁静空灵，新中式美学，高级渐变质感，无任何笔触纹理`
- **EN 提示词（MJ 推荐）**：
  `modern minimalist oriental digital illustration, high mountain flowing water mood, deep indigo-blue night palette, layered mountain silhouettes in soft gradient color layers receding to the left, ethereal mist drifting like gauze through valleys, a thin stream of light winding down, vast empty negative space on the right, soft blue-hour lighting, atmospheric perspective, serene and ethereal, new Chinese aesthetic, premium gradient quality, no brush texture --ar 16:9 --style raw`
- **自检**：右侧正中放 400×560px 卡片样试压，文字读不清则重生。

## 任务 2 ｜ 工作台晨昏云雾层 ×2（ART-ASSET-0002）

- **像素**：1920×1080（16:9）｜ ≤150KB/张 ｜ 落盘 `src/assets/art/workbench/mist-dawn.{avif,webp}`、`mist-dusk.{avif,webp}`
- **构图铁律**：全图近乎柔光渐变，中央与下 60% 无清晰主体——它是衬底不是画。
- **晨版中文**：`现代极简抽象背景，清晨山间云雾，淡青微暖色调，柔和的色带渐变如雾弥漫，极低对比度，微光自上方洒落，缥缈轻盈，无明确主体，新中式空灵美学，纯净高级`
- **晨版 EN**：`modern minimalist abstract background, early morning mountain mist, pale celadon with a hint of warmth, soft gradient bands drifting like fog, extremely low contrast, gentle light falling from above, ethereal and airy, no distinct subject, new Chinese aesthetic, pure and premium --ar 16:9 --style raw`
- **暮版中文**：`现代极简抽象背景，日暮山间云雾，黛蓝微紫色调，柔和色带渐变如暮霭沉降，极低对比度，光线渐暗而温柔，缥缈静谧，无明确主体，新中式空灵美学，纯净高级`
- **暮版 EN**：`modern minimalist abstract background, dusk mountain mist, deep indigo with muted purple transition, soft gradient bands settling like evening haze, extremely low contrast, fading tender light, ethereal and tranquil, no distinct subject, new Chinese aesthetic, pure and premium --ar 16:9 --style raw`

## 任务 3–5 ｜ 空状态插画 ×3（ART-ASSET-0003/0004/0005）

- **像素**：各 640×640（1:1）｜ ≤80KB/张 ｜ 落盘 `src/assets/art/empty/`
- **构图铁律**：主体居中偏上、极简小景；下方 25% 纯留白（放文案与按钮）。

| 场景 | 文件名 | 中文提示词 | EN 提示词 |
|---|---|---|---|
| 无数据 | `empty-data.{avif,webp}` | `现代极简新中式小景插画，空山与一叶极简扁舟剪影浮于渐变水面，远山以淡色渐变层叠，缥缈薄雾，大量留白，黛蓝月白配色，宁静空灵` | `modern minimalist new-Chinese vignette illustration, empty mountains and a tiny boat silhouette on gradient water, distant mountains in pale gradient layers, ethereal thin mist, vast negative space, indigo and moon-white palette, serene --ar 1:1 --style raw` |
| 无权限 | `empty-forbidden.{avif,webp}` | `现代极简新中式小景插画，层叠远山被柔和云雾轻轻遮蔽，一条渐变光径隐入雾中，可望不可即的距离感，大量留白，缥缈空灵，黛蓝配色` | `modern minimalist new-Chinese vignette illustration, layered distant mountains gently veiled by soft mist, a gradient light path fading into fog, sense of unreachable distance, vast negative space, ethereal, indigo palette --ar 1:1 --style raw` |
| 404 走丢 | `empty-404.{avif,webp}` | `现代极简新中式小景插画，一条渐变溪流尽头化作升腾的云雾，水穷云起，豁然开朗的缥缈意境，大量留白，黛蓝天青配色，空灵通透` | `modern minimalist new-Chinese vignette illustration, a gradient stream ending into rising clouds, "where water ends, clouds begin" mood, sudden ethereal openness, vast negative space, indigo and sky-cyan palette, translucent and airy --ar 1:1 --style raw` |

## 任务 6 ｜ 印章 Logo（ART-ASSET-0006，AI 意匠→人工 SVG）

- **像素**：AI 稿 1024×1024（1:1）仅作意匠参考 ｜ 最终 SVG ≤15KB ｜ 落盘 `src/assets/art/brand/seal-logo.svg`
- **中文提示词**：`现代极简印章设计，正方形朱红印章，篆体"知音"二字，几何化现代排版，印面均衡，边缘干净微做旧，纯正朱红，白底，居中`
- **EN**：`modern minimalist seal design, square vermilion red seal, two seal-script characters "知音", geometric modern composition, balanced, clean edges slightly weathered, pure vermilion on white, centered --ar 1:1 --style raw`
- **注意**：AI 篆字几乎必错——只取布局，字人工用篆体矢量重绘合成 SVG。

## 任务 7 ｜ ~~飞白枯笔笔触~~ 已取消

传统笔触随水墨风一并弃用；分隔采用极细浅色线/纯留白（DESIGN-TOKEN / ART-DIR-0001 已同步修订）。ART-ASSET-0008 编号废弃归档。

## 无需生成

- 宣纸纹理（ART-ASSET-0007）：随水墨风废弃；现代版的细微质感由 CSS 渐变/柔影表达，零资产。

## 回填后通知我验收

按 ART-QA-0001 逐项过：文件/像素/格式/体积/安全区/AI 瑕疵（伪文字·水印·多余元素）/文化适配（**不得出现传统水墨肌理**）/暗亮双主题实机效果。
