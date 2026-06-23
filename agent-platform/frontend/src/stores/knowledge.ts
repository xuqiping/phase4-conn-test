// ============================================================
// 知识库 store（薄）：缓存 KB 列表（两 tab 共享 kbId 选择）+ 文档操作。
// setup-store 风格（同 stores/auth.ts）。request 无自动解包 → .data.data。
// ============================================================

import { defineStore } from 'pinia'
import { ref } from 'vue'
import { knowledgeApi } from '@/api/knowledge'
import type { KnowledgeBase, KnowledgeDocument } from '@/api/knowledge'

export const useKnowledgeStore = defineStore('knowledge', () => {
  // === 状态 ===
  const bases = ref<KnowledgeBase[]>([])
  const selectedKbId = ref<number | null>(null)
  const documents = ref<KnowledgeDocument[]>([])
  const loadingBases = ref(false)
  const loadingDocs = ref(false)

  // === 计算属性 ===
  /** 可读 KB（检索调试 tab 的 kbId 候选） */
  const readableBases = () => bases.value.filter(b => b.canRead)

  // === Actions ===
  async function loadBases() {
    loadingBases.value = true
    try {
      const res = await knowledgeApi.listBases()
      bases.value = res.data.data || []
    } finally {
      loadingBases.value = false
    }
  }

  async function loadDocuments(kbId: number) {
    loadingDocs.value = true
    try {
      const res = await knowledgeApi.listDocuments(kbId)
      documents.value = res.data.data || []
    } finally {
      loadingDocs.value = false
    }
  }

  async function uploadDocument(kbId: number, file: File) {
    await knowledgeApi.uploadDocument(kbId, file)
    await loadDocuments(kbId)
  }

  async function deleteDocument(id: number, kbId: number) {
    await knowledgeApi.deleteDocument(id)
    await loadDocuments(kbId)
  }

  function selectKb(kbId: number | null) {
    selectedKbId.value = kbId
    documents.value = []
  }

  return {
    bases,
    selectedKbId,
    documents,
    loadingBases,
    loadingDocs,
    readableBases,
    loadBases,
    loadDocuments,
    uploadDocument,
    deleteDocument,
    selectKb
  }
})
