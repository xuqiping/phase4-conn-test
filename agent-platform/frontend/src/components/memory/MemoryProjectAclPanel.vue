<!-- ============================================================
  项目 ACL 配置（计划12 I4-1 + I4-2）— 花名册 + reader×target 授权矩阵
  · 走 memoryApi.getRoster / getRecallAcl / putRecallAcl
  · 仅 owner / recall_admin 可配（backend 403 兜底；前端按 roster.role/recall_admin 预判）
  · owner 兜底全读（owner 作 reader 不显勾选行）；DEPARTED 保交接仍可作 target
  · reader 侧 DEPARTED 无读权（不入 reader 行）；非成员 403
  ============================================================ -->
<template>
  <div class="memory-acl">
    <n-alert type="info" :bordered="false" size="small" class="memory-acl__top">
      项目召回 ACL：配置「谁能读到谁的流水账」。owner 默认全读无需配；勾选后该 reader 可召回对应 target 的记忆。
    </n-alert>

    <n-space :size="8" align="center" class="memory-acl__toolbar">
      <n-select
        v-model:value="projectId"
        :options="projectOptions"
        placeholder="选择项目"
        size="small"
        :style="{ width: 'min(240px, 60vw)' }"
        :consistent-menu-width="false"
        @update:value="load"
      />
      <n-button size="small" :loading="loading" :disabled="!projectId" @click="load">刷新</n-button>
    </n-space>

    <n-empty v-if="!projectId" size="small" description="请选择项目" />

    <template v-else-if="roster.length">
      <div class="memory-acl__section-title">花名册（含已离开，保交接）</div>
      <div class="memory-acl__roster">
        <n-tag
          v-for="r in roster"
          :key="r.userId"
          size="small"
          :type="r.status === 'DEPARTED' ? 'warning' : (r.role === 'OWNER' ? 'success' : 'default')"
          :bordered="false"
        >
          {{ r.name || r.username }}
          <span class="memory-acl__role">· {{ r.role }}</span>
          <span v-if="r.recallAdmin" class="memory-acl__role">· recall_admin</span>
          <span v-if="r.status === 'DEPARTED'" class="memory-acl__role">· 已离开</span>
        </n-tag>
      </div>

      <div class="memory-acl__section-title">
        授权矩阵
        <span v-if="!canConfigure" class="memory-acl__lock">（仅 owner / recall_admin 可配置）</span>
      </div>
      <div class="memory-acl__matrix-scroll">
        <table class="memory-acl__matrix">
          <thead>
            <tr>
              <th class="memory-acl__corner">reader ＼ target</th>
              <th v-for="t in targets" :key="t.userId" class="memory-acl__th">
                {{ t.name || t.username }}
                <span v-if="t.status === 'DEPARTED'" class="memory-acl__departed">已离开</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="reader in readers" :key="reader.userId">
              <td class="memory-acl__rowhead">
                {{ reader.name || reader.username }}
                <span class="memory-acl__role">· {{ reader.role }}</span>
              </td>
              <td v-for="t in targets" :key="t.userId" class="memory-acl__cell">
                <n-checkbox
                  v-if="reader.userId !== t.userId"
                  :checked="isChecked(reader.userId, t.userId)"
                  :disabled="!canConfigure || savingReader === reader.userId"
                  @update:checked="(v) => toggle(reader.userId, t.userId, v)"
                />
                <span v-else class="memory-acl__self">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <n-empty v-else-if="!loading && projectId" size="small" description="该项目无成员" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { NAlert, NButton, NCheckbox, NEmpty, NSelect, NSpace, NTag, useMessage } from 'naive-ui'
import { memoryApi, type MemoryRosterVO, type MemoryRecallAclVO } from '@/api/memory'
import { projectApi, type Project } from '@/api/project'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const auth = useAuthStore()

const projectId = ref<number | null>(null)
const projectOptions = ref<{ label: string; value: number }[]>([])
const roster = ref<MemoryRosterVO[]>([])
const aclRows = ref<MemoryRecallAclVO[]>([])
const loading = ref(false)
const savingReader = ref<number | null>(null)

// reader→target 授权集（本地态，toggle 即时 PUT 全量替换）
const grantMap = computed(() => {
  const m = new Map<number, Set<number>>()
  for (const a of aclRows.value) {
    if (!m.has(a.readerUserId)) m.set(a.readerUserId, new Set())
    m.get(a.readerUserId)!.add(a.targetUserId)
  }
  return m
})

