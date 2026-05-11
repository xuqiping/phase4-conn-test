# File Keeper 数据持久化方案设计

> **创建日期：** 2026-05-10
> **项目阶段：** Phase 2 - MVP 核心功能开发
> **设计者：** Claude
> **状态：** 待实施

---

## 1. 设计概述

为 File Keeper 实现完整的应用状态持久化功能，让用户的数据在应用重启后保持。

### 1.1 持久化范围

采用**完整应用状态持久化**方案，覆盖以下三类数据：

| 数据类别 | 内容 | Store |
|---------|------|-------|
| 文件数据 | 收藏的文件列表（路径、名称、标签、打开次数等） | `fileStore` |
| 分组数据 | 用户自定义的分组配置 | `groupStore` |
| 应用设置 | 主题、视图模式、窗口位置等 | `settingsStore` |

### 1.2 保存策略

采用**混合保存策略**，平衡数据安全性与性能：

| 操作类型 | 保存时机 | 示例 |
|---------|---------|------|
| 重要操作 | 立即保存 | `addFile`、`removeFile`、`addGroup`、`removeGroup`、`updateSettings` |
| 次要操作 | 防抖保存（500ms） | `recordOpen`、`setSearchQuery`、`updateFile`（部分字段） |

---

## 2. 方案选型

### 2.1 候选方案

#### 方案 A：Pinia Plugin + Tauri Store ✅ **已选定**

**架构：**
- 创建 Pinia 持久化插件，自动监听 store 变化
- 使用 `tauri-plugin-store` 的 JSON 文件存储
- 重要操作立即调用 `store.save()`
- 次要操作使用 500ms 防抖

**优点：**
- 代码集中，Pinia store 无需手动调用保存
- 类型安全，TypeScript 全程支持
- 易于测试和维护
- 符合 Vue 生态标准做法
- 易于扩展（未来新增 store 自动获得持久化能力）

**缺点：**
- 需要编写自定义 Pinia 插件
- 所有数据在一个 JSON 文件中（但可分多个 store 实例规避）

#### 方案 B：手动 Rust 命令

**架构：**
- 在 Rust 端编写 `save_files`、`load_files`、`save_settings` 等命令
- 前端 store 的 action 中手动调用 Rust 命令
- 使用 Rust 的 `serde_json` 序列化

**优点：**
- 完全控制存储逻辑
- 可使用更复杂的存储格式（SQLite、二进制等）

**缺点：**
- 代码分散在前后端
- 需要维护更多 Rust 代码
- 容易遗漏保存调用
- 不符合 Vue 生态最佳实践

#### 方案 C：LocalStorage + Tauri Store 双备份

**架构：**
- 使用浏览器 LocalStorage 作为主存储
- 定期同步到 Tauri Store 作为备份
- 启动时优先读取 Tauri Store

**优点：**
- LocalStorage 访问速度快
- 有备份机制

**缺点：**
- 数据冗余
- 同步逻辑复杂
- LocalStorage 有大小限制（通常 5-10MB）
- 不适合桌面应用场景

### 2.2 选定方案的理由

**选择方案 A** 的核心理由：

1. **符合 Vue 生态** - Pinia 插件是行业标准做法
2. **代码简洁** - store 逻辑无需关心持久化细节
3. **易于扩展** - 未来添加新 store 自动获得持久化能力
4. **混合策略易实现** - 插件可区分操作类型，应用不同保存策略
5. **类型安全** - TypeScript 全程类型检查

---

## 3. 技术架构

### 3.1 整体架构图

```
┌─────────────────────────────────────────────────┐
│                   Vue 组件层                     │
│         (App.vue, FileCard.vue, ...)            │
└────────────────────┬────────────────────────────┘
                     │ 调用 store actions
                     ▼
┌─────────────────────────────────────────────────┐
│              Pinia Store 层                      │
│   fileStore │ groupStore │ settingsStore        │
└────────────────────┬────────────────────────────┘
                     │ 自动触发持久化
                     ▼
┌─────────────────────────────────────────────────┐
│         Pinia 持久化插件 (新增)                  │
│  - 监听 state 变化                              │
│  - 区分重要/次要操作                            │
│  - 防抖控制                                     │
└────────────────────┬────────────────────────────┘
                     │ 调用 Tauri API
                     ▼
┌─────────────────────────────────────────────────┐
│         Tauri Store Plugin                       │
│         (tauri-plugin-store)                     │
└────────────────────┬────────────────────────────┘
                     │ 写入文件系统
                     ▼
┌─────────────────────────────────────────────────┐
│         本地 JSON 文件存储                       │
│  - files.json (文件列表)                        │
│  - groups.json (分组数据)                       │
│  - settings.json (应用设置)                     │
└─────────────────────────────────────────────────┘
```

