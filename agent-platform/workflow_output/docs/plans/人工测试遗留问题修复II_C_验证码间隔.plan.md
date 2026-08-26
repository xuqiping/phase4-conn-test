---
description: "子计划 C：邮箱验证码间隔倒计时恢复 + 429 真实剩余秒 + 次生恶化点修正（§8，Q7=A）"
created-date: 2026-08-26
---

# 子计划 C：验证码间隔与倒计时恢复

> 主索引：[人工测试遗留问题修复II.plan.md](人工测试遗留问题修复II.plan.md)
> 规格：§8（12x-1，Q7=A：本地时间戳 + 429 带剩余秒，不加新端点）。
> 先答用户问：间隔=60s；刷新后再点后端真拒（429 不发信不耗额度）——修复的是体验（倒计时恢复+真实剩余秒）与三个次生恶化点。

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| BusinessException 现无 data 载荷位，429 带不出 retryAfterSeconds | BusinessException 增可选 `Map<String,Object> data`（或单 retryAfter 字段）；GlobalExceptionHandler RATE_LIMIT 分支把 data 透传进 R.data——改动面小，其他错误码不受影响 |
| Redis TTL 读到 -1/-2（key 不存在或永生）当剩余秒 | TTL<=0 时回退常量 60（保守值），不抛错 |
| localStorage key 拼邮箱明文（多账号同机） | key=`mailcode:cd:`+email 小写；低风险接受（本地存储本就有会话痕迹）；倒计时归零即清 |
| 滑块失败计数跳过 RATE_LIMIT 后，真攻击者改刷 429 绕滑块 | 间隔窗口本身限频（60s/封）；IP 每小时配额仍在（修的是顺序不是删配额）；429 响应无敏感信息——滥用面可接受，权衡记录规格 §8.6 |
| 双 toast 修复改错层（拦截器 vs 组件职责混乱） | 保留拦截器统一弹错；组件 catch 对非 40107 静默（只恢复倒计时）；40107 弹滑块逻辑不动 |
| 短信「码未消费返 200」分支语义改动破坏现有前端 | SmsLoginTab 组件层兼容两态（200 文案分支保留解析）；后端统一 429 放最后做+单独人工验证 |

## 实现步骤

- [x] **C1：429 带真实剩余秒（后端）**
  - **目标**：被拒时返回 `data.retryAfterSeconds`，话术动态
  - **动作**：
    ```
    BusinessException 增可选 data 载荷（Map<String,Object>，默认 null）
    EmailService 间隔拒绝分支（:263-271）：
        ttl = redis.getExpire("regcode:resend:"+email)
        remaining = ttl > 0 ? ttl : 60
        throw new BusinessException(RATE_LIMIT, "发送过于频繁，请 "+remaining+" 秒后再试")
              .data(Map.of("retryAfterSeconds", remaining))
    GlobalExceptionHandler：业务异常分支把 e.data 并入 R.data（null 则维持现状）
    ```
  - **文件**：`common/exception/BusinessException.java`、`common/exception/GlobalExceptionHandler.java`、`auth/service/EmailService.java`
  - **依赖**：无
  - **验证**：单测——TTL 35s 时 message 含「35」且 data.retryAfterSeconds=35；TTL 异常回退 60

- [x] **C2：次生修正（滑块计数/配额顺序/间隔可配）**
  - **目标**：被拒不惩罚正常用户
  - **动作**：
    ```
    EmailService.doSendRegisterCode 调整：
        ① 间隔检查移到 checkIpHourly increment 之前（先查窗口再耗配额）
        ② catch 分支：异常为 RATE_LIMIT 时不调 progressiveCaptcha.recordFailure
           （其他异常仍记——那是可疑行为）
    间隔常量改可配：AuthChannelSettingService 增
        auth.channel.mail.resend-interval-seconds（默认 60）
        RESEND_WINDOW_SECONDS 改读配置（每请求实时读，与 daily-cap 同款）
    ```
  - **文件**：`auth/service/EmailService.java`、`auth/service/AuthChannelSettingService.java`
  - **依赖**：无
  - **验证**：单测——①间隔内连点 3 次不触发强制滑块；②被拒请求后 IP 配额计数不变；③改间隔配置即时生效

- [x] **C3：前端倒计时恢复（RegisterModal + SmsLoginTab）**
  - **目标**：刷新/重开弹窗后倒计时续上；429 后按钮进入倒计时
  - **动作**：
    ```
    新工具 frontend/src/utils/cooldown.ts：
        saveCooldown(key, seconds)  → localStorage[key]=now+seconds*1000
        restoreCooldown(key)        → 剩余秒（<=0 时清 key 返 0）
    RegisterModal.vue：
        发码成功 → saveCooldown('mailcode:cd:'+email, interval)
        onMounted/弹窗打开 → restoreCooldown>0 则启动倒计时
        catch 429 → 用 resp.data.retryAfterSeconds 启动倒计时+saveCooldown
        catch 非 40107 → 静默（拦截器已弹 toast）；40107 滑块逻辑不动
    SmsLoginTab.vue：同款（key='sms:cd:'+phone）
    ```
  - **文件**：`frontend/src/utils/cooldown.ts`（新）、`frontend/src/views/login/RegisterModal.vue`、`frontend/src/views/login/SmsLoginTab.vue`
  - **依赖**：C1（429 data）
  - **验证**：人工——①发码后刷新页面按钮仍倒计时且秒数=真实剩余；②间隔内点→「请 N 秒后再试」+按钮进入倒计时；③连点 3 次不触发滑块

- [x] **C4：短信语义统一（可选收尾）**
  - **目标**：「码未消费」分支与邮箱同语义
  - **动作**：SmsService 同号 5min 未消费分支从 HTTP 200+文案 改 429+retryAfterSeconds（SmsLoginTab 组件兼容已由 C3 覆盖，双保险保留 200 文案解析注释一段后删）
  - **文件**：`auth/service/SmsService.java`
  - **依赖**：C3
  - **验证**：人工——短信间隔内再点 → 429 + 倒计时恢复

- [x] **C5：测试收口**
  - 规格 §8.5 单测+人工四项全过

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 发码成功 | localStorage 截止时间 + 倒计时 | 写入+启动 | 邮箱输入框改值 → key 换（按新邮箱查），旧邮箱倒计时不影响新 |
| 429 被拒 | 倒计时 + 滑块计数 + IP 配额 | 恢复/不记失败/不耗配额 | 换浏览器清存储 → 429 真值兜底 |
| 间隔配置改 | 话术 N + 窗口长度 | 即时生效 | 已在倒计中的前端按旧值走完（可接受） |
| 注册成功 | 验证码 key | 现有删码逻辑不动 | 倒计时 key 自然过期自清 |

## 验证收口

- [x] C1-C5 全绿；12x-1 可勾销（含「刷新后再点会怎样」的用户疑问在勾销文案里写明：后端 60s 窗口真拒）
