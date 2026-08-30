# ART-DIR-0002 · 全模块页面级设计（高山流水·现代化）

> 日期：2026-08-29 ｜ 状态：**公共层已实施；模块签名层被 ART-DIR-0002R 取代**（用户目检判定「未体现高山流水意境」，AESTH-0002 归因为意境密度不足 L1→需 L2-L3）
> 上游：ART-DIR-0001（方向）、STYLE-DNA-0001（基因）、DESIGN-TOKEN-0001（令牌）
> 依据：2026-08-29 Playwright 全模块实拍审核（24 路由 × 双主题，admin 真机）
> 范围：frontupdate 全部启用模块。骨架不动（布局/路由/交互不变），只做视觉层的「赋意」。

## 0. 审核结论（现状问题清单）

| # | 问题 | 证据 |
|---|---|---|
| Q1 | 侧栏/顶栏仍是纯科技风：蓝 A 字 logo、平板菜单、无意境元素 | ye-mo-chat 等全部截图 |
| Q2 | 内容区是「平的深色虚空」：晨昏云雾层几乎不可见，无构图节奏 | ye-mo-knowledge |
| Q3 | 页标题全部是普通 sans 粗体，无文楷、无发丝线分隔 | 全部页面 |
| Q4 | 卡片语言混乱：项目组是深色实卡、资产项目卡退化成裸文本堆叠（bug）、图片生成是半透明平板 | ye-mo-assets / project-groups / image-gen |
| Q5 | 表格可用但无气质：表头平涂、无发丝线 | ye-mo-admin-audit / admin-users |
| Q6 | 钱包页红色警示文字对比弱、大数字无排印设计 | ye-mo-wallet |
| Q7 | 聊天主区空态仍是旧自定义（圆点图标+灰字），未用 InkEmptyState | ye-mo-chat |
| Q8 | 宣纸主题下聊天页会话栏与主区同为米白、缺层次 | xuan-zhi-chat |

## 1. 总设计概念：一框一景

> 「框」是骨架（已有的布局，不动）；「景」是每页一眼可辨的山水意象签名。
> 公共层求「淡」——看不出设计的设计；模块层求「远」——每页一个诗意签名，点到即止。

不变边界（承 FILE-STRUCTURE-0001 / DESIGN-TOKEN-0001）：
- 禁水墨肌理、泼墨、枯笔、宣纸纹理图；只用渐变/雾层/极简山形/发丝线
- 对比度红线不变（check-contrast.mjs 30 组全过才可提交）
- 动效曲线统一 var(--ease-cloud)，时长 150/300/400ms；prefers-reduced-motion 全降级
- 意境元素只允许：发丝线、淡山形剪影（CSS 渐变）、雾、朱砂小点（印章/标记）、文楷字

## 2. 公共层设计（所有模块共享）

### P1 侧栏「溪岸」（Sidebar.vue）
- Logo 区：蓝 A 方块 → 知音印章（seal-logo.webp 28px）+ 「高山流水」文楷字标（ye-mo 月白 / xuan-zhi 墨色）
- 菜单项：活跃项左侧 2px 朱砂竖痕（#B54434）+ 淡青底光（rgba(primary,0.10)）；hover 淡青 0.05
- 菜单过处分组：不加分组标题，用发丝线（1px 渐变线，左实右无）轻隔
- 滚动区底色：比主背景深半级（surface），右侧 1px 发丝分界
- 折叠态：印章居中，只留图标

### P2 页头顶栏（AppHeader.vue）
- 页标题改文楷 display 字体 + 字距 0.04em（仅 ink 主题）
- 底部分界：1px 发丝渐变线（linear-gradient 90deg，从 border 色到透明 80%）——「远山淡去」
- 右侧图标区不变（功能密度优先）

### P3 页面头组件（新增 PageHeader.vue）
所有业务页统一页头：
```
[文楷标题 24px]  [副题/计数 text-secondary]
[操作按钮区 右对齐]
——— 发丝渐变线 ———
```
- 用法：`<PageHeader title="资产库" sub="项目级资产中枢 · 五类资产 × 叙事角色双轴矩阵"><template #actions>…</template></PageHeader>`
- 已有自定义页头（AssetListView 等）逐步替换；标题样式 only ink 主题生效，旧主题不受影响

