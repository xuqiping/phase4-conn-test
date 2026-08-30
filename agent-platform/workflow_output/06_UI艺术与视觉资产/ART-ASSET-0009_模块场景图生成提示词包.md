# ART-ASSET-0009 · 模块场景图生成提示词包（雾中浮岛 12 景）

> 状态：**✅ 双主题 24 幅全部在线（宣纸版 AI 生成回填完毕）**（2026-08-30：夜墨 12 幅用户生成（0.35 压淡+融边纱罩）；宣纸 12 幅用户按附录 B v2 提示词生成（PNG 存 `宣纸-图片/`，母版备份 `D:\dev-cache\art-masters\light-ai\`），转码 AVIF 8.5-20KB/张全达标，覆盖程序派生版（派生 v7 与脚本 derive_light.py 保留作兜底）；Playwright 实拍六页验收：亮底/山根坐底/中部无黑团全过。遗留观察项：#5 video-edit 峰顶有微小橙点（疑似小人），0.6 浓度下不可见，后续若显眼可单张重生。宣纸版图浓度 0.6/admin 0.3；纱罩底部渐隐 6%；浓淡旋钮=CSS opacity）
> 验收调整（ART-QA-0002 §4 实测）：生成图偏浓 → 代码层 opacity 0.35（admin lite 0.18）+ 四缘融边纱罩消矩形边；PNG 原图母版存 `D:\dev-cache\art-masters\`（96MB，不入库）
> 上游：ART-DIR-0002R（方向二雾中浮岛）、STYLE-DNA-0002（构图基因）、DESIGN-TOKEN-0002（12 模块色/诗签/山形真值表）
> 风格母版沿用 ART-ASSET-0003：新中式现代极简，**禁止**水墨/宣纸纹理/泼墨/枯笔/笔触肌理

## 0. 范围与降级策略（先读）

- **必做 12 幅：夜墨版**（深底 #151D29，默认主题）
- **可选 12 幅：宣纸版**（亮底 #F5F1E6）——同一提示词把基底与色调用「宣纸版变量行」替换即可；**不生成也不影响上线**：宣纸主题自动回退纯 CSS 场景（现状已在线，零破坏）
- 代码接入方式：ModuleScene 读 `--scene-img` 钩子，图在则叠图、不在则 CSS 渐变兜底；reduced-motion 无影响（场景本来无动画）

## 1. 通用规则（每张必守）

- **像素**：1920×1080（16:9）｜ **体积**：≤120KB AVIF / ≤150KB WebP（squoosh.app 转码，双格式各一）
- **落盘**：`frontupdate/src/assets/art/scenes/scene-{key}.{avif,webp}`；宣纸版 `scene-{key}-light.{avif,webp}`
- **统一负面提示词**（沿用 0003，追加场景专属三条）：
  `水墨画, 国画, 宣纸纹理, 泼墨, 枯笔, 笔触, 书法字, 文字, 水印, logo, 具象人物, 人脸, 现代建筑, 高饱和, 霓虹, 赛博朋克, 卡通, 动漫, 3D渲染, 照片写实, 油画, 画框, 明显焦点主体, 锐利细节, 前景特写`
  EN: `ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up`
- **构图铁律**（与 CSS 场景同构，图即场景的精致化）：
  1. 山形**只许出现在底部 1/5 区域**，中上部严禁任何山体/主体
  2. 上方 45% 渐隐为接近纯基底色的夜空/天空（模块色微微染色）
  3. **右缘 10% 竖条保持干净**（竖排诗签压在上面）
  4. 中央大面积空——这是内容卡的衬底，不是挂画
  5. 整体对比度压到「险些看不见」；若生成图太抢戏，后期把不透明度降到 40-60% 再压缩
- **基底色号**：夜墨版 `#151D29`（深蓝黑）｜ 宣纸版 `#F5F1E6`（暖米白，山形用淡墨灰、不用模块色）

## 2. 提示词脚手架

