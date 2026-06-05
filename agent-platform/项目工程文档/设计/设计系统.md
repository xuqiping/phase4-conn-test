# UI/UX 设计系统

## 1. 概述

本设计系统定义多 Agent 智能体平台的视觉规范和交互标准。系统提供 3 套暗色主题（Deep Space / Dark Pro / Cyber Glow），确保一致的视觉体验和高效的交互模式。

---

## 2. 设计 Token

### 2.1 颜色系统

#### 语义颜色层级

```
颜色语义层级（以 Deep Space 主题为例）:

┌──────────────────────────────────────────────────┐
│  Background (最暗)                                │
│  ┌────────────────────────────────────────────┐  │
│  │  Surface (页面背景)                          │  │
│  │  ┌────────────────────────────────────┐    │  │
│  │  │  Card (卡片背景)                     │    │  │
│  │  │  ┌────────────────────────────┐    │    │  │
│  │  │  │  Elevated (悬浮/高亮元素)    │    │    │  │
│  │  │  └────────────────────────────┘    │    │  │
│  │  └────────────────────────────────────┘    │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘

由暗到亮: Background → Surface → Card → Elevated
```

#### 基础色板

| Token | 用途 | 说明 |
|-------|------|------|
| `--color-primary` | 主色调 | 品牌色，用于主要操作按钮、链接、重点高亮 |
| `--color-primary-hover` | 主色调悬停 | 按钮/链接 hover 状态 |
| `--color-primary-active` | 主色调激活 | 按钮 active 状态 |
| `--color-primary-light` | 主色调浅色 | 信息提示背景、Badge 背景 |
| `--color-secondary` | 辅助色 | 次要操作、辅助信息 |
| `--color-success` | 成功色 | 成功状态、执行完成 |
| `--color-warning` | 警告色 | 警告状态、需注意的信息 |
| `--color-danger` | 危险色 | 错误状态、删除操作、执行失败 |
| `--color-info` | 信息色 | 一般提示信息 |

#### 背景与表面色

| Token | 用途 |
|-------|------|
| `--color-bg` | 最底层背景 |
| `--color-surface` | 页面级背景 |
| `--color-card` | 卡片/面板背景 |
| `--color-elevated` | 弹窗/下拉/悬浮元素背景 |
| `--color-overlay` | 遮罩层背景 |

#### 文字颜色

| Token | 用途 |
|-------|------|
| `--color-text-primary` | 主要文字（标题、正文） |
| `--color-text-secondary` | 次要文字（描述、辅助信息） |
| `--color-text-tertiary` | 三级文字（占位符、禁用文字） |
| `--color-text-inverse` | 反色文字（深色背景上的白色文字） |
| `--color-text-link` | 链接文字 |

#### 边框颜色

| Token | 用途 |
|-------|------|
| `--color-border` | 默认边框 |
| `--color-border-light` | 浅色边框（分割线） |
| `--color-border-focus` | 聚焦状态边框 |

### 2.2 字体系统

```scss
// 字体家族
--font-family-base: 'Inter', 'PingFang SC', 'Microsoft YaHei', -apple-system, sans-serif;
--font-family-code: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;

// 字体大小（基于 4px 网格）
--font-size-xs:    12px;    // 辅助信息、Badge
--font-size-sm:    13px;    // 次要文字、表格内容
--font-size-base:  14px;    // 正文、表单标签
--font-size-md:    16px;    // 小标题、输入框文字
--font-size-lg:    18px;    // 卡片标题
--font-size-xl:    20px;    // 页面标题
--font-size-2xl:   24px;    // 大标题
--font-size-3xl:   30px;    // 展示用大标题

// 行高
--line-height-tight:   1.25;   // 标题
--line-height-base:    1.5;    // 正文
--line-height-relaxed: 1.75;   // 长文本

// 字重
--font-weight-normal:  400;
--font-weight-medium:  500;
--font-weight-semibold: 600;
--font-weight-bold:    700;
```

### 2.3 间距系统

