# Office 兼容样本矩阵

> Chunk 0 的自动与人工兼容验证基准。当前结论仅支持“路由技术尖峰完成”，不支持“完整兼容性检查点通过”。

## 1. 路由判定

| 判定 | 含义 | 当前自动规则 |
|---|---|---|
| `SAFE_OOXML` | 标准 OOXML 可交给跨平台 Worker 的安全子集 | 合法 `.xlsx/.docx/.pptx` ZIP，未发现 VBA、数字签名或外部关系 |
| `HIGH_FIDELITY_REQUIRED` | 必须交给安装 Office 的 Windows Worker 或等待人工确认 | `.xls/.doc/.ppt`、`.xlsm/.docm/.pptm`、包内 `vbaProject.bin`、数字签名或 `TargetMode=External` |
| `BLOCKED` | 当前不能安全处理 | 不支持扩展、损坏 OOXML ZIP、不可读源、请求/关系读取失败、扫描期间源变化 |

路由器只读 ZIP entries 与 `.rels`，不解压、不重写源包，不输出密码或文档正文。每次检查前后计算 SHA-256；不一致则阻止。

## 2. 完整样本矩阵

| 应用/场景 | 样本 | 预期 | 自动步骤 | 真实 Office 人工步骤 | 当前状态 |
|---|---|---|---|---|---|
| Excel 标准 | `.xlsx` 基础结构 | SAFE | JSONL inspect；核对码与源哈希 | 打开/另存/重开；核对 Sheet/样式 | 合成 ZIP 自动通过；真实样本待测 |
| Excel 公式 | 跨 Sheet、命名区域、数组公式 | SAFE 或按复杂度升级 | 扫描结构与关系 | 核对公式文本和计算结果 | 待真实样本 |
| Excel 外链 | 外部工作簿关系 | HIGH | 检出 External relationship | 换源/刷新前后核对 | 合成关系自动通过；真实待测 |
| Excel 宏 | `.xlsm`、包内 VBA | HIGH | 扩展/VBA 风险码 | 禁宏打开；核对工程、引用、签名 | 合成结构自动通过；真实宏待测 |
| Excel 签名/密码宏 | 签名或受保护 VBA | HIGH/阻断冲突 | 检出签名部件 | 核对签名失效提示；不破解密码 | 待真实样本 |
| Excel 旧格式 | `.xls` | HIGH | 按扩展路由，不解析二进制 | Office 打开/另存/重开 | 自动路由通过；真实待测 |
| Excel 富结构 | 条件格式、合并、表格、图表、隐藏 Sheet | SAFE 或 HIGH | 基础路由 | 逐项结构和视觉比较 | 待真实样本 |
| Word 标准 | `.docx` 基础结构 | SAFE | JSONL inspect；源哈希 | 打开/另存/重开 | 合成 ZIP 自动通过；真实待测 |
| Word 范围 | 跨 Run、表格、页眉页脚、脚注尾注、文本框 | SAFE/HIGH | 基础路由 | 逐 Story Range 肉眼核对 | 待真实样本 |
| Word 排除内容 | 批注、修订 | SAFE/HIGH | 基础路由 | 确认内容不变且报告说明排除 | 待真实样本 |
| Word 宏/旧格式 | `.docm/.doc` | HIGH | 扩展路由 | 禁宏打开/另存/重开 | 自动路由通过；真实待测 |
| PowerPoint 标准 | `.pptx` 基础结构 | SAFE | JSONL inspect；源哈希 | 打开/放映/重开 | 合成 ZIP 自动通过；真实待测 |
| PPT 主题 | 多母版、版式、字体缺失、图表 | SAFE/HIGH | 基础路由 | 逐页视觉比较与字体检查 | 待真实样本 |
| PPT Excel 外链 | 图表/对象外部关系 | HIGH | 检出 External relationship | 用户确认换源后刷新核对 | 合成关系自动通过；真实待测 |
| PPT 嵌入对象 | 内嵌 Excel/OLE | SAFE/HIGH | 不把内嵌对象误报为外链 | 确认对象可打开且不换源 | 待真实样本 |
| PPT 宏/旧格式 | `.pptm/.ppt` | HIGH | 扩展/VBA 风险码 | 禁宏打开；核对工程/签名 | 自动路由通过；真实 `.pptm` 待测 |
| 通用损坏 | 损坏 `.xlsx/.docx/.pptx` ZIP | BLOCKED | 核对 `OFFICE_INVALID_OOXML_ZIP` | Office 修复提示不得被自动接受 | 自动通过 |
| 通用不支持 | `.txt/.pdf` 等 | BLOCKED | 核对 `OFFICE_UNSUPPORTED_EXTENSION` | 无 | 自动通过 |
| 通用路径 | 中文、Emoji、超长路径、网络盘、符号链接 | SAFE/HIGH/BLOCKED | JSONL 与路径边界测试 | 网络断开/重连与实际打开 | 待安全底座与真实环境 |
| 通用文件状态 | 只读、占用、磁盘不足、扫描中变化 | BLOCKED 或明确降级 | 哈希变化/读取错误注入 | Office 占用、磁盘故障验证 | 哈希不变自动通过；其余待测 |
| Worker 稳定性 | 崩溃、超时、残留进程 | BLOCKED/可恢复 | 后续故障注入 | 核对只结束任务 PID | 待生产 Worker |

## 3. 自动执行步骤

1. 运行 `cargo test --manifest-path src-tauri/Cargo.toml --test office_ooxml_worker_contract`。
2. 测试运行时生成无敏感合成 OOXML ZIP，不提交大文件。
3. 通过 stdin 连续写入 JSONL 请求，断言 stdout 每行一条 JSON，且请求 ID 对应。
4. 断言风险码/错误码稳定，响应不回显无效输入中的正文标记。
5. 对每个样本在执行前后独立计算 SHA-256，必须一致。

## 4. 人工执行步骤

1. 按 `src-tauri/tests/fixtures/office/README.md` 准备并登记真实无敏感样本。
2. 在至少两代 Office x64 机器执行同一组样本，记录 Office 完整版本、Windows 版本和哈希。
3. 高保真样本使用禁宏、不可见、独立 COM 实例；遇到提示或冲突立即记录并阻断，不自动点击。
4. 处理后用 Office 重新打开；按矩阵核对宏、公式、Story Range、主题、字体、母版、外链和签名。
5. 任一关键 invariant（不变量）不满足则降级为 HIGH 或 BLOCKED，不可写成“支持”。

## 5. 当前实测证据与缺口（2026-08-25）

- 自动契约测试 7/7 通过：JSONL、安全 OOXML、VBA/签名/外链、宏扩展/旧格式、损坏/不支持、伪装普通 ZIP、源哈希不变。
- 本机 Excel/Word/PowerPoint COM 均可创建并退出；Office 为 2019 x64，版本 `16.0.20416.20004`（应用 Build 20416）。
- 本机无可用 .NET SDK，因此尚未实现 .NET COM Worker。
- 只有一代 Office，计划要求的至少两代 Office 依赖未满足。
- 真实带宏 `.xlsm/.pptm`、签名/密码宏、复杂外链/母版尚待人工样本复验。

结论：Chunk 0 的路由技术尖峰可完成，但检查点 0 **不能通过**；必须补齐第二代 Office 与真实宏/高保真样本，并由用户确认支持矩阵后才能进入 Chunk 1。

## 术语表

| 术语 | 大白话 |
|---|---|
| 路由 | 根据风险决定交给普通引擎、高保真引擎或直接阻止 |
| invariant | 验证前后必须保持不变的关键事实 |
