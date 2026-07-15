# File Keeper Windows Server 2019 傻瓜式部署指南

> 本文档面向不熟悉 Linux 的 Windows 运维人员，手把手介绍在 Windows Server 2019 上部署 File Keeper 后端与管理后台。
>
> 版本：2026-06-19

---

## 一、准备工作

### 1.1 你需要的东西

| 组件 | 版本要求 | 用途 |
|---|---|---|
| JDK | 17 | 运行 Spring Boot 后端 |
| PostgreSQL | 14+ | 业务数据库 |
| Redis / Memurai | 5+ | 缓存、JWT 黑名单、频率限制 |
| Node.js | 18+ | 构建前端 |
| Maven | 3.9+ | 构建后端 |
| Nginx / IIS | 任意 | 静态文件服务、反向代理 |

### 1.2 推荐服务器配置

- CPU：4 核+
- 内存：8 GB+
- 磁盘：50 GB+
- 系统：Windows Server 2019 Standard / Datacenter

### 1.3 本机已部署环境参考

如果你在本机（开发机）做验证，可直接使用以下已安装的环境，无需重新下载安装：

| 组件 | 本机状态 | 本机路径 |
|---|---|---|
| JDK 17 | 已安装 | `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` |
| Maven 3.9.11 | 已安装 + 有 zip 包 | `C:\Users\19536\.local\tools\apache-maven-3.9.11` |
| PostgreSQL 17.4 便携版 | 已存在 | `C:\Users\19536\.local\tools\pgsql` |
| Node.js 24.11.1 | 已安装 | `C:\Program Files\nodejs` |
| Memurai（Redis 兼容） | 已安装 | `C:\Program Files\Memurai` |

> 说明：
> - 本机已有环境可用于本地开发/测试部署，不一定要重新下载安装程序。
> - 如果要部署到另一台 Windows Server 2019，需要把便携版 PostgreSQL / Memurai 等复制过去，或下载对应安装包。
> - 本机缺少独立安装包：PostgreSQL MSI、Node.js MSI、Memurai MSI、Nginx zip、WinSW。

---

## 二、安装环境

### 2.1 安装 JDK 17

1. 下载 Eclipse Temurin JDK 17 MSI：
   ```
   https://adoptium.net/temurin/releases/?version=17
   ```
2. 双击安装，记住安装路径，例如：
   ```
   C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
   ```
3. 设置环境变量：
   - 右键“此电脑” → 属性 → 高级系统设置 → 环境变量
   - 新建系统变量 `JAVA_HOME`：
     ```
     C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
     ```
   - 编辑系统变量 `Path`，新增：
     ```
     %JAVA_HOME%\bin
     ```
4. 打开 CMD 验证：
   ```cmd
   java -version
   ```
   应显示 `openjdk version "17"`。

### 2.2 安装 Maven

1. 下载 Maven 3.9.x 二进制 zip：
   ```
   https://maven.apache.org/download.cgi
   ```
2. 解压到：
   ```
   C:\apache-maven-3.9.11
   ```
3. 设置环境变量：
   - 新建系统变量 `MAVEN_HOME`：
     ```
     C:\apache-maven-3.9.11
     ```
   - 编辑 `Path`，新增：
     ```
     %MAVEN_HOME%\bin
     ```
4. 验证：
   ```cmd
   mvn -v
   ```

### 2.3 安装 PostgreSQL

#### 方案 A：使用官方安装包（推荐）

1. 下载 PostgreSQL 17 Windows installer：
   ```
   https://www.postgresql.org/download/windows/
   ```
2. 双击安装，记住：
   - 安装路径：`C:\Program Files\PostgreSQL\17`
   - 数据目录：`C:\Program Files\PostgreSQL\17\data`
   - 超级用户密码：`YourPostgresPassword`
   - 端口：`5432`
3. 安装完成后，打开 **pgAdmin 4** 或 **psql** 创建数据库：
   ```sql
   CREATE DATABASE file_keeper OWNER postgres ENCODING 'UTF8';
   ```

#### 方案 B：使用便携版

如果只需要简单部署，可使用项目中的便携版 PostgreSQL：

1. 将 PostgreSQL 便携目录复制到服务器：
   ```
   C:\pgsql
   ```
2. 初始化数据目录（仅需一次）：
   ```cmd
   C:\pgsql\bin\initdb.exe -D C:\pgdata -U postgres -E UTF8
   ```
3. 启动数据库：
   ```cmd
   C:\pgsql\bin\pg_ctl.exe -D C:\pgdata -l C:\pgdata\postgresql.log start
   ```

### 2.4 安装 Redis（Memurai 推荐）

Windows 原生 Redis 已停止维护，推荐使用 Memurai（Redis 兼容）：

1. 下载 Memurai Developer：
   ```
   https://www.memurai.com/
   ```
2. 安装为 Windows 服务，默认端口 `6379`
3. 打开服务管理器，确认 `Memurai` 服务正在运行

