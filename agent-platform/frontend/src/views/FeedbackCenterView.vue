<template>
  <div class="feedback-center">
    <n-card title="反馈与帮助">
      <n-tabs v-model:value="activeTab" type="line" animated>
        <!-- ==================== 建议台 ==================== -->
        <n-tab-pane name="suggestions" tab="提建议">
          <n-card size="small" title="提交新建议" class="feedback-center__form">
            <n-input v-model:value="sugTitle" placeholder="标题（≤120 字）" maxlength="120" show-count />
            <n-input
              v-model:value="sugContent"
              type="textarea"
              placeholder="描述你的需求或改进建议（≤4000 字）"
              maxlength="4000"
              :autosize="{ minRows: 4, maxRows: 10 }"
              style="margin-top: 8px"
            />
            <!-- 截图附件 ≤3（先传文件后提交表单；后端逐 fileId 校验属主） -->
            <div class="feedback-center__attachments">
              <n-upload
                :show-file-list="false"
                accept="image/*"
                :custom-request="onUploadAttachment"
                :disabled="sugAttachments.length >= 3"
              >
                <n-button size="small" secondary :disabled="sugAttachments.length >= 3">
                  添加截图（{{ sugAttachments.length }}/3）
                </n-button>
              </n-upload>
              <n-tag
                v-for="(a, i) in sugAttachments"
                :key="a.fileId"
                closable
                size="small"
                @close="sugAttachments.splice(i, 1)"
              >
                {{ a.name }}
              </n-tag>
            </div>
            <n-space justify="end" style="margin-top: 12px">
              <n-button type="primary" :loading="sugSubmitting" :disabled="!sugCanSubmit" @click="submitSuggestion">
                提交建议
              </n-button>
            </n-space>
          </n-card>

          <n-card size="small" title="我的建议" style="margin-top: 12px">
            <n-data-table
              remote
              :columns="sugColumns"
              :data="sugList"
              :loading="sugLoading"
              :pagination="sugPagination"
              size="small"
              @update:page="loadSuggestions"
            />
          </n-card>
        </n-tab-pane>

        <!-- ==================== 提问台 ==================== -->
        <n-tab-pane name="questions" tab="提问题">
          <n-card size="small" title="提交新问题" class="feedback-center__form">
            <n-input v-model:value="qTitle" placeholder="问题标题（≤120 字）" maxlength="120" show-count />
            <n-input
              v-model:value="qContent"
              type="textarea"
              placeholder="描述你的问题（≤4000 字）"
              maxlength="4000"
              :autosize="{ minRows: 3, maxRows: 8 }"
              style="margin-top: 8px"
            />
            <n-space justify="end" style="margin-top: 12px">
              <n-button type="primary" :loading="qSubmitting" :disabled="!qCanSubmit" @click="submitQuestion">
                提交问题
              </n-button>
            </n-space>
          </n-card>

          <n-card size="small" title="我的提问" style="margin-top: 12px">
            <n-data-table
              remote
              :columns="qColumns"
              :data="qList"
              :loading="qLoading"
              :pagination="qPagination"
              size="small"
              @update:page="loadQuestions"
            />
          </n-card>

          <!-- FAQ 公开区（检索防抖 300ms；VO 无提问人信息） -->
          <n-card size="small" title="常见问题（FAQ）" style="margin-top: 12px">
            <n-input
              v-model:value="faqKw"
              placeholder="搜索常见问题（标题/内容前缀匹配）"
              clearable
              size="small"
              style="max-width: 320px; margin-bottom: 8px"
            />
            <n-data-table
              remote
              :columns="faqColumns"
              :data="faqList"
              :loading="faqLoading"
              :pagination="faqPagination"
              size="small"
              @update:page="loadFaq"
            />
          </n-card>
        </n-tab-pane>

        <!-- ==================== 说明台 ==================== -->
        <n-tab-pane name="help" tab="使用说明">
          <div class="feedback-center__help">
            <div class="feedback-center__help-nav">
              <n-menu
                :options="categoryOptions"
                :value="activeCategory"
                @update:value="onCategoryChange"
              />
              <n-menu
                :options="articleOptions"
                :value="activeSlug"
                @update:value="onArticleChange"
              />
            </div>
            <div class="feedback-center__help-body">
              <n-empty v-if="!articleDetail" description="左侧选择一篇文章" />
              <template v-else>
                <h3>{{ articleDetail.title }}</h3>
                <!-- markdown 渲染（renderMarkdown html:false，XSS 免疫） -->
                <div class="markdown-body" v-html="renderMarkdown(articleDetail.contentMd)" />
              </template>
            </div>
          </div>
        </n-tab-pane>
      </n-tabs>
    </n-card>

    <!-- 建议详情（含 admin 回复） -->
    <n-modal v-model:show="showSugDetail" preset="card" title="建议详情" style="width: 520px">
      <template v-if="sugDetail">
        <n-tag :type="SUGGESTION_STATUS_TAG_TYPE[sugDetail.status]" size="small" round>
          {{ SUGGESTION_STATUS_LABEL[sugDetail.status] }}
        </n-tag>
        <h4>{{ sugDetail.title }}</h4>
        <p class="feedback-center__content">{{ sugDetail.content }}</p>
        <template v-if="sugDetail.reply">
          <n-divider style="margin: 12px 0" />
          <div class="feedback-center__reply">
            <b>官方回复：</b>{{ sugDetail.reply }}
          </div>
        </template>
      </template>
    </n-modal>

    <!-- 提问详情（答案 markdown 渲染） -->
    <n-modal v-model:show="showQDetail" preset="card" title="提问详情" style="width: 560px">
      <template v-if="qDetail">
        <n-tag :type="QUESTION_STATUS_TAG_TYPE[qDetail.status]" size="small" round>
          {{ QUESTION_STATUS_LABEL[qDetail.status] }}
        </n-tag>
        <h4>{{ qDetail.title }}</h4>
        <p class="feedback-center__content">{{ qDetail.content }}</p>
        <template v-if="qDetail.answer">
          <n-divider style="margin: 12px 0" />
          <b>官方回答：</b>
          <div class="markdown-body" v-html="renderMarkdown(qDetail.answer)" />
        </template>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  NButton, NCard, NDataTable, NDivider, NEmpty, NInput, NMenu, NModal, NSpace, NTag, NTabs,
  NTabPane, NUpload, useMessage
} from 'naive-ui'
import type { DataTableColumns, PaginationProps, UploadCustomRequestOptions } from 'naive-ui'
import {
  feedbackApi, uploadFeedbackFile,
  SUGGESTION_STATUS_LABEL, SUGGESTION_STATUS_TAG_TYPE,
  QUESTION_STATUS_LABEL, QUESTION_STATUS_TAG_TYPE
} from '@/api/feedback'
import type {
  ArticleDetailVO, ArticleListItemVO, FaqVO, QuestionVO, SuggestionVO
} from '@/api/feedback'
import { renderMarkdown } from '@/utils/markdown'

