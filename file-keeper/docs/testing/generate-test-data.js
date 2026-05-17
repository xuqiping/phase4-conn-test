/**
 * 性能测试数据生成脚本
 *
 * 使用方法：
 * 1. 在浏览器中打开 File Keeper 应用
 * 2. 打开浏览器控制台 (F12)
 * 3. 复制此脚本内容并粘贴到控制台
 * 4. 运行 generateTestFiles(1000) 生成 1000 个测试文件
 */

/**
 * 生成指定数量的测试文件
 * @param {number} count - 要生成的文件数量
 * @param {boolean} clearExisting - 是否清空现有文件
 */
async function generateTestFiles(count, clearExisting = false) {
  console.log(`开始生成 ${count} 个测试文件...`)
  const startTime = performance.now()

  // 获取 store (需要在 Vue 应用上下文中)
  const fileStore = window.__VUE_DEVTOOLS_GLOBAL_HOOK__?.apps[0]?.appContext?.config?.globalProperties?.$pinia?._s?.get('file')

  if (!fileStore) {
    console.error('无法访问 fileStore，请确保应用已加载')
    return
  }

  // 清空现有文件
  if (clearExisting) {
    const existingIds = fileStore.files.map(f => f.id)
    existingIds.forEach(id => fileStore.removeFile(id))
    console.log(`已清空 ${existingIds.length} 个现有文件`)
  }

  // 文件类型和扩展名
  const types = ['file', 'folder', 'image', 'code']
  const extensions = ['.txt', '.pdf', '.jpg', '.png', '.js', '.ts', '.md', '.docx', '.xlsx', '.pptx']
  const categories = ['工作', '学习', '项目', '文档', '图片', '代码', '资料', '备份']
  const tags = ['重要', '待处理', '已完成', '归档', '临时', '参考', '草稿', '最终版']

  // 生成文件
  for (let i = 0; i < count; i++) {
    const ext = extensions[i % extensions.length]
    const type = types[i % types.length]
    const category = categories[i % categories.length]

    // 随机选择 1-3 个标签
    const fileTags = []
    const tagCount = Math.floor(Math.random() * 3) + 1
    for (let j = 0; j < tagCount; j++) {
      const tag = tags[Math.floor(Math.random() * tags.length)]
      if (!fileTags.includes(tag)) {
        fileTags.push(tag)
    }
    }

    await fileStore.addFile({
      name: `${category}_测试文件_${String(i).padStart(4, '0')}${ext}`,
      path: `C:\\TestFiles\\${category}\\file_${String(i).padStart(4, '0')}${ext}`,
      type: type,
      groupId: 'default',
      tags: fileTags
    })

    // 每 100 个文件输出一次进度
    if ((i + 1) % 100 === 0) {
      console.log(`已生成 ${i + 1}/${count} 个文件...`)
    }
  }

  const endTime = performance.now()
  const totalTime = endTime - startTime

  console.log(`✓ 成功生成 ${count} 个测试文件`)
  console.log(`总耗时: ${totalTime.toFixed(2)}ms (平均 ${(totalTime / count).toFixed(2)}ms/文件)`)
  console.log(`当前文件总数: ${fileStore.files.length}`)
}

/**
 * 清空所有文件
 */
function clearAllFiles() {
  const fileStore = window.__VUE_DEVTOOLS_GLOBAL_HOOK__?.apps[0]?.appContext?.config?.globalProperties?.$pinia?._s?.get('file')

  if (!fileStore) {
    console.error('无法访问 fileStore')
    return
  }

  const count = fileStore.files.length
  const ids = fileStore.files.map(f => f.id)
  ids.forEach(id => fileStore.removeFile(id))

  console.log(`已清空 ${count} 个文件`)
}

/**
 * 模拟随机打开文件（增加 openCount 和 lastOpened）
 * @param {number} count - 要打开的文件数量
 */
function simulateFileOpens(count) {
  const fileStore = window.__VUE_DEVTOOLS_GLOBAL_HOOK__?.apps[0]?.appContext?.config?.globalProperties?.$pinia?._s?.get('file')

  if (!fileStore) {
    console.error('无法访问 fileStore')
    return
  }

  const files = fileStore.files
  if (files.length === 0) {
    console.error('没有文件可以打开')
    return
  }

  for (let i = 0; i < count; i++) {
    const randomFile = files[Math.floor(Math.random() * files.length)]
    fileStore.recordOpen(randomFile.id)
  }

  console.log(`已模拟打开 ${count} 次文件`)
}

// 导出到全局作用域
window.generateTestFiles = generateTestFiles
window.clearAllFiles = clearAllFiles
window.simulateFileOpens = simulateFileOpens

console.log('测试数据生成脚本已加载')
console.log('可用命令:')
console.log('  generateTestFiles(1000)     - 生成 1000 个测试文件')
console.log('  generateTestFiles(500, true) - 清空现有文件并生成 500 个')
console.log('  clearAllFiles()              - 清空所有文件')
console.log('  simulateFileOpens(100)       - 模拟打开 100 次文件')