### 3.2 文件结构变更

```
file-keeper/
├── src/
│   ├── plugins/                  # 【新增】插件目录
│   │   └── persistPlugin.ts      # 【新增】Pinia 持久化插件
│   ├── stores/
│   │   ├── fileStore.ts          # 【修改】添加持久化配置
│   │   ├── groupStore.ts         # 【修改】添加持久化配置
│   │   └── settingsStore.ts      # 【修改】添加持久化配置
│   ├── api/
│   │   └── persist.ts            # 【新增】持久化 API 封装
│   └── main.ts                   # 【修改】注册持久化插件
└── src-tauri/
    └── tauri.conf.json           # 【验证】确保 store 插件已配置
```

### 3.3 核心组件设计

#### 3.3.1 持久化 API 封装 (`src/api/persist.ts`)

```typescript
// 封装 tauri-plugin-store 的读写操作
export interface PersistAPI {
  load<T>(key: string, defaultValue: T): Promise<T>
  save<T>(key: string, value: T): Promise<void>
  remove(key: string): Promise<void>
}
```

**职责：**
- 抽象底层 Tauri Store 调用
- 处理序列化/反序列化
- 提供错误处理

#### 3.3.2 Pinia 持久化插件 (`src/plugins/persistPlugin.ts`)

```typescript
export interface PersistOptions {
  key: string                    // 存储 key
  paths?: string[]               // 需要持久化的字段（不指定则全部）
  debounceActions?: string[]     // 需要防抖的 action 名称
  debounceMs?: number           // 防抖时长（默认 500ms）
}

export function createPersistPlugin(): PiniaPlugin
```

**职责：**
- 启动时自动加载数据到 store
- 监听 store 变化并触发保存
- 区分重要/次要操作执行不同策略
- 防抖控制

#### 3.3.3 Store 配置示例

```typescript
// fileStore.ts
export const useFileStore = defineStore('file', () => {
  // ...现有代码
}, {
  persist: {
    key: 'files',
    paths: ['files'],  // 不持久化 searchQuery、currentGroupId
    debounceActions: ['recordOpen', 'updateFile']  // 这些操作防抖保存
  }
})
```

---

## 4. 数据流设计

### 4.1 应用启动流程

```
1. main.ts 创建 Pinia 实例
2. 注册持久化插件
3. 创建各 store 实例时，插件自动：
   a. 从 Tauri Store 读取对应数据
   b. 写入 store state
   c. 标记为"已加载"状态
4. UI 组件读取 store 数据渲染
```

### 4.2 数据修改流程（重要操作）

```
1. 用户操作（如点击"添加文件"）
2. 组件调用 store.addFile(...)
3. store state 变化
4. 持久化插件检测到变化
5. 判断为重要操作 → 立即调用 persist.save()
6. tauri-plugin-store 写入 files.json
7. 完成
```

### 4.3 数据修改流程（次要操作）

```
1. 用户操作（如点击文件触发 recordOpen）
2. 组件调用 store.recordOpen(id)
3. store state 变化（openCount + 1）
4. 持久化插件检测到变化
5. 判断为次要操作 → 启动防抖计时器（500ms）
6. 500ms 内若无新变化 → 调用 persist.save()
7. 500ms 内若有新变化 → 重置计时器
```

---

## 5. 错误处理

### 5.1 加载失败

| 场景 | 处理策略 |
|------|---------|
| 文件不存在（首次启动） | 使用默认值，不报错 |
| 文件损坏（JSON 解析失败） | 使用默认值，记录错误日志，提示用户 |
| 权限不足 | 提示用户，建议手动检查 |

### 5.2 保存失败

| 场景 | 处理策略 |
|------|---------|
| 磁盘空间不足 | 弹窗提示用户 |
| 权限不足 | 提示用户，操作不阻塞 |
| 临时性错误 | 自动重试 1 次，仍失败则提示 |

### 5.3 数据迁移

预留版本号字段，未来 schema 变更时可执行迁移：

```json
{
  "version": 1,
  "data": { ... }
}
```

---

