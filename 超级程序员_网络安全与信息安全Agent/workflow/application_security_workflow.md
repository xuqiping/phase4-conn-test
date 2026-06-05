# Application Security Workflow

## Purpose

基于应用安全知识体系，为用户提供Web漏洞防御（SQL注入/XSS/CSRF/SSRF）、API安全（OAuth 2.1/JWT/微服务鉴权）、代码安全审计（SAST/DAST/SCA/IAST/ASPM）、移动端APP安全（五层加固/鸿蒙星盾/生物识别）等技术方案支持。覆盖GPT-5生成变种SQL注入、AI生成代码漏洞率25-30%、LLM Agent Text-to-SQL链式漏洞、OWASP API Security BOLA首位、鸿蒙星盾"管数据非管权限"、SLSA L3 Hermetic Build等2026核心趋势。

## Prerequisites

- 用户已明确应用安全场景或问题
- 知识库文件 `07_网络安全与信息安全.md` 及子目录文件可访问

## Steps

### Step 1: 识别应用安全需求场景

**Goal**: 明确用户的应用安全需求类型、应用形态和开发生命周期阶段
**Completion criterion**: 已确定场景标签、应用类型、技术栈和安全成熟度

1. 读取用户消息，提取以下信息：
   - 场景类型：Web应用防护 / API安全 / 代码安全审计 / 移动端安全 / DevSecOps / 供应链安全 / AI生成代码安全
   - 应用类型：Web应用（前端+后端） / API服务（REST/gRPC/GraphQL） / 移动APP（iOS/Android/鸿蒙） / 小程序 / 桌面应用
   - 技术栈：开发语言（Java/Go/Python/Node.js/PHP）、框架（Spring/Django/Express/Laravel）、数据库（MySQL/PostgreSQL/MongoDB/Redis）、前端（React/Vue/Angular）
   - 安全成熟度：无安全流程 / 基础安全测试（手工渗透） / DevSecOps集成（CI/CD嵌入安全扫描） / ASPM平台（统一聚合+AI修复）
   - 关键痛点：漏洞发现滞后（上线后才发现） / 误报率高（SAST/DAST噪音） / 修复慢（开发排期长） / 第三方组件漏洞多 / AI生成代码安全问题 / 移动端逆向风险
   - 合规要求：等保应用安全要求 / 金融行业APP安全规范 / 数据安全法 / 个人信息保护法

2. 对照知识库中的核心趋势初步判断：
   - Web应用漏洞频发 → SQL注入五层金字塔+XSS洋葱六层+CSRF三层组合
   - API暴露面大 → WAAP新品类+OAuth 2.1 PKCE+BOLA对象级授权
   - 代码漏洞管理 → SAST门禁+SCA SBOM+IAST精准检测+ASPM统一平台
   - 移动端APP → 五层加固+鸿蒙星盾架构+生物识别硬件隔离
   - AI生成代码 → 视为"第三方代码"做完整安全评估+ASPM聚合

3. 如有信息缺失，向用户追问不超过2个澄清问题。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 应用安全]
- [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 各L2摘要 > 应用安全]

### Step 2: 输出应用安全方案

**Goal**: 产出针对性的应用安全防御、代码审计或移动端防护方案
**Completion criterion**: 输出包含漏洞防御策略、工具选型、DevSecOps流程、合规要点

根据Step 1确定的场景，按以下分支处理：

**分支A — Web漏洞防御**：
1. 输出SQL注入防御金字塔五层：
   - L1 编码规范：参数化查询（Prepared Statement）强制使用、ORM框架（MyBatis参数绑定、JPA/Hibernate）、输入长度限制、特殊字符转义
   - L2 框架防御：ORM框架内置防护（Django ORM自动转义、Spring Data JPA参数化）、框架级SQL注入拦截
   - L3 WAF过滤：SQL注入签名规则、语义分析（语法树检测UNION/SELECT/INSERT）、AI模型识别变形注入（编码绕过/注释混淆/十六进制）
   - L4 数据库防火墙：SQL语句审计+拦截、危险操作阻断（DROP/DELETE无WHERE）、行为基线（异常查询模式检测）
   - L5 SIEM运营：SQL执行日志集中分析、异常告警（高频查询/夜间批量操作/敏感表访问）、溯源分析
   - 2025新变化：GPT-5生成变种SQL注入致银行2亿用户数据泄露（绕过传统签名检测），防御需语义分析+AI模型层
   - LLM Agent Text-to-SQL链式漏洞：Prompt注入→LLM生成恶意SQL→数据库泄露，防御需输入过滤+输出校验+数据库权限最小化
