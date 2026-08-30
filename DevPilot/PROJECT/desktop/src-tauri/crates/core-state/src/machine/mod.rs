//! 阶段状态机引擎（FR-029 核心）：YAML 定义 → 校验 → 转移裁决 → 历史落库。
//!
//! - [`loader`]：加载外部 YAML，schema 校验，损坏则回退内置默认（plan 坑点表）
//! - [`validator`]：定义合法性检查（一次报完全部问题）
//! - [`Machine`]：纯逻辑状态机（可单测，不碰 IO）
//! - [`PersistentMachine`]：Machine + SQLite（workflow_states 当前态 + transition_history 历史）
//! - [`history`]：转移历史读写

pub mod history;
pub mod loader;
pub mod validator;

use crate::db::Db;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

// ---------- YAML schema（serde deny_unknown_fields = 反序列化白名单，plan 安全清单） ----------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WorkflowDef {
    pub workflow_version: String,
    pub phases: Vec<PhaseDef>,
    pub transitions: Vec<TransitionDef>,
    #[serde(default)]
    pub gates: BTreeMap<String, GateDef>,
    #[serde(default)]
    pub scale_variants: BTreeMap<String, ScaleVariant>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PhaseDef {
    pub key: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransitionDef {
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub gate: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GateDef {
    pub label: String,
    #[serde(default)]
    pub checklist: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ScaleVariant {
    #[serde(default)]
    pub skip: Vec<String>,
}

// ---------- 转移错误（上抛前端时转大白话，不含路径/堆栈） ----------

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum TransitionError {
    #[error("未知阶段: {0}")]
    UnknownPhase(String),
    #[error("未知门禁: {0}")]
    UnknownGate(String),
    #[error("不允许从「{from}」直接到「{to}」（越阶段或不存在的转移）")]
    NoEdge { from: String, to: String },
    #[error("门禁「{label}」未通过：{}", checklist.join("；"))]
    GateBlocked {
        gate: String,
        label: String,
        checklist: Vec<String>,
    },
}

// ---------- 纯逻辑状态机 ----------

pub struct Machine {
    def: WorkflowDef,
    phase: String,
    gates_passed: BTreeSet<String>,
}

impl Machine {
    /// 按规模展开变体并校验定义；初始阶段 = 展开后第一个阶段。
    pub fn new(def: WorkflowDef, scale: &str) -> Result<Self, String> {
        // 先校验原始定义（含 scale_variants 引用），再展开，展开后再校验一次图完整性
        validator::validate(&def).map_err(|errs| errs.join("；"))?;
        let mut def = apply_scale(def, scale)?;
        def.scale_variants.clear(); // 已展开，变体表不再适用（避免二次校验误报）
        validator::validate(&def).map_err(|errs| errs.join("；"))?;
        let initial = def
            .phases
            .first()
            .ok_or_else(|| "至少需要一个阶段".to_string())?
            .key
            .clone();
        Ok(Self {
            def,
            phase: initial,
            gates_passed: BTreeSet::new(),
        })
    }

    pub fn phase(&self) -> &str {
        &self.phase
    }
    pub fn definition(&self) -> &WorkflowDef {
        &self.def
    }
    pub fn gates_passed(&self) -> &BTreeSet<String> {
        &self.gates_passed
    }

    /// 目标阶段边上的门禁 key（持久化历史用）。
    pub fn gate_for(&self, to: &str) -> Option<&str> {
        self.edge(to).and_then(|e| e.gate.as_deref())
    }

    fn edge(&self, to: &str) -> Option<&TransitionDef> {
        self.def
            .transitions
            .iter()
            .find(|t| t.from == self.phase && t.to == to)
    }

    /// 合法性裁决（AC-032）：阶段存在 → 边存在 → 门禁已通过。
    pub fn can_transition(&self, to: &str) -> Result<(), TransitionError> {
        if !self.def.phases.iter().any(|p| p.key == to) {
            return Err(TransitionError::UnknownPhase(to.into()));
        }
        let edge = self.edge(to).ok_or_else(|| TransitionError::NoEdge {
            from: self.phase.clone(),
            to: to.into(),
        })?;
        if let Some(gate) = &edge.gate {
            if !self.gates_passed.contains(gate) {
                let gd = self.def.gates.get(gate);
                return Err(TransitionError::GateBlocked {
                    gate: gate.clone(),
                    label: gd.map(|g| g.label.clone()).unwrap_or_else(|| gate.clone()),
                    checklist: gd.map(|g| g.checklist.clone()).unwrap_or_default(),
                });
            }
        }
        Ok(())
    }

    pub fn transition(&mut self, to: &str) -> Result<(), TransitionError> {
        self.can_transition(to)?;
        self.phase = to.to_string();
        Ok(())
    }

    pub fn pass_gate(&mut self, gate: &str) -> Result<(), TransitionError> {
        if !self.def.gates.contains_key(gate) {
            return Err(TransitionError::UnknownGate(gate.into()));
        }
        self.gates_passed.insert(gate.into());
        Ok(())
    }

    /// 从持久化状态恢复（断点续开 AC-052）：阶段必须存在于当前定义，否则报定义错误。
    pub fn restore(&mut self, phase: &str, gates_passed: Vec<String>) -> Result<(), String> {
        if !self.def.phases.iter().any(|p| p.key == phase) {
            return Err(format!(
                "持久化阶段「{phase}」不在当前工作流定义中（工作流版本可能已变更）"
            ));
        }
        self.phase = phase.to_string();
        self.gates_passed = gates_passed.into_iter().collect();
        Ok(())
    }
}

/// 规模变体展开：移除 skip 阶段并桥接前后边。
/// 规则：桥接边继承原边门禁；指向无后继的被删终点（如 deploy）的边直接丢弃；
/// 桥接成自环/重复的边丢弃。
fn apply_scale(mut def: WorkflowDef, scale: &str) -> Result<WorkflowDef, String> {
    let skip: BTreeSet<&str> = def
        .scale_variants
        .get(scale)
        .map(|v| v.skip.iter().map(String::as_str).collect())
        .unwrap_or_default();
    if skip.is_empty() {
        return Ok(def);
    }
    let mut bridged: Vec<TransitionDef> = Vec::new();
    'edges: for t in &def.transitions {
        if skip.contains(t.from.as_str()) {
            continue; // 从被删阶段出发的边，由其前驱的桥接边替代
        }
        let mut to = t.to.clone();
        let mut guard = 0;
        while skip.contains(to.as_str()) {
            guard += 1;
            if guard > 16 {
                return Err(format!("规模 {scale} 桥接出现环"));
            }
            // 被删阶段可能有多条出边（正向边 + 回退边）。逐条出边把链追到
            // 非删落点，优先「不回到出发点」的落点——否则回退边若恰好写在
            // 前面，桥出去就成自环被丢弃 → 出发阶段成孤岛（交叉审查 P02 前修-1）。
            let outs: Vec<&str> = def
                .transitions
                .iter()
                .filter(|x| x.from == to)
                .map(|x| x.to.as_str())
                .collect();
            let mut resolved: Option<String> = None;
            for cand in outs {
                let mut cur = cand.to_string();
                let mut dead = false;
                let mut g2 = 0;
                while skip.contains(cur.as_str()) {
                    g2 += 1;
                    if g2 > 16 {
                        return Err(format!("规模 {scale} 桥接出现环"));
                    }
                    match def.transitions.iter().find(|x| x.from == cur) {
                        Some(next) => cur = next.to.clone(),
                        None => {
                            dead = true; // 该链指向无后继的被删终点
                            break;
                        }
                    }
                }
                if dead {
                    continue;
                }
                if cur != t.from {
                    resolved = Some(cur);
                    break;
                }
                resolved.get_or_insert(cur); // 兜底：全部落点都回出发点时保留
            }
            match resolved {
                Some(next) => to = next,
                None => continue 'edges, // 无任何可追后继：丢弃该边
            }
        }
        if t.from == to || bridged.iter().any(|e| e.from == t.from && e.to == to) {
            continue; // 自环 / 重复边
        }
        bridged.push(TransitionDef {
            from: t.from.clone(),
            to,
            gate: t.gate.clone(),
        });
    }
    def.phases.retain(|p| !skip.contains(p.key.as_str()));
    def.transitions = bridged;
    Ok(def)
}

// ---------- 持久化状态机（Machine + SQLite） ----------

#[derive(Debug, thiserror::Error)]
pub enum MachineError {
    #[error(transparent)]
    Transition(#[from] TransitionError),
    #[error(transparent)]
    Db(#[from] crate::db::DbError),
    #[error("状态序列化错误: {0}")]
    Serde(#[from] serde_json::Error),
    #[error("工作流定义错误: {0}")]
    Def(String),
}

pub struct PersistentMachine {
    machine: Machine,
    db: Db,
    project_id: i64,
}

impl PersistentMachine {
    /// 加载已有状态（断点续开）或初始化新项目状态。
    pub fn load_or_init(
        db: Db,
        project_id: i64,
        def: WorkflowDef,
        scale: &str,
    ) -> Result<Self, MachineError> {
        let existing: Option<(String, String)> = db.read(|c| {
            match c.query_row(
                "SELECT phase, gate_status FROM workflow_states WHERE project_id = ?1",
                [project_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            ) {
                Ok(v) => Ok(Some(v)),
                Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
                Err(e) => Err(e.into()),
            }
        })?;

        let mut machine = Machine::new(def, scale).map_err(MachineError::Def)?;
        match existing {
            Some((phase, gates_json)) => {
                let gates: Vec<String> = serde_json::from_str(&gates_json)?;
                machine.restore(&phase, gates).map_err(MachineError::Def)?;
            }
            None => {
                // 并发初始化竞争（多来源同时 load_or_init）：INSERT 冲突让位，
                // 落库成功与否都以库里现值为准重读一次。
                let row = db.write(|c| {
                    c.execute(
                        "INSERT INTO workflow_states (project_id, phase, gate_status)
                         VALUES (?1, ?2, '[]')
                         ON CONFLICT (project_id) DO NOTHING",
                        (project_id, machine.phase()),
                    )?;
                    Ok(c.query_row(
                        "SELECT phase, gate_status FROM workflow_states WHERE project_id = ?1",
                        [project_id],
                        |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
                    )?)
                })?;
                if row.0 != machine.phase() {
                    machine
                        .restore(&row.0, serde_json::from_str(&row.1)?)
                        .map_err(MachineError::Def)?;
                }
            }
        }
        Ok(Self {
            machine,
            db,
            project_id,
        })
    }

    pub fn machine(&self) -> &Machine {
        &self.machine
    }

    pub fn project_id(&self) -> i64 {
        self.project_id
    }

    pub fn pass_gate(&mut self, gate: &str) -> Result<(), MachineError> {
        let from = self.machine.phase().to_string();
        self.machine.pass_gate(gate)?;
        let gates: Vec<&String> = self.machine.gates_passed().iter().collect();
        let gates_json = serde_json::to_string(&gates)?;
        let project_id = self.project_id;
        let won = self.db.write(|c| {
            let n = c.execute(
                "UPDATE workflow_states SET gate_status = ?1, updated_at = datetime('now')
                 WHERE project_id = ?2 AND phase = ?3",
                (gates_json.as_str(), project_id, from.as_str()),
            )?;
            Ok(n > 0)
        })?;
        if !won {
            // 阶段被并发转移：以库为准恢复（门禁集合也随新阶段重置），不覆盖
            self.reload_from_db()?;
        }
        Ok(())
    }

    /// 裁决 + 落库（当前态 + 历史），actor 记录操作来源（user/cli/mcp/deeplink）。
    /// 并发安全三板斧（交叉审查 P02 期间修-3）：
    /// ① 两条 SQL 在同一 write 闭包（写队列串行，不会撕裂）；
    /// ② UPDATE 带 `WHERE phase = 旧值` 的 CAS——并发抢先者赢，后来者 0 行命中；
    /// ③ CAS 失败 = 状态被并发修改，以库内真值恢复本机镜像并报大白话错误。
    pub fn transition(&mut self, to: &str, actor: &str) -> Result<(), MachineError> {
        let from = self.machine.phase().to_string();
        let gate = self.machine.gate_for(to).map(str::to_string);
        self.machine.transition(to)?;
        let gates: Vec<&String> = self.machine.gates_passed().iter().collect();
        let gates_json = serde_json::to_string(&gates)?;
        let project_id = self.project_id;
        let to_owned = to.to_string();
        let won = self.db.write(|c| {
            let n = c.execute(
                "UPDATE workflow_states SET phase = ?1, gate_status = ?2, updated_at = datetime('now')
                 WHERE project_id = ?3 AND phase = ?4",
                (to_owned.as_str(), gates_json.as_str(), project_id, from.as_str()),
            )?;
            if n == 0 {
                return Ok(false); // CAS 失败：并发方已改状态
            }
            history::record_on(c, project_id, &from, &to_owned, gate.as_deref(), actor)?;
            Ok(true)
        })?;
        if !won {
            self.reload_from_db()?;
            return Err(MachineError::Def(
                "状态刚被其他操作改变，已为你刷新，请重试".into(),
            ));
        }
        Ok(())
    }

    /// 以库内真值重建内存镜像（CAS 失败 / 外部变更后的收敛）
    fn reload_from_db(&mut self) -> Result<(), MachineError> {
        let row = self.db.read(|c| {
            Ok(c.query_row(
                "SELECT phase, gate_status FROM workflow_states WHERE project_id = ?1",
                [self.project_id],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
            )?)
        })?;
        self.machine
            .restore(&row.0, serde_json::from_str(&row.1)?)
            .map_err(MachineError::Def)?;
        Ok(())
    }
}
