//! core-exec：进程执行、测试/lint 探测、环境画像、环境一键安装。
//! 对应 FR-003/004/005。P03 Step2/4 实现命令执行器 + 环境探测缓存。

pub mod exec;
pub mod install;
pub mod probe;
pub mod profile;

pub use exec::{run, ExecRequest, ExecResult};
pub use install::{
    plan as install_plan, run_plan as run_install_plan, InstallPlan, InstallResult, InstallStep,
};
pub use probe::{probe, EnvProfile, Stack};
pub use profile::{get_cached, probe_and_cache};

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-exec";

#[cfg(test)]
mod tests {
    use super::CRATE_NAME;

    #[test]
    fn smoke_name() {
        assert_eq!(CRATE_NAME, "core-exec");
    }
}
