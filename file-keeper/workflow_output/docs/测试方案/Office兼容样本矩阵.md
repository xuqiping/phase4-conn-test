# Office 兼容样本矩阵

> Chunk 0 的自动与人工兼容验证基准。当前结论仅支持“路由技术尖峰完成”，不支持“完整兼容性检查点通过”。

## 1. 路由判定

| 判定 | 含义 | 当前自动规则 |
|---|---|---|
| `SAFE_OOXML` | 通过技术尖峰最小结构校验的标准 OOXML 候选，可交给跨平台 Worker 的安全子集 | ZIP 可读；存在 `[Content_Types].xml`、根 `_rels/.rels` 和扩展对应主部件；安全解析真实 XML 元素后，根关系及内容类型与主部件基本一致；未发现 VBA、数字签名或外部关系 |
| `HIGH_FIDELITY_REQUIRED` | 必须交给安装 Office 的 Windows Worker 或等待人工确认 | `.xls/.doc/.ppt`、`.xlsm/.docm/.pptm`、包内 `vbaProject.bin`、数字签名或 `TargetMode=External` |
| `BLOCKED` | 当前不能安全处理 | 不支持扩展、损坏 ZIP、非法/重复 entry 名、缺根关系/主部件、扩展与包类型错配、XML 含 DTD/结构无效、资源预算超限、不可读源或扫描期间源变化 |

路由器只读 ZIP entries 与 `.rels`，不落盘解压、不重写源包，不输出密码或文档正文。每次检查前后计算 SHA-256；不一致则阻止。这里的“最小结构校验通过”只证明路由所需骨架自洽，不等于 Microsoft Office 已实际打开验证，也不代表公式、样式、宏或母版保真。

## 2. 完整样本矩阵

| 应用/场景 | 样本 | 预期 | 自动步骤 | 真实 Office 人工步骤 | 当前状态 |
|---|---|---|---|---|---|
| Excel 标准 | `.xlsx` 基础结构 | SAFE | JSONL inspect；核对最小包结构、码与源哈希 | 打开/另存/重开；核对 Sheet/样式 | 合成最小自洽包自动通过；真实样本待测 |
| Excel 公式 | 跨 Sheet、命名区域、数组公式 | SAFE 或按复杂度升级 | 扫描结构与关系 | 核对公式文本和计算结果 | 待真实样本 |
| Excel 外链 | 外部工作簿关系 | HIGH | 检出 External relationship | 换源/刷新前后核对 | 合成关系自动通过；真实待测 |
| Excel 宏 | `.xlsm`、包内 VBA | HIGH | 扩展/VBA 风险码 | 禁宏打开；核对工程、引用、签名 | 合成结构自动通过；真实宏待测 |
| Excel 签名/密码宏 | 签名或受保护 VBA | HIGH/阻断冲突 | 检出签名部件 | 核对签名失效提示；不破解密码 | 待真实样本 |
| Excel 旧格式 | `.xls` | HIGH | 按扩展路由，不解析二进制 | Office 打开/另存/重开 | 自动路由通过；真实待测 |
| Excel 富结构 | 条件格式、合并、表格、图表、隐藏 Sheet | SAFE 或 HIGH | 基础路由 | 逐项结构和视觉比较 | 待真实样本 |
| Word 标准 | `.docx` 基础结构 | SAFE | JSONL inspect；最小包结构与源哈希 | 打开/另存/重开 | 合成最小自洽包自动通过；真实待测 |
| Word 范围 | 跨 Run、表格、页眉页脚、脚注尾注、文本框 | SAFE/HIGH | 基础路由 | 逐 Story Range 肉眼核对 | 待真实样本 |
| Word 排除内容 | 批注、修订 | SAFE/HIGH | 基础路由 | 确认内容不变且报告说明排除 | 待真实样本 |
| Word 宏/旧格式 | `.docm/.doc` | HIGH | 扩展路由 | 禁宏打开/另存/重开 | 自动路由通过；真实待测 |
| PowerPoint 标准 | `.pptx` 基础结构 | SAFE | JSONL inspect；最小包结构与源哈希 | 打开/放映/重开 | 合成最小自洽包自动通过；真实待测 |
| PPT 主题 | 多母版、版式、字体缺失、图表 | SAFE/HIGH | 基础路由 | 逐页视觉比较与字体检查 | 待真实样本 |
| PPT Excel 外链 | 图表/对象外部关系 | HIGH | 检出 External relationship | 用户确认换源后刷新核对 | 合成关系自动通过；真实待测 |
| PPT 嵌入对象 | 内嵌 Excel/OLE | SAFE/HIGH | 不把内嵌对象误报为外链 | 确认对象可打开且不换源 | 待真实样本 |
| PPT 宏/旧格式 | `.pptm/.ppt` | HIGH | 扩展/VBA 风险码 | 禁宏打开；核对工程/签名 | 自动路由通过；真实 `.pptm` 待测 |
| 通用损坏/错配 | 损坏 ZIP、缺主部件、扩展与包类型错配 | BLOCKED | 核对 `OFFICE_INVALID_OOXML_ZIP`、`OFFICE_OOXML_MAIN_PART_MISSING`、`OFFICE_OOXML_TYPE_MISMATCH` | Office 修复提示不得被自动接受 | 自动通过 |
| 超大关系 | 解压后超过 4 MiB 的 `.rels` | BLOCKED | 核对 `OFFICE_RELATIONSHIP_TOO_LARGE`，禁止截断后判 SAFE | 大文件按风险报告人工处置 | 自动通过 |
| XML 元数据伪造 | 注释内伪造 `Override/Relationship` | BLOCKED | 只识别真实 XML 元素，注释不得使残缺包通过 | 无 | 自动通过 |
| XML 字符引用 | `TargetMode="Extern&#97;l"` | HIGH | 解码字符引用后检出 External relationship | 核对外链处置提示 | 自动通过 |
| XML DTD | 合法 `Override` 后追加 DTD | BLOCKED | 必须扫描至 EOF 并拒绝 DTD，不得找到合法元素后提前放行 | 无 | 自动通过 |
| XML 结构/命名空间 | wrong-root、wrong-namespace、嵌套伪元素、多根、尾随元素 | BLOCKED | 根和直接子级必须使用 OPC 规范命名空间；不接受任意层级 local-name 命中 | 无 | 自动通过 |
| ZIP entry 名 | 反斜杠、前导 `/`、空段、`.`/`..`、drive-like、ASCII 大小写归一后重名 | BLOCKED | 返回 `OFFICE_INVALID_ZIP_ENTRY_NAME` 或 `OFFICE_DUPLICATE_ZIP_ENTRY` | 无 | 自动通过反斜杠、点段、重复关键 entry |
| JSONL 恢复 | 合法→坏 JSON→合法；超大行→合法 | 逐行独立响应 | 单行最多 1 MiB；超限排空到换行并继续下一请求 | 无 | 自动通过 |
| ZIP 资源预算 | entry 过多、累计 XML、压缩比炸弹、超时、超大源 | BLOCKED | 10 万 entries、累计 XML 6 MiB、单 XML 4 MiB、1000:1、30 秒、源 2 GiB | 无 | 累计 XML 自动通过；其余代码边界 |
| 通用不支持 | `.txt/.pdf` 等 | BLOCKED | 核对 `OFFICE_UNSUPPORTED_EXTENSION` | 无 | 自动通过 |
| 通用路径 | 中文、Emoji、超长路径、网络盘、符号链接 | SAFE/HIGH/BLOCKED | JSONL 与路径边界测试 | 网络断开/重连与实际打开 | 待安全底座与真实环境 |
| 通用文件状态 | 只读、占用、磁盘不足、扫描中变化 | BLOCKED 或明确降级 | 哈希变化/读取错误注入 | Office 占用、磁盘故障验证 | 哈希不变自动通过；其余待测 |
| Worker 稳定性 | 崩溃、超时、残留进程 | BLOCKED/可恢复 | 后续故障注入 | 核对只结束任务 PID | 待生产 Worker |