2. 给出XSS防御洋葱模型六层：
   - L1 CSP（内容安全策略）：`script-src 'self'`限制脚本来源、`frame-ancestors 'none'`防点击劫持、`base-uri 'self'`防基标签攻击
   - L2 WAF过滤：XSS签名规则（<script>标签/事件处理器/onerror等）、语义分析（HTML上下文识别）
   - L3 输入校验：白名单校验（只允许预期字符集）、长度限制、类型校验（URL/邮箱/日期格式）
   - L4 存储编码：数据库/缓存存储前HTML实体编码（< → &lt;、> → &gt;）
   - L5 输出编码：渲染上下文感知编码（HTML上下文用HTML实体、JS上下文用JS编码、URL上下文用URL编码）
   - L6 Trusted Types：强制要求所有DOM插入操作通过策略审核，防止innerHTML/outerHTML等危险API直接注入
3. 输出CSRF防御三层组合：
   - SameSite Cookie：`SameSite=Strict`（完全禁止第三方Cookie）/ `SameSite=Lax`（GET请求允许/POST禁止）/ 配合`Secure`和`HttpOnly`
   - CSRF Token：服务端生成随机Token→嵌入表单/Header→提交时校验→Token一次性使用/会话绑定
   - Origin/Referer校验：校验请求来源（`Origin` Header或`Referer`），拒绝跨站请求
   - 双重Cookie提交：Cookie中存放Token+请求参数/Header中提交相同Token，服务端比对
4. 附SSRF防御（2025新兴重点）：
   - 问题：云元数据API攻击（访问`http://169.254.169.254/latest/meta-data/`获取IAM凭证）、LLM Agent工具调用（LLM被诱导访问内网服务）
   - 防御：URL白名单（只允许预期域名/IP）、DNS解析后二次校验IP（防止DNS Rebind）、禁用非HTTP协议（file:///gopher://）、内网IP黑名单（10.x/172.16.x/192.168.x/169.254.x）、响应长度限制（防止数据外泄）
   - OWASP 2025：SSRF独立为新类别，权限失效跃居首位

**分支B — API安全**：
1. 输出API安全架构：
   - 认证层：OAuth 2.1（强制PKCE、废弃Implicit Grant、Refresh Token轮换）、JWT（禁用`alg=none`、短TTL≤1小时、JWKS密钥轮换、签名算法RS256/ES256）、mTLS（客户端证书认证、双向TLS）
   - 鉴权层：RBAC（基于角色）、ABAC（基于属性/动态上下文）、ReBAC（基于关系、如Google Zanzibar）、BOLA（对象级授权失效——每次资源操作校验`owner_id`，OWASP API Security Top 10首位）
   - 微服务鉴权（Istio三层安全）：mTLS STRICT（服务间自动加密通信）、JWT（终端用户身份）、AuthorizationPolicy（基于身份/路径/方法的细粒度授权）
   - 某金融科技案例：Istio零信任全网落地，mTLS覆盖率100%，安全事件同比下降90%
2. 给出API威胁防御：
   - 速率限制：Token Bucket/Sliding Window（按用户/API Key/IP限制）、分层限速（全局/用户/端点三级）
   - 输入验证：JSON Schema校验（类型/长度/格式/枚举值）、参数化查询（防止NoSQL注入）、文件上传限制（类型/大小/内容检测）
   - 敏感数据：响应脱敏（PII字段 masking）、传输加密（TLS 1.3）、存储加密（AES-256-GCM）、日志脱敏（敏感字段替换为***）
   - API版本管理：版本号URL（/v1/ /v2/）、弃用计划（提前6个月通知）、向后兼容策略
   - WAAP（Web Application and API Protection）：API专用WAF、Bot管理（API滥用检测）、行为分析（异常调用模式）
   - Gartner预测：2025年WAAP市场规模68亿美元
