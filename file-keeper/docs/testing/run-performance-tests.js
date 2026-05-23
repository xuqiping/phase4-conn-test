/**
 * 自动化性能测试脚本
 *
 * 使用方法：
 * 1. 启动应用: npm run tauri:dev
 * 2. 打开控制台 (F12)
 * 3. 复制粘贴此脚本
 * 4. 运行: await runPerformanceTests()
 */

// 确保测试数据生成脚本已加载
if (typeof generateTestFiles === 'undefined') {
  console.error('❌ 请先加载 generate-test-data.js 脚本')
  console.log('复制粘贴 docs/testing/generate-test-data.js 的内容到控制台')
}

/**
 * 测试 1: 启动时间测试
 */
async function testStartupTime() {
  console.log('\n=== 测试 1: 启动时间 ===\n')

  const results = []
  const fileCounts = [0, 100, 500, 1000]

  for (const count of fileCounts) {
    console.log(`测试 ${count} 个文件的启动时间...`)

    // 生成测试数据
    if (count > 0) {
      await generateTestFiles(count, true)
    } else {
      clearAllFiles()
    }

    console.log(`✓ 已准备 ${count} 个文件`)
    console.log('请刷新页面并查看控制台输出的 "[Performance] App startup time"')
    console.log('记录 5 次启动时间后，按任意键继续...\n')

    results.push({
      fileCount: count,
      note: '需要手动刷新页面 5 次并记录启动时间'
    })
  }

  return results
}

/**
 * 测试 2: 滚动性能测试
 */
async function testScrollPerformance() {
  console.log('\n=== 测试 2: 滚动性能 ===\n')

  // 生成 1000 个文件
  console.log('生成 1000 个测试文件...')
  await generateTestFiles(1000, true)

  console.log('\n网格视图测试:')
  console.log('1. 确保当前是网格视图')
  console.log('2. 打开 DevTools > Performance')
  console.log('3. 点击 Record 按钮')
  console.log('4. 快速滚动 10 秒')
  console.log('5. 停止录制')
  console.log('6. 查看 FPS 图表，记录平均帧率和最低帧率')

  console.log('\n列表视图测试:')
  console.log('1. 切换到列表视图（点击右下角列表图标）')
  console.log('2. 重复上述步骤 2-6')

  return {
    note: '需要手动使用 DevTools Performance 面板测试'
  }
}

/**
 * 测试 3: 搜索响应时间测试
 */
async function testSearchPerformance() {
  console.log('\n=== 测试 3: 搜索响应时间 ===\n')

  // 生成 1000 个文件
  console.log('生成 1000 个测试文件...')
  await generateTestFiles(1000, true)

  const testCases = [
    { query: '测试文件_0999', expected: 1, desc: '匹配 1 个' },
    { query: '工作', expected: '~100', desc: '匹配约 100 个' },
    { query: '测试', expected: '~1000', desc: '匹配约 1000 个' }
  ]

  console.log('搜索测试用例:')
  testCases.forEach((tc, i) => {
    console.log(`${i + 1}. 搜索 "${tc.query}" (${tc.desc})`)
  })

  console.log('\n测试步骤:')
  console.log('1. 在搜索框输入上述关键词')
  console.log('2. 观察控制台输出的 "[Performance] Search filter" 时间')
  console.log('3. 记录每个搜索的响应时间（不包含 300ms 防抖）')
  console.log('4. 重复 3 次取平均值')

  return {
    testCases,
    note: '需要手动在搜索框测试并观察控制台输出'
  }
}

/**
 * 测试 4: 内存占用测试
 */
async function testMemoryUsage() {
  console.log('\n=== 测试 4: 内存占用 ===\n')
  const fileCounts = [0, 100, 500, 1000]
  const results = []

  for (const count of fileCounts) {
    console.log(`\n测试 ${count} 个文件的内存占用...`)

    // 生成测试数据
    if (count > 0) {
      await generateTestFiles(count, true)
    } else {
      clearAllFiles()
    }
    console.log('1. 打开 DevTools > Memory')
    console.log('2. 点击 "Take heap snapshot"')
    console.log('3. 记录 "JS Heap Size"')
    console.log('4. 滚动 10 秒')
    console.log('5. 再次拍摄快照，记录"滚动后"内存')
    console.log('6. 执行搜索操作')
    console.log('7. 再次拍摄快照，记录"搜索后"内存')
    console.log('\n按任意键继续下一个测试...')

    results.push({
      fileCount: count,
      note: '需要手动使用 DevTools Memory 面板测试'
    })

    // 等待用户确认
    await new Promise(resolve => {
      const handler = () => {
        document.removeEventListener('keydown', handler)
        resolve()
      }
      document.addEventListener('keydown', handler)
    })
  }

  return results
}

/**
 * 运行所有性能测试
 */
async function runPerformanceTests() {
  console.log('╔════════════════════════════════╗')
  console.log('║  File Keeper v0.1.0 性能测试套件      ║')
  console.log('╚═══════════════════════╝')

  console.log('\n⚠️  注意事项:')
  console.log('- 测试期间请关闭其他应用以减少干扰')
  console.log('- 某些测试需要手动操作（刷新页面、使用 DevTools）')
  console.log('- 请准备好记录测试数据')
  console.log('- 测试大约需要 20-30 分钟')

  const confirm = window.confirm('准备好开始测试了吗？')
  if (!confirm) {
    console.log('测试已取消')
    return
  }

  try {
    // 测试 1: 启动时间
    const startupResults = await testStartupTime()

    // 测试 2: 滚动性能
    const scrollResults = await testScrollPerformance()

    // 测试 3: 搜索性能
    const searchResults = await testSearchPerformance()

    // 测试 4: 内存占用
    const memoryResults = await testMemoryUsage()

    console.log('\n╔══════════════════════════════════╗')
    console.log('║  测试完成！                      ║')
    console.log('╚═════════════════════════════════════╝')

    console.log('\n请将测试结果填写到:')
    console.log('docs/testing/v0.1.0-performance-test.md')

    return {
      startup: startupResults,
      scroll: scrollResults,
      search: searchResults,
      memory: memoryResults
    }
  } catch (error) {
    console.error('测试过程中出错:', error)
  }
}

// 导出到全局
window.runPerformanceTests = runPerformanceTests
window.testStartupTime = testStartupTime
window.testScrollPerformance = testScrollPerformance
window.testSearchPerformance = testSearchPerformance
window.testMemoryUsage = testMemoryUsage

console.log('✓ 性能测试脚本已加载')
console.log('运行: await runPerformanceTests()')
console.log('或单独运行某个测试:')
console.log('  - await testStartupTime()')
console.log('  - await testScrollPerformance()')
console.log('  - await testSearchPerformance()')
console.log('  - await testMemoryUsage()')