```scss
// 基于 4px 基数的间距
--spacing-0:   0;
--spacing-1:   4px;    // 极小间距（图标与文字）
--spacing-2:   8px;    // 小间距（同组元素）
--spacing-3:   12px;   // 标准间距（表单项）
--spacing-4:   16px;   // 中等间距（卡片内边距）
--spacing-5:   20px;   // 大间距（区域分隔）
--spacing-6:   24px;   // 区域间距
--spacing-8:   32px;   // 大区域间距
--spacing-10:  40px;   // 页面级间距
--spacing-12:  48px;   // 大页面间距
--spacing-16:  64px;   // 极大间距

// 组件内边距
--padding-input:       var(--spacing-2) var(--spacing-3);   // 8px 12px
--padding-button-sm:   var(--spacing-1) var(--spacing-3);   // 4px 12px
--padding-button:      var(--spacing-2) var(--spacing-4);   // 8px 16px
--padding-button-lg:   var(--spacing-3) var(--spacing-6);   // 12px 24px
--padding-card:        var(--spacing-5);                      // 20px
--padding-section:     var(--spacing-6);                      // 24px
```

### 2.4 圆角系统

```scss
--radius-none:   0;
--radius-sm:     4px;    // 小元素（Badge、Tag）
--radius-base:   6px;    // 按钮、输入框
--radius-md:     8px;    // 卡片
--radius-lg:     12px;   // 弹窗、大卡片
--radius-xl:     16px;   // 特大容器
--radius-full:   9999px; // 圆形（头像、圆形按钮）
```

### 2.5 阴影系统

```scss
// 暗色主题使用带色调的阴影
--shadow-sm:    0 1px 2px rgba(0, 0, 0, 0.3);
--shadow-base:  0 2px 8px rgba(0, 0, 0, 0.4);
--shadow-md:    0 4px 16px rgba(0, 0, 0, 0.5);
--shadow-lg:    0 8px 24px rgba(0, 0, 0, 0.6);
--shadow-xl:    0 12px 40px rgba(0, 0, 0, 0.7);

// 特殊阴影（带主题色调）
--shadow-primary: 0 4px 16px rgba(var(--color-primary-rgb), 0.3);
--shadow-glow:    0 0 20px rgba(var(--color-primary-rgb), 0.15);
```

### 2.6 动效系统

```scss
// 持续时间
--duration-instant:  100ms;   // 微交互（hover颜色变化）
--duration-fast:     200ms;   // 快速过渡（按钮状态）
--duration-normal:   300ms;   // 标准过渡（面板展开）
--duration-slow:     500ms;   // 慢速过渡（页面切换）
--duration-slower:   800ms;   // 极慢（首次加载动画）

// 缓动函数
--ease-linear:      linear;
--ease-in:          cubic-bezier(0.4, 0, 1, 1);
--ease-out:         cubic-bezier(0, 0, 0.2, 1);
--ease-in-out:      cubic-bezier(0.4, 0, 0.2, 1);
--ease-bounce:      cubic-bezier(0.68, -0.55, 0.265, 1.55);
```

---

## 3. 主题变量定义

### 3.1 Deep Space（深空）

深邃的宇宙空间感，使用深蓝黑色调为主，以冷蓝色为强调色。

