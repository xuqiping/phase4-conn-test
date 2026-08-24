/**
 * driver/win/keymap.ts —— xdotool 风格组合键解析（FR-007）
 * "ctrl+shift+a" → [{vk:0x11,down:true},...] 按下序列 + 逆序释放序列。
 */

/** 虚拟键码（Win32 VK_*） */
export const VK: Record<string, number> = {
  ctrl: 0x11, control: 0x11, shift: 0x10, alt: 0x12, option: 0x12,
  meta: 0x5b, cmd: 0x5b, win: 0x5b,
  backspace: 0x08, tab: 0x09, enter: 0x0d, return: 0x0d, esc: 0x1b, escape: 0x1b,
  space: 0x20, delete: 0x2e, del: 0x2e, home: 0x24, end: 0x23,
  pageup: 0x21, pagedown: 0x22, up: 0x26, down: 0x28, left: 0x25, right: 0x27,
  arrowup: 0x26, arrowdown: 0x28, arrowleft: 0x25, arrowright: 0x27,
  f1: 0x70, f2: 0x71, f3: 0x72, f4: 0x73, f5: 0x74, f6: 0x75, f7: 0x76, f8: 0x77, f9: 0x78, f10: 0x79, f11: 0x7a, f12: 0x7b,
};

export interface KeyOp {
  vk: number;
  down: boolean;
}

/** 解析组合键；非法成分抛 INVALID_ARGUMENT 语义错误（AC-008） */
export function parseCombo(combo: string): KeyOp[] {
  const parts = combo.split("+").map((p) => p.trim().toLowerCase()).filter(Boolean);
  if (parts.length === 0) throw new Error(`空组合键`);
  const downs: number[] = [];
  for (const p of parts) {
    const vk = VK[p] ?? charVk(p);
    if (vk === undefined) throw new Error(`未知按键: ${p}`);
    downs.push(vk);
  }
  const ops: KeyOp[] = downs.map((vk) => ({ vk, down: true }));
  for (const vk of downs.reverse()) ops.push({ vk, down: false });
  return ops;
}

/** 单字符 → VK（字母/数字区与大写字母直接映射） */
function charVk(ch: string): number | undefined {
  if (ch.length !== 1) return undefined;
  const c = ch.toUpperCase().charCodeAt(0);
  if ((c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5a)) return c; // 0-9 A-Z
  return undefined;
}