## 3. 自动执行步骤

1. 运行 `cargo test --manifest-path src-tauri/Cargo.toml --test office_ooxml_worker_contract`。
2. 测试运行时生成无敏感合成 OOXML ZIP，不提交大文件。
3. 通过 stdin 连续写入 JSONL 请求，断言 stdout 每行一条 JSON，且请求 ID 对应。
4. 使用命名空间感知的 XML 事件解析断言规范根、直接子级和主部件一致；注释/嵌套伪元素不得命中，字符引用必须解码，DTD/多根/尾随元素必须拒绝。
5. 校验原始 ZIP entry 名和 ASCII 大小写归一后的唯一性；禁止修正反斜杠或点段后继续扫描。
6. 校验 1 MiB JSONL 行限制与排空恢复；校验 ZIP/XML 资源预算和稳定错误码。
7. 对每个样本在执行前后计算同一已打开文件句柄的 SHA-256，并核对 length/modified；必须一致。

## 4. 人工执行步骤

1. 按 `src-tauri/tests/fixtures/office/README.md` 准备并登记真实无敏感样本。
2. 在至少两代 Office x64 机器执行同一组样本，记录 Office 完整版本、Windows 版本和哈希。
3. 高保真样本使用禁宏、不可见、独立 COM 实例；遇到提示或冲突立即记录并阻断，不自动点击。
4. 处理后用 Office 重新打开；按矩阵核对宏、公式、Story Range、主题、字体、母版、外链和签名。
5. 任一关键 invariant（不变量）不满足则降级为 HIGH 或 BLOCKED，不可写成“支持”。

## 5. 当前实测证据与缺口（2026-08-25）

- 自动契约测试 21/21 通过：JSONL 顺序恢复/超大行排空、严格 XML 根/命名空间/层级/单根、非法与重复 ZIP entry、累计 XML 预算，以及既有 OOXML 路由、风险码和源哈希不变。
- 本机 Excel/Word/PowerPoint COM 均可创建并退出；Office 为 2019 x64，版本 `16.0.20416.20004`（应用 Build 20416）。
- 本机无可用 .NET SDK，因此尚未实现 .NET COM Worker。
- 只有一代 Office，计划要求的至少两代 Office 依赖未满足。
- 真实带宏 `.xlsm/.pptm`、签名/密码宏、复杂外链/母版尚待人工样本复验。
- Worker 已避免按路径重复打开，实际扫描对象与返回哈希来自同一文件句柄；跨平台执行票据仍未绑定平台级 file identity，需在生产安全底座补齐。

结论：Chunk 0 的路由技术尖峰可完成，但检查点 0 **不能通过**；必须补齐第二代 Office 与真实宏/高保真样本，并由用户确认支持矩阵后才能进入 Chunk 1。

## 术语表

| 术语 | 大白话 |
|---|---|
| 路由 | 根据风险决定交给普通引擎、高保真引擎或直接阻止 |
| invariant | 验证前后必须保持不变的关键事实 |
