# Changelog

All notable changes to File Keeper will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-05-23
### Added
- 🎉 首个公开版本发布
- ⚡ 虚拟滚动支持 - 流畅处理数千个文件
- 🔍 搜索防抖优化 - 300ms 延迟提升输入体验
- 🖼️ 图标懒加载 - 按需加载文件图标，降低内存占用
- 📊 性能监控框架 - 记录启动时间和搜索性能
- 🎨 深色模式支持 - 亮色/深色主题切换
- 📁 分组管理 - 创建、编辑、删除分组
- 🔄 批量操作 - 多选、批量打开、批量移动、批量删除
- 🖱️ 拖拽添加 - 拖拽文件到应用窗口添加收藏
- 🎯 进程管理 - 查看和关闭已打开文件的进程
- ⌨️ 全局快捷键 - 自定义快捷键快速唤起应用
- 🏷️ 标签系统 - 为文件添加标签，支持标签搜索
- 📈 使用统计 - 记录文件打开次数和最近打开时间
- 🔢 自定义排序 - 支持自定义顺序、打开次数、最近打开等排序方式
- 🎴 双视图模式 - 网格视图和列表视图切换

### Performance
- 🚀 启动时间 < 500ms（1000 个文件）
- 📊 滚动帧率 ≥ 55fps（虚拟滚动技术）
- ⚡ 搜索响应 < 100ms（不含防抖延迟）
- 💾 内存占用 < 100MB（1000 个文件）

### Fixed
- 🐛 修复 Vue 生命周期警告（使用 watch 代替 onMounted）
- 🐛 修复窗口操作卡顿（使用 requestIdleCallback 优化图标加载）
- 🐛 修复状态栏显示问题（调整网格视图高度）
- 🐛 修复切换分组时图标加载卡顿（降低并发数，优化调度）

### Technical
- 🏗️ 技术栈：Tauri 2 + Vue 3 + TypeScript + Pinia
- 🎨 UI 框架：Tailwind CSS + Lucide Icons
- 🧪 测试框架：Vitest
- 📦 打包格式：Windows MSI 安装包

### Known Issues
- ⚠️ 图标提取在某些文件类型上可能失败（使用默认图标）
- ⚠️ 全局快捷键可能与其他应用冲突
- ⚠️ 进程管理功能仅支持 Windows 平台

### Documentation
- 📖 添加性能测试指南
- 📖 添加测试数据生成脚本
- 📖 添加布局诊断工具
- 📖 添加性能优化文档

---

## [Unreleased]

### Planned Features
- 🎯 鼠标拖拽多选
- 🔗 快捷键绑定（Win+1 等）
- 🤖 智能推荐（基于打开次数）
- ☁️ 云同步功能
- 👥 团队协作功能

---

[0.1.0]: https://github.com/yourusername/file-keeper/releases/tag/v0.1.0
