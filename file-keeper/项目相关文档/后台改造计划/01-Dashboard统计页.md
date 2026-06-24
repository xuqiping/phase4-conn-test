# 01 - Dashboard 统计页

> 状态:**✅ 完全完成**(后端接口验证通过 + 前端 `npm run build` 类型检查通过)
> 实施日期:2026-06-15

## 一、目标

管理后台登录后默认落地页,一眼看到运营关键指标:待审核用户、即将过期授权、活跃设备、已过期授权、用户分布。

## 二、已实现的改动

### 后端(4 个新文件,零现有文件改动)

1. **`server/src/main/java/com/superprogrammer/stats/dto/DashboardStats.java`** — record,8 个 long 字段:
   `totalUsers, pendingReviewUsers, activeUsers, disabledUsers, pendingVerificationUsers, activeDevices, expiringSoonEntitlements, expiredEntitlements`

2. **`server/src/main/java/com/superprogrammer/stats/repository/StatsRepository.java`** — `@Repository` + JdbcTemplate,8 条 count SQL(均带 `deleted = 0` 软删除过滤)。关键 SQL:
   - 即将过期:`expires_at > CURRENT_TIMESTAMP AND expires_at <= CURRENT_TIMESTAMP + INTERVAL '7 days' AND enabled = true`
   - 已过期:`expires_at < CURRENT_TIMESTAMP AND enabled = true`

3. **`server/src/main/java/com/superprogrammer/admin/service/AdminStatsService.java`** — 薄服务,转发 repository。

4. **`server/src/main/java/com/superprogrammer/admin/controller/AdminStatsController.java`** — `GET /api/admin/stats/dashboard`,返回 `R<DashboardStats>`。受 SecurityConfig `/api/admin/**` SUPER_ADMIN 保护,无需改安全配置。

### 前端(2 新建 + 4 改动)

- **新建** `admin-web/src/api/stats.ts` — `getDashboardStats()`
- **新建** `admin-web/src/views/DashboardView.vue` — n-grid + n-card + n-statistic 展示 4 张 KPI 卡 + 用户分布卡(带 n-progress 占比条)
- **改动** `admin-web/src/types/index.ts` — 末尾追加 `DashboardStats` interface
- **改动** `admin-web/src/router/index.ts` — 根路径 children 加 `{path:'',name:'dashboard'}`,users 改显式 path,login 已登录跳转目标改 `'dashboard'`
- **改动** `admin-web/src/views/Layout.vue` — menuOptions 头部加 Dashboard 项(StatsChartOutline 图标)
- **改动** `admin-web/src/views/UserListView.vue` — onMounted 读 `route.query.status` 初始化过滤器(支持 Dashboard 卡片跳转自动过滤)

## 三、验证结果

后端接口已验证通过(2026-06-15):
```
GET /api/admin/stats/dashboard (带超管 token) → 200
  totalUsers: 2, active: 2, activeDevices: 3, pendingReview/expiringSoon/expired: 0
未认证请求 → 401 ✓
```

## 四、剩余验证步骤(未完成)

- [x] `cd file-keeper/admin-web && npm run build`(vue-tsc 类型检查)—— ✅ 通过(41s,无类型错误)
- [ ] 浏览器实际访问确认页面渲染、卡片跳转(可选,代码已构建通过)

## 五、设计要点(供后续同类改造参考)

- 后端零现有文件改动:`/api/admin/**` 已强制 SUPER_ADMIN,新接口自动受保护。
- Repository 一律用 JdbcTemplate + 手写 SQL(项目现有 repository 全是这风格,不用 MyBatis-Plus)。
- 单次聚合 GET,前端一个请求渲染整页。
- Dashboard 作为登录后默认落地页,比用户列表更合适。