## 6. 测试策略

### 6.1 单元测试

- ✅ 持久化 API 的 load/save/remove 函数
- ✅ 防抖逻辑正确性
- ✅ 重要/次要操作区分逻辑

### 6.2 集成测试

- ✅ 启动时数据加载
- ✅ 添加文件后重启验证持久化
- ✅ 修改设置后重启验证持久化
- ✅ 数据损坏时降级到默认值

### 6.3 手动测试清单

- [ ] 添加多个文件后重启，文件列表保持
- [ ] 创建分组后重启，分组保持
- [ ] 切换主题后重启，主题保持
- [ ] 多次点击文件（recordOpen）后等待 1 秒重启，打开次数正确保存
- [ ] 删除存储文件后启动，应用使用默认值
- [ ] 手动篡改 JSON 后启动，应用降级到默认值并提示

---

## 7. 实施计划

### 7.1 任务分解

| 序号 | 任务 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 验证 `pnpm tauri:dev` 可正常运行 | 0.5h | P0 |
| 2 | 创建 `src/api/persist.ts` 持久化 API 封装 | 1h | P0 |
| 3 | 创建 `src/plugins/persistPlugin.ts` Pinia 插件 | 2h | P0 |
| 4 | 修改 `fileStore.ts` 添加持久化配置 | 0.5h | P0 |
| 5 | 修改 `groupStore.ts` 添加持久化配置 | 0.5h | P0 |
| 6 | 修改 `settingsStore.ts` 添加持久化配置 | 0.5h | P0 |
| 7 | 修改 `main.ts` 注册插件 | 0.3h | P0 |
| 8 | 编写测试用例 | 1.5h | P1 |
| 9 | 手动测试与调试 | 1h | P0 |
| 10 | 更新开发进度文档 | 0.2h | P1 |

**总预计工时：** 约 8 小时（1 个工作日）

### 7.2 验收标准

- [ ] 添加文件后重启应用，文件列表保持
- [ ] 创建/删除分组后重启，分组数据保持
- [ ] 切换主题/视图模式后重启，设置保持
- [ ] 防抖保存正确工作（频繁操作不阻塞 UI）
- [ ] 数据损坏时优雅降级
- [ ] 启动时间无明显增加（< 100ms 加载耗时）

---

## 8. 后续计划

数据持久化完成后，按照 [开发进度.md](../../开发进度.md) 的优先级进行：

### 8.1 紧接着的任务（Phase 2 剩余）

1. **文件操作功能（Phase 2.2 / 2.3）**
   - 实现文件选择对话框（调用 `tauri-plugin-dialog`）
   - 连接前端"添加文件"按钮到 Rust `validate_path`
   - 连接前端文件点击到 Rust `open_file`
   - 实现拖拽添加文件
   - 错误处理（文件不存在、无权限）

2. **窗口控制 API**
   - 实现最小化/最大化/关闭按钮（当前是 TODO）
   - 使用 `@tauri-apps/api/window`

### 8.2 Phase 3：增强功能（核心特色）

1. **进程管理（最重要的差异化功能）**
   - Rust 端实现 Windows/macOS/Linux 进程枚举
   - 实现进程匹配（通过窗口标题、文件描述符）
   - 实现关闭进程功能
   - 多实例处理

2. **批量操作**
   - 多选文件
   - 批量打开/移动/删除/添加标签

3. **全局快捷键与托盘**

### 8.3 Phase 4：UI 增强

1. 文件图标提取
2. 拖拽排序
3. 主题系统完善

### 8.4 Phase 5：打包发布

1. 性能优化
2. 跨平台测试
3. 打包发布

---

## 9. 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| `tauri-plugin-store` API 与预期不符 | 高 | 先编写小型 demo 验证 API |
| Pinia 插件与 setup store 兼容性 | 中 | 充分测试，必要时改用 options store |
| 数据量大时性能问题 | 低 | 本期聚焦正确性，后续做虚拟滚动 |
| Windows 路径分隔符问题 | 中 | 统一使用正斜杠存储 |

---

## 10. 参考资料

- [Tauri Plugin Store 官方文档](https://tauri.app/plugin/store/)
- [Pinia 插件开发指南](https://pinia.vuejs.org/core-concepts/plugins.html)
- [开发进度.md](../../开发进度.md)
- [技术选型.md](../../技术选型.md)

---

**文档维护者：** Claude
**最后更新：** 2026-05-10