```scss
// deep-space.scss
[data-theme="deep-space"] {
  // === 主色 ===
  --color-primary:         #4F7CFF;
  --color-primary-hover:   #6B91FF;
  --color-primary-active:  #3A66E8;
  --color-primary-light:   rgba(79, 124, 255, 0.12);
  --color-primary-rgb:     79, 124, 255;

  // === 语义色 ===
  --color-secondary:       #8B8FA3;
  --color-success:         #4ADE80;
  --color-warning:         #FBBF24;
  --color-danger:          #F87171;
  --color-info:            #60A5FA;

  // === 背景层级 ===
  --color-bg:              #060A13;
  --color-surface:         #0C1220;
  --color-card:            #131B2E;
  --color-elevated:        #1A2440;
  --color-overlay:         rgba(6, 10, 19, 0.8);

  // === 文字 ===
  --color-text-primary:    #E8ECF4;
  --color-text-secondary:  #8B8FA3;
  --color-text-tertiary:   #4A5068;
  --color-text-inverse:    #060A13;
  --color-text-link:       #4F7CFF;

  // === 边框 ===
  --color-border:          #1E2A45;
  --color-border-light:    #151D32;
  --color-border-focus:    #4F7CFF;

  // === 特殊 ===
  --color-gradient-start:  #4F7CFF;
  --color-gradient-end:    #9333EA;
  --shadow-primary:        0 4px 16px rgba(79, 124, 255, 0.25);
  --shadow-glow:           0 0 30px rgba(79, 124, 255, 0.1);

  // === 画布特殊 ===
  --color-canvas-bg:       #080E1A;
  --color-canvas-grid:     #0E1628;
  --color-node-bg:         #131B2E;
  --color-node-border:     #1E2A45;
  --color-node-selected:   #4F7CFF;
  --color-edge:            #2A3655;
  --color-edge-active:     #4F7CFF;
}
```

### 3.2 Dark Pro（暗夜专业）

专业的深灰色调，以中性灰为主，绿色为强调色。适合长时间使用，视觉疲劳最小。

```scss
// dark-pro.scss
[data-theme="dark-pro"] {
  // === 主色 ===
  --color-primary:         #10B981;
  --color-primary-hover:   #34D399;
  --color-primary-active:  #059669;
  --color-primary-light:   rgba(16, 185, 129, 0.12);
  --color-primary-rgb:     16, 185, 129;

  // === 语义色 ===
  --color-secondary:       #9CA3AF;
  --color-success:         #34D399;
  --color-warning:         #FBBF24;
  --color-danger:          #F87171;
  --color-info:            #60A5FA;

  // === 背景层级 ===
  --color-bg:              #0A0A0A;
  --color-surface:         #111111;
  --color-card:            #1A1A1A;
  --color-elevated:        #222222;
  --color-overlay:         rgba(0, 0, 0, 0.75);

  // === 文字 ===
  --color-text-primary:    #F3F4F6;
  --color-text-secondary:  #9CA3AF;
  --color-text-tertiary:   #4B5563;
  --color-text-inverse:    #0A0A0A;
  --color-text-link:       #10B981;

  // === 边框 ===
  --color-border:          #2A2A2A;
  --color-border-light:    #1F1F1F;
  --color-border-focus:    #10B981;

  // === 特殊 ===
  --color-gradient-start:  #10B981;
  --color-gradient-end:    #06B6D4;
  --shadow-primary:        0 4px 16px rgba(16, 185, 129, 0.2);
  --shadow-glow:           0 0 30px rgba(16, 185, 129, 0.08);

  // === 画布特殊 ===
  --color-canvas-bg:       #0D0D0D;
  --color-canvas-grid:     #151515;
  --color-node-bg:         #1A1A1A;
  --color-node-border:     #2A2A2A;
  --color-node-selected:   #10B981;
  --color-edge:            #333333;
  --color-edge-active:     #10B981;
}
```

### 3.3 Cyber Glow（赛博辉光）

赛博朋克风格，使用暗紫底色搭配霓虹粉蓝强调色，科技感强烈。

