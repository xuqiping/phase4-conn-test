# DESIGN-TOKEN-0002 · 模块场景令牌（雾中浮岛）

> 2026-08-29 ｜ 上游：ART-DIR-0002R 方向二 / STYLE-DNA-0002 ｜ 补 DESIGN-TOKEN-0001

## 1. 场景色令牌（RGB 三元组，供 rgba() 调透明度）

| 模块 | token | RGB | 色名 |
|---|---|---|---|
| chat | --scene-chat | 143,188,212 | 天青 |
| knowledge | --scene-knowledge | 78,127,166 | 石青 |
| video-gen | --scene-video-gen | 138,128,163 | 暮山紫 |
| image-gen | --scene-image-gen | 124,181,164 | 天水碧（深半档保存在感） |
| video-edit | --scene-video-edit | 58,95,125 | 深石青 |
| canvas | --scene-canvas | 154,171,188 | 月白灰蓝 |
| assets | --scene-assets | 53,104,127 | 黛蓝浅 |
| wallet | --scene-wallet | 99,185,154 | 青碧泉 |
| project-groups | --scene-project-groups | 110,160,138 | 雅集青 |
| feedback | --scene-feedback | 169,159,189 | 暮山紫浅 |
| settings | --scene-settings | 155,170,188 | 月白灰 |
| admin | --scene-admin | 110,150,190 | 星野蓝 |

夜墨（暗底）：天际 alpha 0.14 / 近山 0.08 / 远山 0.05
宣纸（亮底）：天际 alpha 0.10 / 山形改用墨色 38,34,28（0.06 / 0.04）

## 2. 组件令牌

| token | 值 | 说明 |
|---|---|---|
| --scene-sky-height | 45% | 天际渐变消隐点 |
| --scene-ridge-zone | 底部 20% | 山形活动区（禁越界） |
| --island-solidity | ≥90% | 浮岛卡实底下限（对比度红线） |
| --poem-rail-size | 14px 文楷竖排 | 诗签字号，color = text-secondary |

## 3. 诗签真值表（承 AESTH-0002 §4，实施以此为准）

chat「高山流水，静候知音」/ knowledge「问渠那得清如许」/ video-gen「行云流影，山川入画」/ image-gen「远山如黛，近水含烟」/ video-edit「裁云为衣，剪霞作幕」/ canvas「空山新雨后」/ assets「海纳百川，有容乃大」/ wallet「清泉石上流」/ project-groups「群贤毕至，少长咸集」/ feedback「空谷传声，虚堂习听」/ settings「心远地自偏」/ admin「居高声自远」

## 4. 不可变边界（追加 DESIGN-TOKEN-0001 §六）

7. 场景装饰层 z-index 恒低于内容层；内容卡实底 ≥90%
8. 场景仅 ink 双主题渲染；旧三主题 DOM 都不输出（v-if 级门控，非仅 CSS 隐藏）
9. 山形只许椭圆渐变剪影，禁位图山水贴图进场景层
