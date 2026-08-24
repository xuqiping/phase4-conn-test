import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const failures = []

function source(relativePath) {
  return readFileSync(resolve(root, relativePath), 'utf8')
}

function expectAbsent(relativePath) {
  if (existsSync(resolve(root, relativePath))) {
    failures.push(`${relativePath} should be removed`)
  }
}

function expectNotContains(relativePath, patterns) {
  const content = source(relativePath)
  for (const pattern of patterns) {
    if (content.includes(pattern)) {
      failures.push(`${relativePath} still contains ${JSON.stringify(pattern)}`)
    }
  }
}

expectAbsent('src/views/AnonymousDevicesView.vue')
expectAbsent('src/views/SettingsView.vue')
expectAbsent('src/api/anonymousDevices.ts')
expectAbsent('src/api/entitlements.ts')
expectAbsent('src/api/settings.ts')

expectNotContains('src/router/index.ts', ['anonymous-devices', "name: 'settings'"])
if (!source('src/router/index.ts').includes(':pathMatch(.*)*')) {
  failures.push('src/router/index.ts should redirect unknown authenticated routes')
}
if (!source('src/router/index.ts').includes("redirect: '/'")) {
  failures.push('src/router/index.ts catch-all should drop legacy route params')
}

expectNotContains('src/views/Layout.vue', ['匿名设备', '系统设置'])
expectNotContains('src/views/DashboardView.vue', [
  'pendingReviewUsers',
  'pendingVerificationUsers',
  'expiringSoonEntitlements',
  'expiredEntitlements'
])
expectNotContains('src/views/UserListView.vue', [
  "value: 'pending_review'",
  '设备上限',
  '离线缓存',
  'approveUser'
])
expectNotContains('src/views/UserDetailView.vue', [
  '模块权益',
  'deviceLimit',
  'offlineCacheMinutes',
  "@/api/entitlements"
])

if (failures.length > 0) {
  console.error(failures.map((failure) => `- ${failure}`).join('\n'))
  process.exit(1)
}

console.log('Commercial admin UI removal contract passed.')
