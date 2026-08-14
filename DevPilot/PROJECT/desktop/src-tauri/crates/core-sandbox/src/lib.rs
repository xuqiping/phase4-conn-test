//! core-sandbox：目录白名单、命令审批、macOS Seatbelt / Windows Job Object。
//! 对应 FR-001/006/009。当前为 Step 2 骨架占位——P03 填实现。

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-sandbox";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-sandbox");
    }
}