// reader 行 = ACTIVE 非 owner 成员（owner 兜底全读；DEPARTED 无读权不入 reader）
const readers = computed(() =>
  roster.value.filter(r => r.status === 'ACTIVE' && r.role !== 'OWNER')
)
// target 列 = 全体成员含 DEPARTED（保交接）
const targets = computed(() => roster.value)

const canConfigure = computed(() => {
  const me = auth.userInfo?.id
  if (me == null) return false
  const my = roster.value.find(r => r.userId === me)
  if (!my) return false
  return my.role === 'OWNER' || !!my.recallAdmin
})

function isChecked(readerId: number, targetId: number): boolean {
  return grantMap.value.get(readerId)?.has(targetId) ?? false
}

async function loadProjects() {
  try {
    const res = await projectApi.list()
    projectOptions.value = (res.data?.data ?? []).map((p: Project) => ({ label: p.name, value: p.id }))
  } catch { /* ignore */ }
}

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const [rosterRes, aclRes] = await Promise.all([
      memoryApi.getRoster(projectId.value),
      memoryApi.getRecallAcl(projectId.value)
    ])
    roster.value = rosterRes.data?.data ?? []
    aclRows.value = aclRes.data?.data ?? []
  } catch (e: any) {
    // 403 = 非 owner/recall_admin 读 acl 失败，但 roster 仍可读
    if (e?.response?.status === 403) {
      try {
        const rosterRes = await memoryApi.getRoster(projectId.value)
        roster.value = rosterRes.data?.data ?? []
        aclRows.value = []
      } catch { /* ignore */ }
    } else {
      message.error(e?.message || '加载失败')
    }
  } finally {
    loading.value = false
  }
}

async function toggle(readerId: number, targetId: number, on: boolean) {
  if (!projectId.value || !canConfigure.value) return
  const cur = new Set(grantMap.value.get(readerId) ?? [])
  if (on) cur.add(targetId)
  else cur.delete(targetId)
  savingReader.value = readerId
  try {
    await memoryApi.putRecallAcl(projectId.value, {
      readerUserId: readerId,
      targetUserIds: [...cur]
    })
    // 用新集替换该 reader 的全部 acl 行
    aclRows.value = aclRows.value.filter(a => a.readerUserId !== readerId)
    for (const t of cur) {
      aclRows.value.push({
        readerUserId: readerId,
        readerUsername: roster.value.find(r => r.userId === readerId)?.username || '',
        targetUserId: t,
        targetUsername: roster.value.find(r => r.userId === t)?.username || '',
        createdBy: 0
      })
    }
  } catch (e: any) {
    message.error(e?.response?.data?.msg || e?.message || '保存失败')
    await load()
  } finally {
    savingReader.value = null
  }
}

loadProjects()
</script>

<style lang="scss" scoped>
.memory-acl {
  &__top {
    margin-bottom: 12px;
  }
  &__toolbar {
    margin-bottom: 16px;
  }
  &__section-title {
    font-size: 13px;
    font-weight: 600;
    margin: 12px 0 8px;
    opacity: 0.85;
  }
  &__roster {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
    margin-bottom: 16px;
  }
  &__role {
    opacity: 0.6;
    font-weight: 400;
  }
  &__lock {
    font-size: 11px;
    opacity: 0.55;
    font-weight: 400;
  }
  &__matrix-scroll {
    overflow-x: auto;
  }
  &__matrix {
    border-collapse: collapse;
    font-size: 12px;
    width: 100%;
    min-width: 480px;
  }
  &__corner,
  &__th,
  &__rowhead,
  &__cell {
    border: 1px solid var(--divider-color, rgba(255, 255, 255, 0.09));
    padding: 6px 8px;
    text-align: center;
    white-space: nowrap;
  }
  &__th,
  &__rowhead {
    background: var(--card-color, rgba(255, 255, 255, 0.03));
    font-weight: 500;
  }
  &__rowhead {
    text-align: left;
  }
  &__departed {
    color: var(--warning-color, #f0a020);
    font-size: 10px;
    margin-left: 2px;
  }
  &__self {
    opacity: 0.3;
  }
}
</style>
