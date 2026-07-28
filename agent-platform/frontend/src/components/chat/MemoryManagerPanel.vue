<!-- ============================================================
  记忆管理面板 — 用户长期记忆 + 冲突解决（自服务，current userId 隔离）
  两区：我的记忆（查/删/清空）+ 记忆冲突（FLAGGED 分组，KEEP_NEW/OLD/BOTH/DISCARD）
  ============================================================ -->
<template>
  <div class="memory-manager">
    <n-tabs v-model:value="activeTab" type="line" size="small" class="memory-manager__tabs" :tabs-padding="0">
      <n-tab-pane name="legacy" tab="记忆（旧栈）">
    <!-- 记忆注入预览（调试用：输入测试问题，看三个检索设置的实际效果 + LLM 实际收到的记忆上下文）-->
    <n-card size="small" class="memory-manager__card">
      <template #header>
        <span>记忆注入预览</span>
        <n-tag size="small" type="info" round :bordered="false">调试</n-tag>
      </template>
      <n-space :size="8" align="center" style="margin-bottom: 8px">
        <n-input
          v-model:value="previewQuery"
          placeholder="输入测试问题"
          clearable
          size="small"
          style="max-width: 320px"
          @keyup.enter="runPreview"
        />
        <n-button size="small" type="primary" :loading="previewing" @click="runPreview">预览注入</n-button>
      </n-space>
      <div class="memory-manager__preview-scope">
        <span class="memory-manager__preview-scope-label">预览范围</span>
        <n-select
          v-model:value="previewScopeMode"
          :options="previewScopeOptions"
          size="small"
          style="width: 200px"
        />
        <n-select
          v-if="previewScopeMode === 'custom'"
          v-model:value="previewScopeProjects"
          multiple
          :options="projectOptions"
          placeholder="选择项目"
          size="small"
          style="width: 280px"
          :consistent-menu-width="false"
        />
        <!-- M4:指定项目时显式「包含总记忆」开关,默认关,避免总记忆被静默注入 -->
        <template v-if="previewScopeMode === 'custom'">
          <n-switch v-model:value="previewCustomIncludeGlobal" size="small" />
          <span class="memory-manager__preview-scope-label">包含总记忆</span>
        </template>
      </div>
      <div v-if="previewResult" class="memory-manager__preview">
        <n-space :size="6">
          <n-tag size="small" :bordered="false">模式：{{ retrievalModeLabel(previewResult.mode) }}</n-tag>
          <n-tag size="small" :bordered="false">标签：{{ previewResult.keyLanguage === 'ZH' ? '中文 key_zh' : '英文 key' }}</n-tag>
          <n-tag size="small" :bordered="false">阈值：{{ previewResult.threshold }}</n-tag>
          <n-tag size="small" :bordered="false">记忆总数：{{ previewResult.totalMemories }}</n-tag>
          <n-tag v-if="previewResult.twoStage" size="small" type="warning" :bordered="false">超阈值→两阶段筛 key</n-tag>
        </n-space>
        <div class="memory-manager__preview-context">
          <template v-if="previewResult.context">
            <div class="memory-manager__preview-label">↑ 实际注入 LLM 的「用户记忆:」上下文：</div>
            <pre>{{ previewResult.context }}</pre>
          </template>
          <n-empty v-else size="small" description="不注入（无命中 / LLM 判无关 / 记忆为空）" style="padding: 12px 0" />
        </div>
        <n-collapse v-if="hasRecallTrace" :default-expanded-names="['recall']" arrow-placement="left" class="memory-manager__recall">
          <n-collapse-item title="召回过程（粗筛候选 / 通道命中 / LLM 精排）" name="recall">
            <div class="memory-manager__recall-channels">
              <n-tag v-if="previewResult.channels?.vector != null" size="small" type="info" :bordered="false">向量命中 {{ previewResult.channels.vector }}</n-tag>
              <n-tag v-if="previewResult.channels?.bm25 != null" size="small" type="success" :bordered="false">BM25 命中 {{ previewResult.channels.bm25 }}</n-tag>
              <n-tag v-if="previewResult.channels?.keyword != null" size="small" type="warning" :bordered="false">关键词命中 {{ previewResult.channels.keyword }}</n-tag>
              <n-tag v-if="previewResult.channels?.llmFallback" size="small" type="error" round :bordered="false">LLM 兜底救场</n-tag>
            </div>
            <div v-if="previewResult.candidates?.length" class="memory-manager__recall-candidates">
              <div class="memory-manager__recall-subhead">粗筛候选 top-{{ previewResult.candidates.length }}（✓ = LLM 精排选中）</div>
              <div
                v-for="(c, idx) in previewResult.candidates"
                :key="idx"
                class="memory-manager__recall-cand"
                :class="{ 'is-selected': previewResult.selectedKeys && previewResult.selectedKeys.includes(c.memoryKey || '') }"
              >
                <n-tag size="tiny" :type="CHANNEL_TAG_TYPE[c.channel || ''] || 'default'" :bordered="false">
                  {{ CHANNEL_LABEL[c.channel || ''] || c.channel || '-' }}
                </n-tag>
                <span v-if="previewResult.selectedKeys && previewResult.selectedKeys.includes(c.memoryKey || '')" class="memory-manager__recall-sel">✓</span>
                <span class="memory-manager__recall-key">{{ c.memoryKeyZh || c.memoryKey || '-' }}</span>
                <span v-if="c.memoryKeyZh && c.memoryKey" class="memory-manager__recall-keyen">({{ c.memoryKey }})</span>
                <span class="memory-manager__recall-val">{{ c.valuePreview || '-' }}</span>
                <span v-if="c.blockLabel" class="memory-manager__recall-block">{{ c.blockLabel }}</span>
              </div>
            </div>
            <div v-else class="memory-manager__recall-empty">无粗筛候选（非 LLM_KEY/两阶段模式）</div>
          </n-collapse-item>
        </n-collapse>
      </div>
    </n-card>

    <!-- 记忆冲突区（有冲突才显）-->
    <n-card v-if="conflicts.length" size="small" class="memory-manager__card" title="待解决的记忆冲突">
      <div v-if="conflicts.length > 1" class="memory-manager__batch">
        <span class="memory-manager__batch-label">共 {{ conflicts.length }} 条，统一处理：</span>
        <n-button size="small" type="primary" :loading="batchResolving === 'KEEP_NEW'" @click="batchResolve('KEEP_NEW')">全部保留新</n-button>
        <n-button size="small" :loading="batchResolving === 'KEEP_OLD'" @click="batchResolve('KEEP_OLD')">全部保留旧</n-button>
        <n-button size="small" :loading="batchResolving === 'KEEP_BOTH'" @click="batchResolve('KEEP_BOTH')">全部合并保留</n-button>
      </div>
      <div v-for="c in conflicts" :key="c.conflictId" class="memory-manager__conflict">
        <div class="memory-manager__conflict-head">
          <n-tag size="small" :type="c.status === 'PENDING' ? 'warning' : 'error'" bordered>{{ c.block || '同组' }}</n-tag>
          <n-tag size="small" :type="c.status === 'PENDING' ? 'warning' : 'error'" :bordered="false" round>
            {{ c.status === 'PENDING' ? '待你确认' : '已标记冲突' }}
          </n-tag>
          <span class="memory-manager__conflict-time">{{ formatTime(c.createdAt) }}</span>
        </div>
        <div v-if="c.askText" class="memory-manager__conflict-ask">{{ c.askText }}</div>
        <div class="memory-manager__candidates">
          <div v-for="(cand, idx) in c.candidates" :key="idx" class="memory-manager__candidate">
            <n-tag v-if="cand.id === null" size="tiny" type="success" round :bordered="false">新</n-tag>
            <n-tag size="tiny" :bordered="false">{{ cand.category || '-' }}</n-tag>
            <span class="memory-manager__candidate-key">{{ cand.memoryKey }}</span>
            <span class="memory-manager__candidate-val">{{ cand.memoryValue }}</span>
          </div>
        </div>
        <n-space size="small">
          <n-button size="small" type="primary" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_NEW')">保留新</n-button>
          <n-button size="small" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_OLD')">保留旧</n-button>
          <n-button size="small" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_BOTH')">合并保留</n-button>
          <n-button size="small" :loading="resolving === c.conflictId" @click="openCustom(c)">自定义</n-button>
          <n-button size="small" quaternary type="error" :loading="resolving === c.conflictId" @click="resolve(c, 'DISCARD')">全删</n-button>
        </n-space>
        <!-- M2 时间线标:该 key 是否按时间线记(住址=是,孩子数量=否)。首次可设,后续 merge 按此标 -->
        <div class="memory-manager__conflict-temporal">
          <n-tooltip placement="top">
            <template #trigger><span class="memory-manager__temporal-label">时间线:</span></template>
            该类记忆是否按时间线记? 时序(住址/工作/日记)合并后各段带日期保留历史;非时序(名字/数量)合并去重。首次设定后该 key 复用此标。
          </n-tooltip>
          <n-button size="tiny" :type="temporalOf(c) === true ? 'success' : 'default'" @click="setTemporal(c, true)">是</n-button>
          <n-button size="tiny" :type="temporalOf(c) === false ? 'success' : 'default'" @click="setTemporal(c, false)">否</n-button>
          <span v-if="temporalOf(c) === null" class="memory-manager__temporal-unknown">未设(默认非时序)</span>
        </div>
        <!-- M2 自定义合并:input 默认填旧 value,手改后落库(KEEP_CUSTOM) -->
        <div v-if="customEditing === c.conflictId" class="memory-manager__custom-merge">
          <n-input v-model:value="customValueInput" type="textarea" :rows="2" size="small" placeholder="手改最终记忆值" />
          <n-space size="small">
            <n-button size="small" type="primary" :loading="resolving === c.conflictId" @click="resolve(c, 'KEEP_CUSTOM', customValueInput)">确认自定义</n-button>
            <n-button size="small" @click="cancelCustom">取消</n-button>
          </n-space>
        </div>
      </div>
    </n-card>

    <!-- 我记忆区 -->
    <n-card size="small" class="memory-manager__card">
      <template #header>
        <span>我的记忆</span>
        <n-tag size="small" round :bordered="false">{{ memories.length }} 条</n-tag>
      </template>
      <template #header-extra>
        <n-space :size="8" align="center">
          <n-button
            v-if="checkedKeys.length"
            size="small"
            type="error"
            :loading="batchDeleting"
            @click="confirmBatchDelete"
          >删除选中 ({{ checkedKeys.length }})</n-button>
          <n-button size="small" quaternary type="error" :disabled="!memories.length" @click="confirmClear">清空全部</n-button>
        </n-space>
      </template>
      <div v-if="memories.length" class="memory-manager__filter">
        <n-input v-model:value="filterKeyword" placeholder="筛选 key / 名称 / 值" clearable size="small" style="max-width: 240px" />
        <n-select
          v-model:value="filterBlock"
          multiple
          :options="blockOptions"
          placeholder="信息块"
          clearable
          size="small"
          style="width: 200px"
          :consistent-menu-width="false"
          max-tag-count="responsive"
        />
        <span class="memory-manager__filter-count">{{ filteredMemories.length }} / {{ memories.length }} 条</span>
      </div>
      <n-data-table
        v-if="memories.length && filteredMemories.length"
        :columns="columns"
        :data="filteredMemories"
        :row-key="row => row.id"
        :checked-row-keys="checkedKeys"
        :pagination="{ pageSize: 10 }"
        :scroll-x="1600"
        size="small"
        striped
        @update:checked-row-keys="onCheckedChange"
      />
      <n-empty v-else-if="memories.length" description="无匹配筛选条件的记忆" />
      <n-empty v-else description="暂无记忆。开启记忆模式对话后，AI 会自动抽取长期记忆。" />
    </n-card>

    <!-- scope 编辑（V33）：勾选总记忆 + 多选项目 -->
    <n-modal v-model:show="scopeEditing" preset="card" title="编辑记忆归属" :style="{ maxWidth: '460px', width: '90vw' }">
      <n-space vertical :size="16">
        <n-space align="center" :size="8">
          <n-switch v-model:value="scopeIsGlobal" />
          <span>总记忆（全局可见）</span>
        </n-space>
        <div>
          <div style="margin-bottom: 6px; font-size: 13px; opacity: 0.8">归属项目（可多选）</div>
          <n-select
            v-model:value="scopeProjectIds"
            multiple
            :options="projectOptions"
            placeholder="选择项目"
            :consistent-menu-width="false"
          />
        </div>
        <n-space justify="end">
          <n-button @click="scopeEditing = false">取消</n-button>
          <n-button type="primary" @click="saveScope">保存</n-button>
        </n-space>
      </n-space>
    </n-modal>

    <!-- M1 行内编辑：key / key_zh / value / block_label -->
    <n-modal v-model:show="editEditing" preset="card" title="编辑记忆" :style="{ maxWidth: '480px', width: '90vw' }">
      <n-space vertical :size="14">
        <div>
          <div class="memory-manager__field-label">英文 key（dedup / 召回锚点）</div>
          <n-input v-model:value="editForm.memoryKey" placeholder="如 child_name" />
        </div>
        <div>
          <div class="memory-manager__field-label">中文标签（名称列）</div>
          <n-input v-model:value="editForm.memoryKeyZh" placeholder="如 女儿" />
        </div>
        <div>
          <div class="memory-manager__field-label">记忆值</div>
          <n-input v-model:value="editForm.memoryValue" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" />
        </div>
        <div>
          <div class="memory-manager__field-label">信息块</div>
          <n-select
            v-model:value="editForm.blockLabel"
            :options="blockOptions"
            filterable
            tag
            placeholder="选择或输入信息块"
            :consistent-menu-width="false"
          />
        </div>
        <div class="memory-manager__field-hint">改 key / 块 / 名称 → 重算召回锚点；改值 → 重算向量。改 key 同归属已存在会报错。</div>
        <n-space justify="end">
          <n-button @click="editEditing = false">取消</n-button>
          <n-button type="primary" :loading="editSaving" @click="saveEdit">保存</n-button>
        </n-space>
      </n-space>
    </n-modal>
      </n-tab-pane>
      <n-tab-pane name="tags" tab="标签库" display-directive="show">
        <MemoryTagLibrary />
      </n-tab-pane>
    </n-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NButton, NCard, NCollapse, NCollapseItem, NDataTable, NEmpty, NInput, NSpace, NTag, NSwitch, NSelect, NModal, NTabs, NTabPane, NTooltip, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { chatApi, type UserMemory, type MemoryConflict, type MemoryContextPreview, type MemoryEditRequest } from '@/api/chat'
