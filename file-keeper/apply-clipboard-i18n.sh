#!/bin/bash
# 剪贴板组件国际化应用脚本

cd "$(dirname "$0")"

# ClipboardList.vue - 统计文本
sed -i "s/已选择 {{ selectedIds.size }} 条记录/{{ t('clipboard.stats.selectedCount', { count: selectedIds.size }) }}/g" src/components/ClipboardList.vue
sed -i "s/共 {{ items.length }} 条记录/{{ t('clipboard.stats.totalCount', { count: items.length }) }}/g" src/components/ClipboardList.vue

# ClipboardList.vue - 按钮文本
sed -i "s/>全选</>{{ t('clipboard.actions.selectAll') }}</" src/components/ClipboardList.vue
sed -i "s/>反选</>{{ t('clipboard.actions.invertSelection') }}</" src/components/ClipboardList.vue
sed -i "s/>批量复制</>{{ t('clipboard.actions.batchCopy') }}</" src/components/ClipboardList.vue
sed -i "s/>删除选中</>{{ t('clipboard.actions.deleteSelected') }}</" src/components/ClipboardList.vue
sed -i "s/>取消选择</>{{ t('clipboard.actions.clearSelection') }}</" src/components/ClipboardList.vue
sed -i "s/>暂无剪贴板历史</>{{ t('clipboard.emptyState') }}</" src/components/ClipboardList.vue

# ClipboardList.vue - 右键菜单
sed -i "s/{{ contextMenu.kind === 'file' ? '复制文件' : '复制' }}/{{ contextMenu.kind === 'file' ? t('clipboard.actions.copyFile') : t('clipboard.actions.copy') }}/g" src/components/ClipboardList.vue
sed -i "s/>打开链接</>{{ t('clipboard.actions.openLink') }}</" src/components/ClipboardList.vue
sed -i "s/>打开文件</>{{ t('clipboard.actions.openFile') }}</" src/components/ClipboardList.vue
sed -i "s/>打开文件所在目录</>{{ t('clipboard.actions.showInFolder') }}</" src/components/ClipboardList.vue
sed -i "s/>复制文件路径</>{{ t('clipboard.actions.copyFilePath') }}</" src/components/ClipboardList.vue
sed -i "s/>编辑备注</>{{ t('clipboard.actions.editNote') }}</" src/components/ClipboardList.vue
sed -i "s/>删除</>{{ t('clipboard.actions.delete') }}</" src/components/ClipboardList.vue

# ClipboardManagement.vue - 标题和设置
sed -i "s/<h2[^>]*>剪贴板<\/h2>/<h2 class=\"text-base font-semibold\">{{ t('clipboard.title') }}<\/h2>/" src/components/ClipboardManagement.vue
sed -i "s/>剪贴板设置</>{{ t('clipboard.settings') }}</" src/components/ClipboardManagement.vue

# 添加 i18n 导入到 ClipboardManagement (如果还没有)
if ! grep -q "useI18n" src/components/ClipboardManagement.vue; then
    sed -i "/import.*from 'vue'/a import { useI18n } from '../composables/useI18n'" src/components/ClipboardManagement.vue
    sed -i "/const.*ref(/a const { t } = useI18n()" src/components/ClipboardManagement.vue
fi

echo "✓ 剪贴板组件国际化应用完成"
echo "请运行 npm run build 验证"
