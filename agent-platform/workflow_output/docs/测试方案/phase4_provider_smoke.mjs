// Phase4 冒烟：模型供应商全URL与类型化改造（FR-001/002/004 前端四分 + VIDEO 真探测）
// 用法：PATH 前置 Node>=22，node phase4_provider_smoke.mjs
// 截图存档到 workflow_output/开发进度/模型供应商全URL改造/phase4-shots/
import { createRequire } from 'module'
import { mkdirSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const require = createRequire('D:/IT/nvm4w/nodejs/node_modules/@playwright/mcp/')
const { chromium } = require('playwright')

const BASE = 'http://localhost:5173'
const RUN = String(Date.now() % 100000)
const SHOTS = join(dirname(fileURLToPath(import.meta.url)),
  '../../开发进度/模型供应商全URL改造/phase4-shots')
mkdirSync(SHOTS, { recursive: true })

const results = []
function report(name, pass, detail = '') {
  results.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

const consoleErrors = []
// 复用 ms-playwright 缓存的 chromium-1140（全局 playwright 期望的 1229 未下载，executablePath 直指缓存）
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.USERPROFILE + '/AppData/Local/ms-playwright/chromium-1140/chrome-win/chrome.exe'
})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })

async function shot(name) {
  await page.screenshot({ path: join(SHOTS, name + '.png'), fullPage: false })
}

