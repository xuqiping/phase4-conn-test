# Security Development Lifecycle Workflow

## Purpose

基于安全开发生命周期知识框架，提供从威胁建模到供应链安全的标准化执行路径，覆盖OWASP Top 10防御、SAST/DAST/SCA安全扫描、STRIDE威胁建模、漏洞响应SLA、SBOM供应链安全五大核心能力。

## Prerequisites

- 已确定应用类型（Web/移动端/API/云原生/AI应用）
- 已确定合规要求（等保/关基/金融/医疗/跨境）

## Steps

### Step 1: OWASP Top 10防御与安全编码

**Goal**: 依据OWASP Top 10 2025建立安全编码基线，覆盖输入验证、访问控制、加密、配置安全
**Completion criterion**: 安全编码规范已发布，输入验证白名单策略落地，三层权限模型（网关/服务/数据）已实施，加密三层密钥架构已建立

依据OWASP与安全编码框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 安全开发生命周期]：
1. OWASP Top 10 2025（第八版）三大结构性变化：
   - 安全配置错误升至第2（云原生配置项激增）
   - 软件供应链失效成为新增重点（50%受访者首要关注）
   - 新增异常情况处理不当（覆盖24个CWE）
   - 新增X系列：应用弹性不足、内存管理故障
2. 安全编码核心实践：
   - 输入验证：白名单优于黑名单，正则表达式+长度限制+类型校验
   - SQL注入防御：参数化查询（Prepared Statements）是唯一终极防御
   - XSS防御：输出编码按上下文选型（HTML/JS/CSS/URL），CSP策略
   - 文件上传：白名单扩展名 + 大小限制 + 存储隔离 + 重命名
3. 访问控制三层权限模型：
   - 网关层粗粒度：JWT验证 + IP白名单 + 速率限制
   - 服务层中粒度：注解权限校验（RBAC/ABAC）
   - 数据层行级控制：RLS（Row Level Security）/ tenant_id过滤
4. 加密三层密钥架构：
   - 根密钥（Root Key）：HSM/KMS保护，永不离开安全硬件
   - KEK（Key Encryption Key）：季度轮换，加密DEK
   - DEK（Data Encryption Key）：年度轮换，实际加密数据
   - 算法：AES-256-GCM对称加密 / Argon2id密码哈希 / RSA-4096非对称加密
5. 后量子密码：
   - FIPS 203/204/205进入部署阶段
   - ML-KEM（密钥封装）/ ML-DSA（数字签名）/ SLH-DSA（无状态哈希签名）
6. 安全配置四层防御：
   - 代码层：编码规范 + SAST扫描
   - IaC层：Terraform + Checkov静态分析
   - 平台层：CIS Benchmark（容器/K8s/OS）
   - 云层：CSPM（Cloud Security Posture Management）
执行：
- 输出《安全编码规范》：输入验证/输出编码/访问控制/加密/日志/异常处理
- 输出《加密密钥管理策略》：密钥层级 + 生命周期 + 轮换策略 + 应急恢复
- 配置三层权限模型代码示例（Spring Security/Express中间件/Django Decorator）

### Step 2: SAST/DAST/SCA安全扫描矩阵

**Goal**: 建立覆盖SAST/DAST/SCA/IAST/RASP的五维安全扫描体系
**Completion criterion**: 安全扫描矩阵已配置，零预算起步方案已实施，高危漏洞CI阻断，扫描结果与Jira/SonarQube集成

依据安全扫描框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > SAST/DAST/SCA安全扫描]：
1. 五维扫描矩阵：
   - SAST（静态应用安全测试）：源码扫描，Semgrep/CodeQL为主力
   - DAST（动态应用安全测试）：运行时扫描，OWASP ZAP为核心
   - SCA（软件成分分析）：依赖分析，Reachability Analysis告警减少80%+
   - IAST（交互式应用安全测试）：运行时插桩，高准确率低误报
   - RASP（运行时应用自我保护）：应用内防护，0Day防御
