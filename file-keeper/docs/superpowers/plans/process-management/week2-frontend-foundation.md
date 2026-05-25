# Week 2: Frontend Foundation

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal**: Build complete Vue 3 frontend with state management and core components.

**Deliverables**:
- TypeScript types and API layer
- Pinia stores (process + settings)
- All Vue components
- Integration into App.vue
- Tab-aware lifecycle working

---

## Task 8: TypeScript Type Definitions
- Create `src/types/process.ts`
- Define ProcessCategory, ProcessInfo, ColumnConfig, ConfirmMode, ProcessSettings, CloseResult
- Verify with `npm run type-check`
- Commit: "feat(frontend): add process type definitions"

## Task 9: API Layer
- Create `src/api/process.ts`
- Implement getRunningProcesses(), closeProcess(), closeProcesses()
- Wrap Tauri invoke calls with error handling
- Commit: "feat(frontend): add process API layer"

## Task 10: Process Store
- Create `src/stores/processStore.ts`
- State: processes, selectedIds, currentCategory, isRefreshing, etc.
- Methods: refresh(), toggleSelect(), selectAll(), invertSelection(), closeProcess(), closeSelected(), clearProcesses()
- Computed: filteredProcesses, selectedCount, categoryCounts
- Commit: "feat(frontend): add process store with cleanup method"

## Task 11: Process Settings Store
- Create `src/stores/processSettingsStore.ts`
- Default settings with columns, autoRefresh, confirmMode, whitelist
- Methods: loadSettings(), saveSettings(), updateColumns()
- Use localStorage for persistence
- Commit: "feat(frontend): add process settings store"

## Task 12: ProcessManagement Component
- Create `src/components/ProcessManagement.vue`
- Use onActivated() to start monitoring when tab active
- Use onDeactivated() to stop monitoring and clear data
- Watch document.visibilityState for browser tab changes
- Commit: "feat(frontend): add ProcessManagement with tab-aware lifecycle"

## Task 13: ProcessToolbar Component
- Create `src/components/ProcessToolbar.vue`
- Buttons: refresh, auto-refresh toggle, select all, invert, close selected
- Show countdown when auto-refresh enabled
- Commit: "feat(frontend): add ProcessToolbar component"

## Task 14: ProcessFilter Component
- Create `src/components/ProcessFilter.vue`
- Dropdown for category selection
- Quick filter buttons for common categories
- Display category counts
- Commit: "feat(frontend): add ProcessFilter component"

## Task 15: ProcessList Component
- Create `src/components/ProcessList.vue`
- Use useVirtualScroll composable
- Render ProcessRow for each visible item
- Status bar with counts
- Commit: "feat(frontend): add ProcessList with virtual scrolling"

## Task 16: ProcessRow Component
- Create `src/components/ProcessRow.vue`
- Display all columns based on settings
- Formatters: formatMemory(), formatRuntime(), truncate()
- Emit toggle-select and close events
- Commit: "feat(frontend): add ProcessRow component"

## Task 17: ConfirmDialog Component
- Create `src/components/ConfirmDialog.vue`
- Modal with process list and warning
- Check whitelist for important apps
- Emit confirm/cancel events
- Commit: "feat(frontend): add ConfirmDialog component"

## Task 18: Integrate into App.vue
- Add currentTab state ('files' | 'processes')
- Add tab button with Activity icon
- Use v-if for ProcessManagement (ensures lifecycle hooks fire)
- Test tab switching triggers onActivated/onDeactivated
- Commit: "feat(frontend): integrate process management with tab-aware lifecycle"

---

## Week 2 Completion Checklist
- [x] All 11 tasks (8-18) completed
- [x] Can switch between file and process tabs
- [x] Process list loads only when tab is active
- [x] Auto-refresh stops when switching away
- [x] All components render correctly

**Status**: ✅ COMPLETED on 2026-05-25

**Next**: Week 3 - Advanced Features
