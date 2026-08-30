# P02 云端骨架与账号计费 · 功能 README

> 受众：C（技术 + 用户）。进度详录见 [开发进度1~4.md](开发进度3.md)；速查见 [Feature Map](../../docs/feature-map/P02_云端骨架与账号计费.feature-map.md)。
> 状态：**全部 Step 完成**（S0~S9，commit 链见各进度文件）。

## 一句话

云端（NestJS）长出了账号、钱包账本、模型网关（chat/estimate/search）与充值回调；桌面端顶栏长出余额环，本地用量镜像与云端账本互相对账——「按 token 卖钱」的骨架闭环。

## 技术半边（A）

- **栈**：NestJS 10 + PGlite（进程内 WASM PG；开发/测试零外部依赖；生产可换 compose PG）+ Redis（验证码限频/缓存，e2e 中降级跳过）。
- **模块**：`auth`（手机号+验证码注册/登录，JWT 15min/7d）、`billing`（wallet/ledger/payment）、`gateway`（chat SSE / estimate / search）。
- **钱的铁律**：动钱只走 `BillingService.charge/credit`；先扣体验金再扣充值；账本只增不改，**账本 SUM = 钱包 balance+gift**（对账不变量，e2e 兜底）；金额全 `*_cents` 整数。
- **三保险防超扣**：幂等键 UNIQUE + 乐观锁 CAS + 进程内按用户互斥（100 并发 e2e 不超扣）。
- **供应商适配**：`GATEWAY_PROVIDER`（mock 默认 / anthropic 预留）；搜索降级链 mock→博查→SearXNG→mock 备，缓存命中不扣费。
- **充值**：订单 0→1 CAS + 账本幂等键双保险；回调 HMAC-SHA256 验签 + 金额一致性校验。
- **客户端**：`cloudApi`（402→PaymentRequiredError / 401→vault refresh 静默续期 / 网络大白话）+ `BalanceRing`（充足/不足红+去充值/对账黄条三态）+ `MeterMirror`（L3 usage_mirror 表，幂等键 `cloud-{id}` 重放零新增）。
- **质量门**：`scripts/check_all.sh` 增云端 lint+tsc+unit+e2e；CI 双 workflow（桌面 `devpilot-ci.yml` / 云端 `devpilot-cloud-ci.yml`，audit 硬拦截在 CI）。
- **基准（本机 mock，2026-08-16）**：dev 冷启动 ~8s（含 ts 编译）；`/balance` ~15-24ms、`/gateway/estimate` ~4-30ms、`/gateway/search` ~3-20ms（PGlite 进程内，无网络往返）。

## 用户半边（B）

- 顶栏右上多了一个**余额环**：显示 ¥ 总额（含体验金标注），点击刷新，60s 自动轮询。
- **余额不足**：环变红 + 「去充值」按钮 → 浏览器打开充值门户（MVP mock 渠道）。
- **对账不平**：黄色告警条，点击关闭（本地镜像与云端账本对不上时出现）。
- 消耗明细在云端 `/balance/transactions`（分页），每笔 chat/搜索/充值都有 kind 标注。

## 边界与遗留

- 真上游（anthropic/博查/SearXNG/Jina）待运营配 key 联调；compose 运行时验证待 CI 首推。
- `meter_sync` userId 暂 0，P04 登录态接入后注入。
- 充值到账后不自动续跑任务，需手动（plan 边界）。