**中文模板**（把【】内替换为下表变量）：
`现代极简新中式数字插画背景，深夜黛蓝基底（#151D29），【色名】色调微染，【意象句】，底部五分之一处【山形】以柔和渐变剪影呈现，上方渐隐为纯净夜空，中央与右缘大面积空灵留白，缥缈薄雾，极低对比度，大气透视，淡雅到几乎看不见，高级渐变质感，无笔触纹理`

**EN 模板（MJ 推荐）**：
`modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with 【color EN】, 【imagery EN】, bottom fifth shows 【ridge EN】 as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw`

**宣纸版**：见附录 B v2（2026-08-29 重写）——独立脚手架与专属负面，三条实机铁律内置：整幅亮底 / 山根坐底边 / 墨色沉下半幅向上渐淡且中部禁黑团。

## 3. 十二景变量表（山形/色值以 DESIGN-TOKEN-0002 为唯一真值）

| # | key（文件名） | 模块 | 色名 RGB | 山形（种子） | 意象句（中文） | imagery EN |
|---|---|---|---|---|---|---|
| 1 | scene-chat | 智能对话 | 天青 143,188,212 | 孤峰偏左，右侧矮岭远陪（种子2） | 一座孤峰静立左侧，一道细长溪流反光蜿蜒流向右下，静候知音 | sky-cyan | a lone peak standing at left, a thin stream of light winding down to lower right, quietly awaiting a kindred soul |
| 2 | scene-knowledge | 知识库 | 石青 78,127,166 | 连绵三岭横列（种子3） | 三座层叠山峦如堆叠的书卷，谷底一缕清泉自山间流出，源头活水 | mineral blue | three layered ridges stacked like volumes of books, a wisp of spring water flowing from the valley, living water from the source |
| 3 | scene-video-gen | 视频生成 | 暮山紫 138,128,163 | 双峰对峙（种子1） | 双峰之间云雾缓缓流动如光影推移，行云流影 | dusk-mountain purple | mist drifting slowly between two facing peaks like shifting light and shadow, flowing clouds and streaming light |
| 4 | scene-image-gen | 图片生成 | 天水碧 124,181,164 | 平远水岸宽矮丘（种子4） | 远山如黛，近水含烟，平静水面倒映极淡山影 | celadon water-green | distant mountains like dark eyebrows, near water veiled in mist, calm water mirroring the faintest mountain reflections |
| 5 | scene-video-edit | 视频剪辑 | 深石青 58,95,125 | 孤峰偏左（种子2） | 层叠云霞如幕布被裁开一道柔光缝隙，裁云剪霞 | deep mineral cyan | layered clouds and mist like curtains parted by a soft seam of light, cutting clouds and trimming haze |
| 6 | scene-canvas | 无限画布 | 月白灰蓝 154,171,188 | 平远水岸（种子4） | 空山新雨后，极淡远山与空濛水汽，画面九成留白 | moon-white grey-blue | empty mountains after fresh rain, faintest distant ridges and misty vapor, ninety percent of the canvas left blank |
| 7 | scene-assets | 资产库 | 黛蓝浅 53,104,127 | 连绵三岭环抱（种子3） | 群山环抱成谷，如海纳百宝，谷口薄雾轻掩 | pale indigo | mountains encircling a valley like the sea gathering a hundred treasures, valley mouth lightly veiled in thin mist |
| 8 | scene-wallet | 我的钱包 | 青碧泉 99,185,154 | 平远水岸（种子4） | 清泉石上流，溪流转折处一点微光，水底卵石隐隐 | spring jade-green | a clear spring flowing over stones, a glint of light where the stream bends, pebbles faintly visible underwater |
| 9 | scene-project-groups | 项目组 | 雅集青 110,160,138 | 双峰对峙（种子1） | 群峰错落相聚如贤者雅集，山间雾气相连 | gathering cyan | scattered peaks gathered like sages at an elegant meeting, mist connecting the mountains |
| 10 | scene-feedback | 反馈帮助 | 暮山紫浅 169,159,189 | 孤峰偏左（种子2） | 空谷幽深，一缕声音般的薄雾自谷底升起传向远方 | light dusk purple | a deep empty valley, a wisp of mist rising like a voice traveling into the distance |
| 11 | scene-settings | 设置 | 月白灰 155,170,188 | 平远水岸（种子4） | 心远地自偏，一线极远极淡的山影，近于无形 | moon-white grey | a mind at distance makes any place secluded, one line of extremely faint far mountain, nearly formless |
| 12 | scene-admin | 管理后台 | 星野蓝 110,150,190 | 连绵三岭（种子3） | 高处方台俯瞰群山，居高声自远，视野辽阔 | star-field blue | a high terrace overlooking ranges of mountains, standing high with a far-reaching voice, vast open vista |