```scss
// cyber-glow.scss
[data-theme="cyber-glow"] {
  // === 主色 ===
  --color-primary:         #E040FB;
  --color-primary-hover:   #EA80FC;
  --color-primary-active:  #C026D3;
  --color-primary-light:   rgba(224, 64, 251, 0.12);
  --color-primary-rgb:     224, 64, 251;

  // === 语义色 ===
  --color-secondary:       #A78BFA;
  --color-success:         #4ADE80;
  --color-warning:         #FCD34D;
  --color-danger:          #FB7185;
  --color-info:            #38BDF8;

  // === 背景层级 ===
  --color-bg:              #0A0510;
  --color-surface:         #110B1A;
  --color-card:            #1A1028;
  --color-elevated:        #241638;
  --color-overlay:         rgba(10, 5, 16, 0.85);

  // === 文字 ===
  --color-text-primary:    #F0E6FF;
  --color-text-secondary:  #9B8BB8;
  --color-text-tertiary:   #5A4A72;
  --color-text-inverse:    #0A0510;
  --color-text-link:       #E040FB;

  // === 边框 ===
  --color-border:          #2D1F42;
  --color-border-light:    #1E1530;
  --color-border-focus:    #E040FB;

  // === 特殊 ===
  --color-gradient-start:  #E040FB;
  --color-gradient-end:    #38BDF8;
  --color-neon-pink:       #FF2E97;
  --color-neon-blue:       #00D4FF;
  --shadow-primary:        0 4px 20px rgba(224, 64, 251, 0.3);
  --shadow-glow:           0 0 40px rgba(224, 64, 251, 0.12);

  // === 画布特殊 ===
  --color-canvas-bg:       #0C0614;
  --color-canvas-grid:     #130D1E;
  --color-node-bg:         #1A1028;
  --color-node-border:     #2D1F42;
  --color-node-selected:   #E040FB;
  --color-edge:            #3D2858;
  --color-edge-active:     #E040FB;
}
```

---

## 4. 组件规范

### 4.1 按钮 (Button)

#### 按钮类型

| 类型 | 使用场景 | 样式 |
|------|---------|------|
| Primary | 主要操作（保存、提交、执行） | 实心背景，主色调 |
| Secondary | 次要操作（取消、返回） | 描边，无填充 |
| Text | 轻量操作（链接式按钮） | 无背景无边框 |
| Danger | 危险操作（删除） | 实心红色背景 |
| Ghost | 画布工具栏操作 | 半透明背景 |

#### 按钮尺寸

| 尺寸 | 高度 | 内边距 | 字号 | 图标间距 |
|------|------|--------|------|---------|
| Small | 28px | 4px 12px | 13px | 4px |
| Default | 36px | 8px 16px | 14px | 6px |
| Large | 44px | 12px 24px | 16px | 8px |

#### 按钮样式规范

```scss
// Primary Button
.btn-primary {
  background: var(--color-primary);
  color: var(--color-text-inverse);
  border: none;
  border-radius: var(--radius-base);
  font-weight: var(--font-weight-medium);
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    background: var(--color-primary-hover);
    box-shadow: var(--shadow-primary);
    transform: translateY(-1px);
  }

  &:active {
    background: var(--color-primary-active);
    transform: translateY(0);
    box-shadow: none;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    transform: none;
    box-shadow: none;
  }
}

// Secondary Button
.btn-secondary {
  background: transparent;
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// Danger Button
.btn-danger {
  background: var(--color-danger);
  color: #FFFFFF;
  border: none;
  border-radius: var(--radius-base);
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    filter: brightness(1.1);
    box-shadow: 0 4px 12px rgba(248, 113, 113, 0.3);
  }
}
```

### 4.2 卡片 (Card)

#### Agent 卡片

```
┌──────────────────────────────────┐
│  ┌──────┐                        │  ← 卡片高度: 自适应
│  │ 头像  │  Agent名称             │  ← 头像: 48x48, 圆形
│  │ 48px │  简短描述文字...         │  ← 名称: 16px, font-weight: 600
│  └──────┘                        │  ← 描述: 13px, text-secondary
│                                  │
│  ┌──────┐ ┌──────┐ ┌──────┐     │  ← 技能标签
│  │技能1 │ │技能2 │ │技能3 │     │  ← Tag: 12px, 圆角4px
│  └──────┘ └──────┘ └──────┘     │
│                                  │
│  ─────────────────────────       │  ← 分割线: border-light
│  分组名称          状态标签        │  ← 底部: 13px, text-tertiary
└──────────────────────────────────┘
```

```scss
.agent-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--padding-card);
  transition: all var(--duration-normal) var(--ease-in-out);
  cursor: pointer;

  &:hover {
    border-color: var(--color-primary);
    background: var(--color-elevated);
    box-shadow: var(--shadow-md);
    transform: translateY(-2px);
  }

  &:active {
    transform: translateY(0);
  }
}
```

