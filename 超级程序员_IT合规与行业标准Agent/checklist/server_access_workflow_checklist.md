# Server Access Management Workflow Checklist

Use this checklist after completing every step of `workflow/server_access_workflow.md`. Every item must be answered **yes** before the step is considered complete. If any item is **no**, fix the output and re-validate.

## Step 1: 权限治理三层模型构建

- [ ] STRIDE威胁建模已覆盖核心系统（生产数据库/核心中间件/管理后台）
- [ ] AAA层已部署：统一身份源+MFA+密码策略（长度≥16+90天轮换）
- [ ] ZTA零信任四点架构（PDP/PEP/PIP/PAP）已设计并映射到基础设施
- [ ] 《威胁建模报告》+ 《AAA配置手册》+ 《零信任架构实施方案》已输出

## Step 2: 五大权限模型选型与实施

- [ ] 权限模型选型决策已记录（RBAC/ABAC/MAC/OPA+Rego/Cedar），选型理由与场景匹配
- [ ] 历史冗余权限已清理（6个月未使用/离职残留/幽灵账号）
- [ ] 用户-权限映射关系准确率100%
- [ ] 《权限基线配置标准》已输出

## Step 3: 堡垒机四层管控建设

- [ ] 堡垒机已覆盖全部生产环境访问入口
- [ ] 身份层已集成SSO+强制MFA
- [ ] 权限层已配置多维授权（人+资源+时间+命令集）
- [ ] 操作层已配置高危命令拦截+SQL审核+文件传输审计
- [ ] 审计层已部署全量会话录屏+命令日志结构化存储+可回放检索
- [ ] 《堡垒机运维手册》+ 《审计日志分析指南》已输出

## Step 4: PAM特权账号管理五阶段建设

- [ ] PAM系统已上线，幽灵账号已清零
- [ ] 特权密码自动轮换周期≤30天
- [ ] 会话代理（PSM）覆盖率100%
- [ ] JIT（Just-In-Time）特权提升已实施
- [ ] 零常驻特权（Zero Standing Privileges）已推进
- [ ] 《PAM建设路线图》+ 《特权账号治理报告》已输出

## Step 5: JIT即时授权工作流实施

- [ ] JIT工作流已上线运行
- [ ] 平均授权审批时间<30分钟
- [ ] AI风险评分机制已配置（0-100分，三级审批路由）
- [ ] 临时权限自动撤销率100%（过期后自动撤销+删除账号）
- [ ] 完整审计存档（申请-审批-授权-使用-撤销）已建立
- [ ] 《JIT即时授权管理办法》已输出

## Step 6: 审计日志体系与运营

- [ ] 审计日志采集覆盖率100%（采集→传输→存储→分析→展示五层）
- [ ] 日志保留期限≥180天
- [ ] 关键告警规则已配置（特权异常登录/批量权限变更/敏感数据访问/权限提升）
- [ ] 六大要素完整（Who/What/When/Where/Why/How）
- [ ] 月度审计报告自动化生成机制已建立
- [ ] 《审计日志标准规范》+ 《SIEM告警规则库》已输出

## Overall

- [ ] All 6 steps in the workflow have been executed in order without skipping.
- [ ] Every step has been cross-validated against its checklist section.
- [ ] No step was advanced before its checklist section passed.
- [ ] `task/current_task.md` has been updated with a completion record.
- [ ] Every `[参考: ...]` annotation points to an existing knowledge base file.
