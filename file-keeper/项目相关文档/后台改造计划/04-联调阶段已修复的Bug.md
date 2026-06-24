# 04 - 联调阶段已修复的 Bug(复盘)

> 记录 2026-06-13~15 阶段 5E 真实联调中发现并修复的真实 bug。
> 供后续会话参考,**避免重复踩坑**。每个 bug 记录:现象 → 调试过程 → 根因 → 修复 → 是否需要永久保留。

---

## Bug 1:管理后台登录报 "Network error"

- **现象**:管理后台登录直接 Network error。
- **根因**:`admin-web/src/api/request.ts` 的 baseURL 默认 `http://localhost:8080`,但后端实际跑在 **8088**。前端请求打到了没有服务的 8080。
- **修复(临时,环境层)**:启动 admin-web 时指定环境变量:
  ```bash
  VITE_API_BASE_URL="http://localhost:8088" npm run dev
  ```
- **建议永久改进**:`request.ts` 默认值应改成 8088,或在 `.env.development` 里固化。目前 admin-web **没有** `.env.development` 文件(桌面端有)。

---

## Bug 2:管理后台登录报 403(Invalid CORS request)

- **现象**:网络通了后,登录报 403,后端日志 `Invalid CORS request`。
- **根因**:`SecurityConfig.java` **没有配置 CORS**。浏览器跨域请求会先发 OPTIONS 预检,后端默认拒绝非同源 → 403。
- **修复**:`SecurityConfig.java` 加 `corsConfigurationSource()` bean,放行 `localhost:1420`(桌面端)/`localhost:5173`(管理后台)/对应 127.0.0.1。
- **状态**:**已永久修复**(代码改动)。但放行的 origin 是硬编码的本地端口,生产环境需要改成从配置读。

---

## Bug 3:桌面端登录报 "服务器内部错误"(最难的一个)

- **现象**:桌面端点登录,前端显示"服务器内部错误",但后端日志完全没有 ERROR。
- **调试过程**:
  1. 最初以为是 Network/CORS/账号问题,逐个排除。
  2. 加了 `RequestLoggingFilter` 记录每个请求 → 发现登录 200 成功,但紧接着的 `POST /api/client/devices/register` 返回 **500**。
  3. 但 500 的堆栈没打印 → 发现 `GlobalExceptionHandler` 的兜底处理器**吞掉异常不记录**(见 Bug 4)。
  4. 给兜底处理器加 `log.error` 打印堆栈 → 终于看到:
     ```
     DataIntegrityViolationException: ERROR: value too long for type character varying(120)
     ```
- **根因**:
  - 前端 `file-keeper/src/api/commercialAuth.ts` 的 `defaultDeviceName()` 返回 **整个 `navigator.userAgent`**(Edge UA 约 125 字符)。
  - 后端 `user_devices.device_name` 限制 `VARCHAR(120)`,超 5 字符 → INSERT 失败 → 500。
  - 为什么 curl 测不出:curl 用短字符串,不会超长。
- **修复(三重)**:
  1. 数据库:`ALTER TABLE user_devices ALTER COLUMN device_name TYPE VARCHAR(255)`(联调期手动改,**未写进 Flyway**)。
  2. 前端:`defaultDeviceName()` 截断 UA 到 100 字符。
  3. 数据:修正已缓存的超长 deviceName(在 `C:/Users/19536/AppData/Roaming/com.filekeeper.app/file-keeper-auth.json`)。
- **教训**:
  - 用 UA 当设备名本身就不合理,应改为机器名或友好名。
  - `GlobalExceptionHandler` 吞异常是严重隐患(见 Bug 4)。
  - **同样的隐患还在 `anonymous_device_trials.device_name`(VARCHAR 120)**,03 计划实现时要一并处理。
- **状态**:前端截断 + 后端异常打印 **已永久修复**;DB 列放宽 **未进 Flyway**(见 00 文件"五")。

---

## Bug 4:GlobalExceptionHandler 兜底处理器吞掉异常(诊断障碍)

- **现象**:任何未捕获异常 → 500,但后端日志没有任何堆栈,极难定位。
- **根因**:`GlobalExceptionHandler.java` 的 `@ExceptionHandler(Exception.class)`:
  ```java
  public ResponseEntity<R<Void>> handleException(Exception exception) {
      return ResponseEntity.status(...).body(R.fail(...));  // exception 参数根本没用
  }
  ```
- **修复**:加 `@Slf4j` + `log.error("...", exception)`。
- **状态**:**已永久修复**。这条修复是定位 Bug 3 的关键,也是产品级改进。

---

## Bug 5:超管登录报 "已达到设备绑定上限(1 台)"

- **现象**:超管登录被拒,提示设备上限 1 台。
- **根因**:超管 `device_limit=1`,而联调用 curl 注册过测试设备占用了唯一名额,真实桌面端是新设备被拒。
- **修复(临时)**:DB 里 `UPDATE users SET device_limit=10 WHERE id=1` + 删除测试设备绑定。
- **状态**:这是**测试数据污染**,非产品 bug。但说明:device_limit=1 对超管自己都不方便,建议超管默认给较大上限,或系统设置页(02)配置默认值。

---

## Bug 6:审批接口 approve 返回 500(假警报)

- **现象**:`POST /api/admin/users/2/approve` 返回 500。
- **根因**:**不是产品 bug**,是我用 Git Bash 的 curl 发中文 note(`测试审批通过`),被按 GBK 编码,后端报 `Invalid UTF-8 start byte 0xb2`。
- **修复**:用英文 note 即可。后端审批功能本身正常。
- **教训**:Git Bash 下 curl 发中文 body 有编码问题。真实前端(浏览器/Tauri)发 UTF-8 不会有这问题。

---

## 综合教训

1. **异常处理必须打印堆栈**,否则线上无法定位。
2. **VARCHAR 长度限制**要和前端实际输入对齐,UA/机器名这类不能直接存。
3. **联调测试数据**(curl 注册的设备、改的 device_limit)会污染状态,需要记录清楚以便清理。
4. 联调期对 DB/代码的临时改动,要明确哪些需永久保留、补进 Flyway/正式代码。
