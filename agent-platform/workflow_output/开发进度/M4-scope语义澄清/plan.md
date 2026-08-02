# M4 — scope 语义澄清(原 20+21)

> 源:速查表09 待办 M4。纯前端,用户痛点:不懂「写目标(home)」vs「读范围(可见性)」分离。

## 目标
1. **预览总记忆开关**(原 20):MemoryManagerPanel 预览「指定项目」时,总记忆仍被注入 → 加显式「包含总记忆」开关,指定项目默认关。
2. **对话底栏写/读区分**(原 21):ChatView tools 区写目标/读范围混淆 → 分组 + 改名 + tooltip。

## Chunk 拆分
- **chunk1**:预览总记忆开关(MemoryManagerPanel.vue)。改 `effectivePreviewScope` + 加 switch。
- **chunk2**:对话底栏区分(ChatView.vue)。纯模板重构,零 store/后端改动。
- **chunk3**:编译双绿 + vue-tsc + 前端测试 + commit。

## 功能联动点清单(v1.9 三处对齐)
| 触发动作 | 联动对象 | 预期 |
|---|---|---|
| 预览模式切「指定项目」 | 「包含总记忆」开关 | 显现,默认 OFF |
| 开关 ON | 预览 scope.includeGlobal | true → 预览注入含 global |
| 开关 OFF | 预览 scope.includeGlobal | false → 仅指定项目 |
| 模式切回「默认/仅总记忆/全部」 | 开关 | 隐藏(仅 custom 显) |
| 底栏「记忆落库于」选项目 | memProjectId | 写目标变 |
| 底栏「读取记忆范围」多选 | memReadProjectIds | 读范围变,独立于写目标 |

反向/半选:指定项目不选任一项目 + 开关 OFF → 预览空注入(不崩);多选清空 → 仅总记忆(若 switch on)。

## 安全检查清单
- [x] 无新端点/无新权限(纯前端)。写目标仍走既有 `memProjectId`,读范围走 `memReadProjectIds`,后端鉴权未变。
- [x] 预览 scope 仍经既有 `/api/chat/memories/preview`(已鉴权),payload 仅补 `includeGlobal`。

## 运维考量清单
- [x] 日志:前端无;后端 preview 入口已有 traceId 记忆日志,不动。
- [x] 开关/降级:开关默认 OFF(custom)是更保守注入,无降级风险。
- [x] 不涉建表/迁移。

## 测试方案
见同目录 `M4-scope语义澄清测试方案.md`(UI 联动 + playwright-mcp 自动化冒烟)。

## 受众
B/C 类(用户直接操作 UI)→ 产 README + UserOps + FeatureMap + 测试方案。

---

## 迭代 2:底栏全下拉化(2026-07-18,用户反馈"混杂看不明白")

> 选定方案 B:switch 全转下拉,动作按钮转无文字小图标。

### Chunk
- **chunk-A**:记忆模式 switch + 跟随 button → 单个 3 态下拉(`跟随全局` / `本会话开` / `本会话关`),跟随 button 并入消失。
- **chunk-B**:总记忆 switch → 开/关 小下拉(读范围内)。
- **chunk-C**:项目 button、记忆 button → 无文字 n-button + NIcon(FolderOpenOutline / BookmarksOutline),记忆保留 badge。
- **chunk-D**:vue-tsc + 99/99 + commit + playwright 冒烟。

### 联动点(增量)
| 触发 | 联动 | 预期 |
|---|---|---|
| 记忆模式下拉切「跟随全局」 | ragPref | null(继承) |
| 切「本会话开/关」 | ragPref | true/false,发消息带 ragEnabled |
| 总记忆下拉切开/关 | memIncludeGlobal | 变,独立于写目标 |
| 点项目图标 | showProjectManager | true,弹项目管理 |
| 点记忆图标 | showMemory | true,弹记忆抽屉 |

### 安全/运维
无新端点/权限/迁移,纯模板+脚本。图标走既有 @vicons 包。

