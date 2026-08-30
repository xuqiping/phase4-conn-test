<!-- ============================================================
  反馈处理（admin，19x）— /admin/feedback，feedback:manage
  · 建议 tab：状态筛选+详情 modal（附件缩略图+回复+审核/改判，改判二次确认）
  · 提问 tab：列表+回答 modal（markdown 预览+公开 FAQ 开关）+关闭
  · 抢态语义：409「已被其他管理员处理」→ toast 提示并刷新列表
  ============================================================ -->
<template>
  <div class="admin-feedback">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="反馈处理" />
    <n-card>
      <n-tabs v-model:value="activeTab" type="line" animated>
        <!-- ==================== 建议审核 ==================== -->
        <n-tab-pane name="suggestions" tab="建议审核">
          <div class="admin-feedback__filter">
            <n-select
              v-model:value="sugStatus"
              :options="sugStatusOptions"
              clearable
              placeholder="全部状态"
              size="small"
              style="width: 160px"
              @update:value="loadSuggestions(1)"
            />
          </div>
          <n-data-table
            remote
            :columns="sugColumns"
            :data="sugList"
            :loading="sugLoading"
            :pagination="sugPagination"
            size="small"
            @update:page="loadSuggestions"
          />
        </n-tab-pane>

        <!-- ==================== 提问回答 ==================== -->
        <n-tab-pane name="questions" tab="提问回答">
          <div class="admin-feedback__filter">
            <n-select
              v-model:value="qStatus"
              :options="qStatusOptions"
              clearable
              placeholder="全部状态"
              size="small"
              style="width: 160px"
              @update:value="loadQuestions(1)"
            />
          </div>
          <n-data-table
            remote
            :columns="qColumns"
            :data="qList"
            :loading="qLoading"
            :pagination="qPagination"
            size="small"
            @update:page="loadQuestions"
          />
        </n-tab-pane>
      </n-tabs>
    </n-card>

    <!-- 建议详情 + 审核 modal -->
    <n-modal v-model:show="showSugModal" preset="card" title="建议详情与审核" style="width: 560px" @after-leave="revokeThumbs">
      <template v-if="sugDetail">
        <div class="admin-feedback__meta">
          <n-tag :type="SUGGESTION_STATUS_TAG_TYPE[sugDetail.status]" size="small" round>
            {{ SUGGESTION_STATUS_LABEL[sugDetail.status] }}
          </n-tag>
          <span class="admin-feedback__user">{{ sugDetail.username }}（#{{ sugDetail.userId }}）</span>
          <span class="admin-feedback__time">{{ fmt(sugDetail.createdAt) }}</span>
        </div>
        <h4>{{ sugDetail.title }}</h4>
        <p class="admin-feedback__content">{{ sugDetail.content }}</p>

        <!-- 附件缩略图（fetchFilePreview 带 JWT 拉 blob → objectURL；关闭 revoke） -->
        <div v-if="sugDetail.attachmentFileIds.length" class="admin-feedback__thumbs">
          <img
            v-for="t in thumbs"
            :key="t.fileId"
            :src="t.url"
            class="admin-feedback__thumb"
            alt="附件截图"
            @click="openThumb(t.url)"
          />
        </div>

        <n-divider style="margin: 12px 0" />
        <n-input
          v-model:value="reviewReply"
          type="textarea"
          placeholder="审核回复（可选，用户可见）"
          maxlength="2000"
          :autosize="{ minRows: 2, maxRows: 6 }"
        />
        <n-space justify="end" style="margin-top: 12px">
          <n-button size="small" @click="openMessages('SUGGESTION', sugDetail.id, sugDetail.title)">留言</n-button>
          <template v-if="sugDetail.status !== 'CLOSED'">
            <n-button
              v-if="sugDetail.status !== 'ADOPTED'"
              type="success"
              size="small"
              :loading="reviewing"
              @click="confirmReview('ADOPTED')"
            >采纳</n-button>
            <n-button
              v-if="sugDetail.status !== 'REJECTED'"
              type="error"
              size="small"
              :loading="reviewing"
              @click="confirmReview('REJECTED')"
            >不采纳</n-button>
            <n-button size="small" :loading="reviewing" @click="confirmReview('CLOSED')">关闭</n-button>
          </template>
          <n-tag v-else size="small" :bordered="false">已关闭为终态，不可再改</n-tag>
        </n-space>
      </template>
    </n-modal>

    <!-- 提问回答 modal -->
    <n-modal v-model:show="showAnswerModal" preset="card" title="回答提问" style="width: 640px">
      <template v-if="answerTarget">
        <div class="admin-feedback__meta">
          <n-tag :type="QUESTION_STATUS_TAG_TYPE[answerTarget.status]" size="small" round>
            {{ QUESTION_STATUS_LABEL[answerTarget.status] }}
          </n-tag>
          <span class="admin-feedback__user">{{ answerTarget.username }}（#{{ answerTarget.userId }}）</span>
        </div>
        <h4>{{ answerTarget.title }}</h4>
        <p class="admin-feedback__content">{{ answerTarget.content }}</p>
        <n-divider style="margin: 12px 0" />

        <n-input
          v-model:value="answerText"
          type="textarea"
          placeholder="回答内容（支持 markdown，≤8000 字）"
          maxlength="8000"
          :autosize="{ minRows: 5, maxRows: 12 }"
        />
        <div class="admin-feedback__answer-bar">
          <n-checkbox v-model:checked="answerPublic">公开到 FAQ（所有用户可见，不含提问人信息）</n-checkbox>
          <n-button size="tiny" quaternary @click="answerPreview = !answerPreview">
            {{ answerPreview ? '收起预览' : '预览' }}
          </n-button>
        </div>
        <!-- markdown 预览（renderMarkdown html:false） -->
        <div v-if="answerPreview" class="markdown-body admin-feedback__preview" v-html="renderMarkdown(answerText)" />

        <n-space justify="end" style="margin-top: 12px">
          <n-button size="small" @click="openMessages('QUESTION', answerTarget.id, answerTarget.title)">留言</n-button>
          <n-button type="primary" size="small" :loading="answering" :disabled="!answerText.trim()" @click="submitAnswer">
            {{ answerTarget.status === 'ANSWERED' ? '保存修改' : '提交回答' }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 留言 modal（19x 未解决#1：审核后可持续留言，每条都通知用户） -->
    <n-modal v-model:show="showMsgModal" preset="card" :title="msgModalTitle" style="width: 520px">
      <div class="admin-feedback__msg-thread">
        <n-empty v-if="!msgThread.length" description="暂无留言" size="small" />
        <div v-for="m in msgThread" :key="m.id" class="admin-feedback__msg-item">
          <div class="admin-feedback__msg-meta">管理员 · {{ fmt(m.createdAt) }}</div>
          <div class="admin-feedback__msg-content">{{ m.content }}</div>
        </div>
      </div>
      <n-input
        v-model:value="msgText"
        type="textarea"
        placeholder="给用户留言（每次发送用户都会收到站内通知，≤2000 字）"
        maxlength="2000"
        :autosize="{ minRows: 2, maxRows: 5 }"
        style="margin-top: 12px"
      />
      <n-space justify="end" style="margin-top: 12px">
        <n-button type="primary" size="small" :loading="msgSending" :disabled="!msgText.trim()" @click="sendMessage">
          发送留言
        </n-button>
      </n-space>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NButton, NCard, NCheckbox, NDataTable, NDivider, NEmpty, NInput, NModal, NSelect, NSpace, NTag, NTabs,
  NTabPane, useDialog, useMessage
} from 'naive-ui'
import type { DataTableColumns, PaginationProps } from 'naive-ui'
import {
  feedbackApi,
  SUGGESTION_STATUS_LABEL, SUGGESTION_STATUS_TAG_TYPE,
  QUESTION_STATUS_LABEL, QUESTION_STATUS_TAG_TYPE
} from '@/api/feedback'
import type {
  AdminQuestionVO, AdminSuggestionVO, FeedbackMessageVO, QuestionStatus, SuggestionStatus
} from '@/api/feedback'
import { fetchFilePreview } from '@/api/file'
import { renderMarkdown } from '@/utils/markdown'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const dialog = useDialog()
const activeTab = ref('suggestions')

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

