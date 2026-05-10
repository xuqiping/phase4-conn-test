# File Keeper

> 轻量级跨平台文件收藏管理器

基于 Tauri 2 + Vue 3 + TypeScript 技术栈的跨平台文件管理工具。

## 🎯 核心功能

- ⚡ **一键快速打开文件** - 收藏常用文件，快速访问
- 🪶 **极致轻量** - 8-12MB 打包体积，毫秒级启动
- 🔄 **跨平台统一** - Windows/macOS/Linux 一致体验
- 🎯 **批量管理** - 一键关闭已打开的文件（独家功能）
- 📁 **分组管理** - 按项目/类型组织文件
- 🔍 **快速搜索** - 实时搜索文件名和标签

## 🚀 快速开始

### 环境要求

- Node.js 18+
- pnpm 8+
- Rust 1.80+

### 安装依赖

```bash
pnpm install
```

### 开发模式

```bash
pnpm tauri:dev
```

### 构建应用

```bash
pnpm tauri:build
```

## 📁 项目结构

```
file-keeper/
├── src/             # 前端 Vue 代码
│   ├── types/             # TypeScript 类型定义
│   ├── stores/            # Pinia 状态管理
│   ├── views/             # 页面组件
│   ├── components/        # UI 组件
│   ├── api/               # Tauri 命令封装
│   └── utils/             # 工具函数
├── src-tauri/          # Rust 后端代码
│   └── src/
│       ├── commands/      # Tauri 命令
│       └── platform/      # 平台适配
└── docs/                  # 文档
```

## 🛠️ 技术栈

- **桌面外壳**: Tauri 2.x
- **前端框架**: Vue 3 + TypeScript
- **构建工具**: Vite 6+
- **状态管理**: Pinia
- **UI 组件库**: Naive UI
- **后端语言**: Rust 1.80+

## 📝 开发进度

- [x] Phase 1: 项目初始化与基础架构
- [ ] Phase 2: MVP核心功能开发
- [ ] Phase 3: 增强功能开发
- [ ] Phase 4: UI增强与体验优化
- [ ] Phase 5: 打包、测试与优化

## 📄 许可证

MIT License
