# Intent Routing Workflow

## Purpose

本工作流负责解析用户意图、匹配领域、并将任务分发给正确的子Agent。顶层Agent不执行具体领域工作，仅做路由调度。

## Prerequisites

- AGENTS.md 已存在且路由表完整
- skills-router.md 已存在且子Agent注册表完整
- 所有子Agent目录已搭建（Phase 2完成后）

## Steps

### Step 1: 解析用户意图

**Goal**: 从用户消息中提取显式和隐式的领域关键词
**Completion criterion**: 至少识别出一个候选领域关键词，或确认无匹配

1. 读取用户原始消息。
2. 扫描消息中的技术术语、框架名称、方法论名称、流程阶段名称。
3. 对照AGENTS.md Routing Table的Task Keyword列进行关键词匹配。
4. 记录所有匹配到的候选领域（可能为0、1或多个）。

### Step 2: 匹配领域

**Goal**: 将候选关键词映射到唯一的子Agent
**Completion criterion**: 确定唯一目标子Agent，或向用户请求澄清

1. 如果**仅匹配一个领域** → 直接进入Step 3。
2. 如果**匹配两个领域** → 向用户列出两个候选子Agent及其描述，询问用户确认或补充信息以缩小范围。
3. 如果**匹配零个领域** → 向用户说明未识别到具体领域，请用户重新表述或从14个领域中选择一个。
4. 如果**匹配超过两个领域** → 提取最相关的两个向用户展示，其余归档供后续追问。

### Step 3: 分发至子Agent

**Goal**: 将任务正式转交给匹配的子Agent
**Completion criterion**: 明确告知用户将由哪个子Agent处理，并加载该子Agent的AGENTS.md

1. 根据确定的领域，在skills-router.md中查找对应的Sub-Agent Directory和Sub-Agent Skill Name。
2. 向用户宣布：您的请求将由「{Display Name}Agent」处理，正在启动...
3. 加载该子Agent目录下的AGENTS.md文件。
4. 读取子Agent的路由表，识别最相关的细粒度工作流文件（workflow/<l2>_workflow.md）。
5. 开始执行该子Agent的工作流（由子Agent自身执行，顶层Agent只做启动宣告）。

## Post-Workflow

1. 读取checklist/intent_routing_workflow_checklist.md。
2. 交叉验证每一步输出。
3. 所有检查项通过后，记录	ask/current_task.md。
