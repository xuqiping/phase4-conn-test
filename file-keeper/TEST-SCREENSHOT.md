# 截图功能测试指南

## 当前已知问题

### 问题 1：应用外使用截图快捷键无响应
**现象**：在其他应用（如记事本）中按 `Ctrl+Shift+X`，File Keeper 无反应

**排查步骤**：
1. 打开 File Keeper
2. 打开浏览器开发者工具（F12）
3. 切换到记事本
4. 按 `Ctrl+Shift+X`
5. 查看控制台输出

**预期日志**：
```
[Screenshot] Opening overlay window...
[ScreenshotOverlay] Creating new window...
[ScreenshotOverlay] Window ready, focusing...
[ScreenshotOverlay] Window focused and ready
```

**如果卡住**：
- 检查是否输出到 `[ScreenshotOverlay] Creating new window...` 就停止了
- 如果1秒后没有超时错误，说明 `waitForOverlayWindow` 没有正确抛出异常

### 问题 2：剪贴板组件英文模式不生效
**现象**：切换到英文后，剪贴板标签页内的按钮、文字仍然是中文

**原因**：剪贴板相关组件尚未接入国际化系统

**需要修改的文件**：
- `src/components/ClipboardManagement.vue`
- `src/components/ClipboardToolbar.vue`
- `src/components/ClipboardList.vue`
- `src/components/ClipboardItemRow.vue`
- `src/components/ClipboardPreview.vue`
- `src/components/ClipboardQuickPanel.vue`
- `src/components/ClipboardSecurityEvents.vue`
- `src/components/ClipboardStorageUsage.vue`

## 调试建议

### 启用详细日志
代码中已添加详细日志，查看控制台应该能看到：
- `[Screenshot]` 前缀：主窗口截图逻辑
- `[ScreenshotOverlay]` 前缀：遮罩窗口创建逻辑

### 检查快捷键注册
在控制台运行：
```javascript
// 检查是否有快捷键冲突
console.log('Screenshot shortcut registered')
```

### 手动测试超时
如果怀疑超时逻辑有问题，可以临时修改 `src/api/screenshotOverlay.ts` 中的：
```typescript
const OVERLAY_READY_TIMEOUT_MS = 1_000  // 改为 5_000 测试
```

## 快速修复方案

如果问题持续存在，可以尝试：

1. **完全重启应用**：关闭所有 File Keeper 进程后重新启动
2. **清理缓存**：删除 `%APPDATA%\com.superprogrammer.file-keeper` 后重启
3. **检查权限**：确保 File Keeper 有截图权限（Windows 隐私设置）
4. **检查快捷键冲突**：尝试修改为其他快捷键（如 `Ctrl+Alt+S`）

## 下一步计划

- [ ] 修复应用外截图无响应问题
- [ ] 为剪贴板组件添加完整国际化支持
- [ ] 添加更多调试日志和错误提示
- [ ] 创建自动化 E2E 测试覆盖截图场景
