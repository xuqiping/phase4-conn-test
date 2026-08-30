<!-- ============================================================
  帮助文章管理（admin，19x#3）— /admin/help-articles，help:manage
  · 列表（slug/标题/分类/排序/发布开关/操作）+ 新建/编辑 modal（正文 md 预览）
  · 删除硬删（释放 slug）→ useDialog 二次确认
  · slug 创建后不可改（后端忽略更新入参的 slug）
  ============================================================ -->
<template>
  <div class="admin-help">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="帮助文章">
      <template #actions>
        <n-button type="primary" size="small" @click="openCreate">新建文章</n-button>
      </template>
    </PageHeader>
    <n-card>
      <n-data-table
        remote
        :columns="columns"
        :data="list"
        :loading="loading"
        :pagination="pagination"
        size="small"
        @update:page="load"
      />
    </n-card>

    <!-- 新建/编辑 modal -->
    <n-modal v-model:show="showEditor" preset="card" :title="editingId == null ? '新建文章' : '编辑文章'" style="width: 720px">
      <n-form label-placement="left" label-width="80">
        <n-form-item label="slug">
          <n-input
            v-model:value="form.slug"
            placeholder="小写字母/数字/连字符，如 how-to-recharge"
            :disabled="editingId != null"
            maxlength="80"
          />
        </n-form-item>
        <n-form-item label="标题">
          <n-input v-model:value="form.title" maxlength="120" show-count />
        </n-form-item>
        <n-form-item label="分类">
          <n-input v-model:value="form.category" placeholder="默认「通用」" maxlength="40" />
        </n-form-item>
        <n-form-item label="排序">
          <n-input-number v-model:value="form.sortOrder" :min="0" :max="9999" style="width: 140px" />
        </n-form-item>
        <n-form-item label="所需权限">
          <n-select
            v-model:value="form.requiredPermission"
            :options="permissionOptions"
            filterable
            tag
            clearable
            placeholder="留空 = 全体登录用户可见"
          />
        </n-form-item>
        <n-form-item label="正文">
          <n-input
            v-model:value="form.contentMd"
            type="textarea"
            placeholder="markdown 原文"
            :autosize="{ minRows: 10, maxRows: 20 }"
          />
        </n-form-item>
      </n-form>
      <div class="admin-help__preview-bar">
        <n-button size="tiny" quaternary @click="showPreview = !showPreview">
          {{ showPreview ? '收起预览' : '预览' }}
        </n-button>
      </div>
      <div v-if="showPreview" class="markdown-body admin-help__preview" v-html="renderMarkdown(form.contentMd)" />

      <n-space justify="end" style="margin-top: 12px">
        <n-button size="small" @click="showEditor = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" :disabled="!canSave" @click="save">
          保存
        </n-button>
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NDataTable, NForm, NFormItem, NInput, NInputNumber, NModal, NSelect, NSpace, NSwitch,
  useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns, PaginationProps } from 'naive-ui'
import { feedbackApi } from '@/api/feedback'
import type { AdminArticleVO } from '@/api/feedback'
import { renderMarkdown } from '@/utils/markdown'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const dialog = useDialog()

const list = ref<AdminArticleVO[]>([])
const loading = ref(false)
const pagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })

const showEditor = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const showPreview = ref(false)
const form = reactive({ slug: '', title: '', category: '', sortOrder: 0, requiredPermission: null as string | null, contentMd: '' })

// 所需权限预设选项（可自由输入其他码；留空=全员可见，V149）
const permissionOptions = [
  { label: 'media:gen（视频/图片生成）', value: 'media:gen' },
  { label: 'media:edit（视频剪辑）', value: 'media:edit' },
  { label: 'canvas:write（无限画布）', value: 'canvas:write' },
  { label: 'asset:write（资产库）', value: 'asset:write' },
  { label: 'project-group:manage（项目组）', value: 'project-group:manage' },
  { label: 'knowledge:manage（知识库管理）', value: 'knowledge:manage' },
  { label: 'user:manage（用户管理）', value: 'user:manage' },
  { label: 'role:manage（角色权限）', value: 'role:manage' },
  { label: 'usage:view（账单总览）', value: 'usage:view' },
  { label: 'pricing:manage（价表配置）', value: 'pricing:manage' },
  { label: 'points:recharge（积分充值）', value: 'points:recharge' },
  { label: 'payment:config（支付渠道）', value: 'payment:config' },
  { label: 'system:audit:read（审计日志）', value: 'system:audit:read' },
  { label: 'security:event:read（安全事件）', value: 'security:event:read' },
  { label: 'feedback:manage（反馈处理）', value: 'feedback:manage' },
  { label: 'help:manage（帮助文章）', value: 'help:manage' },
  { label: 'llm:config（模型配置）', value: 'llm:config' },
  { label: 'ROLE_admin（仅系统管理员）', value: 'ROLE_admin' }
]

