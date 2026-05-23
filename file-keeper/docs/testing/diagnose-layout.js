/**
 * 布局诊断脚本
 * 在浏览器控制台运行此脚本来诊断布局问题
 */

console.log('=== File Keeper 布局诊断 ===\n')

// 1. 检查主容器
const mainContainer = document.querySelector('.min-h-screen')
if (mainContainer) {
  console.log('✓ 主容器存在')
  console.log(`  高度: ${mainContainer.offsetHeight}px`)
  console.log(`  可见高度: ${mainContainer.clientHeight}px`)
  console.log(`  滚动高度: ${mainContainer.scrollHeight}px`)
} else {
  console.error('❌ 找不到主容器')
}

// 2. 检查状态栏
const statusBar = document.querySelector('.h-10.px-4.flex.items-center.justify-between.text-xs')
if (statusBar) {
  console.log('\n✓ 状态栏存在')
  const rect = statusBar.getBoundingClientRect()
  console.log(`  位置: top=${rect.top}px, bottom=${rect.bottom}px`)
  console.log(`  窗口高度: ${window.innerHeight}px`)
  console.log(`  是否在视口内: ${rect.top >= 0 && rect.bottom <= window.innerHeight}`)
  console.log(`  是否可见: ${rect.bottom > 0 && rect.top < window.innerHeight}`)
} else {
  console.error('❌ 找不到状态栏')
}

// 3. 检查视图切换按钮
const gridButton = Array.from(document.querySelectorAll('button')).find(btn => {
  return btn.innerHTML.includes('Grid') || btn.querySelector('svg')?.classList?.toString().includes('lucide')
})
if (gridButton) {
  console.log('\n✓ 找到视图切换按钮')
  const rect = gridButton.getBoundingClientRect()
  console.log(`  位置: top=${rect.top}px, bottom=${rect.bottom}px`)
  console.log(`  是否在视口内: ${rect.top >= 0 && rect.bottom <= window.innerHeight}`)
} else {
  console.log('\n⚠ 未找到视图切换按钮（可能被隐藏）')
}

// 4. 检查主内容区
const mainContent = document.querySelector('.flex-1.overflow-auto')
if (mainContent) {
  console.log('\n✓ 主内容区存在')
  console.log(`  高度: ${mainContent.offsetHeight}px`)
  console.log(`  滚动高度: ${mainContent.scrollHeight}px`)
  console.log(`  当前滚动位置: ${mainContent.scrollTop}px`)
  console.log(`  是否可滚动: ${mainContent.scrollHeight > mainContent.clientHeight}`)
} else {
  console.error('❌ 找不到主内容区')
}

// 5. 检查所有子元素
console.log('\n=== 布局结构 ===')
const children = mainContainer?.children
if (children) {
  Array.from(children).forEach((child, index) => {
    const comment = child.previousSibling?.textContent?.trim()
    console.log(`${index + 1}. ${comment || child.className}`)
    console.log(`   高度: ${child.offsetHeight}px`)
  })
}

console.log('\n=== 诊断完成 ===')
console.log('如果状态栏不在视口内，请尝试：')
console.log('1. 最大化窗口')
console.log('2. 滚动到页面底部')
console.log('3. 调整窗口大小')
