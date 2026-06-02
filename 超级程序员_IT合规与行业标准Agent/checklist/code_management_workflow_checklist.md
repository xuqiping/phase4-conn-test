# Code Management Workflow Checklist

Use this checklist after completing every step of `workflow/code_management_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 代码资产分级分类

- [ ] 全部代码资产已完成盘点，A/B/C/D四级分类覆盖率100%
- [ ] 每级代码资产已定义RBAC基础角色+ABAC动态属性的混合权限矩阵
- [ ] A级核心代码已定义VP审批+双人控制的最小权限集合
- [ ] 《代码资产分级分类表》已输出并存档
- [ ] 《权限矩阵说明书》已输出并存档
- [ ] 所有`[参考: ...]`标注指向知识库中"代码资产四级分类"章节

## Step 2: Git工作流选型与实施

- [ ] Git工作流选型决策已记录（ADR格式），选型理由与组织特征匹配
- [ ] main分支已配置保护规则（PR+审查+CI通过+无冲突）
- [ ] Feature分支命名规范已定义并文档化
- [ ] 合并策略已定义（squash merge/rebase的使用场景）

## Step 3: 四级质量门禁建设

- [ ] 本地pre-commit门禁已配置（格式化+静态检查+单测100%通过）
- [ ] PR自动化扫描已集成（SonarQube/Snyk），高危漏洞阻断合并
- [ ] 人类审查+AI审查流程已定义，审查清单包含安全性/性能/可读性
- [ ] 生产部署前验证已配置（staging回归+灰度健康检查）

## Step 4: AI生成代码治理策略

- [ ] AI代码三级治理框架已定义（自由使用/审批使用/禁止使用）
- [ ] 每级治理的具体场景与审批流程已明确文档化
- [ ] CI已集成AI代码检测能力或人工审查标注机制
- [ ] 《AI代码使用登记簿》已建立，每季度审计机制已定义

## Step 5: 开源合规全链路建设

- [ ] 开源组件准入审查流程已建立，GPL/AGPL已列入黑名单
- [ ] CI已集成FOSSology/ScanCode，每次构建自动生成SBOM
- [ ] 漏洞监控已订阅SNYK/OSV数据库，高危CVE 24小时告警
- [ ] 《开源合规风险案例集》已输出

## Step 6: 代码防泄密分层模型实施

- [ ] 核心代码（A级）已部署VDI+屏幕水印+USB/剪贴板禁用+录屏
- [ ] 重要代码（B级）已部署MFA+全量审计+DLP监控
- [ ] DLP系统已配置代码类敏感数据识别规则
- [ ] 核心研发人员已签署《代码保密协议》
- [ ] 月度代码访问审计机制已建立

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
