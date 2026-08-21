<template>
  <aside
    class="sidebar"
    :class="{
      'sidebar--collapsed': collapsed && !isMobile,
      'sidebar--mobile': isMobile,
      'sidebar--mobile-open': isMobile && mobileOpen
    }"
  >
    <!-- Logo区域 -->
    <div class="sidebar__logo" @click="$router.push('/agents')">
      <div class="sidebar__logo-icon">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="28" height="28">
          <defs>
            <linearGradient id="logo-g" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" style="stop-color:var(--color-gradient-start)"/>
              <stop offset="100%" style="stop-color:var(--color-gradient-end)"/>
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="6" fill="url(#logo-g)"/>
          <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
        </svg>
      </div>
      <span v-show="!collapsed" class="sidebar__logo-text">Agent平台</span>
    </div>

    <!-- 导航列表 -->
    <nav class="sidebar__nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="sidebar__nav-item"
        :class="{ 'sidebar__nav-item--active': isNavItemActive(item.path) }"
      >
        <n-icon size="20" :component="item.icon" />
        <span v-show="!collapsed" class="sidebar__nav-label">{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 底部折叠按钮 -->
    <div class="sidebar__footer">
      <button class="sidebar__toggle" @click="$emit('toggle')">
        <n-icon size="18">
          <ChevronBackOutline v-if="!collapsed" />
          <ChevronForwardOutline v-else />
        </n-icon>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { NIcon } from 'naive-ui'
import {
  GridOutline,
  GitBranchOutline,
  PulseOutline,
  SettingsOutline,
  ChevronBackOutline,
  ChevronForwardOutline,
  PeopleOutline,
  ShieldCheckmarkOutline,
  ChatbubblesOutline,
  ChatboxEllipsesOutline,
  ClipboardOutline,
  LibraryOutline,
  KeyOutline,
  BookOutline,
  VideocamOutline,
  ImageOutline,
  FilmOutline,
  AppsOutline,
  AlbumsOutline,
  WalletOutline,
  BarChartOutline,
  CashOutline,
  CardOutline,
  DocumentTextOutline,
  BanOutline,
  ConstructOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { isModuleEnabled, getModulePermission, type ModuleKey } from '@/config/modules'
import { unhandledEventCount } from '@/api/security'

defineProps<{
  collapsed: boolean
  mobileOpen?: boolean
  isMobile?: boolean
}>()

defineEmits<{
  toggle: []
}>()

const route = useRoute()
const authStore = useAuthStore()

/**
 * 模块是否对当前用户可见（问题 10x-4/10x-5 统一闸）：
 * ① 项目级开关（ENABLED_MODULES）：false → 对所有人隐藏含 admin（如 Agent大厅/工作流/执行监控本项目未启用）；
 * ② RBAC 兜底：模块有权限码 → 用户须 hasPermission（admin 默认有全权限）。
 * 两层叠加，任一不满足即隐藏。后端 @RequirePermission 仍是事实授权闸，菜单隐藏仅 UX 层。
 */
function canSeeModule(key: ModuleKey): boolean {
  if (!isModuleEnabled(key)) return false
  const perm = getModulePermission(key)
  return !perm || authStore.hasPermission(perm)
}
/** 积分计费：价表配置 / 账单总览 / 积分充值（gated，admin 默认有） */
const canManagePricing = computed(() => authStore.hasPermission('pricing:manage'))
const canViewUsage = computed(() => authStore.hasPermission('usage:view'))
const canRecharge = computed(() => authStore.hasPermission('points:recharge'))
/** 审计日志入口仅 system:audit:read 持有者可见（日志系统 LOG-FR-12，admin 默认有） */
const canViewAudit = computed(() => authStore.hasPermission('system:audit:read'))
/** 11x 加固 P4-C12：安全管理组仅 security:event:read 持有者可见（admin 默认有） */
const canViewSecurity = computed(() => authStore.hasPermission('security:event:read'))
/** 未处置安全事件数（60s 轮询，badge 用；告警非秒级，轮询够用） */
const unhandledEvents = ref(0)
let unhandledTimer: ReturnType<typeof setInterval> | null = null
async function pollUnhandled() {
  if (!canViewSecurity.value) return
  try {
    const resp = await unhandledEventCount()
    unhandledEvents.value = Number(resp.data.data ?? 0)
  } catch {
    // 静默：badge 不打扰
  }
}
onMounted(() => {
  pollUnhandled()
  unhandledTimer = setInterval(pollUnhandled, 60_000)
})
onUnmounted(() => {
  if (unhandledTimer) clearInterval(unhandledTimer)
})

/** 导航项配置 */
const navItems = computed(() => {
  const items: Array<{ path: string; label: string; icon: typeof GridOutline; module: ModuleKey }> = [
    { path: '/agents', label: 'Agent大厅', icon: GridOutline, module: 'agentHall' },
    { path: '/chat', label: '智能对话', icon: ChatbubblesOutline, module: 'chat' },
    { path: '/workflow', label: '工作流', icon: GitBranchOutline, module: 'workflow' },
    { path: '/executions', label: '执行监控', icon: PulseOutline, module: 'execution' },
    { path: '/knowledge', label: '知识库', icon: BookOutline, module: 'knowledge' }
  ]
  if (canSeeModule('videoGen')) {
    items.push({ path: '/video-gen', label: '视频生成', icon: VideocamOutline, module: 'videoGen' })
    items.push({ path: '/image-gen', label: '图片生成', icon: ImageOutline, module: 'imageGen' })
  }
  if (canSeeModule('videoEdit')) {
    items.push({ path: '/video-edit', label: '视频剪辑', icon: FilmOutline, module: 'videoEdit' })
  }
  if (canSeeModule('canvas')) {
    items.push({ path: '/canvas', label: '无限画布', icon: AppsOutline, module: 'canvas' })
  }
  if (canSeeModule('assets')) {
    items.push({ path: '/assets', label: '资产库', icon: AlbumsOutline, module: 'assets' })
  }
  if (authStore.isAdmin) {
    items.push(
      { path: '/admin/users', label: '用户管理', icon: PeopleOutline, module: 'settings' },
      { path: '/admin/roles', label: '角色权限', icon: ShieldCheckmarkOutline, module: 'settings' }
    )
  }
  if (canManagePricing.value) {
    items.push({ path: '/admin/billing-pricing', label: '价表配置', icon: CashOutline, module: 'settings' })
  }
  if (canRecharge.value) {
    items.push({ path: '/admin/billing-wallet', label: '积分充值', icon: WalletOutline, module: 'settings' })
  }
  if (canViewUsage.value) {
    items.push({ path: '/admin/billing', label: '账单总览', icon: BarChartOutline, module: 'settings' })
  }
  // 19x：反馈处理/帮助文章（module 权限码 feedback:manage / help:manage，双码分离分管）
  if (canSeeModule('feedbackAdmin')) {
    items.push({ path: '/admin/feedback', label: '反馈处理', icon: ClipboardOutline, module: 'feedbackAdmin' })
  }
  if (canSeeModule('helpAdmin')) {
    items.push({ path: '/admin/help-articles', label: '帮助文章', icon: LibraryOutline, module: 'helpAdmin' })
  }
  // 7x 追加：支付渠道密钥配置（module 权限码 payment:config）
  if (canSeeModule('paymentAdmin')) {
    items.push({ path: '/admin/payment-channels', label: '支付渠道', icon: KeyOutline, module: 'paymentAdmin' })
  }
  if (canViewAudit.value) {
    items.push({ path: '/admin/logs/audit', label: '审计日志', icon: DocumentTextOutline, module: 'settings' })
  }
  // 11x 加固 P4-C12：安全管理组（badge 显未处置事件数）
  if (canViewSecurity.value) {
    const badge = unhandledEvents.value > 0 ? `(${unhandledEvents.value})` : ''
    items.push(
      { path: '/admin/security/dashboard', label: '风险大盘', icon: BarChartOutline, module: 'settings' },
      { path: '/admin/security/events', label: `安全事件${badge}`, icon: ShieldCheckmarkOutline, module: 'settings' },
      { path: '/admin/security/ban', label: '封禁管理', icon: BanOutline, module: 'settings' },
      { path: '/admin/security/rules', label: '安全规则', icon: ConstructOutline, module: 'settings' }
    )
  }
  items.push({ path: '/wallet', label: '我的钱包', icon: CardOutline, module: 'wallet' })
  // 计划5 Step7：项目组推进页（module 权限码 project-group:manage → 组长/成员可见，admin 默认有）
  if (canSeeModule('projectGroups')) {
    items.push({ path: '/project-groups', label: '项目组', icon: PeopleOutline, module: 'projectGroups' })
  }
  // 19x：反馈与帮助（建议/提问/说明三合一；无权限码，登录即可，开关兜底）
  items.push({ path: '/feedback', label: '反馈与帮助', icon: ChatboxEllipsesOutline, module: 'feedback' })
  // 设置入口：开关 ON 且用户是 admin（10x-3：非 admin 不开放设置）
  if (isModuleEnabled('settings') && authStore.isAdmin) {
    items.push({ path: '/settings', label: '设置', icon: SettingsOutline, module: 'settings' })
  }
  // 统一闸：前5项（Agent大厅/对话/工作流/执行监控/知识库）也在这一关过滤，
  // 确保关闭的模块（如 Agent大厅/工作流/执行监控）对所有人隐藏（10x-5）。
  return items.filter((it) => canSeeModule(it.module))
})

/** 判断导航项是否处于激活状态 */
function isNavItemActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<style lang="scss" scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: var(--sidebar-width);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  transition: width var(--duration-normal) var(--ease-in-out);
  z-index: 50;

  &--collapsed {
    width: var(--sidebar-collapsed-width);
  }
}

// Logo区域
.sidebar__logo {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-4) var(--spacing-4);
  height: var(--header-height);
  cursor: pointer;
  border-bottom: 1px solid var(--color-border-light);
}

