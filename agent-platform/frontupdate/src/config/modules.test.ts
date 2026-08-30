import { describe, expect, it } from 'vitest'
import {
  ENABLED_MODULES,
  isModuleEnabled,
  getModulePermission
} from './modules'

describe('config/modules', () => {
  describe('ENABLED_MODULES（问题 10x-5 项目级开关）', () => {
    it('Agent大厅/工作流/执行监控对本项目关闭', () => {
      // 这三项是问题单明确要求「本项目暂用不到，对所有人隐藏含 admin」的模块
      expect(isModuleEnabled('agentHall')).toBe(false)
      expect(isModuleEnabled('workflow')).toBe(false)
      expect(isModuleEnabled('execution')).toBe(false)
    })

    it('常用模块默认开启', () => {
      expect(isModuleEnabled('chat')).toBe(true)
      expect(isModuleEnabled('knowledge')).toBe(true)
      expect(isModuleEnabled('settings')).toBe(true)
      expect(isModuleEnabled('wallet')).toBe(true)
    })

    it('isModuleEnabled 与 ENABLED_MODULES 表一致', () => {
      // 防止有人改了表忘了改函数（或反之）
      ;(Object.keys(ENABLED_MODULES) as Array<keyof typeof ENABLED_MODULES>).forEach((k) => {
        expect(isModuleEnabled(k)).toBe(ENABLED_MODULES[k] === true)
      })
    })

    it('未启用模块显式为 false，而非 undefined', () => {
      // 防止新增模块时漏赋值导致 undefined 被当 truthy
      ;(Object.keys(ENABLED_MODULES) as Array<keyof typeof ENABLED_MODULES>).forEach((k) => {
        expect(ENABLED_MODULES[k]).not.toBeUndefined()
      })
    })
  })

  describe('MODULE_PERMISSION_MAP（问题 10x-4 权限兜底）', () => {
    it('Agent大厅映射到 agent:read（种子权限码）', () => {
      expect(getModulePermission('agentHall')).toBe('agent:read')
    })

    it('工作流映射到 workflow:read', () => {
      expect(getModulePermission('workflow')).toBe('workflow:read')
    })

    it('执行监控映射到 execution:read', () => {
      expect(getModulePermission('execution')).toBe('execution:read')
    })

    it('媒体模块映射到 media:gen / media:edit', () => {
      expect(getModulePermission('videoGen')).toBe('media:gen')
      expect(getModulePermission('imageGen')).toBe('media:gen')
      expect(getModulePermission('videoEdit')).toBe('media:edit')
    })

    it('不卡权限码的模块返回 undefined', () => {
      expect(getModulePermission('chat')).toBeUndefined()
      expect(getModulePermission('knowledge')).toBeUndefined()
      expect(getModulePermission('wallet')).toBeUndefined()
    })
  })
})
