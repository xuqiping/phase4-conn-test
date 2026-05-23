# 状态栏不可见问题修复

## 问题描述
用户看不到底部的视图切换按钮（网格/列表视图切换）。

## 根本原因

### 布局问题
主内容区使用了 `flex-1 overflow-auto`，导致：
1. 主内容区占据所有剩余空间
2. 内部的虚拟滚动容器使用固定高度 `calc(100vh - 220px)`
3. 固定高度可能超出 flex 容器，导致状态栏被推到视口外

### 布局结构
```
<div class="min-h-screen flex flex-col">
  <!-- 标题栏 (h-10) -->
  <!-- 工具栏 (py-4) -->
  <!-- 分组栏 (py-3) -->
  <!-- 主内容区 (flex-1 overflow-auto) -->  ❌ 问题所在
    <div style="height: calc(100vh - 220px)">  ❌ 固定高度
  <!-- 状态栏 (h-10) -->  ❌ 被推到视口外
</div>
```

## 解决方案

### 1. 修改主内容区
将 `overflow-auto` 改为 `overflow-hidden`，让虚拟滚动容器自己处理滚动：

```vue
<!-- 修复前 -->
<div class="flex-1 overflow-auto p-6 ...">

<!-- 修复后 -->
<div class="flex-1 p-6 ... overflow-hidden">
```

### 2. 网格视图使用 100% 高度
移除固定高度，使用 `h-full` 填充父容器：

```vue
<!-- 修复前 -->
<div
  ref="gridContainerRef"
  class="relative overflow-y-auto"
  style="height: calc(100vh - 220px);"
>

<!-- 修复后 -->
<div
  ref="gridContainerRef"
  class="relative overflow-y-auto h-full"
>
```

### 3. 列表视图使用 flex 布局
列表视图有表头，需要使用 flex 布局：

```vue
<!-- 修复前 -->
<div v-else class="flex flex-col ... overflow-hidden">
  <div><!-- 表头 --></div>
  <div
    class="relative overflow-y-auto"
    style="height: calc(100vh - 280px);"
  >

<!-- 修复后 -->
<div v-else class="flex flex-col ... overflow-hidden h-full">
  <div><!-- 表头 --></div>
  <div
    class="relative overflow-y-auto flex-1"
  >
```

## 修复后的布局
```
<div class="min-h-screen flex flex-col">
  <!-- 标题栏 (h-10) -->
  <!-- 工具栏 (py-4) -->
  <!-- 分组栏 (py-3) -->
  <!-- 主内容区 (flex-1 overflow-hidden) -->  ✅ 不滚动
    <div class="h-full">  ✅ 填充父容器
      <!-- 虚拟滚动容器处理滚动 -->
    </div>
  <!-- 状态栏 (h-10) -->  ✅ 始终可见
</div>
```

## 测试步骤

1. 重新启动应用：`npm run tauri:dev`
2. 检查底部状态栏是否可见
3. 点击网格/列表视图切换按钮
4. 验证两种视图都能正常显示和滚动

## 相关文件

- `src/App.vue`
  - 第 182 行：主内容区 - 改为 `overflow-hidden`
  - 第 200 行：网格视图容器 - 使用 `h-full`
  - 第 323 行：列表视图容器 - 添加 `h-full`
  - 第 332 行：列表滚动容器 - 使用 `flex-1`

## 技术要点

### Flexbox 布局
- `flex-1`: 占据所有剩余空间
- `overflow-hidden`: 防止内容溢出
- 子元素使用 `h-full` 或 `flex-1` 填充父容器

### 虚拟滚动
- 虚拟滚动容器自己处理滚动
- 父容器只需要提供固定的可视区域
- 不需要在父容器上设置 `overflow-auto`