### 4.3 输入框 (Input)

```scss
.app-input {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-base);
  padding: var(--padding-input);
  color: var(--color-text-primary);
  font-size: var(--font-size-base);
  transition: all var(--duration-instant) var(--ease-in-out);

  &::placeholder {
    color: var(--color-text-tertiary);
  }

  &:hover {
    border-color: var(--color-text-tertiary);
  }

  &:focus {
    outline: none;
    border-color: var(--color-border-focus);
    box-shadow: 0 0 0 3px var(--color-primary-light);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  // 错误状态
  &.is-error {
    border-color: var(--color-danger);

    &:focus {
      box-shadow: 0 0 0 3px rgba(248, 113, 113, 0.15);
    }
  }
}
```

### 4.4 导航 (Navigation)

#### 顶部导航栏

```
┌─────────────────────────────────────────────────────────────┐
│  [Logo]  多Agent智能体平台   [Agent大厅] [工作流]  [主题] [用户头像 ▾] │
│  高度: 56px                                                  │
│  背景: var(--color-surface) + 底部 border                     │
└─────────────────────────────────────────────────────────────┘
```

```scss
.nav-bar {
  height: 56px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  display: flex;
  align-items: center;
  padding: 0 var(--spacing-6);
  position: sticky;
  top: 0;
  z-index: 100;
  backdrop-filter: blur(12px);
  background: rgba(var(--color-surface-rgb, 17, 17, 17), 0.85);

  .nav-logo {
    font-size: var(--font-size-lg);
    font-weight: var(--font-weight-bold);
    color: var(--color-primary);
    margin-right: var(--spacing-6);
  }

  .nav-item {
    padding: var(--spacing-2) var(--spacing-4);
    color: var(--color-text-secondary);
    border-radius: var(--radius-base);
    transition: all var(--duration-instant);
    font-size: var(--font-size-base);

    &:hover {
      color: var(--color-text-primary);
      background: var(--color-primary-light);
    }

    &.active {
      color: var(--color-primary);
      background: var(--color-primary-light);
      font-weight: var(--font-weight-medium);
    }
  }
}
```

### 4.5 表格 (Table)

```scss
.app-table {
  width: 100%;
  border-collapse: collapse;

  thead {
    th {
      background: var(--color-surface);
      color: var(--color-text-secondary);
      font-size: var(--font-size-sm);
      font-weight: var(--font-weight-medium);
      padding: var(--spacing-3) var(--spacing-4);
      text-align: left;
      border-bottom: 1px solid var(--color-border);
      white-space: nowrap;
    }
  }

  tbody {
    tr {
      border-bottom: 1px solid var(--color-border-light);
      transition: background var(--duration-instant);

      &:hover {
        background: var(--color-primary-light);
      }
    }

    td {
      padding: var(--spacing-3) var(--spacing-4);
      color: var(--color-text-primary);
      font-size: var(--font-size-sm);
    }
  }
}
```

---

## 5. 页面布局规范

### 5.1 整体布局

```
┌─────────────────────────────────────────────────┐
│                  顶部导航栏 (56px)                │
├─────────────────────────────────────────────────┤
│                                                 │
│              主内容区域                           │
│              max-width: 1440px                   │
│              padding: 24px                       │
│              margin: 0 auto                      │
│                                                 │
│                                                 │
│                                                 │
└─────────────────────────────────────────────────┘
```

### 5.2 登录页面

```
┌───────────────────────────────────────────────────────┐
│                                                       │
│                  居中布局 (垂直水平居中)                  │
│                                                       │
│              ┌───────────────────────┐                │
│              │      [Logo]           │                │
│              │   多Agent智能体平台    │                │
│              │                       │                │
│              │   ┌───────────────┐   │                │
│              │   │  用户名        │   │                │
│              │   └───────────────┘   │                │
│              │   ┌───────────────┐   │                │
│              │   │  密码          │   │                │
│              │   └───────────────┘   │                │
│              │                       │                │
│              │   [    登录按钮     ]  │                │
│              │                       │                │
│              │   主题切换: ○○○       │                │
│              └───────────────────────┘                │
│                                                       │
│   背景: 暗色渐变 + 微弱网格图案                         │
│   登录卡片宽度: 400px, 圆角: 12px                      │
│   卡片背景: var(--color-card) + 毛玻璃效果              │
└───────────────────────────────────────────────────────┘
```

