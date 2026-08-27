# 视频 TOKEN 分辨率分档计价（V162）· README

## 用户地图（admin）

- **谁用**：平台管理员（`pricing:manage` 权限）配价；全体视频生成用户被动受益（扣费变准）。
- **场景**：seedance2.0（Cdance2.0）等视频模型 4K 与 480p 每 token 成本不同，原价表只能填一个每百万价，4K 任务按低价档亏、480p 按高价档多收。
- **效益**：一行价表内按分辨率各配一个每百万价；未配档自动回落通用价（老配置零迁移、行为不变）；预估秒价（预检）与真实扣费（分档价）两套独立配置互不干扰。
- **操作**：价表配置 → 编辑 VIDEO+TOKEN 行 → 「通用每百万价」+ 四档选填（留空=按通用价）。详见[用户操作手册](../docs/user-ops/积分计费系统用户操作手册.md)第五章。

## 技术说明

- 存储：`pricing_rule.token_price_per_resolution JSONB`（V162，键⊆{480p,720p,1080p,4k}，无 general——通用价=price_input_per_million 复用）。
- 计费：`PricingService.videoCost` TOKEN 分支 `槽位[normalize(res)] ?? 通用价 ?? 0 元`；脏 JSON WARN 回落不炸结算；命中链/预扣多退少补零改动。
- 配置：服务层白名单校验（键/正数）+ 导入三态（缺失=不动、`{}`=清空、非空=整替）+ 模板骨架；前端 TOKEN 行恒发对象防「清空被吞」。
- 回滚：清槽即回存量口径；DB `DROP COLUMN` 可逆。
- 详档：[feature-map §13](../docs/feature-map/积分计费系统.feature-map.md) / [规格](../docs/specs/视频TOKEN分辨率分档计价.md) / [测试方案](../docs/测试方案/视频TOKEN分辨率分档计价测试方案.md) / [开发进度](开发进度1.md)。