// ==================== 建议审核 ====================
const sugStatus = ref<SuggestionStatus | null>(null)
const sugList = ref<AdminSuggestionVO[]>([])
const sugLoading = ref(false)
const sugPagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })
const showSugModal = ref(false)
const sugDetail = ref<AdminSuggestionVO | null>(null)
const reviewReply = ref('')
const reviewing = ref(false)
const thumbs = ref<{ fileId: string; url: string }[]>([])

const sugStatusOptions = (Object.keys(SUGGESTION_STATUS_LABEL) as SuggestionStatus[])
  .map(s => ({ label: SUGGESTION_STATUS_LABEL[s], value: s }))

const sugColumns: DataTableColumns<AdminSuggestionVO> = [
  { title: '时间', key: 'createdAt', width: 150, render: r => fmt(r.createdAt) },
  { title: '用户', key: 'username', width: 110, ellipsis: { tooltip: true } },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', round: true, type: SUGGESTION_STATUS_TAG_TYPE[r.status] },
      { default: () => SUGGESTION_STATUS_LABEL[r.status] })
  },
  { title: '回复', key: 'reply', width: 70, render: r => (r.reply ? '有' : '—') },
  {
    title: '操作', key: 'op', width: 80,
    render: r => h(NButton, { size: 'tiny', quaternary: true, onClick: () => openSugDetail(r) },
      { default: () => r.status === 'PENDING' ? '审核' : '查看' })
  }
]