### 5.3 Agent 大厅

```
┌───────────────────────────────────────────────────────┐
│  导航栏                                                │
├───────────────────────────────────────────────────────┤
│  搜索栏 ────────────────────────────── [搜索]          │
│  高度: 64px, 全宽搜索框                                 │
├───────────────────────────────────────────────────────┤
│                                                       │
│  ┌─ 通用助手 ─────────────────────────────────────┐   │
│  │                                                 │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐       │   │
│  │  │ Agent卡片 │ │ Agent卡片 │ │ Agent卡片 │ ...  │   │
│  │  └──────────┘ └──────────┘ └──────────┘       │   │
│  │                                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                       │
│  ┌─ 数据分析 ─────────────────────────────────────┐   │
│  │                                                 │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐       │   │
│  │  │ Agent卡片 │ │ Agent卡片 │ │ Agent卡片 │ ...  │   │
│  │  └──────────┘ └──────────┘ └──────────┘       │   │
│  │                                                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                       │
│  分组标题: 20px, font-weight: 600, text-primary        │
│  卡片网格: grid, 4列, gap: 20px                        │
│  响应式: 大屏4列, 中屏3列, 小屏2列, 手机1列              │
└───────────────────────────────────────────────────────┘
```

### 5.4 Agent 详情

```
┌───────────────────────────────────────────────────────┐
│  导航栏                                                │
├───────────────────────────────────────────────────────┤
│                                                       │
│  ┌─ Agent 信息区 ─────────────────────────────────┐   │
│  │  [头像]  Agent名称                               │   │
│  │          描述文字                                 │   │
│  │          状态标签 | 分组 | 创建时间                │   │
│  └─────────────────────────────────────────────────┘   │
│                                                       │
│  ┌─ 配置信息 ────────┐  ┌─ 技能列表 ──────────────┐   │
│  │                    │  │                          │   │
│  │  模型: gpt-4       │  │  技能1: 意图识别          │   │
│  │  温度: 0.7         │  │    ├ 步骤1: LLM调用      │   │
│  │  最大Token: 4096   │  │    └ 步骤2: 结果处理      │   │
│  │  系统提示词: ...   │  │                          │   │
│  │                    │  │  技能2: 问答生成          │   │
│  │  左侧宽度: 40%     │  │    ├ 步骤1: ...          │   │
│  │                    │  │    └ 步骤2: ...          │   │
│  │                    │  │                          │   │
│  │                    │  │  右侧宽度: 60%           │   │
│  └────────────────────┘  └──────────────────────────┘   │
│                                                       │
│  [创建工作流] 按钮                                     │
│                                                       │
└───────────────────────────────────────────────────────┘
```

### 5.5 工作流编辑器

```
┌───────────────────────────────────────────────────────┐
│  导航栏: 工作流名称 | [保存] [发布] [运行]              │
├───────────────────────────────────────────────────────┤
│  ┌────────────┐  ┌──────────────────────────────────┐ │
│  │ 节点面板    │  │                                  │ │
│  │            │  │          画布区域                  │ │
│  │ ○ 开始     │  │                                  │ │
│  │ ○ 结束     │  │    [节点] ──→ [节点] ──→ [节点]  │ │
│  │ ○ Agent    │  │                │                  │ │
│  │ ○ 条件     │  │                ▼                  │ │
│  │ ○ 并行     │  │            [节点]                  │ │
│  │ ○ 循环     │  │                                  │ │
│  │            │  │    背景: 网格点阵                  │ │
│  │ 宽度: 240px│  │    缩放: 支持 0.2x - 2x           │ │
│  │            │  │    小地图: 右下角                   │ │
│  └────────────┘  └──────────────────────────────────┘ │
│                  │  ┌──────────────────────────┐      │
│                  │  │ 属性面板 (选中节点时显示)   │      │
│                  │  │ 宽度: 320px               │      │
│                  │  └──────────────────────────┘      │
│                  └──────────────────────────────────┘ │
└───────────────────────────────────────────────────────┘
```

