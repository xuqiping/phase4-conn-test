# AGENTS.md — Task Routing Table

## Agent: 超级程序员Agent

本文件是任务路由的唯一真相来源。当用户向本Agent提出请求时，读取此表以确定哪个子Agent应处理该请求。

## Routing Table

| 任务关键词 / 意图 | 子Agent目录 | 子Agent技能名 | 描述 |
|------------------|------------|-------------|------|
| Java,Python,Go,后端,前端,JavaScript,TypeScript,Vue,React,Angular,编译原理,算法,数据结构,设计模式,低代码,无代码 | ../超级程序员_编程语言与基础开发Agent | super_programmer_programming_language_agent_skill | 前后端语言体系、编译原理与底层基础、低代码无代码开发 |
| 微服务,分布式,消息队列,MQ,RabbitMQ,RocketMQ,Kafka,Pulsar,Redis,缓存,服务治理,链路追踪,监控,限流,熔断,降级,多租户 | ../超级程序员_后端架构与中间件Agent | super_programmer_backend_architecture_agent_skill | 微服务架构、消息队列、缓存中间件、服务治理与观测 |
| MySQL,PostgreSQL,Oracle,SQL,数据库,MongoDB,Elasticsearch,ES,时序数据库,图数据库,向量数据库,数仓,数据仓库,Hive,Spark,Flink,湖仓一体,分库分表 | ../超级程序员_数据库与数据存储Agent | super_programmer_database_storage_agent_skill | 关系型/NoSQL数据库、数据仓库与湖仓一体、数据库运维调优 |
| 阿里云,腾讯云,华为云,百度云,天翼云,容器,Docker,K8s,Kubernetes,Serverless,CI/CD,GitHub Actions,Argo,GitOps,Istio,服务网格,FinOps,虚拟化,VMware,KVM,OpenStack,私有云,混合云 | ../超级程序员_云计算与云原生Agent | super_programmer_cloud_native_agent_skill | 公有云生态、容器技术、云原生生态、虚拟化与私有云 |
| 机器学习,深度学习,AI,大模型,LLM,Prompt,RAG,Agent,智能体,微调,LoRA,多模态,YOLO,目标检测,图像分割,语音识别,AIGC,计算机视觉 | ../超级程序员_人工智能与大模型Agent | super_programmer_ai_ml_agent_skill | AI理论、大模型技术体系、AI应用落地、计算机视觉与语音 |
| Hadoop,Spark,Flink,大数据,数据治理,数据质量,数据血缘,元数据,BI,商业智能,数据分析,数据仓库,数据湖 | ../超级程序员_大数据技术生态Agent | super_programmer_big_data_agent_skill | 大数据基础组件、数据治理、BI可视化与数据分析 |
| 网络安全,信息安全,渗透测试,WAF,防火墙,DDoS,等保,数据安全,隐私计算,密码学,安全合规,应急响应,漏洞挖掘,Web安全,代码审计 | ../超级程序员_网络安全与信息安全Agent | super_programmer_cybersecurity_agent_skill | 网络基础安全、应用安全、企业安全合规、安全运维与应急响应 |
| 嵌入式,物联网,IoT,单片机,STM32,ESP32,ARM,RTOS,MQTT,CoAP,数字孪生,ROS2,PLC,机械臂,边缘AI,硬件自动化 | ../超级程序员_嵌入式与物联网Agent | super_programmer_embedded_iot_agent_skill | 嵌入式开发、物联网平台、硬件自动化与智能控制 |
| 软件测试,测试用例,自动化测试,接口测试,UI测试,性能测试,压测,DevOps,质量门禁,SonarQube,缺陷管理,测试左移,测试右移 | ../超级程序员_软件测试与质量保障Agent | super_programmer_testing_qa_agent_skill | 功能测试、自动化测试、测试工程体系 |
| 区块链,以太坊,比特币,智能合约,Solidity,DeFi,联盟链,Hyperledger,长安链,蚂蚁链,元宇宙,NFT,Web3,数字身份 | ../超级程序员_区块链与Web3Agent | super_programmer_blockchain_web3_agent_skill | 区块链基础、联盟链与国产链、元宇宙与数字身份 |
| Linux,运维,SRE,DevOps,Shell,Python脚本,性能调优,机房,组网,SD-WAN,VPN,Windows,AD,域控,桌面运维 | ../超级程序员_运维工程与系统架构Agent | super_programmer_devops_sysadmin_agent_skill | Linux运维、机房与组网架构、Windows系统运维 |
| 项目管理,敏捷,Scrum,瀑布,PMP,PMBOK,团队管理,研发流程,技术梯队,创业团队,产品经理,需求设计,PRD,原型,商业化 | ../超级程序员_IT项目管理与团队管理Agent | super_programmer_project_team_mgmt_agent_skill | 项目管理方法论、技术团队管理、产品经理体系 |
| 合规,国标,等保,代码管理制度,数据保密,权限管理,运营商,政企,招投标,信创,行业标准,软件开发标准 | ../超级程序员_IT合规与行业标准Agent | super_programmer_compliance_standard_agent_skill | 国标行标规范、企业IT制度、运营商与政企IT体系 |
| 软件工程,需求工程,系统设计,DDD,架构设计,技术选型,数据库设计,API设计,编码规范,Git,Code Review,测试策略,部署,发布,蓝绿,灰度,金丝雀,运维监控,故障排查,容量规划,混沌工程,安全开发,SDL,OWASP,威胁建模,技术文档,知识管理,Runbook | ../超级程序员_软件工程与开发全流程Agent | super_programmer_software_engineering_agent_skill | 需求→设计→编码→测试→部署→运维→安全→知识完整闭环 |

## Dispatch Rules

1. 解析用户消息中的**领域关键词**（显式或隐式）。
2. 在Routing Table的Task Keyword列中匹配。
3. **唯一匹配** → 直接分发到对应子Agent。
4. **多匹配**（≤2个）→ 向用户展示两个选项并要求澄清。
5. **无匹配** → 询问用户重新表述或指明具体领域。
6. 分发意味着：加载子Agent的AGENTS.md，识别最相关的细粒度工作流，并开始执行。

## Notes

- 顶层Agent**不包含执行工作流**，仅负责路由分发。
- 关键词覆盖范围要足够广以捕获同义/转述请求。
- 新增子Agent时更新此表。