async function loadSuggestions(page = 1) {
  sugLoading.value = true
  try {
    const res = await feedbackApi.adminSuggestions({
      status: sugStatus.value ?? undefined,
      page, size: sugPagination.pageSize ?? 10
    })
    sugList.value = res.data.data.records
    sugPagination.itemCount = res.data.data.total
    sugPagination.page = page
  } catch {
    sugList.value = []
  } finally {
    sugLoading.value = false
  }
}

async function openSugDetail(row: AdminSuggestionVO) {
  sugDetail.value = row
  reviewReply.value = row.reply ?? ''
  thumbs.value = []
  showSugModal.value = true
  // 附件缩略图逐个拉（≤3，量小不并发池）
  for (const fileId of row.attachmentFileIds) {
    try {
      const url = await fetchFilePreview(fileId)
      thumbs.value.push({ fileId, url })
    } catch { /* 单个附件失败不阻塞审核 */ }
  }
}

function revokeThumbs() {
  thumbs.value.forEach(t => URL.revokeObjectURL(t.url))
  thumbs.value = []
}

function openThumb(url: string) {
  window.open(url, '_blank')
}

/** 审核/改判统一入口：改判（非 PENDING 起点）先弹二次确认。 */
function confirmReview(toStatus: 'ADOPTED' | 'REJECTED' | 'CLOSED') {
  const from = sugDetail.value?.status
  if (!from || from === 'CLOSED') return
  const doReview = () => review(toStatus)
  if (from === 'PENDING') {
    doReview()
    return
  }
  // 改判：ADOPTED↔REJECTED 会重发通知（后端语义），须明示
  dialog.warning({
    title: '确认改判',
    content: `当前状态「${SUGGESTION_STATUS_LABEL[from]}」，改为「${SUGGESTION_STATUS_LABEL[toStatus]}」后将重新通知用户。确认？`,
    positiveText: '确认',
    negativeText: '取消',
    onPositiveClick: doReview
  })
}

