import { describe, it, expect } from 'vitest'
import router from '@/router'

// 路由冒烟：5 核心页路由存在（渲染级冒烟由 dev 服务器 + 测试方案浏览器走查覆盖，
// vue-flow 等画布依赖在 happy-dom 下不适合做整页挂载）
describe('路由', () => {
  it('5 核心页路由齐全', () => {
    const paths = router.getRoutes().map((r) => r.path)
    for (const p of ['/canvas', '/chat', '/agents', '/workflows', '/:pathMatch(.*)*']) {
      expect(paths).toContain(p)
    }
  })

  it('默认路由重定向到 /canvas', async () => {
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/canvas')
  })
})
