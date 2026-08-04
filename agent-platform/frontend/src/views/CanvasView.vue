<template>
  <div class="canvas-view">
    <!-- 无权限兜底（直访 /canvas 无 canvas:write） -->
    <n-card v-if="!canEdit" class="canvas-view__forbid" title="无访问权限">
      <p>无限画布需要「canvas:write」权限。请联系管理员授权后重试。</p>
    </n-card>

    <!-- 列表模式：我的画布 -->
    <div v-else-if="!editingId" class="canvas-view__list">
      <div class="canvas-view__header">
        <h2 class="canvas-view__title">我的画布</h2>
        <n-button type="primary" :loading="creating" @click="onCreate">
          <template #icon><n-icon :component="AddOutline" /></template>
          新建画布
        </n-button>
      </div>

      <n-spin :show="loadingList">
        <n-empty v-if="!canvases.length" description="还没有画布，点击「新建画布」开始创作" />
        <div v-else class="canvas-view__grid">
          <div
            v-for="c in canvases"
            :key="c.id"
            class="canvas-card"
            @click="openEditor(c.id)"
          >
            <div class="canvas-card__icon">
              <n-icon size="28" :component="AppsOutline" />
            </div>
            <div class="canvas-card__body">
              <div class="canvas-card__name" :title="c.name">{{ c.name }}</div>
              <div class="canvas-card__meta">
                {{ c.nodeCount ?? 0 }} 节点 · {{ formatTime(c.updatedAt) }}
              </div>
            </div>
            <n-button
              class="canvas-card__del"
              quaternary
              size="tiny"
              @click.stop="onDelete(c)"
            >
              <n-icon :component="TrashOutline" />
            </n-button>
          </div>
        </div>
      </n-spin>
    </div>

    <!-- 编辑模式 -->
    <div v-else class="canvas-view__editor">
      <div class="canvas-view__editor-header">
        <n-button quaternary @click="backToList">
          <n-icon :component="ArrowBackOutline" /> 返回
        </n-button>
        <n-input
          v-model:value="currentName"
          class="canvas-view__name-input"
          placeholder="画布名"
          @blur="onRename"
        />
        <n-button :loading="saving" type="primary" @click="onSave">
          <n-icon :component="SaveOutline" /> 保存
        </n-button>
      </div>

      <div class="canvas-view__main">
        <!-- 节点调色板（拖到画布即增节点；C3 起扩 5 类节点） -->
        <aside class="canvas-palette">
          <div class="canvas-palette__title">节点</div>
          <div
            v-for="p in palette"
            :key="p.type"
            class="canvas-palette__item"
            draggable="true"
            @dragstart="onPaletteDragStart($event, p)"
            @click="onPaletteClick(p)"
          >
            <n-icon :component="p.icon" />
            <span>{{ p.label }}</span>
          </div>
        </aside>

        <!-- 画布板 -->
        <CanvasBoard ref="boardRef" @node-selected="onNodeSelect" />

        <!-- 属性面板（选中节点编辑） -->
        <PropertyPanel :node="selectedNode" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton, NCard, NEmpty, NIcon, NInput, NSpin, useMessage
} from 'naive-ui'
import {
  AddOutline, AppsOutline, ArrowBackOutline, SaveOutline, TrashOutline,
  DocumentTextOutline, ImageOutline, VideocamOutline, MusicalNotesOutline, CodeSlashOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { canvasApi, type CanvasVO } from '@/api/canvas'
import type { CanvasNode, CanvasSnapshot } from '@/types/canvas'
import CanvasBoard from '@/components/canvas/CanvasBoard.vue'
import PropertyPanel from '@/components/canvas/PropertyPanel.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const message = useMessage()

/** 4 层权限显隐兜底：菜单隐藏 + 页内 hasPermission + API 403（plan IC-16）。 */
const canEdit = computed(() => authStore.hasPermission('canvas:write'))

const canvases = ref<CanvasVO[]>([])
const loadingList = ref(false)
const creating = ref(false)
const saving = ref(false)

/** 当前编辑画布 id（null=列表模式）。 */
const editingId = ref<number | null>(null)
const currentName = ref('')
const boardRef = ref<InstanceType<typeof CanvasBoard> | null>(null)
/** 当前选中节点（属性面板编辑目标；null=未选）。 */
const selectedNode = ref<CanvasNode | null>(null)

function onNodeSelect(node: CanvasNode | null) {
  selectedNode.value = node
}

/** 节点调色板（C3 起接入各自属性面板与产出触发；MVP 先通用节点占位）。 */
const palette = [
  { type: 'text', label: '文本', icon: DocumentTextOutline },
  { type: 'image', label: '图片', icon: ImageOutline },
  { type: 'video', label: '视频', icon: VideocamOutline },
  { type: 'audio', label: '音频', icon: MusicalNotesOutline },
  { type: 'script', label: '脚本', icon: CodeSlashOutline }
]

async function loadList() {
  loadingList.value = true
  try {
    const res = await canvasApi.list()
    canvases.value = res.data.data
  } catch {
    message.error('画布列表加载失败')
  } finally {
    loadingList.value = false
  }
}

async function onCreate() {
  creating.value = true
  try {
    const res = await canvasApi.create()
    canvases.value.unshift(res.data.data)
    openEditor(res.data.data.id)
  } catch {
    message.error('新建画布失败')
  } finally {
    creating.value = false
  }
}

function openEditor(id: number) {
  router.push(`/canvas/${id}`)
}

async function loadCanvas(id: number) {
  try {
    const res = await canvasApi.get(id)
    const c = res.data.data
    editingId.value = c.id
    currentName.value = c.name
    const snap = parseSnapshot(c.snapshot)
    boardRef.value?.loadSnapshot(snap)
  } catch {
    message.error('画布加载失败')
    backToList()
  }
}

function parseSnapshot(raw: string | null): CanvasSnapshot {
  if (!raw) return { nodes: [], edges: [] }
  try {
    const obj = JSON.parse(raw)
    return { nodes: obj.nodes ?? [], edges: obj.edges ?? [], viewport: obj.viewport }
  } catch {
    return { nodes: [], edges: [] }
  }
}

async function onSave() {
  if (!editingId.value || !boardRef.value) return
  saving.value = true
  try {
    const snap = boardRef.value.getSnapshot()
    await canvasApi.save(editingId.value, {
      name: currentName.value || '未命名画布',
      snapshot: JSON.stringify(snap)
    })
    message.success('已保存')
    await loadList()
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function onRename() {
  if (!editingId.value) return
  const name = currentName.value.trim()
  if (!name) return
  try {
    await canvasApi.rename(editingId.value, name)
  } catch {
    message.error('重命名失败')
  }
}

async function onDelete(c: CanvasVO) {
  if (!confirm(`确认删除画布「${c.name}」？产出物文件会保留，不级联清理。`)) return
  try {
    await canvasApi.remove(c.id)
    canvases.value = canvases.value.filter(x => x.id !== c.id)
    message.success('已删除')
  } catch {
    message.error('删除失败')
  }
}

function backToList() {
  editingId.value = null
  currentName.value = ''
  router.push('/canvas')
}

function onPaletteDragStart(event: DragEvent, p: { type: string; label: string }) {
  if (!event.dataTransfer) return
  event.dataTransfer.setData('application/vueflow', JSON.stringify({ type: p.type, label: p.label }))
  event.dataTransfer.effectAllowed = 'move'
}

function onPaletteClick(p: { type: string; label: string }) {
  boardRef.value?.addNode({ type: p.type, data: { label: p.label } })
}

function formatTime(t: string | null): string {
  if (!t) return ''
  return t.slice(0, 16).replace('T', ' ')
}

// 路由 param 驱动 列表/编辑 切换
watch(
  () => route.params.id,
  (id) => {
    if (id) loadCanvas(Number(id))
    else { editingId.value = null }
  }
)

onMounted(() => {
  loadList()
  if (route.params.id) loadCanvas(Number(route.params.id))
})
</script>

<style lang="scss" scoped>
.canvas-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: var(--spacing-4);
  gap: var(--spacing-3);
}

.canvas-view__forbid {
  max-width: 480px;
  margin: auto;
}

// 列表模式
.canvas-view__list {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
}

.canvas-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.canvas-view__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin: 0;
}

.canvas-view__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--spacing-3);
}