3. 输出API安全测试：
   - 自动化扫描：OWASP ZAP API扫描、Burp Suite API测试、Postman Collection+安全测试脚本
   - BOLA测试：遍历资源ID（如`GET /api/users/1`→`GET /api/users/2`），验证跨用户数据访问
   - 模糊测试：API Fuzzing（随机/边界/畸形输入）、参数污染（同名参数多次提交）、HTTP方法覆盖（GET/POST/PUT/DELETE/PATCH越权）
   - 契约测试：OpenAPI/Swagger规范→自动校验请求/响应格式、数据类型、必填字段
4. 附API安全监控：API调用日志（请求/响应/延迟/错误码/用户身份）、异常检测（调用频率突增/异常时间/异常来源IP）、熔断降级（错误率>阈值→自动熔断）。

**分支C — 代码安全审计**：
1. 输出安全测试工具链：
   - SAST（静态应用安全测试）：SonarQube（规则丰富、IDE集成、PR门禁）、Semgrep（轻量、自定义规则、快速）、Checkmarx（企业级、多语言、深度分析）、CodeQL（GitHub原生、语义分析、Taint Tracking）
   - DAST（动态应用安全测试）：OWASP ZAP（开源、自动化扫描）、Burp Suite（专业渗透、手工+自动化）、Invicti（企业级、精准、低误报）
   - SCA（软件成分分析）：Snyk（漏洞数据库大、修复建议详细）、JFrog Xray（与Artifact仓库集成）、OWASP Dependency-Check（开源、基础）、Black Duck（企业级、许可证合规）
   - IAST（交互式应用安全测试）：Contrast Security（运行时检测、零误报、98%检测率）、Seeker（Synopsys、精准漏洞定位）、适合DevOps流水线
   - 选型矩阵：快速上线→SAST+SCA；深度安全→SAST+DAST+IAST+SCA；预算有限→SonarQube+ZAP+Dependency-Check
2. 给出DevSecOps流程：
   - IDE阶段：IDE插件实时检测（SonarLint/Snyk IDE）、编码规范提示（安全规则实时标注）、秘密扫描预防（硬编码密钥/API Token检测）
   - PR阶段：SAST门禁（高危漏洞阻断合并、中危警告）、SCA依赖扫描（新引入组件漏洞检查）、秘密扫描拦截（Push Protection，提交前检测敏感信息）
   - CI阶段：DAST自动化扫描（部署到测试环境后自动扫描）、IAST运行时检测（测试用例触发漏洞检测）、容器安全扫描（镜像漏洞/配置错误/恶意软件）
   - CD阶段：签名验证（镜像签名/代码签名）、部署前安全审批（变更影响评估+安全测试报告）、金丝雀发布（灰度验证+安全监控）
   - 运营阶段：RASP（运行时自保护）、漏洞管理闭环（发现→评估→修复→验证→关闭）、ASPM平台统一聚合
3. 输出ASPM（应用安全态势管理）平台：
   - 核心功能：多源数据聚合（SAST/DAST/SCA/IAST/秘密扫描/容器扫描）、统一去重（同一漏洞多工具报告合并）、三维优先级排序（可达性+业务影响+利用成熟度）、AI辅助修复（自动生成修复代码/修复建议）
   - 某互联网公司案例：汇聚SonarQube(SAST)+Invicti(DAST)+Snyk(SCA)+truffleHog(秘密扫描)四源数据，6个月内高危漏洞存量下降60%，中位修复时间从90分钟降至28分钟
   - AI生成代码安全：AI生成代码必须视为"第三方代码"做完整安全评估（SAST+SCA+IAST），不能因"AI写的"而降低安全标准
4. 附软件供应链安全（SLSA框架）：
   - SLSA L1：Provenance（来源可追溯，构建过程记录）
   - SLSA L2：Signed Provenance（数字签名，防篡改）
   - SLSA L3：Hermetic Build（构建环境完全隔离，无外部网络访问）、Reproducible Build（可重复构建，相同输入→相同输出）
   - SBOM：CycloneDX/SPDX格式，组件清单+版本+许可证+漏洞状态，成为供应链漏洞管理标配
   - 秘密扫描四层防御：IDE预防→Push Protection拦截→历史检测→Vault集中管理（HashiCorp Vault/AWS Secrets Manager/Azure Key Vault）

