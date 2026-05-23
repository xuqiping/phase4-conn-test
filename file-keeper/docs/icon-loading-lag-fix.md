# 图标懒加载卡顿问题修复

## 问题描述
切换分组（例如从其他栏目切换到"全部"）时，所有文件的图标同时加载，导致明显卡顿。

## 根本原因

### 1. 虚拟滚动重新渲染
切换分组时，`fileStore.filteredFiles` 改变，虚拟滚动重新渲染所有可见卡片，每个卡片都调用 `setupIconLazyLoad`。

### 2. IntersectionObserver 立即触发
所有可见元素同时进入视口，IntersectionObserver 同时触发所有回调，导致大量图标同时进入加载队列。

### 3. 同步处理队列
`processIconQueue()` 直接递归调用自己，没有给主线程喘息的机会，阻塞了 UI 渲染。

### 4. 并发数过高
`MAX_CONCURRENT_ICONS = 5` 在切换分组时可能导致过多的并发 I/O 操作。

## 解决方案

### 1. 使用 requestIdleCallback 调度
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
- 在浏览器空闲时处理图标队列
- 不阻塞 UI 渲染和用户交互
- 切换分组时不会卡顿

### 2. 降低并发数
```typescript
const MAX_CONCURRENT_ICONS = 3 // 从 5 降到 3
```

减少同时进行的 I/O 操作，降低系统负载。

### 3. 缓存导入的函数
```typescript
let getFileIconFn: ((path: string) => Promise<string | null>) | null = null

if (!getFileIconFn) {
  const { getFileIcon } = await import('./api/icons')
  getFileIconFn = getFileIcon
}
```

避免重复的动态导入开销。

### 4. 添加 rootMargin
```typescript
{
  threshold: 0.1,
  rootMargin: '100px' // 提前 100px 开始加载
}
```

提前加载即将进入视口的图标，改善用户体验。

### 5. 清理定时器
```typescript
onUnmounted(() => {
  if (processQueueTimer !== null) {
    const cancelCallback = (window as any).cancelIdleCallback ||
      ((id: number) => clearTimeout(id))
    cancelCallback(processQueueTimer)
  }
  // ...
})
```

防止内存泄漏。

## 工作流程

### 修复前
```
切换分组
  ↓
虚拟滚动重新渲染 50 个可见卡片
  ↓
50 个 IntersectionObserver 同时触发
  ↓
50 个图标同时进入队列
  ↓
processIconQueue() 立即处理（阻塞主线程）
  ↓
5 个并发加载
  ↓
UI 卡顿 ❌
```

### 修复后
```
切换分组
  ↓
虚拟滚动重新渲染 50 个可见卡片
  ↓
50 个 IntersectionObserver 同时触发
  ↓
50 个图标进入队列
  ↓
scheduleIconProcessing() 调度（不阻塞）
  ↓
浏览器空闲时处理队列
  ↓
3 个并发加载（降低负载）
  ↓
UI 流畅 ✅
```

## 性能对比

### 修复前
- ❌ 切换分组时明显卡顿
- ❌ 5 个并发可能过多
- ❌ 递归调用阻塞主线程
- ❌ 重复动态导入模块

### 修复后
- ✅ 切换分组流畅
- ✅ 3 个并发更合理
- ✅ requestIdleCallback 不阻塞
- ✅ 模块只导入一次

## 测试步骤

1. 重新启动应用：`npm run tauri:dev`
2. 生成 1000 个测试文件：
   ```javascript
   generateTestFiles(1000, true)
   ```
3. 测试切换分组：
   - 从"全部"切换到其他分组
   - 再切换回"全部"
   - 观察是否还有卡顿
4. 测试滚动：
   - 快速滚动
   - 观察图标是否按需加载
   - 检查性能

## 相关文件

- `src/App.vue`
  - 第 739-756 行：添加 `scheduleIconProcessing()` 函数
  - 第 757-787 行：优化 `processIconQueue()` 函数
  - 第 812 行：使用 `scheduleIconProcessing()` 代替直接调用
  - 第 820-823 行：添加 `rootMargin: '100px'`
  - 第 1217-1229 行：清理定时器

## 技术要点

### requestIdleCallback
- 浏览器 API，在主线程空闲时执行回调
- 优先级低于渲染、用户交互等任务
- 适合处理非紧急的后台任务（如图标加载）

### 为什么不用 setTimeout(0)？
- `setTimeout(0)` 实际延迟约 4ms
- 仍然会在主线程繁忙时执行
- `requestIdleCallback` 会等到真正空闲时才执行

### 为什么降低并发数？
- 图标提取是 I/O 密集型操作
- 3 个并发足够保持流畅的加载体验
- 降低并发可以减少系统资源占用
- 避免在切换分组时产生过多的并发请求

## 预期效果

- 切换分组时不再卡顿
- 图标按需懒加载
- 滚动流畅
- 窗口操作响应快速
