/**
 * 12x-1 C3：验证码发码倒计时跨刷新/重开恢复。
 *
 * 原理：发码成功/被 429 拒时，把「截止时间戳」（now + seconds*1000）写进 localStorage；
 * 刷新页面或重开弹窗后读回截止时间算剩余秒，倒计时续上而不是从 0 重新可点。
 * 归零即清 key（不留痕迹）；存储异常（隐私模式/满）静默忽略——只损失恢复能力，不影响当次倒计时。
 *
 * key 建议带账号标识（如 `mailcode:cd:user@x.com`）：多账号同机各自独立，
 * 换邮箱输入时按新 key 查，旧邮箱的倒计时不会错误压在新邮箱上。
 */
export function saveCooldown(key: string, seconds: number): void {
  try {
    localStorage.setItem(key, String(Date.now() + seconds * 1000))
  } catch {
    /* 存储不可用 → 放弃持久化，仅本次会话内倒计时 */
  }
}

/** 读剩余秒；<=0/无 key/存储异常 → 0（并顺手清过期 key）。 */
export function restoreCooldown(key: string): number {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return 0
    const deadline = Number(raw)
    if (!Number.isFinite(deadline)) {
      localStorage.removeItem(key)
      return 0
    }
    const remain = Math.ceil((deadline - Date.now()) / 1000)
    if (remain <= 0) {
      localStorage.removeItem(key)
      return 0
    }
    return remain
  } catch {
    return 0
  }
}
