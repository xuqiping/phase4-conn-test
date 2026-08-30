# ART-ASSET-0002 最终生成提示词与像素清单

> 状态：待用户生成回填 ｜ 取代 ART-ASSET-0001 任务包（提示词已按内置知识库深化）
> 知识引用：55 艺术表现形式（留白 AF.101 / 疏密 AF.103 / 水墨晕染 AF.001 / 泼墨 AF.002 / 宣纸 AF.166）、07 氛围（禅寂 AT.85 / 空灵仙气 AT.86 / 蓝调时刻 AT.6 / 开阔旷远 AT.19）、30 意境（富春山居图·千里江山图·高山流水知音）、26 传统色（黛蓝/月白/天青/石青/朱砂）
> 像素与体积：UI-0001 第二节冻结值 ｜ 目录：FILE-STRUCTURE-0001

## 通用规则

- **统一负面提示词**（每张必加）：`鲜艳色彩, 高饱和, 霓虹, 赛博朋克, 具象人物, 人脸, 现代建筑, 桥梁, 船只细节, 文字, 书法字, 印章, 水印, logo, 卡通, 动漫, 3D渲染, 油画, 照片写实, 边框, 画框`
  EN: `vivid colors, high saturation, neon, cyberpunk, people, faces, modern buildings, text, calligraphy characters, seal, watermark, logo, cartoon, anime, 3d render, oil painting, photorealistic, frame, border`
- **统一风格锚**：传统中国水墨（浅绛/水墨山水），参考《富春山居图》的淡墨与留白；不用青绿重彩铺满，石青/石绿只作远峰点染。
- **出图后处理**：压缩转 AVIF + WebP 双格式（可用 squoosh.app / cwebp）；验收对照 UI-0001 体积上限。

---

## 任务 1 ｜ 登录页泼墨大背景（ART-ASSET-0001）

- **像素**：2560×1440（16:9）｜ ≤200KB/张 ｜ 落盘 `src/assets/art/login/splash-ink.{avif,webp}`
- **构图铁律**：右 40% 低细节低对比（登录卡片压在上面）；视觉重心在左侧与底部远山。
- **中文提示词**：
  `传统中国水墨山水画，泼墨与晕染技法，深夜黛蓝色调（接近黑蓝的墨色），左侧群山浓墨层叠渐远，山间云雾氤氲流动，右侧大面积留白空远，极简主义，禅意，蓝调时刻的柔光，宣纸纹理质感，大气磅礴而空灵，富春山居图式的淡墨层次，杰作`
- **EN 提示词（MJ 推荐）**：
  `traditional Chinese ink wash painting, splash-ink shan shui landscape, deep indigo-blue night palette, layered ink mountains receding on the left, mist flowing through valleys, vast negative space on the right, minimalist, zen, soft blue-hour light, xuan rice paper texture, ethereal and vast, Fuchun Mountains style pale ink layers, masterpiece --ar 16:9 --style raw`
- **自检**：放一块 400×560px 卡片样在右侧正中试压——文字若读不清则作废重生。

## 任务 2 ｜ 工作台晨昏云雾层 ×2（ART-ASSET-0002）

- **像素**：1920×1080（16:9）｜ ≤150KB/张 ｜ 落盘 `src/assets/art/workbench/mist-dawn.{avif,webp}`、`mist-dusk.{avif,webp}`
- **构图铁律**：全图近乎纯色渐变，中央与下 60% 无任何清晰主体——它是衬底不是画。
- **晨版中文**：`水墨云雾，氤氲朦胧，清晨淡青微暖色调（月白泛一点点暖），极低的对比度，柔和天光自上方弥漫，大面积空白，抽象空灵，无明确主体，极简`
- **晨版 EN**：`ink wash mist clouds, hazy and diffused, early morning pale celadon with a hint of warmth, extremely low contrast, soft skylight from above, vast emptiness, abstract ethereal, no distinct subject, minimalist --ar 16:9 --style raw`
- **暮版中文**：`水墨云雾，氤氲朦胧，傍晚黛蓝微紫色调（暮山紫过渡），极低对比度，光线渐暗而柔和，大面积空白，抽象空灵，无明确主体，极简`
- **暮版 EN**：`ink wash mist clouds, hazy and diffused, dusk deep indigo with muted purple transition, extremely low contrast, fading soft light, vast emptiness, abstract ethereal, no distinct subject, minimalist --ar 16:9 --style raw`