2. 工具选型：
   - SAST：Semgrep（轻量/规则可定制）/ CodeQL（GitHub原生/深度分析）/ SonarQube Security
   - DAST：OWASP ZAP（开源/CI集成）/ Burp Suite Enterprise（商业）
   - SCA：Snyk（漏洞+License）/ Dependabot（GitHub原生免费）/ OWASP Dependency-Check
   - IAST：Contrast Security / Seeker / 自研探针
   - RASP：Signal Sciences / Imperva / 自研
3. CI/CD集成：
   - L1 PR阶段：Semgrep快速扫描（<2分钟）+ Dependabot Alert
   - L2 合并阶段：SonarQube全量扫描 + CodeQL分析
   - L3 Nightly：OWASP ZAP DAST扫描 + 容器镜像扫描（Trivy）
   - L4 发布前：渗透测试 + 人工代码审计
4. 零预算起步方案：
   - GitHub原生安全：Dependabot + CodeQL + Secret Scanning（全部免费）
   - OWASP ZAP Baseline：Docker 5分钟启动
   - Trivy镜像扫描：开源免费
执行：
- 输出《安全扫描矩阵配置手册》：每种扫描的触发时机/扫描范围/告警阈值/修复SLA
- 配置CI Pipeline安全门禁：Critical漏洞阻断合并，High漏洞需审批
- 配置漏洞与Jira集成：自动创建Ticket/分配Owner/跟踪修复进度

### Step 3: STRIDE威胁建模与安全评审

**Goal**: 建立STRIDE威胁建模流程，覆盖系统设计到AI应用的威胁识别
**Completion criterion**: STRIDE威胁建模已执行，DFD数据流图已绘制，DREAD风险评级>7分的威胁已制定缓解措施

依据威胁建模框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > STRIDE威胁建模]：
1. STRIDE六类威胁：
   - S（Spoofing）：身份欺骗/伪造Token/Session劫持
   - T（Tampering）：数据篡改/请求篡改/配置篡改
   - R（Repudiation）：否认操作/日志缺失/审计不足
   - I（Information Disclosure）：信息泄露/越权访问/日志暴露敏感数据
   - D（Denial of Service）：资源耗尽/限流绕过/慢速攻击
   - E（Elevation of Privilege）：权限提升/垂直越权/水平越权
2. DREAD风险评级：
   - D（Damage）：损害程度（0-10）
   - R（Reproducibility）：复现难度（0-10）
   - E（Exploitability）：利用难度（0-10）
   - A（Affected Users）：影响用户数（0-10）
   - D（Discoverability）：发现难度（0-10）
   - 总分 = 平均分，>7分需缓解，>8分必须立即修复
3. DFD数据流图分层：
   - L0系统级：系统与外部实体的交互
   - L1子系统级：主要子系统之间的数据流
   - L2组件级：具体组件/服务/数据库之间的数据流
   - 信任边界：用不同颜色标注（外部不可信/内部可信/高敏区域）
4. 云原生与AI威胁建模新增：
   - 容器逃逸：特权容器/挂载宿主机目录/内核漏洞
   - Sidecar劫持：服务网格Sidecar配置错误/证书泄露
   - AI数据投毒：训练数据污染/后门注入
   - 提示注入：LLM Prompt绕过安全限制
   - 模型窃取：API逆向工程/模型提取攻击
执行：
- 输出《威胁建模报告》：STRIDE分析表（威胁+风险等级+缓解措施+Owner）
- 绘制DFD数据流图（L0/L1/L2）
- 输出《安全评审检查单》：设计评审必查项（输入验证/访问控制/加密/日志/异常处理）

### Step 4: 漏洞响应SLA与应急管理

**Goal**: 建立漏洞分级响应体系，实现从发现到修复的全流程跟踪
**Completion criterion**: 漏洞分级标准已发布，SLA达标率≥90%，零日应急虚拟补丁方案就绪，漏洞响应Playbook已建立