try {
  // 1. 登录
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入用户名"]', 'admin')
  await page.fill('input[type="password"]', 'admin123')
  await page.click('.login-card__submit')
  await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 10000 })
  report('登录 admin', true)

  // 2. 设置 → 全局模型供应商
  await page.goto(BASE + '/settings', { waitUntil: 'networkidle' })
  await page.click('.n-tabs-tab:has-text("全局模型供应商")')
  await page.waitForSelector('.n-data-table-tbody tr', { timeout: 8000 })
  await shot('01-provider-table')
  const tableText = await page.textContent('.n-data-table')
  report('列表徽标含「视频」', tableText.includes('视频'))
  report('列表徽标无「对话+向量/媒体」旧类', !tableText.includes('对话+向量') && !tableText.includes('媒体'))

  // 3. 打开添加供应商弹窗（CHAT 默认）
  await page.click('button:has-text("添加供应商")')
  await page.waitForSelector('.n-modal', { timeout: 5000 })
  const endpointInput = page.locator('.n-modal input').nth(2) // 名称/显示名/端点
  const phChat = await endpointInput.getAttribute('placeholder')
  report('CHAT placeholder=/chat/completions', phChat?.includes('/chat/completions'), phChat)
  report('CHAT 协议可见', await page.locator('.n-modal .n-form-item:has-text("协议")').isVisible())
  await shot('02-modal-chat')

  // 切类型辅助：点类型 select → 点选项（:visible 过滤，naive 下拉里关闭的选项残留 DOM）
  async function pickCategory(label) {
    await page.locator('.n-modal .n-form-item:has-text("类型") .n-base-selection').click()
    await page.locator('.n-base-select-option:visible:has-text("' + label + '")').first().click()
    await page.waitForTimeout(300)
  }

  // 4. EMBEDDING：placeholder 切换 + ANTHROPIC 禁选
  await pickCategory('向量')
  const phEmbed = await endpointInput.getAttribute('placeholder')
  report('EMBEDDING placeholder=/embeddings', phEmbed?.includes('/embeddings'), phEmbed)
  await page.locator('.n-modal .n-form-item:has-text("协议") .n-base-selection').click()
  await page.waitForSelector('.n-base-select-option:visible', { timeout: 3000 })
  const anthropicOpt = page.locator('.n-base-select-option:visible:has-text("Anthropic")')
  const anthropicDisabled = await anthropicOpt.getAttribute('class')
  report('EMBEDDING 下 ANTHROPIC 禁选', anthropicDisabled?.includes('disabled'), anthropicDisabled)
  await shot('03-embedding-anthropic-disabled')
  await page.keyboard.press('Escape')

  // 5. VIDEO：协议隐藏 + placeholder 任务端点
  await pickCategory('视频')
  const phVideo = await endpointInput.getAttribute('placeholder')
  report('VIDEO placeholder=任务端点完整URL', phVideo?.includes('/contents/generations/tasks'), phVideo)
  report('VIDEO 协议隐藏', !(await page.locator('.n-modal .n-form-item:has-text("协议")').isVisible().catch(() => false)))
  await shot('04-modal-video')

  // 6. IMAGE：协议隐藏 + 测试不发请求直接 info
  await pickCategory('生图')
  const phImage = await endpointInput.getAttribute('placeholder')
  report('IMAGE placeholder=生图占位', phImage?.includes('生图'), phImage)
  await endpointInput.fill('https://example.com/v1/images/generations')
  let imageTestRequested = false
  page.on('request', r => { if (r.url().includes('/test')) imageTestRequested = true })
  await page.click('.n-modal button:has-text("测试连通")')
  await page.waitForSelector('.n-message', { timeout: 5000 })
  const msgImage = await page.textContent('.n-message')
  report('IMAGE 测试=info 话术', msgImage?.includes('生图 provider 尚未接入'), msgImage?.trim())
  report('IMAGE 测试未发请求', !imageTestRequested)
  await shot('05-image-test-info')

  // 7. CHAT + base 形态 URL 保存 → 软警告
  await pickCategory('对话')
  await page.locator('.n-modal input').nth(0).fill('phase4-smoke-' + RUN + '')
  await endpointInput.fill('https://api.openai.com/v1')
  await page.locator('.n-modal input[type="password"]').fill('sk-phase4-dummy')
  await page.click('.n-modal button:has-text("保存")')
  await page.waitForSelector('.n-message--warning, .n-message', { timeout: 8000 })
  await page.waitForTimeout(500)
  const msgs = await page.$$eval('.n-message', els => els.map(e => e.textContent))
  const warned = msgs.some(t => t.includes('疑似 base URL'))
  const saved = msgs.some(t => t.includes('创建成功'))
  report('base 形态 URL 保存软警告', warned, msgs.join(' | '))
  report('保存未拦截（创建成功）', saved)
  await shot('06-baseurl-warning')

  // 8. 清理冒烟 provider
  await page.waitForTimeout(800)
  const row = page.locator('.n-data-table-tbody tr:has-text("phase4-smoke-' + RUN + '")')
  if (await row.count()) {
    await row.locator('button:has-text("删除")').click()
    await page.waitForTimeout(1000)
    report('清理冒烟 provider', true)
  } else {
    report('清理冒烟 provider', false, '行未找到')
  }

  // 9. VIDEO provider 真探测（seedance 行点测试）
  const videoRow = page.locator('.n-data-table-tbody tr:has-text("视频")').first()
  if (await videoRow.count()) {
    await videoRow.locator('button:has-text("测试")').click()
    // 等探测结果消息（区别于残留的「创建成功」）：轮询最新一条含结果关键字的消息
    let msgVideo = ''
    try {
      await page.waitForFunction(() => {
        const texts = [...document.querySelectorAll('.n-message')].map(e => e.textContent)
        return texts.some(t => /连接成功|Key 无效|HTTP \d|失败|未配置/.test(t))
      }, { timeout: 30000 })
      const texts = await page.$$eval('.n-message', els => els.map(e => e.textContent))
      msgVideo = texts.filter(t => /连接成功|Key 无效|HTTP \d|失败|未配置/.test(t)).pop() ?? ''
    } catch { msgVideo = '(30s 无探测结果消息)' }
    const probeOk = msgVideo.includes('连接成功')
    report('VIDEO 真探测（ctaigw 任务端点）', probeOk, msgVideo.trim())
    await shot('07-video-probe-result')
  } else {
    report('VIDEO 真探测', false, '无 VIDEO 行')
  }

  // 10. 视频生成页模型目录（VIDEO 语义）
  await page.goto(BASE + '/video-gen', { waitUntil: 'networkidle' }).catch(() => {})
  await page.waitForTimeout(2000)
  const vgText = await page.textContent('body')
  const hasModelSelect = vgText.includes('视频模型')
  const oldWording = vgText.includes('MEDIA 类供应商')
  report('视频页模型选择器存在（或空态 VIDEO 措辞）', hasModelSelect || vgText.includes('VIDEO 类供应商'))
  report('视频页无 MEDIA 旧措辞', !oldWording)
  await shot('08-video-gen')

  report('浏览器 console 无错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
} catch (e) {
  report('冒烟执行异常', false, String(e).slice(0, 300))
  await shot('99-error')
} finally {
  await browser.close()
}

const fails = results.filter(r => !r.pass)
console.log(`\n==== 冒烟汇总：${results.length - fails.length}/${results.length} 通过 ====`)
process.exit(fails.length ? 1 : 0)