> EN 列「色名」与「意象 EN」拼装进 §2 模板；山形 EN 固定短语：种子1 `two facing peaks` / 种子2 `a lone left-leaning peak with a low distant ridge at right` / 种子3 `three rolling ridges in a row` / 种子4 `a wide low shoreline of gentle hills`。

## 4. 验收（回填后我走 ART-QA-0002 基线逐项过）

1. 文件/像素/格式/体积达标（§1）
2. **压字测试**：图上叠 90% 实底卡片 + 正文，文字可读性与纯 CSS 场景一致（红线）
3. **构图核对**：山形不入中上部、右缘干净、中央空（对照 §1 铁律逐张过）
4. **AI 瑕疵**：伪文字/水印/莫名建筑船只/高饱和色块 → 重生
5. **文化适配**：不得出现传统水墨肌理（与 0003 同红线）
6. 实机双主题截图：夜墨叠图效果 + 宣纸回退 CSS（或宣纸版图）效果

## 5. 生成建议

- MJ 用 EN 提示词 + `--style raw`；国产模型（即梦/通义万相）用中文提示词
- 每张至少出 2 稿挑更淡的一稿；**宁淡勿浓**——浓了后期压不淡，淡了代码层可以加一点 opacity
- 12 张分批生成无压力：代码按文件存在与否自动决定叠图还是 CSS 兜底，先回填几张就先生效几张

---

## 附录 A · 拼装完成的提示词（复制即用，MJ 直贴）

### 1. scene-chat（智能对话）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with sky-cyan, a lone peak standing at left, a thin stream of light winding down to lower right, quietly awaiting a kindred soul, bottom fifth shows a lone left-leaning peak with a low distant ridge at right as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 2. scene-knowledge（知识库）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with mineral blue, three layered ridges stacked like volumes of books, a wisp of spring water flowing from the valley, living water from the source, bottom fifth shows three rolling ridges in a row as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 3. scene-video-gen（视频生成）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with dusk-mountain purple, mist drifting slowly between two facing peaks like shifting light and shadow, flowing clouds and streaming light, bottom fifth shows two facing peaks as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 4. scene-image-gen（图片生成）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with celadon water-green, distant mountains like dark eyebrows, near water veiled in mist, calm water mirroring the faintest mountain reflections, bottom fifth shows a wide low shoreline of gentle hills as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 5. scene-video-edit（视频剪辑）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with deep mineral cyan, layered clouds and mist like curtains parted by a soft seam of light, cutting clouds and trimming haze, bottom fifth shows a lone left-leaning peak with a low distant ridge at right as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 6. scene-canvas（无限画布）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with moon-white grey-blue, empty mountains after fresh rain, faintest distant ridges and misty vapor, ninety percent of the canvas left blank, bottom fifth shows a wide low shoreline of gentle hills as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 7. scene-assets（资产库）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with pale indigo, mountains encircling a valley like the sea gathering a hundred treasures, valley mouth lightly veiled in thin mist, bottom fifth shows three rolling ridges in a row as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 8. scene-wallet（我的钱包）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with spring jade-green, a clear spring flowing over stones, a glint of light where the stream bends, pebbles faintly visible underwater, bottom fifth shows a wide low shoreline of gentle hills as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 9. scene-project-groups（项目组）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with gathering cyan, scattered peaks gathered like sages at an elegant meeting, mist connecting the mountains, bottom fifth shows two facing peaks as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 10. scene-feedback（反馈帮助）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with light dusk purple, a deep empty valley, a wisp of mist rising like a voice traveling into the distance, bottom fifth shows a lone left-leaning peak with a low distant ridge at right as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 11. scene-settings（设置）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with moon-white grey, a mind at distance makes any place secluded, one line of extremely faint far mountain, nearly formless, bottom fifth shows a wide low shoreline of gentle hills as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 12. scene-admin（管理后台）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, deep night indigo base (#151D29) softly tinted with star-field blue, a high terrace overlooking ranges of mountains, standing high with a far-reaching voice, vast open vista, bottom fifth shows three rolling ridges in a row as soft gradient silhouettes, upper area fading into plain night sky, vast negative space in center and right edge, ethereal thin mist, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
ink wash painting, paper texture, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

