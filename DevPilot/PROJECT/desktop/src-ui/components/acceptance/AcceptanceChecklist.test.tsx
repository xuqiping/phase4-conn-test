// P06 Phase4 审查修复（C7）：「重新生成」会重置验收状态，必须二次确认。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AcceptanceChecklist from "./AcceptanceChecklist";

const getAcceptanceChecklist = vi.fn(async (_id: number) => [] as unknown[]);
const regenerateAcceptanceChecklist = vi.fn(async (_id: number) => [] as unknown[]);
vi.mock("../../lib/ipc", () => ({
  ipc: {
    getAcceptanceChecklist: (id: number) => getAcceptanceChecklist(id),
    regenerateAcceptanceChecklist: (id: number) => regenerateAcceptanceChecklist(id),
  },
  errMessage: String,
}));

const confirmSpy = vi.spyOn(window, "confirm");

const item = {
  id: 1, tc_id: "TC-01", title: "首页可打开", method: "auto", status: "pass",
  steps: "", expected: "", evidence: null, fix_task_id: null,
};

describe("重新生成二次确认（C7）", () => {
  beforeEach(() => {
    confirmSpy.mockReset();
    regenerateAcceptanceChecklist.mockClear();
  });
  afterEach(cleanup);

  it("已有清单时取消确认则不调用；确认后才重新生成", async () => {
    getAcceptanceChecklist.mockResolvedValue([item]);
    render(<AcceptanceChecklist projectId={1} />);
    await waitFor(() => expect(screen.getByTestId("btn-regenerate")).toBeTruthy());

    confirmSpy.mockReturnValue(false);
    fireEvent.click(screen.getByTestId("btn-regenerate"));
    expect(confirmSpy).toHaveBeenCalled();
    expect(regenerateAcceptanceChecklist).not.toHaveBeenCalled();

    confirmSpy.mockReturnValue(true);
    fireEvent.click(screen.getByTestId("btn-regenerate"));
    await waitFor(() => expect(regenerateAcceptanceChecklist).toHaveBeenCalledWith(1));
  });

  it("空清单时直接生成，不弹确认（正例）", async () => {
    getAcceptanceChecklist.mockResolvedValue([]);
    render(<AcceptanceChecklist projectId={1} />);
    await waitFor(() => expect(screen.getByTestId("btn-regenerate")).toBeTruthy());
    fireEvent.click(screen.getByTestId("btn-regenerate"));
    expect(confirmSpy).not.toHaveBeenCalled();
    await waitFor(() => expect(regenerateAcceptanceChecklist).toHaveBeenCalledWith(1));
  });
});