## 任务 3–5 ｜ 空状态水墨小品（ART-ASSET-0003/0004/0005）

- **像素**：各 640×640（1:1）｜ ≤80KB/张 ｜ 落盘 `src/assets/art/empty/`
- **构图铁律**：主体居中偏上，下方 25% 纯留白（放引导文案与按钮）。

| 场景 | 文件名 | 中文提示词 | EN 提示词 |
|---|---|---|---|
| 无数据 | `empty-data.{avif,webp}` | `极简水墨小品，空山孤舟，独钓寒江雪意境，一叶扁舟浮于空旷水面，远山淡墨一抹，大量留白，黑白灰微蓝，静谧空灵` | `minimalist Chinese ink painting, lone small boat on empty water, snow-less cold river solitude mood, faint distant mountain in pale ink, vast negative space, black grey with a hint of blue, serene ethereal --ar 1:1 --style raw` |
| 无权限 | `empty-forbidden.{avif,webp}` | `极简水墨小品，远山重重阻隔，云雾缭绕封住山间小径，路径隐没于雾中，可望而不可即的距离感，大量留白，淡墨` | `minimalist Chinese ink painting, layered distant mountains blocking the way, mist sealing a hidden mountain path, sense of unreachable distance, vast negative space, pale ink --ar 1:1 --style raw` |
| 404 走丢 | `empty-404.{avif,webp}` | `极简水墨小品，行到水穷处坐看云起时，溪流尽头消散于云雾，云雾自谷底升腾，豁然开朗的意境，大量留白，淡墨微青` | `minimalist Chinese ink painting, a stream ending into rising clouds from the valley, "walk to the water's end, sit and watch clouds rise" mood, sudden openness, vast negative space, pale ink with faint celadon --ar 1:1 --style raw` |

## 任务 6 ｜ 印章意匠稿（ART-ASSET-0006，AI 出稿→人工 SVG 化）

- **像素**：AI 稿 1024×1024（1:1）仅作意匠参考 ｜ 最终 SVG ≤15KB ｜ 落盘 `src/assets/art/brand/seal-logo.svg`
- **中文提示词**：`中国篆刻印章，正方形朱文（阳文）红印，篆书"知音"二字，白底红印，印面饱满均衡，边缘轻微做旧斑驳，传统印泥朱红色，纯正篆刻风格，居中`
- **EN**：`Chinese seal carving, square red zhusha cinnabar seal, two seal-script characters "知音", vermilion red on white, balanced composition, slightly weathered edges, traditional seal carving style, centered --ar 1:1 --style raw`
- **注意**：AI 篆字几乎必错——只取布局与斑驳感，字必须人工用篆体矢量重绘后合成 SVG。

## 任务 7 ｜ 飞白枯笔笔触 ×3（ART-ASSET-0008，AI 出稿→SVG 化）

- **像素**：AI 稿 2048×68（30:1 横条）｜ 最终 SVG 视图框 1200×40 ≤10KB ｜ 落盘 `src/assets/art/texture/brush-divider-01..03.svg`
- **中文提示词**：`书法枯笔横向一笔，由左至右由浓到枯，飞白肌理清晰可见，纯黑墨色，纯白底，笔触两端自然渐隐，极简，三支不同形态（平直横扫/微微上扬/中间略顿）`
- **EN**：`single horizontal dry-brush calligraphy stroke, fading from wet to dry left to right, visible flying-white texture, pure black ink on white, natural fade at both ends, minimalist --ar 30:1 --style raw`
- **注意**：生成后抠去白底→转 SVG（Inkscape 描摹或 vectorizer.ai），保留飞白颗粒。

## 回填后通知我验收

按 ART-QA-0001 逐项过：文件/像素/格式/体积/安全区/AI 瑕疵（伪文字·水印·多余元素）/文化适配/暗亮双主题实机效果。
