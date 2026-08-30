// ============================================================
// 7x#1：UUID v4 生成——crypto.randomUUID 仅安全上下文（https/localhost）可用，
// 纯 http 部署（内网 IP 访问）下 undefined → 直接 TypeError 把充值/任务恢复链路整崩。
// 这里统一出口：原生可用走原生，否则 Math.random 回落（幂等键/本地 id 场景，非安全用途）。
// ============================================================

/** UUID v4（安全上下文走 crypto.randomUUID，http 环境回落伪随机实现）。 */
export function uuid(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, ch => {
    const r = (Math.random() * 16) | 0
    const v = ch === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