const message = useMessage()
const route = useRoute()

// 铃铛跳转预选 tab（?tab=suggestions|questions|help）；已在页内时 query 变化不重挂载 → watch 同步
const TAB_NAMES = ['suggestions', 'questions', 'help'] as const
const activeTab = ref<string>(
  TAB_NAMES.includes(route.query.tab as (typeof TAB_NAMES)[number])
    ? (route.query.tab as string)
    : 'suggestions'
)
watch(() => route.query.tab, tab => {
  if (typeof tab === 'string' && (TAB_NAMES as readonly string[]).includes(tab)) activeTab.value = tab
})

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—'
}

// ==================== 建议台 ====================
const sugTitle = ref('')
const sugContent = ref('')
const sugAttachments = ref<{ fileId: string; name: string }[]>([])
const sugSubmitting = ref(false)
const sugList = ref<SuggestionVO[]>([])
const sugLoading = ref(false)
const sugPagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })
const showSugDetail = ref(false)
const sugDetail = ref<SuggestionVO | null>(null)

const sugCanSubmit = computed(() => !!sugTitle.value.trim() && !!sugContent.value.trim())

const sugColumns: DataTableColumns<SuggestionVO> = [
  { title: '时间', key: 'createdAt', width: 160, render: r => fmt(r.createdAt) },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', round: true, type: SUGGESTION_STATUS_TAG_TYPE[r.status] },
      { default: () => SUGGESTION_STATUS_LABEL[r.status] })
  },
  { title: '回复', key: 'reply', width: 80, render: r => (r.reply ? '有' : '—') },
  {
    title: '操作', key: 'op', width: 80,
    render: r => h(NButton, { size: 'tiny', quaternary: true, onClick: () => openSugDetail(r) },
      { default: () => '查看' })
  }
]