---
## 附录 B · 宣纸版（亮色）拼装完成提示词 v2（2026-08-29 按五轮实机迭代教训重写，取代旧版）

> 旧版（「bottom fifth + 大量留白」写法）废弃——它正是 AI 出图「山形悬空、页底全空、中部黑团」的提示词根因。
> 本版把实机迭代结论写进每一条：**山根坐底边、墨色沉下半幅向上渐淡、中部禁黑团**。
> 命名 `scene-{key}-light.png` 给我即可，我转码覆盖现有程序派生版（派生版转为兜底）。

### 宣纸版专属铁律（每条提示词已内置，核对时用）

1. **整幅亮底**：全幅暖米白 #F5F1E6，如柔和阴天日光——严禁夜空/深色底
2. **山根坐底边**：山体与云雾必须自画面最底边升起、贴满整条底边，底部不允许出现空白留带
3. **墨色沉下半幅**：墨集中在下 1/2 并向上渐淡；上半幅只许极淡雾丝，**中部不得出现灰黑团块**
4. 柔和渐变剪影、无硬边、极低对比；宁淡勿浓（淡了代码层加 opacity，浓了救不回）
5. 尺寸/体积/落盘同 §1；每张至少出 2 稿挑更淡且底部更满的一稿

### 中文模板（即梦/通义万相等国产模型用；MJ 用下方逐条 EN）

`现代极简新中式数字插画背景，整幅暖米白宣纸底（#F5F1E6）如柔和阴天日光，景物全部为淡墨灰色调，【色名】极淡微染入雾，【意象句】，下半幅【山形】以淡墨灰柔和渐变剪影自画面最底边升起，山根消融于薄雾并贴满整条底边，墨色集中在下半幅、向上逐渐变淡，上半幅保持干净宣纸色仅有极淡雾丝，画面中部无深黑团块，只有柔和渐变，无硬边，极低对比度，大气透视，淡雅到几乎看不见，高级渐变质感，无笔触纹理`

**统一中文负面**：`夜景, 夜空, 深色底, 黑色重团块, 浓密乌云, 中部灰色横带, 山体悬空底部留白, 底边空白, 硬边, 锐利轮廓, 高对比, 水墨画, 宣纸纹理颗粒, 泼墨, 枯笔, 笔触, 书法字, 文字, 水印, logo, 具象人物, 人脸, 现代建筑, 高饱和, 霓虹, 赛博朋克, 卡通, 动漫, 3D渲染, 照片写实, 油画, 画框, 明显焦点主体, 锐利细节, 前景特写`

