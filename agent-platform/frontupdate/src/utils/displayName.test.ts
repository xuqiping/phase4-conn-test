// agent-platform/frontend/src/utils/displayName.test.ts
import { describe, expect, it } from 'vitest'
import { displayName } from './displayName'

// 修复III E2（12x#3）：displayName 统一口径（姓名优先，回落用户名）
describe('displayName (E2 12x#3)', () => {
  it('有姓名 → 显姓名', () => {
    expect(displayName({ name: '张三', username: 'zhangsan' })).toBe('张三')
  })

  it('姓名空白 → 回落用户名', () => {
    expect(displayName({ name: '   ', username: 'zhangsan' })).toBe('zhangsan')
    expect(displayName({ name: '', username: 'zhangsan' })).toBe('zhangsan')
  })

  it('无姓名（null/undefined）→ 显用户名', () => {
    expect(displayName({ name: null, username: 'zhangsan' })).toBe('zhangsan')
    expect(displayName({ username: 'zhangsan' })).toBe('zhangsan')
  })

  it('空对象防御 → 空串', () => {
    expect(displayName(null)).toBe('')
    expect(displayName(undefined)).toBe('')
  })
})