async function onUploadAttachment(options: UploadCustomRequestOptions) {
  const file = options.file.file
  if (!file) return
  if (sugAttachments.value.length >= 3) {
    message.warning('附件最多 3 个')
    return
  }
  try {
    const res = await uploadFeedbackFile(file)
    sugAttachments.value.push({ fileId: res.data.data.fileId, name: res.data.data.name || file.name })
  } catch {
    message.error('附件上传失败')
  }
  options.onFinish()
}

async function submitSuggestion() {
  if (!sugCanSubmit.value || sugSubmitting.value) return
  sugSubmitting.value = true
  try {
    await feedbackApi.submitSuggestion({
      title: sugTitle.value.trim(),
      content: sugContent.value,
      attachmentFileIds: sugAttachments.value.map(a => a.fileId)
    })
    message.success('建议已提交')
    sugTitle.value = ''
    sugContent.value = ''
    sugAttachments.value = []
    loadSuggestions(1)
  } catch {
    // axios 层已 toast 后端 msg（含 429 限流人话文案）
  } finally {
    sugSubmitting.value = false
  }
}

async function loadSuggestions(page = 1) {
  sugLoading.value = true
  try {
    const res = await feedbackApi.mySuggestions({ page, size: sugPagination.pageSize ?? 10 })
    sugList.value = res.data.data.records
    sugPagination.itemCount = res.data.data.total
    sugPagination.page = page
  } catch {
    sugList.value = []
  } finally {
    sugLoading.value = false
  }
}

function openSugDetail(row: SuggestionVO) {
  sugDetail.value = row
  showSugDetail.value = true
}

// ==================== 提问台 ====================
const qTitle = ref('')
const qContent = ref('')
const qSubmitting = ref(false)
const qList = ref<QuestionVO[]>([])
const qLoading = ref(false)
const qPagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })
const showQDetail = ref(false)
const qDetail = ref<QuestionVO | null>(null)

const qCanSubmit = computed(() => !!qTitle.value.trim() && !!qContent.value.trim())

const qColumns: DataTableColumns<QuestionVO> = [
  { title: '时间', key: 'createdAt', width: 160, render: r => fmt(r.createdAt) },
  { title: '问题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: '状态', key: 'status', width: 90,
    render: r => h(NTag, { size: 'small', round: true, type: QUESTION_STATUS_TAG_TYPE[r.status] },
      { default: () => QUESTION_STATUS_LABEL[r.status] })
  },
  {
    title: '操作', key: 'op', width: 80,
    render: r => h(NButton, { size: 'tiny', quaternary: true, onClick: () => openQDetail(r) },
      { default: () => '查看' })
  }
]

async function submitQuestion() {
  if (!qCanSubmit.value || qSubmitting.value) return
  qSubmitting.value = true
  try {
    await feedbackApi.submitQuestion({ title: qTitle.value.trim(), content: qContent.value })
    message.success('提问已提交')
    qTitle.value = ''
    qContent.value = ''
    loadQuestions(1)
  } catch {
    // toast 已在 axios 层
  } finally {
    qSubmitting.value = false
  }
}