.canvas-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);
  position: relative;

  &:hover {
    border-color: var(--color-primary);
    transform: translateY(-2px);
    box-shadow: var(--shadow-md);
  }

  &__icon {
    color: var(--color-primary);
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-base);
    font-weight: var(--font-weight-medium);
    color: var(--color-text-primary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    margin-top: 2px;
  }

  &__del {
    opacity: 0;
    transition: opacity var(--duration-fast);
  }

  &:hover &__del {
    opacity: 1;
  }
}

// 编辑模式
.canvas-view__editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-2);
}

.canvas-view__editor-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.canvas-view__name-input {
  max-width: 320px;
}

.canvas-view__main {
  flex: 1;
  display: flex;
  gap: var(--spacing-2);
  min-height: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.canvas-palette {
  width: 120px;
  flex-shrink: 0;
  padding: var(--spacing-2);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-1);

  &__title {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    padding: var(--spacing-1) var(--spacing-2);
    text-transform: uppercase;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--spacing-2);
    padding: var(--spacing-2);
    border-radius: var(--radius-base);
    color: var(--color-text-secondary);
    cursor: grab;
    font-size: var(--font-size-sm);
    transition: all var(--duration-instant) var(--ease-in-out);

    &:hover {
      background: var(--color-primary-light);
      color: var(--color-primary);
    }
  }
}
</style>
