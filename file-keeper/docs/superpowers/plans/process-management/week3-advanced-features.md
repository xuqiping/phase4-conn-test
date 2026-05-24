# Week 3: Advanced Features

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal**: Add advanced features for better UX and configurability.

**Deliverables**:
- Auto-refresh with configurable interval
- Column customization UI
- Confirmation logic with whitelist
- Error handling and toast notifications

---

## Task 19: Auto-Refresh Implementation
- Modify `src/stores/processStore.ts`
- Add startAutoRefresh() method with setInterval
- Add stopAutoRefresh() method to clear timer
- Use refreshInterval from settings store
- Export methods
- Commit: "feat(frontend): add auto-refresh functionality"

## Task 20: Column Customization
- Create `src/components/ColumnSettings.vue`
- Checkboxes for each column visibility
- Drag-and-drop for column reordering
- Save to processSettingsStore
- Add settings button to toolbar
- Commit: "feat(frontend): add column customization"

## Task 21: Confirmation Logic Integration
- Modify `src/components/ProcessList.vue`
- Add shouldConfirm() helper checking confirmMode and whitelist
- Show ConfirmDialog before close operations
- Handle confirmation result
- Commit: "feat(frontend): integrate confirmation logic"

## Task 22: Error Handling and Toast Notifications
- Create `src/composables/useToast.ts`
- Simple toast notification system
- Add error handling in store methods
- Show success/error toasts for operations
- Commit: "feat(frontend): add error handling and toast notifications"

---

## Week 3 Completion Checklist
- [ ] All 4 tasks (19-22) completed
- [ ] Auto-refresh works with configurable interval
- [ ] Can customize which columns to display
- [ ] Confirmation dialogs show based on settings
- [ ] Toast notifications appear for all operations

**Next**: Week 4 - Polish and Testing