async function loadQuestions(page = 1) {
  qLoading.value = true
  try {
    const res = await feedbackApi.myQuestions({ page, size: qPagination.pageSize ?? 10 })
    qList.value = res.data.data.records
    qPagination.itemCount = res.data.data.total
    qPagination.page = page
  } catch {
    qList.value = []
  } finally {
    qLoading.value = false
  }
}

function openQDetail(row: QuestionVO) {
  qDetail.value = row
  showQDetail.value = true
}

// ---- FAQ（检索防抖 300ms） ----
const faqKw = ref('')
const faqList = ref<FaqVO[]>([])
const faqLoading = ref(false)
const faqPagination = reactive<PaginationProps>({ page: 1, pageSize: 10, itemCount: 0 })
let faqDebounce: ReturnType<typeof setTimeout> | null = null

const faqColumns: DataTableColumns<FaqVO> = [
  { title: '问题', key: 'title', width: 220, ellipsis: { tooltip: true } },
  { title: '回答', key: 'answer', ellipsis: { tooltip: true } },
  { title: '回答时间', key: 'answeredAt', width: 160, render: r => fmt(r.answeredAt) }
]

async function loadFaq(page = 1) {
  faqLoading.value = true
  try {
    const kw = faqKw.value.trim()
    const res = await feedbackApi.faq({ kw: kw || undefined, page, size: faqPagination.pageSize ?? 10 })
    faqList.value = res.data.data.records
    faqPagination.itemCount = res.data.data.total
    faqPagination.page = page
  } catch {
    faqList.value = []
  } finally {
    faqLoading.value = false
  }
}

watch(faqKw, () => {
  if (faqDebounce) clearTimeout(faqDebounce)
  faqDebounce = setTimeout(() => loadFaq(1), 300)
})

// ==================== 说明台 ====================
const articles = ref<ArticleListItemVO[]>([])
const activeCategory = ref<string | null>(null)
const activeSlug = ref<string | null>(null)
const articleDetail = ref<ArticleDetailVO | null>(null)

const categoryOptions = computed(() => {
  const cats = [...new Set(articles.value.map(a => a.category))]
  return cats.map(c => ({ label: c, key: c }))
})

/** 当前分类下的文章目录（未选分类=全部）。 */
const articleOptions = computed(() =>
  articles.value
    .filter(a => !activeCategory.value || a.category === activeCategory.value)
    .map(a => ({ label: a.title, key: a.slug }))
)

function onCategoryChange(key: string | null) {
  activeCategory.value = activeCategory.value === key ? null : key
}

async function onArticleChange(key: string) {
  activeSlug.value = key
  try {
    const res = await feedbackApi.helpArticle(key)
    articleDetail.value = res.data.data
  } catch {
    articleDetail.value = null
    message.error('文章加载失败（可能已下架）')
  }
}

async function loadArticles() {
  try {
    const res = await feedbackApi.helpArticles()
    articles.value = res.data.data ?? []
    if (articles.value.length > 0 && !activeSlug.value) {
      onArticleChange(articles.value[0].slug)
    }
  } catch {
    articles.value = []
  }
}

onMounted(() => {
  loadSuggestions(1)
  loadQuestions(1)
  loadFaq(1)
  loadArticles()
})
</script>

<style lang="scss" scoped>
.feedback-center {
  padding: var(--spacing-4);
  max-width: 1080px;
  margin: 0 auto;

  &__attachments {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
    flex-wrap: wrap;
  }

  &__content {
    white-space: pre-wrap;
    color: var(--color-text-secondary);
  }

  &__reply {
    padding: 8px 12px;
    background: var(--color-bg-secondary, rgba(255, 255, 255, 0.04));
    border-radius: 6px;
  }

  &__help {
    display: flex;
    gap: 16px;
    min-height: 400px;
  }

  &__help-nav {
    width: 220px;
    flex-shrink: 0;
  }

  &__help-body {
    flex: 1;
    min-width: 0;
  }
}
</style>
