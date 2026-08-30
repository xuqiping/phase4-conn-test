// P07 S5（FR-025/AC-028 后半）：'/' 技能候选、Enter 展开、'/usr/bin' 不弹、禁用技能不在列表、
// 提交时 prompt = 技能正文 + 用户文本。
import { cleanup, render, screen, fireEvent, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TaskInputBox from "./TaskInputBox";
import { filterSkills, slashQuery } from "./SkillAutocomplete";
import type { SkillDto } from "../../lib/ipc";

const skills: SkillDto[] = [
  { id: 1, name: "release-check", display_name: "release-check", description: "发版检查", version: "0.1.0", status: "valid" },
  { id: 2, name: "daily-report", display_name: "daily-report", description: "日报", version: "0.1.0", status: "valid" },
];
// 禁用技能不会出现在 list_skills 返回里（Rust 侧已过滤），这里模拟同口径

const listSkills = vi.fn(async () => skills);
const invokeSkill = vi.fn(async (name: string) => `[技能 ${name} 正文]`);
vi.mock("../../lib/ipc", () => ({
  ipc: {
    listSkills: () => listSkills(),
    invokeSkill: (name: string) => invokeSkill(name),
    voiceProbe: async () => false,
  },
}));

beforeEach(() => {
  invokeSkill.mockClear();
});
afterEach(cleanup);

describe("slashQuery / filterSkills 纯函数", () => {
  it("仅首词斜杠触发；/usr/bin 这类路径不弹", () => {
    expect(slashQuery("/re")).toBe("re");
    expect(slashQuery("/usr/bin")).toBeNull();
    expect(slashQuery("hello /re")).toBeNull();
    expect(slashQuery("/Re")).toBeNull(); // 大写不匹配技能名规则
  });
  it("按前缀过滤", () => {
    expect(filterSkills(skills, "re").map((s) => s.name)).toEqual(["release-check"]);
    expect(filterSkills(skills, "").length).toBe(2);
  });
});

describe("TaskInputBox 斜杠候选与提交拼接", () => {
  it("/re 弹出 release-check，Enter 展开成 chip", async () => {
    render(<TaskInputBox onSubmit={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const ta = screen.getByTestId("task-input-textarea") as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: "/re" } });
    await waitFor(() => expect(screen.queryByTestId("skill-autocomplete")).toBeTruthy());
    expect(screen.queryByTestId("skill-option-release-check")).toBeTruthy();
    fireEvent.keyDown(ta, { key: "Enter" });
    await waitFor(() => expect(screen.getByTestId("skill-chip")).toBeTruthy());
    expect(screen.getByTestId("skill-chip").textContent).toContain("release-check");
    expect(ta.value).toBe(""); // 斜杠词被清掉，输入框留给用户正文
  });

  it("挂技能提交时 prompt = 技能正文 + 用户文本", async () => {
    const onSubmit = vi.fn(async (_p: string) => {});
    render(<TaskInputBox onSubmit={onSubmit} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const ta = screen.getByTestId("task-input-textarea") as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: "/daily" } });
    await waitFor(() => expect(screen.queryByTestId("skill-option-daily-report")).toBeTruthy());
    fireEvent.keyDown(ta, { key: "Enter" });
    await waitFor(() => expect(screen.getByTestId("skill-chip")).toBeTruthy());
    fireEvent.change(ta, { target: { value: "今天修了登录" } });
    fireEvent.click(screen.getByTestId("task-input-submit"));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toBe("[技能 daily-report 正文]\n\n今天修了登录");
    expect(invokeSkill).toHaveBeenCalledWith("daily-report");
  });

  it("不挂技能按原文提交；'/' 匹配不到候选不弹层", async () => {
    const onSubmit = vi.fn(async (_p: string) => {});
    render(<TaskInputBox onSubmit={onSubmit} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const ta = screen.getByTestId("task-input-textarea") as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: "/usr/bin" } });
    expect(screen.queryByTestId("skill-autocomplete")).toBeNull();
    fireEvent.change(ta, { target: { value: "普通指令" } });
    fireEvent.click(screen.getByTestId("task-input-submit"));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("普通指令"));
  });

  it("Esc 关闭候选后 Enter 不再被候选拦截", async () => {
    const onSubmit = vi.fn(async () => {});
    render(<TaskInputBox onSubmit={onSubmit} />);
    await waitFor(() => expect(screen.getByTestId("task-input-textarea")).toBeTruthy());
    const ta = screen.getByTestId("task-input-textarea") as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: "/rel" } });
    await waitFor(() => expect(screen.queryByTestId("skill-autocomplete")).toBeTruthy());
    fireEvent.keyDown(ta, { key: "Escape" });
    expect(screen.queryByTestId("skill-autocomplete")).toBeNull();
    // 关闭后再 Enter = 直接提交原文
    fireEvent.keyDown(ta, { key: "Enter" });
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("/rel"));
  });
});
