//! 用量镜像（AC-045 客户端半边）：云端账本的本地只增镜像 + 定时对账。
//! 镜像行与云端 token_ledger 同构；幂等键 UNIQUE 保证补拉/重推不重记。
//! 对账不平（drift ≠ 0）以报告形式上抛，由上层记日志/告警（运维清单）。

use core_state::{Db, DbResult};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone)]
pub struct MirrorEntry {
    pub user_id: i64,
    pub kind: i64, // 1消费 2充值 3赠送 4人工调整
    pub model: Option<String>,
    pub amount_cents: i64,
    pub idempotency_key: String,
}

/// 云端账本行（与云端 token_ledger DTO 对齐；金额为 BIGINT→字符串防精度丢失）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CloudLedgerRow {
    pub id: i64,
    pub kind: i64,
    pub model: Option<String>,
    pub amount_cents: String,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct ReconcileReport {
    pub ok: bool,
    pub local_cents: i64,
    pub cloud_cents: i64,
    /// cloud - local：正=本地少记（漏拉），负=本地多记（异常）
    pub drift_cents: i64,
}

pub struct MeterMirror {
    db: Db,
}

impl MeterMirror {
    pub fn new(db: Db) -> Self {
        Self { db }
    }

    /// 追加一条镜像（幂等：同 key 已存在不重记）。返回是否新插入。
    pub fn record(&self, e: &MirrorEntry) -> DbResult<bool> {
        self.db.write(|c| {
            let n = c.execute(
                "INSERT INTO usage_mirror (user_id, kind, model, amount_cents, idempotency_key)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT (idempotency_key) DO NOTHING",
                rusqlite::params![
                    e.user_id,
                    e.kind,
                    e.model,
                    e.amount_cents,
                    e.idempotency_key
                ],
            )?;
            Ok(n > 0)
        })
    }

    /// 批量同步云端账本行（幂等键 = cloud-{云端id}）。返回新插入行数。
    pub fn sync_from_cloud(&self, user_id: i64, rows: &[CloudLedgerRow]) -> DbResult<usize> {
        let mut inserted = 0;
        for r in rows {
            let ok = self.record(&MirrorEntry {
                user_id,
                kind: r.kind,
                model: r.model.clone(),
                amount_cents: r.amount_cents.parse().unwrap_or(0),
                idempotency_key: format!("cloud-{}", r.id),
            })?;
            if ok {
                inserted += 1;
            }
        }
        Ok(inserted)
    }

    /// 本地镜像推导余额（分；消费行为负数，与云端口径一致）
    pub fn sum_cents(&self, user_id: i64) -> DbResult<i64> {
        self.db.read(|c| {
            Ok(c.query_row(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM usage_mirror WHERE user_id = ?1",
                [user_id],
                |r| r.get(0),
            )?)
        })
    }

    /// 对账：本地镜像推导值 vs 云端 /balance 的 total_cents。
    pub fn reconcile(&self, user_id: i64, cloud_cents: i64) -> DbResult<ReconcileReport> {
        let local = self.sum_cents(user_id)?;
        Ok(ReconcileReport {
            ok: local == cloud_cents,
            local_cents: local,
            cloud_cents,
            drift_cents: cloud_cents - local,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mirror() -> MeterMirror {
        MeterMirror::new(Db::open_in_memory().expect("建库"))
    }

    fn entry(key: &str, cents: i64, kind: i64) -> MirrorEntry {
        MirrorEntry {
            user_id: 7,
            kind,
            model: Some("gpt-4o-mini".into()),
            amount_cents: cents,
            idempotency_key: key.into(),
        }
    }

    #[test]
    fn mirror_append_and_sum() {
        let m = mirror();
        assert!(m.record(&entry("k1", 500, 3)).expect("记赠送"));
        assert!(m.record(&entry("k2", -315, 1)).expect("记消费"));
        assert_eq!(m.sum_cents(7).expect("求和"), 185);
    }

    #[test]
    fn mirror_idempotent_no_double_count() {
        let m = mirror();
        assert!(m.record(&entry("k1", -100, 1)).expect("首记"));
        assert!(!m.record(&entry("k1", -100, 1)).expect("重记不报错")); // 幂等：不重记
        assert_eq!(m.sum_cents(7).expect("求和"), -100);
    }

    #[test]
    fn cloud_sync_uses_cloud_id_key_and_is_replay_safe() {
        let m = mirror();
        let rows = vec![
            CloudLedgerRow {
                id: 11,
                kind: 2,
                model: Some("P200".into()),
                amount_cents: "20500".into(),
            },
            CloudLedgerRow {
                id: 12,
                kind: 1,
                model: Some("web_search".into()),
                amount_cents: "-10".into(),
            },
        ];
        assert_eq!(m.sync_from_cloud(7, &rows).expect("同步"), 2);
        assert_eq!(m.sync_from_cloud(7, &rows).expect("重放同步"), 0); // 同批重推零新增
        assert_eq!(m.sum_cents(7).expect("求和"), 20490);
    }

    #[test]
    fn reconcile_detects_drift() {
        let m = mirror();
        m.record(&entry("k1", 1000, 2)).expect("充值");
        m.record(&entry("k2", -315, 1)).expect("消费");
        let ok = m.reconcile(7, 685).expect("对账-平");
        assert!(ok.ok);
        assert_eq!(ok.drift_cents, 0);
        let drift = m.reconcile(7, 700).expect("对账-不平");
        assert!(!drift.ok);
        assert_eq!(drift.drift_cents, 15); // 云端多 15 分 → 本地漏拉
    }
}
