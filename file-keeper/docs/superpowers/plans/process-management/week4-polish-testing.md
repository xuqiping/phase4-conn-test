# Week 4: Polish and Testing

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal**: Complete all process mappings, test thoroughly, document, and prepare for release.

**Deliverables**:
- All 400+ process mappings
- Performance testing results
- Manual testing completed
- Documentation updated
- Production-ready build

---

## Task 23: Add Remaining Process Mappings
- Modify `src-tauri/src/platform/windows/process_mappings.rs`
- Add Document category (100+ apps)
- Add Media category (40+ apps)
- Add Image category (40+ apps)
- Add Communication category (40+ apps)
- Add Download category (40+ apps)
- Add Game category (40+ apps)
- Add System category (50+ apps)
- Update load_process_mappings() to call all functions
- Commit: "feat(backend): add all remaing process category mappings"

## Task 24: Performance Testing and Optimization
- Test enumeration with 100+ processes
- Measure time (target < 500ms)
- Test virtual scrolling framerate (target ≥ 55fps)
- Test auto-refresh CPU/memory impact
- Optimize if needed (caching, reduce frequency)
- Document results

## Task 25: Manual Testing
- Test all 13+ categories identify processes correctly
- Test single close operation
- Test batch close operation
- Test with/without confirmation
- Test edge cases: no processes, permission denied, process already closed
- Test settings persistence (columns, auto-refresh, whitelist)
- Test UI responsiveness (no lag or freezing)
- Test tab switching lifecycle

## Task 26: Documentation
- Update `file-keeper/README.md`
- Add process management section
- Add screenshots of the interface
- Update feature list
- Document data storage location
- Commit: "docs: add process management feature documentation"

## Task 27: Final Integration Test
- Run `npm run tauri build`
- Test MSI installer
- Test on clean Windows system
- Verify no missing dependencies
- Create release notes
- Tag release version

---

## Week 4 Completion Checklist
- [ ] All 5 tasks (23-27) completed
- [ ] All 400+ process mappings added
- [ ] Performance targets met
- [ ] All manual tests passed
- [ ] Documentation complete
- [ ] Release build successful

**Status**: Feature complete and ready for production!