**统一 EN 负面**（12 条共用，下方每条仍完整复制方便直贴）：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 1. scene-chat-light（智能对话 · 天青）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of sky-cyan tint breathed into the mist, a lone peak standing at left, a thin stream of light winding down to lower right, quietly awaiting a kindred soul, lower half holds a lone left-leaning peak with a low distant ridge at right as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 2. scene-knowledge-light（知识库 · 石青）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of mineral blue tint breathed into the mist, three layered ridges stacked like volumes of books, a wisp of spring water flowing from the valley, living water from the source, lower half holds three rolling ridges in a row as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 3. scene-video-gen-light（视频生成 · 暮山紫）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of dusk-mountain purple tint breathed into the mist, mist drifting slowly between two facing peaks like shifting light and shadow, flowing clouds and streaming light, lower half holds two facing peaks as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 4. scene-image-gen-light（图片生成 · 天水碧）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of celadon water-green tint breathed into the mist, distant mountains like dark eyebrows, near water veiled in mist, calm water mirroring the faintest mountain reflections, lower half holds a wide low shoreline of gentle hills as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 5. scene-video-edit-light（视频剪辑 · 深石青）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of deep mineral cyan tint breathed into the mist, layered clouds and mist like curtains parted by a soft seam of light, cutting clouds and trimming haze, lower half holds a lone left-leaning peak with a low distant ridge at right as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 6. scene-canvas-light（无限画布 · 月白灰蓝）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of moon-white grey-blue tint breathed into the mist, empty mountains after fresh rain, faintest distant ridges and misty vapor, sparse composition with generous blank space above, lower half holds a wide low shoreline of gentle hills as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 7. scene-assets-light（资产库 · 黛蓝浅）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of pale indigo tint breathed into the mist, mountains encircling a valley like the sea gathering a hundred treasures, valley mouth lightly veiled in thin mist, lower half holds three rolling ridges in a row as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 8. scene-wallet-light（我的钱包 · 青碧泉）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of spring jade-green tint breathed into the mist, a clear spring flowing over stones, a glint of light where the stream bends, pebbles faintly visible underwater, lower half holds a wide low shoreline of gentle hills as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 9. scene-project-groups-light（项目组 · 雅集青）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of gathering cyan tint breathed into the mist, scattered peaks gathered like sages at an elegant meeting, mist connecting the mountains, lower half holds two facing peaks as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 10. scene-feedback-light（反馈帮助 · 暮山紫浅）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of light dusk purple tint breathed into the mist, a deep empty valley, a wisp of mist rising like a voice traveling into the distance, lower half holds a lone left-leaning peak with a low distant ridge at right as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 11. scene-settings-light（设置 · 月白灰）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of moon-white grey tint breathed into the mist, a mind at distance makes any place secluded, one line of extremely faint far mountain, nearly formless, lower half holds a wide low shoreline of gentle hills as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```

### 12. scene-admin-light（管理后台 · 星野蓝）

**正面提示词**：
```
modern minimalist new-Chinese digital illustration background, the whole frame is warm rice-cream paper white (#F5F1E6) like soft overcast daylight, all scenery rendered in pale ink-grey tones with a whisper of star-field blue tint breathed into the mist, a high terrace overlooking ranges of mountains, standing high with a far-reaching voice, vast open vista, lower half holds three rolling ridges in a row as soft pale ink-grey gradient silhouettes rising from the very bottom edge, their bases melting into thin mist that touches and fills the entire bottom border, ink density concentrated in the lower half and thinning gradually upward, upper half stays mostly clean cream with only the faintest wisps of drifting mist, no dark masses in the middle of the frame, gentle gradients only, no hard edges, extremely low contrast, atmospheric perspective, barely-there subtlety, premium gradient quality, no brush texture --ar 16:9 --style raw
```

**负面提示词**：
```
night, night sky, dark background, dark blue base, heavy black mass, dense dark cloud cluster, grey wash band across the middle, mountains floating above empty space, empty blank bottom margin, hard edges, sharp outlines, high contrast, ink wash painting, paper texture grain, splash ink, brush strokes, calligraphy, text, watermark, logo, people, faces, modern buildings, high saturation, neon, cyberpunk, cartoon, anime, 3d render, photorealistic, oil painting, frame, distinct focal subject, sharp details, foreground close-up
```
