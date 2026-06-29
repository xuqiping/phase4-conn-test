# Chat Target Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted "none / agent / workflow" selector next to the chat model selector and validate selected targets on new chat sessions.

**Architecture:** Backend exposes a unified `/api/chat/targets` read model and validates `agentId` / `workflowId` before creating sessions. Frontend loads those targets into a focused selector component, persists the current choice, and sends the selected target only when creating a new chat session.

**Tech Stack:** Spring Boot, MyBatis Plus, JUnit/Mockito, Vue 3, Pinia, Naive UI, Vitest.

---

### Task 1: Backend Chat Target API

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/chat/dto/ChatTargetVO.java`
- Create: `backend/src/main/java/com/superprogrammer/chat/service/ChatTargetService.java`
- Modify: `backend/src/main/java/com/superprogrammer/chat/controller/ChatController.java`
- Test: `backend/src/test/java/com/superprogrammer/chat/controller/ChatControllerTest.java`

- [ ] Add a controller test for `GET /api/chat/targets` returning `NONE`, `AGENT`, and `WORKFLOW` options.
- [ ] Run `mvn -q "-Dtest=ChatControllerTest" test` and verify the test fails because `ChatTargetService` and controller method do not exist.
- [ ] Add `ChatTargetVO`, `ChatTargetService.listTargets`, and controller endpoint.
- [ ] Run `mvn -q "-Dtest=ChatControllerTest" test` and verify it passes.

### Task 2: Backend Target Validation

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java`
- Test: `backend/src/test/java/com/superprogrammer/chat/service/ChatSessionServiceTest.java`

- [ ] Add tests proving a request cannot contain both `agentId` and `workflowId`, and that agent/workflow requests call `ChatTargetService.validateTarget`.
- [ ] Run `mvn -q "-Dtest=ChatSessionServiceTest" test` and verify the new tests fail.
- [ ] Call validation before session insert in `createSession`.
- [ ] Run `mvn -q "-Dtest=ChatSessionServiceTest" test` and verify it passes.

### Task 3: Frontend Target API And Store

**Files:**
- Create: `frontend/src/api/chatTarget.ts`
- Create: `frontend/src/api/chatTarget.test.ts`
- Modify: `frontend/src/api/chat.ts`
- Modify: `frontend/src/utils/storage.ts`
- Modify: `frontend/src/stores/chat.ts`
- Test: `frontend/src/stores/chat.test.ts`

- [ ] Add tests for `chatTargetApi.listTargets`, target persistence, and `streamNewMessage` carrying selected `agentId` / `workflowId`.
- [ ] Run `npm test -- src/api/chatTarget.test.ts src/stores/chat.test.ts` and verify tests fail because the API/store state does not exist.
- [ ] Implement `CHAT_SELECTED_TARGET`, `selectedTarget`, `visibleTargetValue`, `setSelectedTarget`, and target payload resolution.
- [ ] Run the same frontend tests and verify they pass.

### Task 4: Frontend Selector Component And Chat View

**Files:**
- Create: `frontend/src/components/chat/TargetSelector.vue`
- Create: `frontend/src/components/chat/TargetSelector.test.ts`
- Modify: `frontend/src/views/ChatView.vue`

- [ ] Add a component test proving a persisted target is preserved when still available.
- [ ] Run `npm test -- src/components/chat/TargetSelector.test.ts` and verify it fails because the component does not exist.
- [ ] Implement `TargetSelector` and render it beside `ModelSelector` in `ChatView`.
- [ ] Run component tests, full frontend tests, and frontend build.

### Task 5: Final Verification

- [ ] Run `mvn -q "-Dtest=ChatControllerTest,ChatSessionServiceTest" test` from `backend`.
- [ ] Run `npm test -- --run` from `frontend`.
- [ ] Run `npm run build` from `frontend`.
- [ ] Review `git diff` and confirm only intended files changed.