const canSave = computed(() =>
  !!form.title.trim() && !!form.contentMd.trim() &&
  (editingId.value != null || /^[a-z0-9-]+$/.test(form.slug))
)

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

const columns: DataTableColumns<AdminArticleVO> = [
  { title: 'slug', key: 'slug', width: 160, ellipsis: { tooltip: true } },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '分类', key: 'category', width: 100 },
  { title: '所需权限', key: 'requiredPermission', width: 150, ellipsis: { tooltip: true },
    render: r => r.requiredPermission ?? '全员' },
  { title: '排序', key: 'sortOrder', width: 70 },
  {
    title: '发布', key: 'published', width: 80,
    render: r => h(NSwitch, {
      size: 'small',
      value: r.published,
      onUpdateValue: (v: boolean) => togglePublish(r, v)
    })
  },
  { title: '更新时间', key: 'updatedAt', width: 150, render: r => fmt(r.updatedAt ?? r.createdAt) },
  {
    title: '操作', key: 'op', width: 130,
    render: r => h('div', { style: 'display:flex;gap:4px' }, [
      h(NButton, { size: 'tiny', quaternary: true, onClick: () => openEdit(r) }, { default: () => '编辑' }),
      h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => confirmDelete(r) },
        { default: () => '删除' })
    ])
  }
]

async function load(page = 1) {
  loading.value = true
  try {
    const res = await feedbackApi.adminArticles({ page, size: pagination.pageSize ?? 10 })
    list.value = res.data.data.records
    pagination.itemCount = res.data.data.total
    pagination.page = page
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  form.slug = ''
  form.title = ''
  form.category = ''
  form.sortOrder = 0
  form.requiredPermission = null
  form.contentMd = ''
  showPreview.value = false
  showEditor.value = true
}

function openEdit(row: AdminArticleVO) {
  editingId.value = row.id
  form.slug = row.slug
  form.title = row.title
  form.category = row.category
  form.sortOrder = row.sortOrder
  form.requiredPermission = row.requiredPermission ?? null
  form.contentMd = row.contentMd
  showPreview.value = false
  showEditor.value = true
}

async function save() {
  if (!canSave.value || saving.value) return
  saving.value = true
  try {
    const payload = {
      slug: form.slug.trim(),
      title: form.title.trim(),
      category: form.category.trim() || undefined,
      sortOrder: form.sortOrder,
      requiredPermission: form.requiredPermission?.trim() || undefined,
      contentMd: form.contentMd
    }
    if (editingId.value == null) {
      await feedbackApi.createArticle(payload)
      message.success('已创建（未发布，用户不可见）')
    } else {
      await feedbackApi.updateArticle(editingId.value, payload)
      message.success('已保存')
    }
    showEditor.value = false
    load(pagination.page ?? 1)
  } catch (e: unknown) {
    message.error((e as Error)?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/** 发布/下架开关：失败回滚开关态（重载行）。 */
async function togglePublish(row: AdminArticleVO, published: boolean) {
  try {
    await feedbackApi.setArticlePublished(row.id, published)
    row.published = published
    message.success(published ? '已发布，用户可见' : '已下架')
  } catch (e: unknown) {
    message.error((e as Error)?.message || '操作失败')
  }
}

/** 删除=硬删（释放 slug），必须二次确认。 */
function confirmDelete(row: AdminArticleVO) {
  dialog.warning({
    title: '确认删除',
    content: `删除「${row.title}」（${row.slug}）后不可恢复，slug 将被释放可重建。确认删除？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await feedbackApi.deleteArticle(row.id)
        message.success('已删除')
        load(pagination.page ?? 1)
      } catch (e: unknown) {
        message.error((e as Error)?.message || '删除失败')
      }
    }
  })
}

onMounted(() => load(1))

// 测试探针
defineExpose({ load, openCreate, openEdit, save, togglePublish, confirmDelete, form, editingId, list })
</script>

<style lang="scss" scoped>
.admin-help {
  padding: var(--spacing-4);
  max-width: 1080px;
  margin: 0 auto;

  &__preview-bar {
    display: flex;
    justify-content: flex-end;
  }

  &__preview {
    margin-top: 8px;
    padding: 8px 12px;
    border: 1px dashed var(--color-border-light);
    border-radius: 6px;
    max-height: 320px;
    overflow-y: auto;
  }
}
</style>