.sidebar__logo-icon {
  flex-shrink: 0;
}

.sidebar__logo-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
}

// 导航列表
.sidebar__nav {
  flex: 1;
  padding: var(--spacing-2);
  overflow-y: auto;
}

.sidebar__nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-2) var(--spacing-3);
  margin-bottom: 2px;
  border-radius: var(--radius-base);
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: all var(--duration-instant) var(--ease-in-out);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }

  &--active {
    color: var(--color-primary);
    background: var(--color-primary-light);
    font-weight: var(--font-weight-medium);
  }
}

.sidebar__nav-label {
  font-size: var(--font-size-base);
  overflow: hidden;
  text-overflow: ellipsis;
}

// 底部折叠按钮
.sidebar__footer {
  padding: var(--spacing-2);
  border-top: 1px solid var(--color-border-light);
}

.sidebar__toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: var(--spacing-2);
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

// === 移动端：抽屉模式 ===
.sidebar--mobile {
  transform: translateX(-100%);
  box-shadow: var(--shadow-lg);
  // 移动端永远展开宽度（无折叠态）
  width: var(--sidebar-width);

  // 桌面端折叠按钮在移动端无意义，隐藏
  .sidebar__footer {
    display: none;
  }
}

.sidebar--mobile-open {
  transform: translateX(0);
}
</style>