---

## 6. 交互规范

### 6.1 过渡动画

| 场景 | 动画类型 | 持续时间 | 缓动函数 |
|------|---------|---------|---------|
| 按钮 hover 颜色变化 | color, background-color | 150ms | ease-in-out |
| 按钮 hover 位移 | transform: translateY | 200ms | ease-out |
| 卡片 hover 浮起 | transform + box-shadow | 300ms | ease-out |
| 面板展开/折叠 | max-height + opacity | 300ms | ease-in-out |
| 模态框出现 | transform: scale + opacity | 250ms | ease-out |
| 模态框消失 | transform: scale + opacity | 200ms | ease-in |
| 页面切换 | opacity + transform | 300ms | ease-in-out |
| Toast 通知 | transform: translateY + opacity | 300ms | ease-out |
| 下拉菜单 | opacity + transform: scaleY | 200ms | ease-out |
| 加载骨架屏 | opacity 脉冲 | 1.5s | linear (循环) |

### 6.2 画布交互动画

```scss
// 节点拖拽
.workflow-node {
  transition: box-shadow var(--duration-instant);

  // 拖拽中状态
  &.dragging {
    box-shadow: var(--shadow-lg);
    opacity: 0.9;
    z-index: 10;
    // 拖拽时无过渡，跟随鼠标
    transition: none;
  }

  // 拖拽放置后
  &.dropped {
    animation: nodeDrop 0.3s var(--ease-out);
  }
}

@keyframes nodeDrop {
  0%   { transform: scale(1.05); }
  100% { transform: scale(1); }
}

// 节点选中
.workflow-node.selected {
  border-color: var(--color-node-selected);
  box-shadow: var(--shadow-primary);

  &::before {
    content: '';
    position: absolute;
    inset: -2px;
    border-radius: inherit;
    border: 2px solid var(--color-node-selected);
    animation: selectPulse 2s var(--ease-in-out) infinite;
  }
}

@keyframes selectPulse {
  0%, 100% { opacity: 1; }
  50%      { opacity: 0.4; }
}

// 连线动画
.workflow-edge {
  stroke: var(--color-edge);
  stroke-width: 2;
  fill: none;
  transition: stroke var(--duration-instant);

  &.active {
    stroke: var(--color-edge-active);
    stroke-width: 2.5;

    // 流动动画
    stroke-dasharray: 8 4;
    animation: edgeFlow 0.5s linear infinite;
  }
}

@keyframes edgeFlow {
  to { stroke-dashoffset: -12; }
}

// 条件边标签动画
.edge-label {
  font-size: var(--font-size-xs);
  background: var(--color-elevated);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  color: var(--color-text-secondary);
}
```

### 6.3 Hover 效果规范

```scss
// 卡片 Hover
.card-hover {
  transition: all var(--duration-normal) var(--ease-out);

  &:hover {
    transform: translateY(-3px);
    box-shadow: var(--shadow-md);
    border-color: var(--color-primary);
  }
}

// 列表项 Hover
.list-item-hover {
  transition: background var(--duration-instant);

  &:hover {
    background: var(--color-primary-light);
  }
}

// 图标按钮 Hover
.icon-btn-hover {
  transition: all var(--duration-instant);

  &:hover {
    color: var(--color-primary);
    background: var(--color-primary-light);
    transform: scale(1.05);
  }

  &:active {
    transform: scale(0.95);
  }
}
```

### 6.4 状态反馈

#### 执行状态颜色

