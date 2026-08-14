//! core-exec：进程执行、测试/lint 探测、环境画像、环境一键安装。
//! 对应 FR-003/004/005。当前为 Step 2 骨架占位——P03 填实现。

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-exec";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    /// 冒烟测试：crate 可编译、可互引（P01 Step 2 验收）。
    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-exec");
    }
}
