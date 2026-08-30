#!/usr/bin/env node
// ============================================================
// 高山流水 · WCAG 对比度校验（ART-QA-0001 第四节）
// 校验 ye-mo / xuan-zhi 双主题关键色对，红线：正文 ≥4.5、大字 ≥3.0
// 运行：node scripts/check-contrast.mjs  （CI 可挂 npm run check:contrast）
// ============================================================

/** 相对亮度（WCAG 2.1） */
function luminance(hex) {
  const rgb = hex.replace('#', '').match(/.{2}/g).map(v => parseInt(v, 16) / 255)
  const [r, g, b] = rgb.map(c => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function ratio(a, b) {
  const [l1, l2] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (l1 + 0.05) / (l2 + 0.05)
}

// 色对清单：与 ye-mo.scss / xuan-zhi.scss / naive-overrides.ts 保持一致（token 真值源变更时同步）
const THEMES = {
  'ye-mo（夜墨·暗）': {
    pairs: [
      ['正文/主背景', '#DFE7EE', '#151D29', 4.5],
      ['正文/卡片', '#DFE7EE', '#1C2634', 4.5],
      ['次级文字/主背景', '#9AABBC', '#151D29', 4.5],
      ['次级文字/卡片', '#9AABBC', '#1C2634', 4.5],
      ['主按钮文字/主色', '#151D29', '#8FBCD4', 4.5],
      ['链接天青/主背景', '#8FBCD4', '#151D29', 4.5],
      ['成功/卡片', '#63B98A', '#1C2634', 3.0],
      ['警告/卡片', '#D9A45B', '#1C2634', 3.0],
      ['错误/卡片', '#D9564A', '#1C2634', 3.0],
      ['信息/卡片', '#7FA3CC', '#1C2634', 3.0],
      // Toast 四态：正文 textColor1 on 染色底（naive-overrides.ts Message）
      ['Toast正文/信息底', '#DFE7EE', '#26344A', 4.5],
      ['Toast正文/成功底', '#DFE7EE', '#22392E', 4.5],
      ['Toast正文/警告底', '#DFE7EE', '#3A2F1E', 4.5],
      ['Toast正文/错误底', '#DFE7EE', '#3D2426', 4.5],
      ['Toast错误图标/错误底', '#D9564A', '#3D2426', 3.0]
    ]
  },
  'xuan-zhi（宣纸·明）': {
    pairs: [
      ['正文/主背景', '#26221C', '#F5F1E6', 4.5],
      ['正文/卡片', '#26221C', '#FDFBF4', 4.5],
      ['次级文字/主背景', '#6B655A', '#F5F1E6', 4.5],
      ['次级文字/卡片', '#6B655A', '#FDFBF4', 4.5],
      ['主按钮文字/主色', '#F5F1E6', '#35687F', 4.5],
      ['链接深天青/主背景', '#35687F', '#F5F1E6', 4.5],
      ['成功/卡片', '#3E8E63', '#FDFBF4', 3.0],
      ['警告/卡片', '#B07A28', '#FDFBF4', 3.0],
      ['错误/卡片', '#C03A2E', '#FDFBF4', 3.0],
      ['信息/卡片', '#4A6E9E', '#FDFBF4', 3.0],
      // Toast 四态：正文 textColor1 on 染色底（naive-overrides.ts Message）
      ['Toast正文/信息底', '#26221C', '#E9EFF5', 4.5],
      ['Toast正文/成功底', '#26221C', '#E4F0E9', 4.5],
      ['Toast正文/警告底', '#26221C', '#F5ECDC', 4.5],
      ['Toast正文/错误底', '#26221C', '#F6E4E1', 4.5],
      ['Toast错误图标/错误底', '#C03A2E', '#F6E4E1', 3.0]
    ]
  }
}

let fail = 0
for (const [theme, { pairs }] of Object.entries(THEMES)) {
  console.log(`\n=== ${theme} ===`)
  for (const [name, fg, bg, min] of pairs) {
    const r = ratio(fg, bg)
    const ok = r >= min
    if (!ok) fail++
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${fg} on ${bg}  = ${r.toFixed(2)}:1（要求 ≥${min}:1）`)
  }
}

console.log(fail === 0 ? '\n全部通过 ✓' : `\n${fail} 项不达标 ✗`)
process.exit(fail === 0 ? 0 : 1)
