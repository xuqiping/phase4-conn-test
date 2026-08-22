// P07 S7（FR-011/AC-013）：拖非图片拒绝、超 10MB 拒绝、chip 删除联动；语音探测失败隐藏。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TaskInputBox from "./TaskInputBox";
import { validateImageFile } from "./AttachmentChips";
import VoiceDictation from "./VoiceDictation";
import type { AttachmentDto } from "../../lib/ipc";

const saveAttachment = vi.fn(
  async (_p: number, _b: Uint8Array) =>
    ({ id: 7, path: "/tmp/img-1.jpg", source_kb: 120 }) as AttachmentDto,
);
const deleteAttachment = vi.fn(async (_id: number) => {});
const voiceProbe = vi.fn(async () => false);
vi.mock("../../lib/ipc", () => ({
  ipc: {
    listSkills: async () => [],
    invokeSkill: async (_n: string) => "body",
    saveAttachment: (p: number, b: Uint8Array) => saveAttachment(p, b),
    deleteAttachment: (id: number) => deleteAttachment(id),
    voiceProbe: () => voiceProbe(),
  },
}));

beforeEach(() => {
  saveAttachment.mockClear();
  deleteAttachment.mockClear();
});
afterEach(cleanup);

describe("validateImageFile 纯校验", () => {
  it("非图片与超 10MB 都给大白话", () => {
    expect(validateImageFile(new File(["x"], "a.txt", { type: "text/plain" }))).toContain("png");
    const big = new File([new ArrayBuffer(0)], "big.png", { type: "image/png" });
    Object.defineProperty(big, "size", { value: 11 * 1024 * 1024 });
    expect(validateImageFile(big)).toContain("10MB");
    expect(validateImageFile(new File([new ArrayBuffer(0)], "ok.png", { type: "image/png" })))
      .toBeInstanceOf(Uint8Array);
  });
});

describe("TaskInputBox 附件集成", () => {
  it("拖入合法图片 → saveAttachment 调用 + chip 出现；× 删除联动", async () => {
    render(<TaskInputBox projectId={1} onSubmit={vi.fn(async (_p: string) => {})} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const png = new File([new Uint8Array([1, 2, 3])], "shot.png", { type: "image/png" });
    fireEvent.drop(screen.getByTestId("task-input-textarea"), {
      dataTransfer: { files: [png] },
    });
    await waitFor(() => expect(saveAttachment).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId("attachment-chip-7")).toBeTruthy());

    fireEvent.click(screen.getByTestId("attachment-chip-7").querySelector("button")!);
    await waitFor(() => expect(deleteAttachment).toHaveBeenCalledWith(7));
    await waitFor(() => expect(screen.queryByTestId("attachment-chip-7")).toBeNull());
  });

  it("拖入非图片 → 本地拒绝，不调 saveAttachment", async () => {
    render(<TaskInputBox projectId={1} onSubmit={vi.fn(async (_p: string) => {})} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const txt = new File(["hi"], "a.txt", { type: "text/plain" });
    fireEvent.drop(screen.getByTestId("task-input-textarea"), {
      dataTransfer: { files: [txt] },
    });
    expect(saveAttachment).not.toHaveBeenCalled();
    expect(screen.getByText(/只支持 png/)).toBeTruthy();
  });

  it("不传 projectId 不渲染附件区", async () => {
    render(<TaskInputBox onSubmit={vi.fn(async (_p: string) => {})} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    expect(screen.queryByTestId("attachment-chips")).toBeNull();
  });
});

describe("VoiceDictation 降级", () => {
  it("探测不可用 → 不渲染任何东西", async () => {
    const { container } = render(<VoiceDictation />);
    await waitFor(() => expect(voiceProbe).toHaveBeenCalled());
    // 探测完成后组件应隐藏（null 渲染）
    await waitFor(() =>
      expect(container.querySelector('[data-testid="voice-dictation"]')).toBeNull(),
    );
  });
});