**分支D — 移动端APP安全**：
1. 输出APP安全全生命周期：
   - 开发阶段：安全编码规范（输入校验/输出编码/加密使用）、依赖安全（SCA扫描第三方SDK/库）、权限最小化（仅申请必要权限）、代码混淆（ProGuard/R8混淆、控制流平坦化）
   - 打包阶段：DEX加密（类加载时动态解密）、资源加密（图片/配置文件加密存储）、签名校验（防止重打包）、防二次打包（签名校验+完整性校验）
   - 运行阶段：反调试（检测Debugger附加）、反Hook（检测Frida/Xposed等Hook框架）、反模拟器（检测模拟器环境）、反ROOT/越狱（检测设备是否ROOT）、证书固定（SSL Pinning防中间人攻击）
   - 通信阶段：TLS 1.3（强制加密通信）、证书固定（内置服务端证书公钥）、双向TLS（高安全场景）、请求签名（防重放攻击/篡改）
   - 监控阶段：异常上报（崩溃/安全事件/可疑行为）、运行时自我保护（RASP for Mobile）、威胁情报（已知恶意样本特征匹配）
2. 给出金融APP"五层加固"：
   - L1 编译期：R8混淆（代码混淆+压缩）、字符串加密、控制流平坦化、反编译难度提升
   - L2 打包期：DEX加密（运行时解密加载）、SO库加密（Native代码保护）、资源文件加密
   - L3 运行期：反调试（ptrace检测）、反Hook（Frida/Xposed检测）、反模拟器（qemu检测）、ROOT检测（Su文件检测）、签名校验
   - L4 通信期：证书固定（SSL Pinning）、双向TLS（mTLS）、请求签名（HMAC-SHA256）、防重放（时间戳+Nonce）
   - L5 监控期：异常行为上报（Root/调试/Hook检测触发上报）、威胁情报匹配、动态风险评分
   - 效果：逆向难度提升约300%
3. 输出鸿蒙星盾架构革新：
   - 核心理念："管数据非管权限"——应用通过系统安全Picker访问特定数据（如选择一张照片），无法直接读取原始数据，系统返回的是裁剪/压缩后的数据
   - 隐私沙箱：每个应用运行在独立沙箱中，文件系统隔离、网络隔离、进程隔离
   - 分布式安全：跨设备数据流转时端到端加密（基于设备互信链）、最小权限原则
   - 与Android/iOS对比：Android权限列表长且用户难理解、iOS权限较细但仍有数据泄露风险，鸿蒙通过系统级数据管控实现更细粒度隐私保护
4. 附生物识别与隐私：
   - 核心原则："模板永不离开硬件"——指纹/人脸模板存储在Secure Enclave/TEE中，应用只能获取匹配结果（是/否），无法获取原始模板
   - FIDO2/WebAuthn：无密码认证、公私钥对存储在硬件安全模块、防钓鱼（绑定域名）
   - 第三方SDK风险：引入前必须完成网络行为分析（抓包分析数据传输）+ 漏洞扫描（已知CVE）+ 隐私合规审查（收集数据范围/目的/去向），2026年梆梆安全报告：超八成APP存在隐私违规
   - 合规：个人信息保护法最小必要原则、APP隐私合规检测（工信部/网信办通报机制）、隐私政策透明（收集范围/使用目的/共享方/用户权利）

将结果保存到 `output/application_security.md` 或直接在对话中呈现。

**Knowledge Base Reference**:
- [参考: Agents知识库/0_超级编程行业知识库/07_网络安全与信息安全.md > 各L2摘要 > 应用安全]
- [参考: Agents知识库/0_超级编程行业知识库/网络安全与信息安全/应用安全.md]

### Step 3: 验证与交付

**Goal**: 确保应用安全方案覆盖全生命周期、与知识库一致
**Completion criterion**: 已通过 checklist 逐项核对

1. 读取 `checklist/application_security_workflow_checklist.md`。
2. 逐项核对输出是否覆盖要求的知识点。
3. 确认漏洞数据（GPT-5 SQL注入/AI代码漏洞率25-30%/OWASP API BOLA首位等）准确。
4. 如有遗漏或偏差，补充修正。
5. 向用户交付最终答案。

## Post-Workflow

1. 记录完成状态到 `task/current_task.md`。
2. 如需深入某一具体技术（如"Istio AuthorizationPolicy ABAC策略配置"、"鸿蒙星盾安全Picker开发集成"），在当前 Agent 内继续追问并输出。
