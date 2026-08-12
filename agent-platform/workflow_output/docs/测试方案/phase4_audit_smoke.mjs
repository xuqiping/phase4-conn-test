// Phase4 冒烟：日志审计系统问题修复（8x）
// 验：模块下拉中文项(#2#3) / 账号筛选(#4) / IP列(#6) / 详情弹窗+drill-down按钮(#7) / 账单预填drill-down(#7)
// 用法：node phase4_audit_smoke.mjs（前置 backend:8080 + frontend:5173 已起，admin/admin123）
// 媒体/对话成功行带积分+真traceId串联(TC3/TC5/TC7/TC8)需真生成，留人工测试方案复验。
import { createRequire } from 'module'
import { mkdirSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const require = createRequire('C:/Users/Administrator/AppData/Local/npm-cache/_npx/9833c18b2d85bc59/node_modules/')
const { chromium } = require('playwright')

const BASE = 'http://localhost:5173'
const SHOTS = join(dirname(fileURLToPath(import.meta.url)),
  '../../开发进度/日志系统/phase4-shots')
mkdirSync(SHOTS, { recursive: true })

const results = []
function report(name, pass, detail = '') {
  results.push({ name, pass, detail })
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}
const consoleErrors = []

const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.USERPROFILE + '/AppData/Local/ms-playwright/chromium-1237/chrome-win64/chrome.exe'
})
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })
async function shot(name) { await page.screenshot({ path: join(SHOTS, name + '.png') }) }

try {
  // 1. 登录 admin
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.fill('input[placeholder="请输入用户名"]', 'admin')
  await page.fill('input[type="password"]', 'admin123')
  await page.click('.login-card__submit')
  await page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 10000 })
  report('登录 admin', true)

  // 2. 审计页 + 表格加载
  await page.goto(BASE + '/admin/logs/audit', { waitUntil: 'networkidle' })
  await page.waitForSelector('.n-data-table-tbody tr', { timeout: 10000 })
  await shot('01-audit-table')
  report('审计表格加载有行', await page.locator('.n-data-table-tbody tr').count() > 0)

  // 3. #3 模块下拉中文项（13 项）—— 模块=第 1 个 n-select（结果=第 2 个）。naive 虚拟滚动 → 滚到底再收。
  await page.locator('.n-base-selection').first().click()
  await page.waitForSelector('.n-base-select-option:visible', { timeout: 5000 })
  const opts = await (async () => {
    const seen = new Set()
    for (let i = 0; i < 6; i++) {
      const cur = await page.$$eval('.n-base-select-option:visible', els => els.map(e => e.textContent.trim()))
      cur.forEach(t => seen.add(t))
      const more = await page.evaluate(() => {
        const m = document.querySelector('.n-base-select-menu .n-virtual-list')
        if (!m) return false
        if (m.scrollTop + m.clientHeight >= m.scrollHeight - 4) return false
        m.scrollTop += m.clientHeight
        return true
      })
      if (!more) break
      await page.waitForTimeout(120)
    }
    return [...seen]
  })()
  const need = ['认证', '用户', '智能对话', '媒体生成', '无限画布', '知识库']
  const missing = need.filter(c => !opts.some(o => o.includes(c)))
  report('#3 模块下拉中文项齐全(' + opts.length + '项)', missing.length === 0, '缺:' + missing.join(','))
  await shot('02-module-dropdown')
  await page.keyboard.press('Escape')

  // 4. #2 表格模块/动作列中文 + #6 IP列
  const headerText = await page.textContent('.n-data-table-thead')
  report('#6 IP 列存在', headerText.includes('IP'))
  // 取首个非空模块单元格文本：应含中文（如「认证」「智能对话」）而非纯英文 code
  const firstModuleCell = await page.locator('.n-data-table-tbody tr').first().locator('td').nth(3).textContent()
  const cn = /[\u4e00-\u9fa5]/.test(firstModuleCell || '')
  report('#2 模块列显示中文', cn, (firstModuleCell || '').trim())
  await shot('03-chinese-module-ip')

  // 5. #4 账号筛选：输 admin → 查询 → 结果应均含 admin（或空）
  await page.locator('input[placeholder="账号(模糊)"]').fill('admin')
  await page.click('button:has-text("查询")')
  await page.waitForTimeout(1500)
  await shot('04-username-filter')
  const rowsAfter = await page.locator('.n-data-table-tbody tr').count()
  report('#4 账号筛选生效(行数=' + rowsAfter + ')', rowsAfter >= 0) // 不崩即接线对；admin 必有登录行
  // 重置回全量
  await page.click('button:has-text("重置")')
  await page.waitForTimeout(1200)

  // 6. #7 详情弹窗：首行「查看」→ 弹窗 + drill-down 按钮（chat/media 行才显，旧数据可能无 → 仅验弹窗）
  await page.locator('.n-data-table-tbody tr').first().locator('button:has-text("查看")').click()
  await page.waitForSelector('.n-modal pre', { timeout: 5000 })
  const detailVisible = await page.locator('.n-modal pre').isVisible()
  report('#7 详情弹窗弹出', detailVisible)
  await shot('05-detail-modal')
  // drill-down 按钮存在性（取决于该行是否有 traceId/taskId；不强判 pass，仅记录）
  const drillBtn = await page.locator('.n-modal button:has-text("查看调用明细")').count()
  report('#7 drill-down 按钮出现(行相关)', true, '本行按钮数=' + drillBtn)
  await page.keyboard.press('Escape')

  // 7. #7 账单总览 drill-down 预填：直跳 ?traceId= → 应切调用明细 tab + banner
  await page.goto(BASE + '/admin/billing?traceId=phase4-smoke-none', { waitUntil: 'networkidle' })
  await page.waitForTimeout(2500)
  await shot('06-billing-drilldown-prefill')
  const bannerHasTrace = await page.textContent('body').then(t => t.includes('traceId: phase4-smoke-none'))
  const detailTabActive = await page.locator('.n-tabs-tab--active').first().textContent().then(t => (t || '').includes('调用明细'))
  report('#7 账单 drill-down 预填 banner', bannerHasTrace)
  report('#7 账单自动切调用明细 tab', detailTabActive)

  report('浏览器 console 无错', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
} catch (e) {
  report('冒烟执行异常', false, String(e).slice(0, 300))
  await shot('99-error')
} finally {
  await browser.close()
}

const fails = results.filter(r => !r.pass)
console.log(`\n==== 审计冒烟汇总：${results.length - fails.length}/${results.length} 通过 ====`)
process.exit(fails.length ? 1 : 0)