async function review(toStatus: 'ADOPTED' | 'REJECTED' | 'CLOSED') {
  if (!sugDetail.value || reviewing.value) return
  reviewing.value = true
  try {
    await feedbackApi.reviewSuggestion(sugDetail.value.id, {
      toStatus,
      reply: reviewReply.value.trim() || undefined
    })
    message.success(`已${SUGGESTION_STATUS_LABEL[toStatus]}`)
    showSugModal.value = false
    loadSuggestions(sugPagination.page ?? 1)
  } catch (e: unknown) {
    // 409 抢态：另一管理员已处理 → 提示并刷新
    message.error((e as Error)?.message || '操作失败')
    loadSuggestions(sugPagination.page ?? 1)
  } finally {
    reviewing.value = false
  }
}

// ==================== 提问回答 ====================
const qStatus = ref<QuestionStatus | null>(null)
const qList = ref<AdminQuestionVO[]>([])
const qLoading = ref(false)
const qPagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })
const showAnswerModal = ref(false)
const answerTarget = ref<AdminQuestionVO | null>(null)
const answerText = ref('')
const answerPublic = ref(false)
const answerPreview = ref(false)
const answering = ref(false)

const qStatusOptions = (Object.keys(QUESTION_STATUS_LABEL) as QuestionStatus[])
  .map(s => ({ label: QUESTION_STATUS_LABEL[s], value: s }))

const qColumns: DataTableColumns<AdminQuestionVO> = [
  { title: '时间', key: 'createdAt', width: 150, render: r => fmt(r.createdAt) },
  { title: '用户', key: 'username', width: 110, ellipsis: { tooltip: true } },
  { title: '问题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', round: true, type: QUESTION_STATUS_TAG_TYPE[r.status] },
      { default: () => QUESTION_STATUS_LABEL[r.status] })
  },
  {
    title: 'FAQ', key: 'isPublic', width: 70,
    render: r => (r.isPublic ? h(NTag, { size: 'tiny', type: 'success', bordered: false }, { default: () => '公开' }) : '—')
  },
  {
    title: '操作', key: 'op', width: 190,
    render: r => h('div', { style: 'display:flex;gap:4px' }, [
      r.status !== 'CLOSED'
        ? h(NButton, { size: 'tiny', quaternary: true, onClick: () => openAnswer(r) },
            { default: () => (r.status === 'ANSWERED' ? '改答案' : '回答') })
        : null,
      r.status !== 'CLOSED'
        ? h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => closeQuestion(r) },
            { default: () => '关闭' })
        : null,
      h(NButton, { size: 'tiny', quaternary: true, onClick: () => openMessages('QUESTION', r.id, r.title) },
        { default: () => '留言' })
    ])
  }
]

async function loadQuestions(page = 1) {
  qLoading.value = true
  try {
    const res = await feedbackApi.adminQuestions({
      status: qStatus.value ?? undefined,
      page, size: qPagination.pageSize ?? 10
    })
    qList.value = res.data.data.records
    qPagination.itemCount = res.data.data.total
    qPagination.page = page
  } catch {
    qList.value = []
  } finally {
    qLoading.value = false
  }
}

function openAnswer(row: AdminQuestionVO) {
  answerTarget.value = row
  answerText.value = row.answer ?? ''
  answerPublic.value = row.isPublic
  answerPreview.value = false
  showAnswerModal.value = true
}

async function submitAnswer() {
  if (!answerTarget.value || !answerText.value.trim() || answering.value) return
  answering.value = true
  try {
    await feedbackApi.answerQuestion(answerTarget.value.id, {
      answer: answerText.value,
      isPublic: answerPublic.value
    })
    message.success(answerTarget.value.status === 'ANSWERED' ? '答案已更新' : '已回答并通知提问人')
    showAnswerModal.value = false
    loadQuestions(qPagination.page ?? 1)
  } catch (e: unknown) {
    message.error((e as Error)?.message || '操作失败')
    loadQuestions(qPagination.page ?? 1)
  } finally {
    answering.value = false
  }
}

