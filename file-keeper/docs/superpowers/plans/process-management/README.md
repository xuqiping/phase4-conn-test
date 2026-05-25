# Process Management Feature - Implementation Plans

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add application management feature to File Keeper, allowing users to monitor and close running Windows applications across 13+ categories.

**Architecture:** Three-layer architecture with Vue 3 frontend, Pinia state management, and Rust/Tauri backend. Uses Windows API for process enumeration and control, with virtual scrolling for performance.

**Tech Stack:** Vue 3, TypeScript, Pinia, Tauri 2.0, Rust, Windows API, sysinfo crate

---

## 📋 Plan Structure

This implementation is split into 4 weekly plans to reduce token consumption:

### [Week 1: Backend Foundation](./week1-backend-foundation.md) ✅ COMPLETED
**Tasks 1-7** - Rust types, dependencies, process mappings, process monitor, Tauri commands
- Estimated: 40 hours
- Deliverable: Fully functional backend API for process enumeration and control
- **Status**: ✅ Completed on 2026-05-25

### [Week 2: Frontend Foundation](./week2-frontend-foundation.md) 🔄 IN PROGRESS
**Tasks 8-18** - TypeScript types, API layer, stores, core Vue components, App integration
- Estimated: 40 hours
- Deliverable: Complete UI with basic functionality
- **Status**: ⏳ Not started

### [Week 3: Advanced Features](./week3-advanced-features.md) ⏳ PENDING
**Tasks 19-22** - Auto-refresh, column customization, confirmation logic, error handling
- Estimated: 40 hours
- Deliverable: Polished user experience with all advanced features
- **Status**: ⏳ Not started

### [Week 4: Polish and Testing](./week4-polish-testing.md) ⏳ PENDING
**Tasks 23-27** - Complete mappings, performance testing, manual testing, documentation
- Estimated: 40 hours
- Deliverable: Production-ready feature with full documentation
- **Status**: ⏳ Not started

---

## 🎯 Key Features

- **13+ Application Categories**: Browser, Office, Explorer, Terminal, Archive, Document, Media, Image, Communication, Download, Game, System, Other
- **400+ Process Mappings**: Comprehensive recognition of common applications
- **Virtual Scrolling**: Handle 100+ processes smoothly
- **Tab-Aware Performance**: Zero overhead when not viewing process management tab
- **Configurable Safety**: Confirmation modes and whitelist for important apps
- **Auto/Manual Refresh**: Flexible refresh strategies

---

## 📁 File Structure Overview

### Frontend Files (Create)
- `src/types/process.ts` - TypeScript type definitions
- `src/api/process.ts` - Tauri command API wrapper
- `src/stores/processStore.ts` - Application state management
- `src/stores/processSettingsStore.ts` - Settings state management
- `src/components/ProcessManagement.vue` - Main container
- `src/components/ProcessToolbar.vue` - Toolbar with actions
- `src/components/ProcessFilter.vue` - Category filter
- `src/components/ProcessList.vue` - Virtual scrolling list
- `src/components/ProcessRow.vue` - Single process row
- `src/components/ConfirmDialog.vue` - Confirmation dialog

### Backend Files (Create)
- `src-tauri/src/types/process.rs` - Rust type definitions
- `src-tauri/src/commands/process.rs` - Tauri command handlers
- `src-tauri/src/platform/windows/mod.rs` - Windows platform module
- `src-tauri/src/platform/windows/process_monitor.rs` - Process monitoring core
- `src-tauri/src/platform/windows/process_mappings.rs` - Process category mappings

### Files to Modify
- `src/App.vue` - Add process management tab
- `src-tauri/Cargo.toml` - Add dependencies
- `src-tauri/src/main.rs` - Register commands

---

## ⚡ Performance Optimization

**Tab-Aware Monitoring**: Process monitoring ONLY activates when the user is on the process management tab. When switching to file management, all monitoring stops to save resources.

**Implementation**:
- Use `onActivated()`/`onDeactivated()` hooks in ProcessManagement.vue
- Use `v-if` (not `v-show`) in App.vue for complete lifecycle
- Call `clearProcesses()` on deactivation to free memory
- Stop auto-refresh timers when tab is inactive

**Result**: Zero performance impact when managing files.

---

## 🧪 Testing Strategy

- **Unit Tests**: Store logic (selection, filtering)
- **Integration Tests**: API calls, component interactions
- **Manual Tests**: All categories, close operations, edge cases, settings persistence
- **Performance Tests**: 100+ processes, virtual scrolling, auto-refresh impact

---

## 📊 Success Criteria

- Enumerate 100 processes < 500ms
- Virtual scrolling ≥ 55fps
- Memory increase < 50MB
- Auto-refresh no noticeable lag
- All 13+ categories correctly identify processes
- Close operations work reliably

---

## 📈 Progress Tracking

**Overall Progress**: 25% (Week 1 of 4 completed)

**Completed**:
- ✅ Week 1: Backend Foundation (7/7 tasks)
  - Rust type definitions
  - Dependencies added (sysinfo, lazy_static, windows crate)
  - Process mappings (Browser, Office, Explorer, Terminal, Archive)
  - Process enumeration using Windows API
  - Process close operations
  - Tauri commands registered

**Next Steps**:
- 🔄 Week 2: Frontend Foundation (0/11 tasks)
  - TypeScript types
  - API layer
  - Pinia stores
  - Vue components

---

## 🚀 Getting Started

1. Read the design spec: `../specs/2026-05-24-process-management-design.md`
2. Start with Week 1: `./week1-backend-foundation.md`
3. Use subagent-driven-development or executing-plans skill
4. Complete tasks sequentially, commit frequently

---

**Total Duration**: 4 weeks (160 hours)
**Started**: 2026-05-25
**Last Updated**: 2026-05-25
**Current Status**: Week 1 completed, Week 2 ready to start