| 状态 | 颜色 | 图标 | 动画 |
|------|------|------|------|
| RUNNING | `--color-primary` | 旋转加载图标 | 旋转动画 |
| SUCCESS | `--color-success` | 对勾图标 | 淡入 + 缩放弹跳 |
| FAILED | `--color-danger` | 叉号图标 | 抖动动画 |
| CANCELLED | `--color-text-tertiary` | 横线图标 | 无 |

#### Toast 通知

```scss
.toast {
  position: fixed;
  top: 72px; // 导航栏下方
  right: var(--spacing-6);
  padding: var(--spacing-3) var(--spacing-5);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  font-size: var(--font-size-base);
  z-index: 1000;
  animation: toastIn 0.3s var(--ease-out);

  &.toast-success {
    background: var(--color-elevated);
    border-left: 3px solid var(--color-success);
    color: var(--color-success);
  }

  &.toast-error {
    background: var(--color-elevated);
    border-left: 3px solid var(--color-danger);
    color: var(--color-danger);
  }

  &.toast-exit {
    animation: toastOut 0.2s var(--ease-in) forwards;
  }
}

@keyframes toastIn {
  from {
    opacity: 0;
    transform: translateX(100px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@keyframes toastOut {
  to {
    opacity: 0;
    transform: translateX(100px);
  }
}
```

### 6.5 加载状态

```scss
// 骨架屏
.skeleton {
  background: linear-gradient(
    90deg,
    var(--color-card) 0%,
    var(--color-elevated) 50%,
    var(--color-card) 100%
  );
  background-size: 200% 100%;
  animation: skeletonShimmer 1.5s ease-in-out infinite;
  border-radius: var(--radius-base);
}

@keyframes skeletonShimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

// 全局加载进度条（顶部）
.loading-bar {
  position: fixed;
  top: 0;
  left: 0;
  height: 3px;
  background: var(--color-primary);
  z-index: 9999;
  transition: width 0.3s ease;
  box-shadow: 0 0 10px var(--color-primary);
}
```

---

## 7. 响应式断点

```scss
// 断点定义
$breakpoint-sm:  640px;    // 手机横屏
$breakpoint-md:  768px;    // 平板竖屏
$breakpoint-lg:  1024px;   // 平板横屏/小笔记本
$breakpoint-xl:  1280px;   // 标准桌面
$breakpoint-2xl: 1536px;   // 大屏桌面

// 使用示例
.agent-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-5);

  @media (max-width: $breakpoint-xl) {
    grid-template-columns: repeat(3, 1fr);
  }

  @media (max-width: $breakpoint-lg) {
    grid-template-columns: repeat(2, 1fr);
  }

  @media (max-width: $breakpoint-md) {
    grid-template-columns: 1fr;
  }
}
```

---

## 8. 主题切换实现

### 8.1 切换机制

```typescript
// composables/useTheme.ts
const THEME_KEY = 'app-theme'
type ThemeName = 'deep-space' | 'dark-pro' | 'cyber-glow'

export function useTheme() {
  const currentTheme = ref<ThemeName>(
    (localStorage.getItem(THEME_KEY) as ThemeName) || 'deep-space'
  )

  function setTheme(theme: ThemeName) {
    currentTheme.value = theme
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem(THEME_KEY, theme)
  }

  // 初始化
  onMounted(() => {
    setTheme(currentTheme.value)
  })

  return { currentTheme, setTheme }
}
```

### 8.2 主题选择器组件

```
┌─────────────────────────┐
│  选择主题                │
│                         │
│  ┌────────────────────┐ │
│  │ ● Deep Space       │ │  ← 当前选中
│  │   深空蓝黑          │ │
│  │   [色块预览]        │ │
│  └────────────────────┘ │
│  ┌────────────────────┐ │
│  │ ○ Dark Pro         │ │
│  │   暗夜专业          │ │
│  │   [色块预览]        │ │
│  └────────────────────┘ │
│  ┌────────────────────┐ │
│  │ ○ Cyber Glow       │ │
│  │   赛博辉光          │ │
│  │   [色块预览]        │ │
│  └────────────────────┘ │
└─────────────────────────┘
```
