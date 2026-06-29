# Chat Workflow Runtime Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make chat-selected workflows receive the chat message as the START node input, stream workflow steps into assistant thinking, and return the final workflow text as the assistant answer.

**Architecture:** Add a chat-specific runtime entry point that augments workflow input with `input`, `message`, `prompt`, `text`, and the configured START node `inputKey`. In chat streaming, route `WORKFLOW` sessions through `RuntimeExecutionService`, translate `ExecutionEvent` records into `THINKING` events, emit one final `CHUNK`, and let existing chat persistence save the final answer plus thinking metadata.

**Tech Stack:** Spring Boot, Reactor Flux, JUnit/Mockito, runtime-sidecar `ExecutionEvent`.

---

### Task 1: Chat Runtime Input

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/runtime/service/RuntimeExecutionService.java`
- Test: `backend/src/test/java/com/superprogrammer/runtime/service/RuntimeExecutionServiceTest.java`

- [ ] Add a failing test proving `runWorkflowFromChat(10L, 7L, "hello")` sends input keys `input`, `message`, `prompt`, `text`, and configured START key `ccc`.
- [ ] Implement `runWorkflowFromChat` by loading the workflow, parsing the START node config, and calling existing `runWorkflowDefinition`.
- [ ] Run `mvn -q "-Dtest=RuntimeExecutionServiceTest#runWorkflowFromChat_overridesStartInputKeyWithChatMessage" test`.

### Task 2: Chat Workflow Streaming

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/chat/service/ChatSessionService.java`
- Test: `backend/src/test/java/com/superprogrammer/chat/service/ChatSessionServiceTest.java`

- [ ] Add a failing test proving workflow streaming calls runtime service, emits node progress as `THINKING`, emits final `CHUNK`, and persists that final text as assistant content.
- [ ] Implement a workflow branch in `sendMessageStream`.
- [ ] Run `mvn -q "-Dtest=ChatSessionServiceTest#sendMessageStream_workflowStreamsRuntimeThinkingAndFinalOutput" test`.

### Task 3: Verification

- [ ] Run `mvn -q "-Dtest=ChatSessionServiceTest,RuntimeExecutionServiceTest,ChatControllerTest" test`.
- [ ] Run `mvn -q -DskipTests compile`.
- [ ] Restart backend and send a real chat workflow message from the browser.