import { projectApi } from '@/api/project'
import { useChatStore } from '@/stores/chat'
import { formatRelativeTime, formatAbsoluteTime } from '@/utils/time'
import { isTimelineValue, parseMemoryValue } from '@/utils/memoryTimeline'
import MemoryTagLibrary from '@/components/memory/MemoryTagLibrary.vue'

const chatStore = useChatStore()

// F-1 三页签壳：旧栈卡入「记忆（旧栈）」pane，新栈逐 chunk 加 pane（标签库已接）
const activeTab = ref<'legacy' | 'tags'>('legacy')

const message = useMessage()
const dialog = useDialog()

const memories = ref<UserMemory[]>([])
const conflicts = ref<MemoryConflict[]>([])
const loading = ref(false)
const resolving = ref<number | null>(null)
const batchResolving = ref<string | null>(null)
const checkedKeys = ref<number[]>([])
const batchDeleting = ref(false)

// M1 字段筛选（前端筛，数据量小）：关键词命中 key/key_zh/value + 块下拉
const filterKeyword = ref('')
const filterBlock = ref<string[]>([])
const distinctBlocks = computed(() => {
  const s = new Set<string>()
  for (const m of memories.value) if (m.blockLabel) s.add(m.blockLabel)
  return [...s].sort()
})
const blockOptions = computed(() => distinctBlocks.value.map(b => ({ label: b, value: b })))
const filteredMemories = computed<UserMemory[]>(() => {
  const kw = filterKeyword.value.trim().toLowerCase()
  const blks = filterBlock.value
  return memories.value.filter(m => {
    if (blks.length && !blks.includes(m.blockLabel as string)) return false
    if (kw) {
      const hay = `${m.memoryKey || ''} ${m.memoryKeyZh || ''} ${m.memoryValue || ''}`.toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

// M1 行内编辑态
const editEditing = ref(false)
const editSaving = ref(false)
const editTarget = ref<UserMemory | null>(null)
const editForm = ref<MemoryEditRequest>({ memoryKey: '', memoryKeyZh: '', memoryValue: '', blockLabel: '' })
function openEdit(m: UserMemory) {
  editTarget.value = m
  editForm.value = {
    memoryKey: m.memoryKey || '',
    memoryKeyZh: m.memoryKeyZh || '',
    memoryValue: m.memoryValue || '',
    blockLabel: m.blockLabel || ''
  }
  editEditing.value = true
}
async function saveEdit() {
  if (!editTarget.value) return
  const key = (editForm.value.memoryKey || '').trim()
  const value = (editForm.value.memoryValue || '').trim()
  if (!key) { message.warning('记忆 key 不能为空'); return }
  if (!value) { message.warning('记忆值不能为空'); return }
  editSaving.value = true
  try {
    await chatApi.updateMemory(editTarget.value.id, {
      memoryKey: key,
      memoryKeyZh: (editForm.value.memoryKeyZh || '').trim() || undefined,
      memoryValue: value,
      blockLabel: (editForm.value.blockLabel || '').trim() || undefined
    })
    message.success('记忆已更新')
    editEditing.value = false
    await loadMemories()
  } catch { message.error('更新失败（key 可能同归属已存在）') }
  finally { editSaving.value = false }
}

// 记忆注入预览
const previewQuery = ref('')
const previewing = ref(false)
const previewResult = ref<MemoryContextPreview | null>(null)

// 预览范围 scope（V38）：默认当前会话 scope，可切 global-only / 全部可读项目 / 指定项目
const previewScopeMode = ref<'session' | 'global' | 'all' | 'custom'>('session')
const previewScopeProjects = ref<number[]>([])   // 'custom' 模式手选项目集
// M4:custom 模式显式总记忆开关,默认 OFF(指定项目时不再静默注入总记忆)
const previewCustomIncludeGlobal = ref<boolean>(false)
const previewScopeOptions = [
  { label: '默认（当前会话 scope）', value: 'session' },
  { label: '仅总记忆（global-only）', value: 'global' },
  { label: '全部可读项目', value: 'all' },
  { label: '指定项目', value: 'custom' }
]
function effectivePreviewScope(): { includeGlobal?: boolean; projectIds?: number[] } {
  switch (previewScopeMode.value) {
    case 'global': return { includeGlobal: true, projectIds: [] }
    case 'all': return { includeGlobal: true, projectIds: [...chatStore.memReadProjectIds] }
    case 'custom': return { includeGlobal: previewCustomIncludeGlobal.value, projectIds: [...previewScopeProjects.value] }
    case 'session':
    default: return { includeGlobal: chatStore.memIncludeGlobal, projectIds: [...chatStore.memReadProjectIds] }
  }
}

// 召回过程是否值得展开（有候选 / 有通道命中）
const hasRecallTrace = computed(() => {
  const r = previewResult.value
  if (!r) return false
  const c = r.channels
  const channelHits = c && (c.vector || c.keyword || c.bm25 || c.llmFallback)
  return !!(r.candidates?.length || r.selectedKeys?.length || channelHits)
})

// 通道标签颜色映射（向量=蓝 / BM25=绿 / 关键词=橙 / both=紫 / 其他=默认）
const CHANNEL_TAG_TYPE: Record<string, 'info' | 'success' | 'warning' | 'error'> = {
  vector: 'info', bm25: 'success', keyword: 'warning', both: 'error'
}
const CHANNEL_LABEL: Record<string, string> = {
  vector: '向量', bm25: 'BM25', keyword: '关键词', both: '向量+BM25'
}

// 项目记忆 scope（V33）
const projectOptions = ref<Array<{ label: string; value: number }>>([])
const projectIdMap = ref<Map<number, string>>(new Map())
async function loadProjects() {
  try {
    const res = await projectApi.list()
    const list = res.data.data || []
    projectOptions.value = list.map(p => ({ label: p.name, value: p.id }))
    projectIdMap.value = new Map(list.map(p => [p.id, p.name]))
  } catch { projectOptions.value = [] }
}

// scope 编辑
const scopeEditing = ref(false)
const scopeTarget = ref<UserMemory | null>(null)
const scopeIsGlobal = ref(true)
const scopeProjectIds = ref<number[]>([])
async function openScopeEdit(m: UserMemory) {
  scopeTarget.value = m
  scopeIsGlobal.value = m.isGlobal !== false
  scopeProjectIds.value = Array.isArray(m.projectIds) ? [...m.projectIds] : []
  scopeEditing.value = true
  if (projectOptions.value.length === 0) await loadProjects()
}
async function saveScope() {
  if (!scopeTarget.value) return
  try {
    await chatApi.updateMemoryScopes(scopeTarget.value.id, {
      isGlobal: scopeIsGlobal.value,
      projectIds: scopeProjectIds.value
    })
    message.success('记忆归属已更新')
    scopeEditing.value = false
    await loadMemories()
  } catch { message.error('更新归属失败') }
}

const RETRIEVAL_MODE_LABELS: Record<string, string> = {
  LLM_FULL_CONTEXT: '全量',
  EMBEDDING_VECTOR: '向量 top-K',
  VECTOR_KEYWORD: '混合(向量+关键词)'
}
function retrievalModeLabel(mode: string): string {
  return RETRIEVAL_MODE_LABELS[mode] || mode
}

async function runPreview() {
  if (!previewQuery.value.trim()) {
    message.warning('请输入测试问题')
    return
  }
  previewing.value = true
  try {
    const res = await chatApi.previewMemoryContext(previewQuery.value.trim(), effectivePreviewScope())
    previewResult.value = res.data.data
  } catch {
    message.error('预览失败')
  } finally {
    previewing.value = false
  }
}

const categoryType: Record<string, 'success' | 'info' | 'warning'> = {
  PREFERENCE: 'success', FACT: 'info', FEEDBACK: 'warning'
}

const columns: DataTableColumns<UserMemory> = [
  { type: 'selection', width: 45 },
  { title: '分类', key: 'category', width: 100, render: r => h(NTag, { size: 'small', type: categoryType[r.category || ''] || 'default', bordered: false }, () => r.category || '-') },
  { title: '键', key: 'memoryKey', width: 160, ellipsis: { tooltip: true }, render: r => r.memoryKey || '-' },
  { title: '名称', key: 'memoryKeyZh', width: 120, ellipsis: { tooltip: true }, render: r => r.memoryKeyZh || '-' },
  { title: '值', key: 'memoryValue', width: 240, ellipsis: { tooltip: true },
    render: r => {
      if (!r.memoryValue) return '-'
      if (!isTimelineValue(r.memoryValue)) return r.memoryValue
      // M2 时间线 value:多段日期行渲染(dated 段升序在前,undated 附后)
      const segs = parseMemoryValue(r.memoryValue)
      return h('div', { style: 'display:flex;flex-direction:column;gap:2px' },
        segs.map(s => h('div', { style: 'font-size:12px;line-height:1.4' }, [
          s.date ? h('span', { style: 'color:var(--text-color-3);margin-right:4px' }, s.date) : null,
          s.content
        ])))
    } },
  { title: '信息块', key: 'blockLabel', width: 110, ellipsis: { tooltip: true }, render: r => r.blockLabel || '-' },
  { title: '置信度', key: 'confidence', width: 90, render: r => r.confidence != null ? Number(r.confidence).toFixed(2) : '-' },
  { title: '来源', key: 'source', width: 90, render: r => r.source || '-' },
  {
    title: '冲突', key: 'conflictStatus', width: 110,
    render: r => r.conflictStatus === 'FLAGGED'
      ? h(NTag, { size: 'small', type: 'warning', bordered: false }, () => `⚠ ${r.conflictWith || '冲突'}`)
      : '-'
  },
  { title: '创建', key: 'createdAt', width: 110, render: r => timeCell(r.createdAt) },
  { title: '更新', key: 'updatedAt', width: 110, render: r => timeCell(r.updatedAt) },
  {
    title: '归属', key: 'scope', width: 180,
    render: r => {
      const tags: any[] = []
      const homeName = r.homeProjectId == null
        ? '总记忆(归属)'
        : `${projectIdMap.value.get(r.homeProjectId) || '项目' + r.homeProjectId}(归属)`
      tags.push(h(NTag, { size: 'small', type: 'info', bordered: false }, () => homeName))
      if (r.isGlobal !== false) tags.push(h(NTag, { size: 'small', type: 'success', bordered: false }, () => '全局可见'))
      if (Array.isArray(r.projectIds)) {
        for (const pid of r.projectIds) {
          if (pid === r.homeProjectId) continue      // home 已显，跳过
          tags.push(h(NTag, { size: 'small', bordered: false }, () => `${projectIdMap.value.get(pid) || '项目' + pid}(共享)`))
        }
      }
      return h(NTooltip, { placement: 'top' }, {
        trigger: () => h(NSpace, { size: 4 }, () => tags),
        default: () => '归属 = 记忆存于哪个 scope（决定 key 唯一性）；共享 = 可被哪些项目读取'
      })
    }
  },
  {
    title: '操作', key: 'actions', width: 170, fixed: 'right',
    render: r => h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', quaternary: true, type: 'primary', onClick: () => openEdit(r) }, () => '编辑'),
      h(NButton, { size: 'small', quaternary: true, onClick: () => openScopeEdit(r) }, () => '归属'),
      h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => confirmDelete(r) }, () => '删除')
    ])
  }
]

function formatTime(iso: string): string {
  try { return new Date(iso).toLocaleString('zh-CN') } catch { return iso }
}

// M1 相对时间单元格：文本相对（刚刚/N分钟前/昨天…），hover tooltip 显绝对时间
function timeCell(iso: string | null | undefined): any {
  if (!iso) return '-'
  return h(NTooltip, { placement: 'top' }, {
    trigger: () => h('span', { style: 'cursor: default' }, formatRelativeTime(iso)),
    default: () => formatAbsoluteTime(iso)
  })
}

async function loadMemories() {
  loading.value = true
  try {
    const res = await chatApi.listMemories()
    memories.value = res.data.data
  } catch { message.error('加载记忆失败') }
  finally { loading.value = false }
}

async function loadConflicts() {
  try {
    const res = await chatApi.listMemoryConflicts()
    conflicts.value = res.data.data
    // M2:拉每条冲突所属 key 的时序标(null=首次待询问)。已标后续 merge 按此走。
    temporalMap.value = new Map()
    for (const c of conflicts.value) {
      const key = conflictKey(c)
      if (!key) continue
      try {
        const r = await chatApi.getMemoryKeyMeta(key)
        temporalMap.value.set(c.conflictId, r.data.data?.isTemporal ?? null)
      } catch { temporalMap.value.set(c.conflictId, null) }
    }
  } catch { message.error('加载冲突失败') }
}

function confirmDelete(m: UserMemory) {
  dialog.warning({
    title: '删除记忆', content: `删除「${m.memoryKey}」？`, positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try { await chatApi.deleteMemory(m.id); message.success('已删除'); await loadMemories() }
      catch { message.error('删除失败') }
    }
  })
}

