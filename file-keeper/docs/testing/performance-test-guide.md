# 性能测试执行指南

本文档提供详细的性能测试执行步骤。

---

## 准备工作
### 1. 构建应用

```bash
cd file-keeper
npm run tauri:dev
```

### 2. 打开开发者工具

- 按 `F12` 或右键 > 检查
- 切换到 Console 标签页

### 3. 加载测试数据生成脚本

1. 打开 `docs/testing/generate-test-data.js`
2. 复制全部内容
3. 粘贴到浏览器控制台并回车

---

## 测试 1: 启动时间测试

### 测试步骤

1. **准备不同数量的测试数据**

```javascript
// 测试 0 个文件
clearAllFiles()

// 测试 100 个文件
generateTestFiles(100, true)

// 测试 500 个文件
generateTestFiles(500, true)

// 测试 1000 个文件
generateTestFiles(1000, true)
```

2. **重启应用并记录启动时间**

- 关闭应用
- 重新启动 `npm run tauri:dev`
- 查看控制台输出的 `[Performance] App startup time`
- 记录 5 次测试的平均值

3. **记录结果到测试报告**

---

## 测试 2: 滚动性能测试

### 测试步骤

1. **生成 1000 个测试文件**

```javascript
generateTestFiles(1000, true)
```

2. **测试网格视图滚动**

- 切换到网格视图
- 打开 DevTools > Performance 标签页
- 点击 Record 按钮
- 快速滚动 10 秒
- 停止录制
- 查看 FPS 图表，记录平均帧率和最低帧率

3. **测试列表视图滚动**

- 切换到列表视图
- 重复上述步骤

4. **记录结果**

---

## 测试 3: 搜索响应时间测试

### 测试步骤

1. **生成 1000 个测试文件**

```javascript
generateTestFiles(1000, true)
```

2. **测试不同匹配数量的搜索**

在搜索框输入以下关键词，观察控制台输出的 `[Performance] Search filter` 时间：

- `测试文件_0999` (匹配 1 个)
- `工作` (匹配约 100 个)
- `测试` (匹配约 1000 个)
- `*.txt` (通配符搜索)

3. **记录响应时间**

注意：显示的时间不包含 300ms 防抖延迟

---

## 测试 4: 内存占用测试

### 测试步骤

1. **测试不同文件数量的内存占用**

```javascript
// 0 个文件
clearAllFiles()
```

- 打开 DevTools > Memory 标签页
- 点击 "Take heap snapshot"
- 记录 JS Heap Size

```javascript
// 100 个文件
generateTestFiles(100, true)
```

- 再次拍摄快照，记录内存

```javascript
// 500 个文件
generateTestFiles(500, true)
```

- 再次拍摄快照

```javascript
// 1000 个文件
generateTestFiles(1000, true)
```

- 再次拍摄快照

2. **测试操作后的内存占用**

- 滚动 10 秒
- 拍摄快照，记录"滚动后"内存
- 执行搜索操作
- 拍摄快照，记录"搜索后"内存

3. **记录结果**
---

## 测试 5: 图标懒加载效果验证

### 测试步骤

1. **生成测试文件**

```javascript
generateTestFiles(100, true)
```

2. **观察图标加载行为**

- 打开 DevTools > Network 标签页
- 滚动页面
- 观察是否只有可见卡片的图标被加载
- 检查并发数是否控制在 5 个以内

3. **验证队列机制**

- 快速滚动到底部
- 观察控制台是否有图标提取的日志
- 验证图标是否按需加载

---

## 测试 6: 虚拟滚动效果验证

### 测试步骤

1. **生成大量文件**

```javascript
generateTestFiles(1000, true)
```

2. **检查 DOM 节点数量**

- 打开 DevTools > Elements 标签页
- 查看网格视图容器中的卡片数量
- 应该只有可见区域 + 缓冲区的卡片（约 50-60 个）
- 滚动时观察 DOM 节点是否动态更新

3. **验证列表视图**

- 切换到列表视图
- 重复上述检查

---

## 性能优化前后对比

如果有 Phase 5 优化前的版本，可以进行对比测试：

1. 切换到优化前的分支
2. 执行相同的测试
3. 记录结果
4. 切换回 phase5 分支
5. 执行相同的测试
6. 对比结果

---

## 常见问题

### Q: 测试数据生成脚本无法运行

A: 确保应用已完全加载，并且在浏览器控制台中运行。如果仍然失败，尝试刷新页面。

### Q: 性能监控日志没有输出

A: 检查控制台过滤器，确保显示所有日志级别。

### Q: 内存快照太大无法分析

A: 使用 Chrome DevTools 的 "Comparison" 功能，对比两个快照的差异。

---

## 结果记录

将测试结果填写到 `v0.1.0-performance-test.md` 文档中。
