# 性能优化说明

## 问题诊断

### 原始问题
1. **Vue 生命周期警告**: `onMounted` 在非组件上下文中被调用
2. **严重卡顿**: 500 个文件时滚动非常卡顿

### 根本原因
使用 `watch` 替代 `onMounted` 后，每次虚拟滚动更新 DOM 时都会：
- 触发 `watch` 回调
- 创建新的 `IntersectionObserver`
- 断开旧的 observer

这导致在滚动时创建和销毁大量 observer，严重影响性能。

## 解决方案

### 使用单个全局 IntersectionObserver

**优点**:
- 只创建一次 observer（在第一次调用时）
- 所有元素共享同一个 observer
- 避免频繁创建/销毁 observer 的开销
- 使用 `Map` 跟踪元素和文件的映射关系

**实现位置**: `src/App.vue` 第 733-803 行

```typescript
// 单个全局 observer
const iconObserver = ref<IntersectionObserver | null>(null)
const observedElements = new Map<HTMLElement, FileItem>()

function setupIconLazyLoad(el: HTMLElement | null, file: FileItem) {
  if (!el || file.icon) return

  // 只在第一次调用时创建 observer
  if (!iconObserver.value) {
    iconObserver.value = new IntersectionObserver(...)
  }

  // 记录映射并开始观察
  observedElements.set(el, file)
  iconObserver.value.observe(el)
}
```

## 性能对比

### 修复前（使用 watch）
- 每个可见元素创建一个 observer
- 滚动时频繁创建/销毁 observer
- 500 个文件时严重卡顿

### 修复后（单个全局 observer）
- 整个应用只有一个 observer
- 滚动时只是添加/移除观察目标
- 性能应该恢复到 Phase 5 优化前的水平

## 测试步骤

1. 启动应用: `npm run tauri:dev`
2. 生成 500 个测试文件
3. 测试滚动性能
4. 检查控制台是否还有 Vue 警告

## 相关文件

- `src/App.vue` - 主要修改
- `src/composables/useIconLazyLoad.ts` - 不再使用（可以删除）
- `src/main.ts` - 暴露 `window.__PINIA__` 用于测试
- `src/vite-env.d.ts` - Vite 类型定义
