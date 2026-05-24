# Week 1: Backend Foundation

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement tasks sequentially.

**Goal**: Build complete Rust backend for process enumeration and control.

**Deliverables**:
- Rust type definitions
- Process category mappings (13+ categories)
- Process monitor core (enumerate and close)
- Tauri command handlers
- All backend tests passing

---

## Task 1: Rust Type Definitions

**Files:**
- Create: `file-keeper/src-tauri/src/types/process.rs`
- Modify: `file-keeper/src-tauri/src/types/mod.rs`

**Steps:**
- [ ] Create ProcessCategory enum with 13 variants
- [ ] Create ProcessInfo struct with all fields
- [ ] Create ProcessMapping struct
- [ ] Export types module
- [ ] Verify compilation with `cargo check`
- [ ] Commit: "feat(backend): add process type definitions"

---

## Task 2: Add Rust Dependencies

**Files:**
- Modify: `file-keeper/src-tauri/Cargo.toml`

**Steps:**
- [ ] Add sysinfo = "0.30"
- [ ] Add lazy_static = "1.4"
- [ ] Add windows crate with required features
- [ ] Run `cargo build` to download dependencies
- [ ] Commit: "feat(backend): add process management dependencies"

---

## Task 3: Process Mappings - Browser Category

**Files:**
- Create: `file-keeper/src-tauri/src/platform/mod.rs`
- Create: `file-keeper/src-tauri/src/platform/windows/mod.rs`
- Create: `file-keeper/src-tauri/src/platform/windows/process_mappings.rs`

**Steps:**
- [ ] Create platform module structure
- [ ] Implement load_process_mappings() function
- [ ] Add add_browser_mappings() with 30+ browsers
- [ ] Export platform module in main.rs
- [ ] Verify compilation
- [ ] Commit: "feat(backend): add browser process mappings"

---

## Task 4: Process Mappings - Remaining Categories

**Files:**
- Modify: `file-keeper/src-tauri/src/platform/windows/process_mappings.rs`

**Steps:**
- [ ] Add add_office_mappings() (6 apps)
- [ ] Add add_explorer_mappings() (1 app)
- [ ] Add add_terminal_mappings() (18 apps)
- [ ] Add add_archive_mappings() (18 apps)
- [ ] Update load_process_mappings() to call all functions
- [ ] Verify compilation
- [ ] Commit: "feat(backend): add office, explorer, terminal, archive mappings"

---

## Task 5: Process Monitor Core - Enumeration

**Files:**
- Create: `file-keeper/src-tauri/src/platform/windows/process_monitor.rs`

**Steps:**
- [ ] Create ProcessMonitor struct with mappings and system
- [ ] Implement new() to initialize with load_process_mappings()
- [ ] Implement enumerate_processes() using EnumWindows API
- [ ] Create enum_windows_callback to filter visible windows
- [ ] Get process info via sysinfo for each window
- [ ] Match process names to categories using mappings
- [ ] Return Vec<ProcessInfo>
- [ ] Verify compilation
- [ ] Commit: "feat(backend): add process enumeration core"

---

## Task 6: Process Monitor - Close Operations

**Files:**
- Modify: `file-keeper/src-tauri/src/platform/windows/process_monitor.rs`

**Steps:**
- [ ] Implement close_process() using PostMessageW with WM_CLOSE
- [ ] Implement close_processes() for batch operations
- [ ] Create CloseResult struct
- [ ] Handle errors gracefully
- [ ] Verify compilation
- [ ] Commit: "feat(backend): add process close operations"

---

## Task 7: Tauri Commands

**Files:**
- Create: `file-keeper/src-tauri/src/commands/mod.rs`
- Create: `file-keeper/src-tauri/src/commands/process.rs`
- Modify: `file-keeper/src-tauri/src/main.rs`

**Steps:**
- [ ] Create commands module
- [ ] Create lazy_static PROCESS_MONITOR instance
- [ ] Implement get_running_processes command
- [ ] Implement close_process command
- [ ] Implement close_processes command
- [ ] Register commands in main.rs invoke_handler
- [ ] Run `cargo build` to verify
- [ ] Commit: "feat(backend): add Tauri commands for process management"

---

## Week 1 Completion Checklist

- [ ] All 7 tasks completed
- [ ] Backend compiles without errors
- [ ] Can enumerate processes via Tauri command
- [ ] Can close processes via Tauri command
- [ ] All commits pushed to repository

**Next**: Proceed to Week 2 - Frontend Foundation