或使用 Microsoft 维护的 Redis Windows 版：
```
https://github.com/microsoftarchive/redis/releases
```

### 2.5 安装 Node.js

1. 下载 Node.js 18 LTS MSI：
   ```
   https://nodejs.org/en/download/
   ```
2. 双击安装
3. 验证：
   ```cmd
   node -v
   npm -v
   ```

---

## 三、部署后端

### 3.1 复制代码

将 `file-keeper\server` 目录复制到服务器：
```
C:\file-keeper\server
```

### 3.2 配置环境变量

打开“环境变量”设置，新增以下系统变量：

```
FILE_KEEPER_DB_URL=jdbc:postgresql://localhost:5432/file_keeper
FILE_KEEPER_DB_USERNAME=postgres
FILE_KEEPER_DB_PASSWORD=YourPostgresPassword
FILE_KEEPER_REDIS_HOST=localhost
FILE_KEEPER_REDIS_PORT=6379
FILE_KEEPER_JWT_SECRET=your-very-long-random-secret-at-least-32-bytes
FILE_KEEPER_SUPER_ADMIN_EMAIL=admin@yourcompany.com
FILE_KEEPER_SUPER_ADMIN_PASSWORD=YourSecureAdminPassword
FILE_KEEPER_VERIFICATION_DEV_FIXED_CODE=        # 生产环境留空
```

> ⚠️ 重要：
> - `FILE_KEEPER_JWT_SECRET` 必须至少 32 字节随机字符串
> - 生产环境不要使用 `FILE_KEEPER_VERIFICATION_DEV_FIXED_CODE`
> - 修改后需要重启 CMD 窗口或服务器才能生效

### 3.3 构建后端

打开 CMD，进入后端目录：
```cmd
cd C:\file-keeper\server
mvn clean package -DskipTests
```

构建成功后，会在 `target` 目录生成：
```
file-keeper-server-0.1.0-SNAPSHOT.jar
```

### 3.4 启动后端

#### 开发/测试方式：直接运行

```cmd
cd C:\file-keeper\server
mvn spring-boot:run
```

#### 生产方式：作为 Windows 服务运行

1. 下载 **Winsw**：
   ```
   https://github.com/winsw/winsw/releases
   ```
2. 将 `WinSW-x64.exe` 重命名为 `file-keeper-server.exe`
3. 创建同名配置文件 `file-keeper-server.xml`：
   ```xml
   <service>
     <id>file-keeper-server</id>
     <name>File Keeper Server</name>
     <description>File Keeper 后端服务</description>
     <env name="FILE_KEEPER_DB_URL" value="jdbc:postgresql://localhost:5432/file_keeper" />
     <env name="FILE_KEEPER_DB_USERNAME" value="postgres" />
     <env name="FILE_KEEPER_DB_PASSWORD" value="YourPostgresPassword" />
     <env name="FILE_KEEPER_REDIS_HOST" value="localhost" />
     <env name="FILE_KEEPER_REDIS_PORT" value="6379" />
     <env name="FILE_KEEPER_JWT_SECRET" value="your-very-long-random-secret-at-least-32-bytes" />
     <env name="FILE_KEEPER_SUPER_ADMIN_EMAIL" value="admin@yourcompany.com" />
     <env name="FILE_KEEPER_SUPER_ADMIN_PASSWORD" value="YourSecureAdminPassword" />
     <env name="FILE_KEEPER_VERIFICATION_DEV_FIXED_CODE" value="" />
     <exeutable>java</exeutable>
     <arguments>-jar "C:\file-keeper\server\target\file-keeper-server-0.1.0-SNAPSHOT.jar"</arguments>
     <logpath>C:\file-keeper\server\logs</logpath>
     <log mode="roll-by-time">
       <pattern>yyyy-MM-dd</pattern>
     </log>
   </service>
   ```
4. 安装并启动服务：
   ```cmd
   cd C:\file-keeper\server
   file-keeper-server.exe install
   file-keeper-server.exe start
   ```
5. 验证服务状态：
   ```cmd
   file-keeper-server.exe status
   ```

### 3.5 验证后端

打开浏览器访问：
```
http://服务器IP:8088/api/admin/auth/login
```

应返回 401（未提供凭证），说明服务已启动。

---

## 四、部署管理后台

### 4.1 复制代码

将 `file-keeper\admin-web` 目录复制到服务器：
```
C:\file-keeper\admin-web
```

### 4.2 安装依赖

```cmd
cd C:\file-keeper\admin-web
npm install
```

### 4.3 配置 API 地址

创建或修改 `.env.production`：
```
VITE_API_BASE_URL=http://服务器IP:8088
```

### 4.4 构建

```cmd
cd C:\file-keeper\admin-web
npm run build
```

构建成功后生成 `dist` 目录。

### 4.5 使用 Nginx 部署静态文件

1. 下载 Nginx Windows 版：
   ```
   https://nginx.org/en/download.html
   ```