function confirmClear() {
  dialog.error({
    title: '清空全部记忆',
    content: '将删除当前用户全部长期记忆，不可恢复。确认？',
    positiveText: '清空', negativeText: '取消',
    onPositiveClick: async () => {
      try { const res = await chatApi.clearMemories(); message.success(`已清空 ${res.data.data} 条`); await loadMemories() }
      catch { message.error('清空失败') }
    }
  })
}

function onCheckedChange(keys: Array<string | number>) {
  checkedKeys.value = keys.map(k => Number(k))
}

function confirmBatchDelete() {
  const ids = [...checkedKeys.value]
  dialog.warning({
    title: '批量删除记忆',
    content: `删除选中的 ${ids.length} 条记忆？不可恢复。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      batchDeleting.value = true
      try {
        const res = await chatApi.batchDeleteMemories(ids)
        message.success(`已删除 ${res.data.data} 条`)
        checkedKeys.value = []
        await loadMemories()
      } catch { message.error('批量删除失败') }
      finally { batchDeleting.value = false }
    }
  })
}

async function resolve(c: MemoryConflict, decision: string, customValue?: string) {
  resolving.value = c.conflictId
  try {
    const res = await chatApi.resolveMemoryConflict(c.conflictId, decision, customValue)
    if (res.data.data) {
      message.success('已解决冲突')
      customEditing.value = null
      customValueInput.value = ''
      await Promise.all([loadConflicts(), loadMemories()])
    } else {
      message.error(res.data.message || '解决失败')
    }
  } catch { message.error('解决失败') }
  finally { resolving.value = null }
}

// ---- M2:自定义合并 + per-key 时序标 ----
const customEditing = ref<number | null>(null)
const customValueInput = ref('')
// conflictId → 该 key 的时序标(null=未设/首次待询问,true=时序,false=非时序)
const temporalMap = ref(new Map<number, boolean | null>())

function temporalOf(c: MemoryConflict): boolean | null {
  return temporalMap.value.get(c.conflictId) ?? null
}

function conflictKey(c: MemoryConflict): string | undefined {
  return c.candidates?.[0]?.memoryKey ?? undefined
}

/** 打开自定义合并:input 默认填旧 value(candidates 里非"新"的那条,无则首条)。 */
function openCustom(c: MemoryConflict) {
  const oldCand = c.candidates?.find(x => x.id !== null && x.memoryValue)
  customValueInput.value = oldCand?.memoryValue ?? c.candidates?.[0]?.memoryValue ?? ''
  customEditing.value = c.conflictId
}

function cancelCustom() {
  customEditing.value = null
  customValueInput.value = ''
}

async function setTemporal(c: MemoryConflict, isTemporal: boolean) {
  const key = conflictKey(c)
  if (!key) { message.warning('无 memory key,无法设时间线标'); return }
  try {
    await chatApi.updateMemoryKeyMeta(key, isTemporal)
    temporalMap.value.set(c.conflictId, isTemporal)
    message.success(`已设该类记忆为${isTemporal ? '时间线(带日期段)' : '非时间线'}`)
  } catch { message.error('设置时间线标失败') }
}

async function batchResolve(decision: string) {
  batchResolving.value = decision
  try {
    const res = await chatApi.batchResolveMemoryConflicts(decision)
    message.success(`已批量解决 ${res.data.data} 条`)
    await Promise.all([loadConflicts(), loadMemories()])
  } catch { message.error('批量解决失败') }
  finally { batchResolving.value = null }
}

onMounted(() => { void loadMemories(); void loadConflicts(); void loadProjects() })
</script>

<style lang="scss" scoped>
.memory-manager {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}
.memory-manager__card {
  :deep(.n-card-header) { padding: 12px 16px; }
  :deep(.n-card-header__main) { display: flex; align-items: center; gap: 8px; }
  // 记忆表横向滚动条加粗常驻可见（naive 默认 thumb 仅 hover 显，全局又压 5px 透明 → 看不见）
  :deep(.n-data-table .n-scrollbar-rail--horizontal) {
    height: 10px !important;
    background: var(--color-bg-secondary, rgba(255,255,255,0.06)) !important;
    .n-scrollbar-rail__scrollbar {
      height: 10px !important;
      background: var(--color-text-tertiary, #888) !important;
      opacity: 0.85 !important;
    }
  }
}
.memory-manager__preview {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}
.memory-manager__preview-context {
  margin-top: 4px;
}
.memory-manager__preview-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  margin-bottom: 4px;
}
.memory-manager__preview-context pre {
  margin: 0;
  padding: var(--spacing-2);
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
  border: 1px solid var(--color-border, rgba(255, 255, 255, 0.08));
  border-radius: 6px;
}
.memory-manager__preview-scope {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.memory-manager__preview-scope-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
}
.memory-manager__recall {
  margin-top: 4px;
}
.memory-manager__recall-channels {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.memory-manager__recall-subhead {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  margin-bottom: 4px;
}
.memory-manager__recall-candidates {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.memory-manager__recall-cand {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 4px;
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.03));
}
.memory-manager__recall-cand.is-selected {
  background: rgba(24, 160, 88, 0.12);
}
.memory-manager__recall-sel {
  color: #18a058;
  font-weight: 600;
}
.memory-manager__recall-key {
  color: var(--color-text-primary, #eee);
  font-weight: 500;
}
.memory-manager__recall-keyen {
  color: var(--color-text-tertiary, #888);
  font-size: 11px;
}
.memory-manager__recall-val {
  color: var(--color-text-secondary, #aaa);
  word-break: break-all;
  flex: 1;
  min-width: 0;
}
.memory-manager__recall-block {
  font-size: 11px;
  color: var(--color-text-tertiary, #888);
  padding: 1px 5px;
  border: 1px solid var(--color-border, rgba(255, 255, 255, 0.12));
  border-radius: 3px;
  white-space: nowrap;
}
.memory-manager__recall-empty {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
}
.memory-manager__conflict {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
  padding: var(--spacing-2) 0;
  border-bottom: 1px solid var(--color-border, rgba(255, 255, 255, 0.08));
  &:last-child { border-bottom: none; }
}
.memory-manager__conflict-head {
  display: flex; align-items: center; gap: var(--spacing-2);
}
.memory-manager__conflict-time { font-size: 12px; color: var(--color-text-tertiary, #888); }
.memory-manager__conflict-ask {
  font-size: 13px; color: var(--color-text-secondary, #ccc);
  padding: var(--spacing-1) var(--spacing-2);
  background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
  border-radius: 6px;
}
.memory-manager__candidates {
  display: flex; flex-direction: column; gap: 4px;
}
.memory-manager__candidate {
  display: flex; align-items: center; gap: 6px; font-size: 13px;
}
.memory-manager__candidate-key { color: var(--color-text-secondary, #aaa); min-width: 100px; }
.memory-manager__candidate-val { color: var(--color-text-primary, #eee); word-break: break-all; }
.memory-manager__conflict-temporal { display: flex; align-items: center; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.memory-manager__temporal-label { font-size: 12px; color: var(--color-text-tertiary, #888); cursor: help; }
.memory-manager__temporal-unknown { font-size: 11px; color: var(--color-text-tertiary, #888); }
.memory-manager__custom-merge { margin-top: 6px; display: flex; flex-direction: column; gap: 6px; }
.memory-manager__filter {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.memory-manager__filter-count {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
}
.memory-manager__field-label {
  margin-bottom: 6px;
  font-size: 13px;
  opacity: 0.85;
}
.memory-manager__field-hint {
  font-size: 12px;
  color: var(--color-text-tertiary, #888);
  line-height: 1.5;
}
</style>
