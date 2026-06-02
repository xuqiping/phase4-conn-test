# AGENTS.md — Task Routing Table

## Agent: 超级程序员_网络安全与信息安全Agent

本文件是任务路由的唯一真相来源。当用户向本子Agent提出请求时，读取此表以确定加载哪个细粒度工作流。

## Routing Table

| 任务关键词 / 意图 | 工作流文件 | 描述 |
|------------------|-----------|------|
| 网络基础安全,边界防护,防火墙,WAF,渗透测试,PTES,MITRE ATT&CK,DDoS,TCP/IP,协议安全,eBPF,XDP,云原生网络,深度防御,入侵检测,IDS,IPS,红蓝对抗,护网行动,攻击面管理,ASM,威胁情报 | workflow/network_security_workflow.md | 网络基础安全：TCP/IP分层防御、eBPF/XDP云原生网络、WAF六层规则、渗透测试（PTES+ATT&CK+AI驱动）、DDoS分层防御（AI智能清洗） |
| 应用安全,Web安全,SQL注入,XSS,CSRF,SSRF,API安全,OAuth,JWT,微服务鉴权,Istio,代码审计,SAST,DAST,SCA,IAST,ASPM,DevSecOps,供应链安全,SLSA,SBOM,秘密扫描,移动端安全,APP加固,鸿蒙星盾,生物识别,隐私合规,第三方SDK | workflow/application_security_workflow.md | 应用安全：Web漏洞防御（SQL注入五层金字塔/XSS洋葱六层/CSRF三层/SSRF）、API安全（OAuth 2.1/JWT/BOLA/Istio）、代码审计（SAST/DAST/SCA/IAST/ASPM）、移动端（五层加固/鸿蒙星盾/生物识别） |
| 等保,关保,数据安全法,个人信息保护法,PIPL,数据合规,隐私计算,联邦学习,MPC,同态加密,差分隐私,TEE,数据脱敏,数据分类分级,数据跨境,安全评估,标准合同,保护认证,SABSA,零信任,SASE,IAM,XDR,SOC,AI安全,AI-BOM,国密,SM2,SM3,SM4,DSMM | workflow/security_compliance_workflow.md | 企业安全合规：等保2.0/关保测评、数据安全法/PIPL合规、隐私计算五大技术（MPC/FL/HE/DP/TEE）、政企架构（SABSA/零信任/SOC/AI安全五层防护） |
| SIEM,安全日志,日志分析,UEBA,威胁狩猎,入侵检测,IDS,IPS,应急响应,事件响应,NIST,勒索软件,备份恢复,不可变备份,WORM,SOAR,安全运营,SOC,MTTD,MTTR,黄金30分钟,应急演练,BAS,红蓝对抗,AI驱动SOC,Agentic AI,安全人员培训 | workflow/security_operations_workflow.md | 安全运维与应急响应：SIEM六层漏斗（三阶段成熟度）、IDS/IPS分层部署+AI级联、NIST六阶段应急处置（勒索专项/黄金30分钟/不可变备份）、SOC运营（SOAR/KPI/AI驱动/人员能力） |

## Notes

- 本子Agent处理所有与网络安全、信息安全、应用安全、合规、隐私计算、安全运维、应急响应相关的请求。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增能力时在此表添加新行，并创建对应 workflow + checklist 文件对。

## Cross-Module Dependencies

- `network_security_workflow.md` Step 2 中的云原生网络可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 容器技术 > Kubernetes]（K8s网络策略+Cilium+eBPF）
- `application_security_workflow.md` Step 2 中的DevSecOps CI/CD可能引用 [参考: Agents知识库/0_超级编程行业知识库/04_前端开发与用户交互.md > 云原生生态 > CI/CD]（Jenkins/GitLab CI/GitHub Actions嵌入安全扫描）
- `security_compliance_workflow.md` Step 2 中的数据分类分级可能引用 [参考: Agents知识库/0_超级编程行业知识库/06_大数据处理与BI.md > 数据治理]（数据标准/元数据管理/DataHub）
- `security_compliance_workflow.md` Step 2 中的隐私计算联邦学习可能引用 [参考: Agents知识库/0_超级编程行业知识库/05_人工智能与机器学习.md > 基础AI理论]（ML模型训练/评估/安全）
- `security_operations_workflow.md` Step 2 中的SIEM存储层可能引用 [参考: Agents知识库/0_超级编程行业知识库/03_数据库与数据存储.md > 时序数据库]（ClickHouse/Elasticsearch时序日志存储）
