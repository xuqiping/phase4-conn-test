# AGENTS.md · 项目级 AI 指令

> 这是 Context Engineering 的核心产物。AI agent 每次开工前必读，定义它的「行为准则」。
> 等价于 CLAUDE.md（Claude）/ GEMINI.md（Gemini）/ copilot-instructions.md（Copilot），用 AGENTS.md 通用命名。
> Phase 0 建初版，Phase 3 每完成一个通用能力就织入更新。
> **last_updated: YYYY-MM-DD**（每次改动更新此日期；Phase 4 review / Phase 5 迭代收尾时，核对本文件是否与代码现状矛盾——过期的规则会带偏 AI。）
> **防膨胀**：本文件聚焦「每次开工都需要的规则」，目标 ≤400 行；细节拆 `XX约束.md`，本文件只留索引。定期瘦身（Phase 5 迭代收尾时做一次）。**2026 经验：聚焦的 400 行上下文文件常优于 4000 行的——膨胀的规则文件本身就是上下文污染，会带偏 AI。**

## 项目宪法（不可协商 · IMMUTABLE）

> 吸收自 Spec Kit 的 `constitution.md` 概念（条目 24 评估）。这是本项目**最高位**的红线——比下面的「通用规则」更高、不随迭代随意改。分两层：① 沿用工作流底线（见 `0_方法论核心.md` 9 铁律）；② **本项目特有红线**（下面填）。AI 任何建议若触碰红线，必须拒绝并提示人。

**工作流底线（所有项目通用，不删）**：
- **specs before code**：没规格不写码。
- **commit 当存档点**：再赶也每 chunk 测试通过即提交。
- **再信任也要 review**：AI 写的代码当初级开发的提交，读、跑、测 + 第二个 AI 审。
- **commit 前必跑 `scripts/check_all`**：不绕过质量门（`--no-verify` 仅紧急人工场景）。

**本项目特有红线（Phase 0 / 1 填，按项目风险定）**：
- <如：支付金额永远以服务端二次校算为准，前端值只作展示>
- <如：删除用户数据必须软删（`deleted` 字段），永不物理删>
- <如：生产配置（密钥/DB 密码）绝不进仓库明文，见 `docs/config/多环境配置说明.md`>
- <如：对外 API 改字段必须走 Phase 6 版本号 + 兼容期，绝不顺手改>

> 改宪法 = 改项目根基，必须显式 review + 记 ADR，不能混在普通 AGENTS.md 更新里。

## 通用规则（CORE RULES）

### 代码风格
- 语言/框架：
- 缩进、命名、范式偏好（如：函数式优先 / 避免 OOP 滥用）：
- 必须通过的 lint / 格式化工具：

### 禁忌清单（anti-patterns · 不要做）
> 「不要做什么」比「要做什么」更能防 AI 翻车。每条写**具体的反面例子**，别写抽象口号；踩过的坑优先记在这里。
- 不使用：<某些函数/库/模式，及为什么>
- 不引入：<某些依赖>
- 不绕过：<如：不绕过 check_all 提交、不改已执行的 Flyway 旧脚本、不直接手写 SQL 入库>
- 不编造：不引用不存在的函数/库/API（与下方反幻觉条款呼应）

### 偏好（优先这么做）
- 遇到 X 优先用 Y 方式
- 注释风格：<如：修 bug 时在注释里简述理由>

## 反幻觉条款（硬性）
- 不确定或缺少上下文时，**先问，不要编**。
- 不要引用不存在的函数/库/API。
- 修 bug 时说明理由（注释或对话）。

## 工作流约束
- **specs before code**：开工前先读 workflow_output/docs/specs/PRD.md。
- **plan before implement**：按 workflow_output/docs/plans/<功能>.plan.md 走，逐步骤勾选。
- **commit 当存档点**：每完成一个 chunk（测试通过）立即建议提交。
- **commit 前必跑最小质量门（硬性）**：每次 commit 前先跑 `scripts/check_all.bat`（Windows）或 `scripts/check_all.sh`（Linux/Mac），全绿才允许提交；失败日志贴回给 AI 修。不要绕过（`--no-verify` 仅限紧急人工场景）。
- **追溯编号（全链路）**：PRD 定义 `FR-xxx`（需求）/ `AC-xxx`（验收标准）为唯一出处；plan 每 Step 标对应 FR；自动化测试用例名/注释带 AC 号；commit message 带 FR 号（如 `feat: FR-003 登录失败锁定`）；开发进度记录 plan Step / FR / AC。
- **人工测试方案（按需）**：每开发一个功能，判断是否需要人工交互测试（UI / 主观体验 / 真实第三方 / 需人工确认）；需要则在 `workflow_output/docs/测试方案/<功能名>测试方案.md` 产出测试方案，不需要则跳过、不产文件。
- **never commit code you can't explain**：看不懂的代码先加注释或简化。

## 文档写作规范
- **单文件 5000 tokens 上限**：所有 workflow_output/ 下的文档（开发进度、Feature Map、User-Ops、功能 README、测试方案、PRD、AGENTS.md 等）不得超过 5000 tokens。接近 4000 时预警，超限时拆分为子文件 + 总路由索引，禁止无限膨胀。
- **功能 README（每个功能完成时）**：在 `workflow_output/开发进度/<功能名>/README.md` 产出，先判定受众：**A 技术类**只写技术说明；**B 用户类**写用户地图（谁用 / 什么场景 / 什么效益）+ 简要技术说明；**C 两者**都写。
- **开发进度（每一轮对话结束）**：在 `workflow_output/开发进度/<功能名>/开发进度n.md` 记录，文档类写清步骤/产出文件/被谁引用；代码类写清实现功能/对应 plan/涉及文件/关键代码位置/测试/commit。
- **专业术语批注（specs / plans）**：术语首次出现**行内括注一句大白话**，并在**文档底部维护术语表**（术语 \| 大白话 \| 简单案例）。主文专业度不变，批注只作辅助。

## 模块级约束（按需新增 XX约束.md 并在此索引）
- [通用约束.md](通用约束.md) —— 跨所有模块
- [鉴权约束.md] —— 商业授权/登录体系（示例，建立后所有模块基于它）
- [i18n约束.md] —— 中英双语规范（示例）

## 参考文档
- 项目结构 → [workflow_output/docs/file_structure.md](../docs/file_structure.md)
- 需求规格 → [workflow_output/docs/specs/PRD.md](../docs/specs/PRD.md)