2. 解压到 `C:\nginx`
3. 修改 `C:\nginx\conf\nginx.conf`：
   ```nginx
   server {
       listen       80;
       server_name  localhost;

       location / {
           root   C:/file-keeper/admin-web/dist;
           index  index.html;
           try_files $uri $uri/ /index.html;
       }

       location /api/ {
           proxy_pass http://localhost:8088/;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       }
   }
   ```
4. 启动 Nginx：
   ```cmd
   cd C:\nginx
   start nginx
   ```
5. 浏览器访问：
   ```
   http://服务器IP
   ```

### 4.6 使用 IIS 部署（可选）

1. 安装 IIS + URL Rewrite 模块
2. 新建网站，物理路径指向 `C:\file-keeper\admin-web\dist`
3. 端口设为 80
4. 添加反向代理规则，将 `/api/*` 转发到 `http://localhost:8088`
5. 安装 `Application Request Routing` 以支持反向代理

---

## 五、部署桌面端（可选）

### 5.1 构建桌面端

在开发机或服务器上：

```cmd
cd C:\file-keeper
npm install
npm run tauri:build
```

构建成功后，安装包位于：
```
src-tauri\target\release\bundle\msi\file-keeper_0.1.0_x64_en-US.msi
```

### 5.2 分发安装

将 MSI 分发给用户安装。桌面端默认连接 `http://localhost:8088`，如需修改，在 `src/api/commercialAuth.ts` 或打包前配置默认 baseUrl。

---

## 六、首次启动检查清单

| 检查项 | 命令/操作 |
|---|---|
| PostgreSQL 运行中 | 服务管理器查看 `postgresql-x64-17` |
| Redis / Memurai 运行中 | 服务管理器查看 `Memurai` |
| 后端服务运行中 | `http://服务器IP:8088/api/admin/auth/login` 返回 401 |
| 管理后台可访问 | `http://服务器IP` 显示登录页 |
| 数据库迁移成功 | 后端日志显示 `Successfully applied 4 migrations` |
| 超管可登录 | 使用 `FILE_KEEPER_SUPER_ADMIN_EMAIL` 和对应密码 |

---

## 七、常见问题

### Q1：后端启动报 `Port 8088 was already in use`

解决：
```cmd
netstat -ano | findstr :8088
taskkill /PID 占用进程PID /F
```

### Q2：管理后台报 `Network error`

检查：
1. `.env.production` 中 `VITE_API_BASE_URL` 是否正确
2. Nginx 反向代理 `/api/` 是否配置正确
3. 后端服务是否启动
4. Windows 防火墙是否放行 8088 端口

### Q3：PostgreSQL 中文乱码

确保：
1. 数据库编码为 UTF8
2. psql 客户端使用 `chcp 65001`

### Q4：如何修改端口

后端：修改 `server/src/main/resources/application.yml` 中 `server.port`
管理后台：修改 Nginx 监听端口

### Q5：如何更新部署

后端：
```cmd
cd C:\file-keeper\server
mvn clean package -DskipTests
file-keeper-server.exe stop
file-keeper-server.exe start
```

管理后台：
```cmd
cd C:\file-keeper\admin-web
npm run build
```

然后刷新 Nginx：
```cmd
cd C:\nginx
nginx -s reload
```

---

## 八、安全建议

1. **数据库密码**：使用强密码，不要与示例相同
2. **JWT Secret**：至少 32 字节随机字符串，生产环境必须更换
3. **防火墙**：仅开放 80/443，后端 8088 不对外暴露
4. **HTTPS**：生产环境使用 SSL 证书，Nginx 配置 443 端口
5. **日志清理**：定期清理 `C:\file-keeper\server\logs` 和 Nginx 日志
6. **备份**：定期备份 PostgreSQL 数据目录

---

## 九、一键启动脚本

创建 `C:\file-keeper\start-all.bat`：

```bat
@echo off
chcp 65001
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set MAVEN_HOME=C:\apache-maven-3.9.11
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%

set FILE_KEEPER_DB_URL=jdbc:postgresql://localhost:5432/file_keeper
set FILE_KEEPER_DB_USERNAME=postgres
set FILE_KEEPER_DB_PASSWORD=YourPostgresPassword
set FILE_KEEPER_REDIS_HOST=localhost
set FILE_KEEPER_REDIS_PORT=6379
set FILE_KEEPER_JWT_SECRET=your-very-long-random-secret-at-least-32-bytes
set FILE_KEEPER_SUPER_ADMIN_EMAIL=admin@yourcompany.com
set FILE_KEEPER_SUPER_ADMIN_PASSWORD=YourSecureAdminPassword

cd /d C:\file-keeper\server
start "File Keeper Server" mvn spring-boot:run

cd /d C:\nginx
start nginx

echo File Keeper 已启动
echo 后端：http://localhost:8088
echo 后台：http://localhost
pause
```

---

## 十、联系与支持

如部署过程中遇到问题，请查看：
- 后端日志：`C:\file-keeper\server\logs`
- Nginx 日志：`C:\nginx\logs`
- 联调文档：`项目相关文档/后台改造计划/`
