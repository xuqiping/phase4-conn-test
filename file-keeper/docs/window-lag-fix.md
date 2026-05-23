# 窗口操作卡顿问题修复

## 问题描述
修复 Vue 生命周期警告后，应用出现新的性能问题：
- 滚动性能恢复正常
- **但是**最小化、最大化、移动窗口等操作反应很慢

## 根本原因

### 1. 重复的动态导入
每次处理图标队列时都执行 `await import('./api/icons')`，导致不必要的模块加载开销。

### 2. 递归调用阻塞主线程
`processIconQueue()` 在 `finally` 中直接递归调用自己，没有给主线程喘息的机会。

### 3. 并发数过高
`MAX_CONCURRENT_ICONS = 5` 可能在某些情况下导致过多的并发 I/O 操作。

## 解决方案

### 1. 缓存导入的函数
```typescript
let getFileIconFn: ((path: string) => Promise<string | null>) | null = null

// 只导入一次
if (!getFileIconFn) {
  const { getFileIcon } = await import('./api/icons')
  getFileIconFn = getFileIcon
}
```

### 2. 使用 requestIdleCallback 调度
```typescript
function scheduleIconProcessing() {
  if (processQueueTimer !== null) return

  const scheduleCallback = (window as any).requestIdleCallback ||
    ((cb: () => void) => setTimeout(cb, 16))

  processQueueTimer = scheduleCallback(() => {
    processQueueTimer = null
    processIconQueue()
  })
}
```

**优点**:
- `requestIdleCallback` 在浏览器空闲时执行，不阻塞用户交互
- 降级到 `setTimeout(cb, 16)` 确保兼容性（16ms ≈ 1 帧）
- 给主线程留出时间处理窗口操作等高优先级任务

### 3. 降低并发数
```typescript
const MAX_CONCURRENT_ICONS = 3 // 从 5 降到 3
```

### 4. 添加 rootMargin 优化
```typescript
{ threshold: 0.1, rootMargin: '50px' }
```
提前 50px 开始加载图标，改善用户体验。

### 5. 清理定时器
```typescript
onUnmounted(async () => {
  if (processQueueTimer !== null) {
    const cancelCallback = (window as any).cancelIdleCallback ||
      ((id: number) => clearTimeout(id))
    cancelCallback(processQueueTimer)
  }
  // ...
})
```

## 性能对比

### 修复前
- ❌ 每次队列处理都动态导入模块
- ❌ 递归调用阻塞主线程
- ❌ 5 个并发可能过多
- ❌ 窗口操作响应慢

### 修复后
- ✅ 模块只导入一次
- ✅ 使用 requestIdleCallback 避免阻塞
- ✅ 3 个并发更合理
- ✅ 窗口操作应该流畅

## 测试步骤

1. 重新启动应用：`npm run tauri:dev`
2. 生成 500 个测试文件
3. 测试以下操作：
   - ✅ 滚动性能（应该流畅）
   - ✅ 最小化/最大化窗口（应该快速响应）
   - ✅ 移动窗口（应该流畅）
   - ✅ 调整窗口大小（应该流畅）
4. 检查控制台无 Vue 警告

## 相关文件

- `src/App.vue` - 主要修改
  - 第 739-756 行：添加 `scheduleIconProcessing()` 函数
  - 第 757-787 行：优化 `processIconQueue()` 函数
  - 第 812 行：使用 `scheduleIconProcessing()` 代替直接调用
  - 第 820 行：添加 `rootMargin: '50px'`
  - 第 1192-1217 行：清理定时器

## 技术要点

### requestIdleCallback
- 浏览器 API，在主线程空闲时执行回调
- 不会阻塞用户交互、动画、窗口操作等高优先级任务
- 降级方案：`setTimeout(cb, 16)` 确保兼容性

### 为什么不用 requestAnimationFrame？
- `requestAnimationFrame` 在每一帧渲染前执行
- 图标加载不需要与渲染同步
- `requestIdleCallback` 更适合低优先级的后台任务

### 为什么降低并发数？
- 图标提取是 I/O 密集型操作
- 3 个并发足够保持流畅的加载体验
- 降低并发可以减少系统资源占用
