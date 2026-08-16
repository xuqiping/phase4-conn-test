//! core-meter：Token 预估/实扣本地镜像、余额同步、超限暂停。
//! 对应 FR-041 / AC-045 客户端半边（P02 Step8）：
//! - [`mirror`]：usage_mirror 只增镜像 + 对账（L3 迁移在 core-state）
//! - [`vault`]：refresh token 存 OS 凭据管理器

pub mod mirror;
pub mod vault;

pub use mirror::{CloudLedgerRow, MeterMirror, MirrorEntry, ReconcileReport};
pub use vault::{OsKeyringVault, TokenVault, VaultError};

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-meter";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-meter");
    }
}
