# Security Development Lifecycle Workflow Checklist

Use this checklist after completing every step of `workflow/security_lifecycle_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: OWASP Top 10防御与安全编码

- [ ] 安全编码规范已发布（输入验证/输出编码/访问控制/加密/日志/异常处理）
- [ ] 输入验证白名单策略落地
- [ ] 三层权限模型（网关/服务/数据）已实施
- [ ] 加密三层密钥架构已建立（Root Key/KEK/DEK）
- [ ] 后量子密码（FIPS 203/204/205）评估完成

## Step 2: SAST/DAST/SCA安全扫描矩阵

- [ ] 五维扫描矩阵已配置（SAST/DAST/SCA/IAST/RASP）
- [ ] 零预算起步方案已实施（GitHub原生安全+ZAP+Trivy）
- [ ] 高危漏洞CI阻断
- [ ] 扫描结果与Jira/SonarQube集成

## Step 3: STRIDE威胁建模与安全评审

- [ ] STRIDE威胁建模已执行（S/T/R/I/D/E六类威胁）
- [ ] DFD数据流图已绘制（L0/L1/L2）
- [ ] DREAD风险评级>7分的威胁已制定缓解措施
- [ ] 云原生与AI威胁建模新增（容器逃逸/Sidecar劫持/AI数据投毒/提示注入/模型窃取）

## Step 4: 漏洞响应SLA与应急管理

- [ ] 漏洞分级标准已发布（Critical/High/Medium/Low）
- [ ] SLA达标率≥90%
- [ ] 零日应急虚拟补丁方案就绪（WAF/eBPF/RASP）
- [ ] 漏洞响应Playbook已建立（发现→评估→修复→验证→通报）

## Step 5: SBOM供应链安全管理

- [ ] 每个构建自动生成SBOM（SPDX/CycloneDX）
- [ ] SBOM与NVD/OSV实时关联
- [ ] SLSA框架Level 3实施
- [ ] Sigstore签名已配置（cosign sign/verify）

## Overall

- [ ] All 5 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
