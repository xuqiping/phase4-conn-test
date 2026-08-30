<template>
  <div class="role-manage">
    <!-- 雾中浮岛场景层（ART-DIR-0002R 方向二 admin 淡版，仅 ink 主题渲染） -->
    <ModuleScene scene="admin" lite />
    <PageHeader title="角色权限管理" />

    <!-- 角色卡片列表 -->
    <div class="role-manage__cards">
      <div
        v-for="role in roles"
        :key="role.id"
        class="role-card"
        :class="{ 'role-card--active': selectedRole?.id === role.id }"
        @click="selectRole(role)"
      >
        <div class="role-card__name">{{ role.name }}</div>
        <div class="role-card__code">{{ role.code }}</div>
        <div class="role-card__desc">{{ role.description || '无描述' }}</div>
        <div class="role-card__count">{{ getRolePermCount(role.id) }} 个权限</div>
      </div>
    </div>

    <!-- 权限编辑区 -->
    <div v-if="selectedRole" class="role-manage__editor">
      <h3>{{ selectedRole.name }} — 权限编辑</h3>
      <div class="perm-grid">
        <div v-for="resource in permissionGroups" :key="resource.name" class="perm-group">
          <div class="perm-group__title">{{ resource.label }}</div>
          <n-checkbox-group :value="selectedPermIds" @update:value="(val: (string|number)[]) => onPermChange(val as number[])">
            <n-space>
              <n-checkbox
                v-for="perm in resource.items"
                :key="perm.id"
                :value="perm.id"
                :label="perm.name"
              />
            </n-space>
          </n-checkbox-group>
        </div>
      </div>
      <div class="role-manage__actions">
        <n-button type="primary" :loading="saving" @click="savePermissions">保存权限</n-button>
      </div>
    </div>
    <div v-else class="role-manage__hint">
      ← 请选择一个角色
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NCheckboxGroup, NCheckbox, NSpace, NButton, useMessage } from 'naive-ui'
import { adminApi, type Role, type Permission } from '@/api/admin'
import PageHeader from '@/components/PageHeader.vue'
import ModuleScene from '@/components/ModuleScene.vue'

const message = useMessage()
const roles = ref<Role[]>([])
const allPermissions = ref<Permission[]>([])
const selectedRole = ref<Role | null>(null)
const selectedPermIds = ref<number[]>([])
const rolePermMap = ref<Record<number, number[]>>({})
const saving = ref(false)

const resourceLabels: Record<string, string> = {
  // V148：agent（Agent管理）/ workflow（工作流管理）权限码已删，两组不再渲染，标签一并移除
  skill: '技能管理',
  execution: '执行管理',
  user: '用户管理',
  role: '角色管理'
}

const permissionGroups = computed(() => {
  const groups: { name: string; label: string; items: Permission[] }[] = []
  const map = new Map<string, Permission[]>()
  for (const p of allPermissions.value) {
    if (!map.has(p.resource)) map.set(p.resource, [])
    map.get(p.resource)!.push(p)
  }
  for (const [name, items] of map) {
    groups.push({ name, label: resourceLabels[name] || name, items })
  }
  return groups
})

function getRolePermCount(roleId: number): number {
  return rolePermMap.value[roleId]?.length ?? 0
}

async function loadData() {
  try {
    const [rolesRes, permsRes] = await Promise.all([
      adminApi.listAllRoles(),
      adminApi.listAllPermissions()
    ])
    roles.value = rolesRes.data.data
    allPermissions.value = permsRes.data.data

    // 加载每个角色的权限
    for (const role of roles.value) {
      const res = await adminApi.getRolePermissions(role.id)
      rolePermMap.value[role.id] = res.data.data
    }
  } catch {
    message.error('加载数据失败')
  }
}

async function selectRole(role: Role) {
  selectedRole.value = role
  selectedPermIds.value = [...(rolePermMap.value[role.id] || [])]
}

function onPermChange(val: number[]) {
  selectedPermIds.value = val
}

async function savePermissions() {
  if (!selectedRole.value) return
  saving.value = true
  try {
    await adminApi.updateRolePermissions(selectedRole.value.id, selectedPermIds.value)
    rolePermMap.value[selectedRole.value.id] = [...selectedPermIds.value]
    message.success('权限保存成功')
  } catch {
    message.error('权限保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadData)
</script>

<style lang="scss" scoped>
.role-manage {
  padding: var(--spacing-6);

  &__cards {
    display: flex;
    gap: var(--spacing-4);
    margin-bottom: var(--spacing-6);
    flex-wrap: wrap;
  }

  &__editor {
    background: var(--color-card);
    border: 1px solid var(--color-border);
    border-radius: var(--radius-lg);
    padding: var(--spacing-5);

    h3 {
      margin: 0 0 var(--spacing-4) 0;
      color: var(--color-text-primary);
    }
  }

  &__hint {
    text-align: center;
    padding: var(--spacing-8);
    color: var(--color-text-tertiary);
  }

  &__actions {
    margin-top: var(--spacing-4);
    padding-top: var(--spacing-4);
    border-top: 1px solid var(--color-border-light);
    display: flex;
    justify-content: flex-end;
  }
}

.role-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-4);
  width: 200px;
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
  }

  &--active {
    border-color: var(--color-primary);
    box-shadow: 0 0 12px rgba(var(--color-primary-rgb), 0.2);
  }

  &__name {
    font-weight: var(--font-weight-semibold);
    color: var(--color-text-primary);
    margin-bottom: 2px;
  }

  &__code {
    font-size: var(--font-size-xs);
    color: var(--color-text-tertiary);
    font-family: monospace;
    margin-bottom: var(--spacing-2);
  }

  &__desc {
    font-size: var(--font-size-sm);
    color: var(--color-text-secondary);
    margin-bottom: var(--spacing-2);
  }

  &__count {
    font-size: var(--font-size-xs);
    color: var(--color-primary);
  }
}

.perm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: var(--spacing-5);
}

.perm-group {
  &__title {
    font-weight: var(--font-weight-semibold);
    color: var(--color-text-primary);
    margin-bottom: var(--spacing-2);
    padding-bottom: var(--spacing-1);
    border-bottom: 1px solid var(--color-border-light);
  }
}
</style>