依据漏洞响应框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > 漏洞响应SLA]：
1. 漏洞分级多维融合：
   - CVSS v4.0：技术评分（基础分0-10）
   - EPSS v4：利用概率（0-1，预测未来30天被利用的概率）
   - KEV（Known Exploited Vulnerabilities）：CISA确认在野利用
   - 资产关键性：核心资产×高影响 vs 边缘资产×低影响
   - 综合评分 = CVSS × EPSS × 资产关键性 × KEV系数
2. SLA标准四级：
   - Critical（CVSS 9.0-10.0）：72小时内修复或缓解
   - High（CVSS 7.0-8.9）：7工作日内修复
   - Medium（CVSS 4.0-6.9）：30天内修复
   - Low（CVSS 0.1-3.9）：90天内修复或接受风险
3. 零日应急响应：
   - 虚拟补丁（Virtual Patch）：WAF规则 / eBPF程序 / RASP策略
   - 在官方补丁发布前阻断攻击路径
   - 监控利用尝试：IDS/IPS规则更新 + 威胁情报订阅
4. 漏洞响应Playbook：
   - 发现：安全扫描/威胁情报/白帽报告/监管通报
   - 评估：复现漏洞 → 评级 → 影响分析
   - 修复：开发修复 → 测试验证 → 发布部署
   - 验证：复测确认修复 → 关闭Ticket
   - 通报：内部通报（受影响团队）/ 外部披露（如必要）
执行：
- 输出《漏洞响应政策》：分级标准/SLA/责任人/升级路径
- 配置漏洞管理平台：Snyk/Qualys/自研，集成CI/CD和Jira
- 建立虚拟补丁库：常见漏洞类型的WAF规则/eBPF程序模板
- 输出《零日应急响应手册》：发现→评估→虚拟补丁→修复→验证→通报

### Step 5: SBOM供应链安全管理

**Goal**: 建立SBOM全生命周期管理体系，实现供应链安全的动态治理
**Completion criterion**: 每个构建自动生成SBOM（SPDX/CycloneDX），与NVD/OSV实时关联，SLSA框架Level 3实施，Sigstore签名已配置

依据供应链安全框架 [参考: Agents知识库/0_超级编程行业知识库/14_软件工程与开发全流程.md > SBOM供应链安全]：
1. SBOM管理：
   - 生成：CI/CD每次构建自动生成（Syft/Trivy/CycloneDX CLI）
   - 格式：SPDX（Linux基金会）/ CycloneDX（OWASP）
   - 存储：与制品同仓库，版本关联
   - 关联：与NVD/OSV/EPSS实时关联，自动告警新漏洞
2. SLSA框架（Supply-chain Levels for Software Artifacts）：
   - Level 1：文档化构建过程
   - Level 2：签名构建产物，使用版本控制
   - Level 3：强化构建平台，防止篡改
   - Level 4：双方复核，最高完整性
   - 目标：至少达到Level 3
3. 供应链安全数据：
   - 现代软件70-90%代码来自开源组件
   - 2025年投毒包突破59,000个（NPM占92%）
   - 防御：私有镜像仓库/签名验证/依赖锁定（package-lock.json/yarn.lock）
4. Sigstore无密钥签名：
   - 被npm/PyPI/容器Registry广泛采用
   - 消除GPG密钥管理负担
   - 支持rekor透明日志，可审计
5. 依赖安全实践：
   - 依赖最小化：只引入必要的依赖
   - 版本锁定：package-lock.json + Dependabot更新PR
   - 私有Registry：Nexus/Artifactory/自研，管控外部依赖引入
   - 定期审计：每月执行依赖安全扫描，更新过期组件
执行：
- 配置CI自动生成SBOM：Syft生成 → 关联NVD → 上传制品库
- 配置Sigstore签名：cosign sign/verify容器镜像
- 输出《供应链安全管理手册》：SBOM生成/存储/关联/审计 + SLSA实施路径 + 依赖管控

## Post-Workflow

1. 读取 `checklist/security_lifecycle_workflow_checklist.md`
2. 交叉验证每个威胁缓解措施、每个漏洞修复记录、每个SBOM版本
3. 全部通过后输出《安全开发生命周期总纲》并归档