async function closeQuestion(row: AdminQuestionVO) {
  try {
    await feedbackApi.closeQuestion(row.id)
    message.success('已关闭')
    loadQuestions(qPagination.page ?? 1)
  } catch (e: unknown) {
    message.error((e as Error)?.message || '操作失败')
    loadQuestions(qPagination.page ?? 1)
  }
}

// ==================== 留言（19x 未解决#1） ====================
const showMsgModal = ref(false)
const msgTargetType = ref<'SUGGESTION' | 'QUESTION'>('SUGGESTION')
const msgTargetId = ref<number | null>(null)
const msgTargetTitle = ref('')
const msgThread = ref<FeedbackMessageVO[]>([])
const msgText = ref('')
const msgSending = ref(false)
const msgModalTitle = computed(() => '留言 - ' + msgTargetTitle.value)

async function openMessages(type: 'SUGGESTION' | 'QUESTION', id: number, title: string) {
  msgTargetType.value = type
  msgTargetId.value = id
  msgTargetTitle.value = title
  msgText.value = ''
  showMsgModal.value = true
  await loadMsgThread()
}

async function loadMsgThread() {
  if (msgTargetId.value == null) return
  try {
    const res = msgTargetType.value === 'SUGGESTION'
      ? await feedbackApi.adminSuggestionMessages(msgTargetId.value)
      : await feedbackApi.adminQuestionMessages(msgTargetId.value)
    msgThread.value = res.data.data ?? []
  } catch {
    msgThread.value = []
  }
}

async function sendMessage() {
  if (msgTargetId.value == null || !msgText.value.trim() || msgSending.value) return
  msgSending.value = true
  try {
    const api = msgTargetType.value === 'SUGGESTION'
      ? feedbackApi.adminMessageSuggestion
      : feedbackApi.adminMessageQuestion
    await api(msgTargetId.value, { content: msgText.value.trim() })
    message.success('留言已发送并通知用户')
    msgText.value = ''
    await loadMsgThread()
  } catch (e: unknown) {
    message.error((e as Error)?.message || '留言失败')
  } finally {
    msgSending.value = false
  }
}

onMounted(() => {
  loadSuggestions(1)
  loadQuestions(1)
})

// 测试探针
defineExpose({
  loadSuggestions, loadQuestions, openSugDetail, confirmReview, review,
  openAnswer, submitAnswer, closeQuestion,
  openMessages, sendMessage, loadMsgThread,
  sugDetail, reviewReply, answerTarget, answerText, answerPublic,
  msgThread, msgText, showMsgModal
})
</script>

<style lang="scss" scoped>
.admin-feedback {
  padding: var(--spacing-4);
  max-width: 1080px;
  margin: 0 auto;

  &__filter {
    margin-bottom: 8px;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--color-text-secondary);
  }

  &__content {
    white-space: pre-wrap;
    color: var(--color-text-secondary);
  }

  &__thumbs {
    display: flex;
    gap: 8px;
    margin-top: 8px;
    flex-wrap: wrap;
  }

  &__thumb {
    width: 96px;
    height: 96px;
    object-fit: cover;
    border-radius: 6px;
    border: 1px solid var(--color-border-light);
    cursor: zoom-in;
  }

  &__answer-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 8px;
  }

  &__preview {
    margin-top: 8px;
    padding: 8px 12px;
    border: 1px dashed var(--color-border-light);
    border-radius: 6px;
    max-height: 240px;
    overflow-y: auto;
  }

  &__msg-thread {
    max-height: 280px;
    overflow-y: auto;
  }

  &__msg-item {
    padding: 8px 0;
    border-bottom: 1px solid var(--color-border-light);

    &:last-child {
      border-bottom: none;
    }
  }

  &__msg-meta {
    font-size: 12px;
    color: var(--color-text-secondary);
    margin-bottom: 4px;
  }

  &__msg-content {
    white-space: pre-wrap;
    line-height: 1.7;
  }
}
</style>
