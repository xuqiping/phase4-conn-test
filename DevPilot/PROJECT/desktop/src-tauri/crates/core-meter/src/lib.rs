//! core-meter：Token 预估/实扣本地镜像、余额同步、超限暂停。
//! 对应 FR-041。当前为 Step 2 骨架占位——P02 起对接云端网关。

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