### P4 绢本卡 u-ink-card（texture.scss 新增工具类）
统一卡片语言，修复 Q4：
- 背景 var(--color-surface) + 1px 发丝边（var(--color-border-light)）+ 软阴影（0 2px 8px rgba(0,0,0,.08)）
- 圆角 8px；hover：translateY(-2px) + 阴影加深（300ms ease-cloud）
- xuan-zhi 下阴影换 rgba(38,34,28,.10)
- 用于：项目组卡、资产项目卡（修复裸文本 bug）、钱包卡、画布卡、设置表单区

### P5 表格深化（naive-overrides.ts DataTable 增补）
- 表头：底部 2px 发丝线（dividerColor）+ 文字 textColor2 字重 500
- 行 hover：rgba(primary, 0.04)
- 斑马纹：不开（保持干净）

### P6 主内容区氛围（MainLayout.vue 微调）
- 云雾层透明度 0.12 → 0.18（Q2：现在几乎看不见）
- 内容区右上角加极淡山形水印（CSS 渐变，opacity 0.05，pointer-events none）——「处处有远山」

### P7 聊天空态收口（ChatView 主区）
- 旧「圆点图标+开始对话」→ InkEmptyState type="data"，description「选择已有会话或创建新会话开始对话」，action「开始对话」（Q7）

## 3. 模块签名设计（每模块一个意象，轻量落地）

| 模块 | 签名意象 | 落地动作（限量，不重排布局） |
|---|---|---|
| 智能对话 chat | **流水** | 会话列表活跃项左侧 2px 青痕（如溪边水草）；消息输入框 focus 时边框淡青扩散（涟漪 300ms）；主区空态换 InkEmptyState（P7） |
| 知识库 knowledge | **层峦藏书** | 知识库管理 Tab 的裸表格上方加页头（P3）；RAG 问答/检索调试面板标题文楷化 |
| 视频生成 video-gen | **流觞** | 表单卡 + 结果卡统一 u-ink-card；页头 P3 化 |
| 图片生成 image-gen | **画屏** | 结果图加 8px 圆角 + 绢本卡边框（如画框）；历史任务卡 u-ink-card |
| 视频剪辑 video-edit | **剪裁** | 同 video-gen 处理（u-ink-card + 页头） |
| 无限画布 canvas | **留白** | 画布列表卡 u-ink-card；页头 P3 化 |
| 资产库 assets | **藏珍阁** | **修复项目卡裸文本 bug（Q4）**：项目卡 u-ink-card 化（名称文楷 16px + 描述 secondary + 操作行）；页头已是 P3 结构，接组件 |
| 钱包 wallet | **清泉** | 余额大数字文楷 40px（「0 积分」）；警示条改 warning 染色底+高对比正文（修 Q6）；充值记录/流水表 P5 生效 |
| 项目组 project-groups | **雅集** | 组卡 u-ink-card 统一；「组池 X 分」数字文楷化 |
| 反馈与帮助 feedback | **传音** | 文章列表选中项左侧朱砂痕；详情面板页头 P3 化 |
| 设置 settings | **静室** | 各 Tab 表单区 u-ink-card 包裹；标签右对齐不变 |
| admin ×13 | **观星台** | 不单独做花活（工具属性，效率优先）；统一吃公共层：页头 P3 + 表格 P5 + 绢本卡；审计/安全页保持信息密度 |

## 4. 实施批次（执行顺序）

1. **批次A 公共层**（P1 侧栏 / P2 顶栏 / P3 PageHeader / P4 u-ink-card / P5 表格 / P6 氛围 / P7 聊天空态）
2. **批次B 用户侧模块**：assets（含 bug 修复）/ wallet / project-groups / canvas / feedback / settings
3. **批次C 生成类模块**：video-gen / image-gen / video-edit / knowledge
4. **批次D admin 13 页**：只接 PageHeader + 验证表格效果
5. **验证**：build + check-contrast + Playwright 复检截图（同 audit.js 路由清单）

## 5. 验收标准（ART-QA-0001 增补）

- AC-07：每页页标题在 ink 主题为文楷字体，旧三主题不受影响
- AC-08：资产库项目卡正常渲染为卡片（Q4 bug 修复）
- AC-09：聊天主区空态为 InkEmptyState
- AC-10：check-contrast.mjs 30 组全过；新增色对须补进脚本
- AC-11：侧栏活跃项有朱砂竖痕；logo 区为印章+文楷字标
- AC-12：旧三主题（deep-space/dark-pro/cyber-glow）页面样式零变化
