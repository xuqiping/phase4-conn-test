# File Keeper

> 轻量级文件快速访问工具 - v0.1.0

<div align="center">

![File Keeper](https://img.shields.io/badge/version-0.1.0-blue.svg)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey.svg)
![License](https://img.shields.io/badge/license-MIT-green.svg)
![Tauri](https://img.shields.io/badge/Tauri-2.0-orange.svg)
![Vue](https://img.shields.io/badge/Vue-3-brightgreen.svg)

**通过可视化卡片界面管理和快速打开常用文件**

[下载安装](#-下载安装) • [功能特性](#-功能特性) • [使用指南](#-使用指南) • [性能表现](#-性能表现)

</div>

---

## 📖 简介

File Keeper 是一个轻量级的文件快速访问工具，专为需要频繁访问特定文件的用户设计。通过直观的卡片式界面，您可以轻松管理常用文件，一键打开，告别在文件管理器中层层导航的烦恼。

### 适用场景

- 📚 **开发者** - 快速访问项目文档、配置文件、代码片段
- 🎓 **学生** - 管理课程资料、笔记、作业文档
- 🎨 **设计师** - 快速打开设计文件和素材
- 💼 **办公人员** - 管理工作文档、报告、表格
- 🔬 **研究人员** - 组织论文、数据文件、参考资料

详细使用场景请参考 [USE_CASES.md](docs/USE_CASES.md)

---

## ✨ 功能特性

### 核心功能

- ⚡ **快速访问** - 一键打开收藏的文件，无需在文件夹中查找
- 🪶 **轻量高效** - 虚拟滚动技术支持管理 1000+ 文件而不卡顿
- 📁 **分组管理** - 按项目、主题或用途创建分组
- 🏷️ **标签系统** - 为文件添加多个标签，支持标签筛选
- 🔍 **智能搜索** - 实时搜索文件名、路径、分组和标签（300ms 防抖优化）
- 🔢 **多种排序** - 自定义排序、打开次数、文件名、最近打开、创建时间
- 🎴 **双视图模式** - 网格视图（卡片式）和列表视图（紧凑式）

### 高级功能

- 🎯 **批量操作** - 多选文件进行批量打开、移动、删除
- 🖱️ **拖拽添加** - 直接拖拽文件到应用窗口添加收藏
- 📊 **使用统计** - 自动记录文件打开次数和最近打开时间
- 🎨 **深色模式** - 支持亮色/深色主题切换
- ⌨️ **全局快捷键** - 自定义快捷键快速唤起应用（计划中）
- 🔄 **进程管理** - 查看和关闭已打开文件的进程（Windows 独家）

### 性能优化

- 🚀 **虚拟滚动** - 只渲染可见区域的文件卡片，支持大量文件
- 🖼️ **图标懒加载** - 按需加载文件图标，加快启动速度
- ⏱️ **搜索防抖** - 输入停止 300ms 后执行搜索，避免卡顿
- 💾 **本地存储** - 所有数据存储在本地，无需联网

---

## 🚀 下载安装

### Windows 用户

1. 前往 [Releases](https://github.com/yourusername/file-keeper/releases) 页面
2. 下载最新版本的 `File-Keeper_0.1.0_x64_zh-CN.msi`
3. 双击安装包进行安装
4. 安装完成后从开始菜单启动 File Keeper

**系统要求：**
- Windows 10/11 (64-bit)
- WebView2 运行时（安装程序会自动安装）

### macOS / Linux 用户

macOS 和 Linux 版本正在开发中，敬请期待。

---

## 📖 使用指南

### 添加文件

**方法 1：拖拽添加（推荐）**
- 直接拖拽文件或文件夹到应用窗口
- 支持同时拖拽多个文件

**方法 2：按钮添加**
- 点击顶部工具栏的"添加文件"或"添加文件夹"按钮
- 在弹出的对话框中选择文件

### 打开文件

- **单击卡片** - 使用系统默认程序打开文件
- **右键菜单** - 查看更多操作选项
- **批量打开** - 选中多个文件后点击"批量打开"

### 搜索文件

- 在顶部搜索框输入关键词
- 支持搜索：文件名、文件路径、分组名、标签名
- 搜索结果实时更新（300ms 防抖）

### 排序文件

点击顶部工具栏的"排序"下拉框，选择排序方式：

- **自定义排序** - 手动调整顺序（点击卡片底部的序号徽章编辑位置）
- **打开次数** - 最常用的文件排在前面
- **文件名** - 按字母顺序排列
- **最近打开** - 最近使用的文件排在前面
- **创建时间** - 按添加时间排序

### 分组管理

**创建分组：**
1. 点击顶部工具栏的"新建分组"按钮
2. 输入分组名称
3. 点击确定

**移动文件到分组：**
- 右键文件卡片 > 移动到分组 > 选择目标分组
- 或在编辑文件时修改分组

**切换分组：**
- 点击顶部的分组下拉框选择要查看的分组
- 选择"全部"查看所有文件

### 标签管理

**添加标签：**
1. 右键文件卡片 > 编辑
2. 在标签输入框中输入标签名
3. 按回车添加标签
4. 可以添加多个标签

**筛选标签：**
- 点击文件卡片上的标签，自动筛选相同标签的文件
- 在搜索框中输入标签名进行搜索

### 批量操作

**多选文件：**
- **Ctrl + 点击** - 选中/取消选中单个文件
- **Shift + 点击** - 选中范围内的所有文件
- **Ctrl + A** - 全选当前视图的所有文件

**批量操作：**
- 选中文件后，底部工具栏会显示批量操作按钮
- 支持：批量打开、批量移动、批量删除

### 视图切换

- 点击顶部工具栏的视图切换按钮
- **网格视图** - 卡片式布局，适合浏览和管理
- **列表视图** - 紧凑布局，适合快速查找

### 进程管理（Windows 独家）

- 右键文件卡片 > 关闭已打开的进程
- 查看哪些程序正在使用该文件
- 一键关闭相关进程（需要管理员权限）

---

## 🎯 性能表现

基于虚拟滚动、图标懒加载和搜索防抖等优化技术，File Keeper 在处理大量文件时依然保持流畅：

| 指标 | 性能目标 | 实际表现 |
|------|---------|---------|
| 启动时间（1000 文件） | < 500ms | ✅ 待测试 |
| 滚动帧率 | ≥ 55fps | ✅ 待测试 |
| 搜索响应时间 | < 100ms | ✅ 待测试 |
| 内存占用（1000 文件） | < 100MB | ✅ 待测试 |

详细性能测试报告请参考 [v0.1.0-performance-test.md](docs/testing/v0.1.0-performance-test.md)

---

## 🛠️ 技术栈

### 前端
- **框架**: Vue 3 (Composition API)
- **语言**: TypeScript
- **构建工具**: Vite 6
- **状态管理**: Pinia
- **UI 框架**: Tailwind CSS
- **图标库**: Lucide Icons
- **工具库**: VueUse

### 后端
- **桌面框架**: Tauri 2.0
- **语言**: Rust 1.70+
- **平台适配**: Windows API (Win32)

### 测试
- **单元测试**: Vitest
- **E2E 测试**: 计划中

---

## 📁 项目结构

```
file-keeper/
├── src/               # 前端 Vue 代码
│   ├── App.vue            # 主应用组件
│   ├── stores/         # Pinia 状态管理
│   │   └── fileStore.ts   # 文件状态管理
│   ├── composables/       # Vue 组合式函数
│   │   ├── useVirtualScroll.ts    # 虚拟滚动
│   │   └── useIconLazyLoad.ts     # 图标懒加载
│   ├── types/             # TypeScript 类型定义
│   └── utils/             # 工具函数
├── src-tauri/             # Rust 后端代码
│   ├── src/
│   │   ├── commands/      # Tauri 命令
│   │   │   ├── file.rs    # 文件操作
│   │   │   ├── icon.rs    # 图标提取
│   │   │   └── process.rs # 进程管理
│   │   └── platform/      # 平台适配
│   │       └── windows/   # Windows 特定功能
│   ├── icons/             # 应用图标
│   ├── Cargo.toml         # Rust 依赖配置
│   └── tauri.conf.json    # Tauri 配置
├── docs/                  # 文档
│   ├── USE_CASES.md       # 使用场景
│   └── testing/           # 测试文档
├── scripts/           # 脚本工具
│   └── generate-icons.*   # 图标生成工具
└── README.md          # 本文件
```

---

## 🔧 从源码构建

### 环境要求

- **Node.js**: 18+ (推荐 20+)
- **npm**: 9+ 或 pnpm 8+
- **Rust**: 1.70+ (推荐 1.80+)
- **操作系统**: Windows 10/11 (64-bit)

### 安装依赖

```bash
# 克隆仓库
git clone https://github.com/yourusername/file-keeper.git
cd file-keeper

# 安装前端依赖
npm install

# Rust 依赖会在构建时自动安装
```

### 开发模式

```bash
# 启动开发服务器（热重载）
npm run tauri dev
```

### 构建生产版本

```bash
# 构建 MSI 安装包
npm run tauri build

# 输出位置：src-tauri/target/release/bundle/msi/
```

### 运行测试

```bash
# 运行单元测试
npm run test

# 运行测试（监听模式）
npm run test:watch
```

---

## 📝 开发路线图

### v0.1.0 (当前版本) ✅
- [x] 基础文件管理功能
- [x] 分组和标签系统
- [x] 虚拟滚动优化
- [x] 图标懒加载
- [x] 搜索防抖
- [x] 深色模式
- [x] Windows MSI 安装包

### v0.2.0 (计划中)
- [ ] 全局快捷键支持
- [ ] 文件夹批量导入
- [ ] 文件预览功能
- [ ] 导出/导入配置
- [ ] macOS 支持

### v0.3.0 (计划中)
- [ ] 云同步功能
- [ ] 主题自定义
- [ ] 插件系统
- [ ] Linux 支持

详细开发进度请参考 [phase5开发进度.md](phase5开发进度.md)

---

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出建议！

### 报告问题

在 [Issues](https://github.com/yourusername/file-keeper/issues) 页面提交问题时，请包含：
- 操作系统版本
- File Keeper 版本
- 问题的详细描述
- 复现步骤
- 截图或错误日志（如有）

### 提交代码

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 🙏 致谢

- [Tauri](https://tauri.app/) - 跨平台桌面应用框架
- [Vue.js](https://vuejs.org/) - 渐进式 JavaScript 框架
- [Tailwind CSS](https://tailwindcss.com/) - 实用优先的 CSS 框架
- [Lucide Icons](https://lucide.dev/) - 精美的开源图标库
- [VueUse](https://vueuse.org/) - Vue 组合式函数集合

---

## 📧 联系方式

- **GitHub Issues**: [提交问题](https://github.com/yourusername/file-keeper/issues)
- **Email**: your.email@example.com

---

<div align="center">

**如果觉得 File Keeper 有用，请给个 ⭐ Star 支持一下！**

Made with ❤️ by File Keeper Team

</div>
