<template>
  <header class="app-header">
    <!-- 左侧：折叠按钮 + 面包屑 -->
    <div class="app-header__left">
      <button class="app-header__menu-btn" @click="$emit('toggleSidebar')">
        <n-icon size="20" :component="MenuOutline" />
      </button>
      <span class="app-header__page-title">{{ pageTitle }}</span>
    </div>

    <!-- 右侧：参与项目 + 积分徽标 + 搜索 + 主题切换 + 用户 -->
    <div class="app-header__right">
      <!-- 7x/17x：参与项目全局统一入口（五入口分散选择收拢于此）+ 个人/组池积分页顶展示。
           null=个人钱包计费；选中后所有模型调用（对话/问答/生图/生视频/画布）消耗组池。 -->
      <div class="app-header__project">
        <ProjectGroupSelector :model-value="pgStore.groupId" @update:model-value="onSelectGroup" />
        <n-tag
          v-if="pgStore.groupId != null && pgStore.groupBalance != null"
          size="small"
          type="info"
          :bordered="false"
          title="当前项目组池余额"
        >
          组池 {{ fmtPoints(pgStore.groupBalance) }}
        </n-tag>
        <n-tag
          v-if="pgStore.personalPoints != null"
          size="small"
          :bordered="false"
          title="个人积分余额"
        >
          个人 {{ fmtPoints(pgStore.personalPoints) }}
        </n-tag>
      </div>

      <!-- 搜索框（占位） -->
      <n-input
        class="app-header__search"
        placeholder="搜索..."
        size="small"
        clearable
        round
      >
        <template #prefix>
          <n-icon :component="SearchOutline" />
        </template>
      </n-input>

      <!-- 17x#3/#4：通知铃铛（组邀请/入组申请等波及通知；原仅挂 ChatView，上页顶全局可达） -->
      <MemoryNotificationBadge />

      <!-- 19x：反馈通知铃铛（建议审核/提问回答；点击跳反馈中心对应 tab） -->
      <FeedbackNotificationBadge />

      <!-- 主题切换按钮 -->
      <n-tooltip trigger="hover">
        <template #trigger>
          <button class="app-header__icon-btn" @click="showThemeSwitcher = true">
            <n-icon size="18" :component="ColorPaletteOutline" />
          </button>
        </template>
        切换主题
      </n-tooltip>

      <!-- 用户头像 + 下拉菜单 -->
      <n-dropdown :options="userMenuOptions" @select="handleUserMenu">
        <div class="app-header__user">
          <n-avatar
            :size="32"
            round
            :style="{ background: `linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end))` }"
          >
            {{ userInitial }}
          </n-avatar>
          <span class="app-header__username">{{ userDisplay }}</span>
        </div>
      </n-dropdown>
    </div>

    <!-- 主题切换弹窗 -->
    <ThemeSwitcher v-model:show="showThemeSwitcher" />
  </header>
</template>

<script setup lang="ts">
import { ref, computed, h, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NInput, NTooltip, NDropdown, NAvatar, NTag } from 'naive-ui'
import {
  MenuOutline,
  SearchOutline,
  ColorPaletteOutline,
  PersonOutline,
  LogOutOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { useProjectGroupStore } from '@/stores/projectGroup'
import ThemeSwitcher from '@/components/ThemeSwitcher.vue'
import ProjectGroupSelector from '@/components/projectgroup/ProjectGroupSelector.vue'
import MemoryNotificationBadge from '@/components/memory/MemoryNotificationBadge.vue'
import FeedbackNotificationBadge from '@/components/feedback/FeedbackNotificationBadge.vue'

defineEmits<{
  toggleSidebar: []
}>()

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const pgStore = useProjectGroupStore()

const showThemeSwitcher = ref(false)

// 7x/17x：页顶统一入口初始化（组列表 + 个人钱包并行；失败静默降级为个人计费），
// 路由切换轻刷新余额（跨页消耗后徽标跟上；单次一请求，无轮询）
onMounted(() => { void pgStore.init() })
watch(() => route.path, () => {
  void pgStore.loadWallet()
  void pgStore.loadGroups()
})

/** 全局切组（唯一写入口；单键持久化，所有入口即时生效）。 */
function onSelectGroup(id: number | null) {
  pgStore.setGroup(id)
}

/** 积分展示：去尾零（后端 DECIMAL 序列化 1000.00 → 1000）。 */
function fmtPoints(n: number): string {
  return Number.isInteger(n) ? String(n) : String(n).replace(/\.?0+$/, '')
}

/** 页面标题（从路由meta获取） */
const pageTitle = computed(() => (route.meta.title as string) || '多Agent智能体平台')

/** 显示名：优先 name，空则回退 username */
const displayName = computed(() => authStore.userInfo?.name || authStore.userInfo?.username || 'U')

/** 右上角展示文案：有主部门则「部门 - 姓名」，否则只姓名 */
const userDisplay = computed(() => {
  const dept = authStore.userInfo?.primaryDepartmentName
  return dept ? `${dept} - ${displayName.value}` : displayName.value
})

/** 显示名首字母（头像占位） */
const userInitial = computed(() => displayName.value.charAt(0).toUpperCase())

/** 用户下拉菜单选项 */
const userMenuOptions = [
  {
    label: '个人信息',
    key: 'profile',
    icon: () => h(NIcon, null, { default: () => h(PersonOutline) })
  },
  {
    type: 'divider',
    key: 'd1'
  },
  {
    label: '退出登录',
    key: 'logout',
    icon: () => h(NIcon, null, { default: () => h(LogOutOutline) })
  }
]

/** 处理用户菜单选择 */
async function handleUserMenu(key: string) {
  if (key === 'logout') {
    await authStore.logout()
    router.push('/login')
  } else if (key === 'profile') {
    // 17x：个人信息（昵称/姓名）→ 设置页 profile tab（17x-2026-08-25 起全员可进设置）
    router.push('/settings')
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--spacing-6);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  backdrop-filter: blur(12px);
  background: rgba(var(--color-primary-rgb), 0.03);
}

// 左侧
.app-header__left {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.app-header__menu-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }
}

.app-header__page-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

// 右侧
.app-header__right {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

// 7x/17x：参与项目统一入口 + 积分徽标组
.app-header__project {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
}

.app-header__search {
  width: 200px;
}

.app-header__icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// 用户头像
.app-header__user {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  cursor: pointer;
  padding: var(--spacing-1) var(--spacing-2);
  border-radius: var(--radius-base);
  transition: background var(--duration-instant);

  &:hover {
    background: var(--color-primary-light);
  }
}

.app-header__username {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

// === 移动端 ===
@media (max-width: 768px) {
  .app-header {
    padding: 0 var(--spacing-3);
  }

  // 搜索框固定 200px，移动端隐藏
  .app-header__search {
    display: none;
  }

  // 参与项目选择器保留但积分徽标隐藏（窄屏省位）
  .app-header__project .n-tag {
    display: none;
  }

  // 用户名隐藏，仅留头像
  .app-header__username {
    display: none;
  }

  // 页面标题过长省略
  .app-header__page-title {
    max-width: 45vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
