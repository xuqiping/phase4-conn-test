//! 工作流定义校验：一次报完全部问题（改 YAML 时体验好）。

use super::WorkflowDef;
use std::collections::{BTreeMap, BTreeSet, VecDeque};

pub fn validate(def: &WorkflowDef) -> Result<(), Vec<String>> {
    let mut errors = Vec::new();

    // 1. 阶段 key 非空且唯一
    let mut seen = BTreeSet::new();
    for p in &def.phases {
        if p.key.trim().is_empty() {
            errors.push("存在空 key 的阶段".to_string());
        }
        if !seen.insert(p.key.as_str()) {
            errors.push(format!("阶段 key 重复: {}", p.key));
        }
    }
    let phase_keys: BTreeSet<&str> = def.phases.iter().map(|p| p.key.as_str()).collect();

    // 2. 转移端点已定义、无自环、门禁已定义
    for t in &def.transitions {
        if !phase_keys.contains(t.from.as_str()) {
            errors.push(format!("转移 from 未定义: {}", t.from));
        }
        if !phase_keys.contains(t.to.as_str()) {
            errors.push(format!("转移 to 未定义: {}", t.to));
        }
        if t.from == t.to {
            errors.push(format!("自环转移: {}", t.from));
        }
        if let Some(g) = &t.gate {
            if !def.gates.contains_key(g) {
                errors.push(format!("转移引用了未定义门禁: {g}"));
            }
        }
    }

    // 3. 首阶段可达末阶段（全流程可走通）
    if let (Some(first), Some(last)) = (def.phases.first(), def.phases.last()) {
        if !reachable(def, &first.key, &last.key) {
            errors.push(format!("从「{}」无法到达「{}」", first.key, last.key));
        }
    }

    // 4. 规模变体 skip 的阶段必须已定义
    for (name, v) in &def.scale_variants {
        for s in &v.skip {
            if !phase_keys.contains(s.as_str()) {
                errors.push(format!("规模 {name} skip 了未定义阶段: {s}"));
            }
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn reachable(def: &WorkflowDef, from: &str, to: &str) -> bool {
    let mut adj: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for t in &def.transitions {
        adj.entry(t.from.as_str()).or_default().push(t.to.as_str());
    }
    let mut queue = VecDeque::from([from]);
    let mut visited = BTreeSet::from([from]);
    while let Some(node) = queue.pop_front() {
        if node == to {
            return true;
        }
        for &next in adj.get(node).into_iter().flatten() {
            if visited.insert(next) {
                queue.push_back(next);
            }
        }
    }
    false
}
