//! DevPilot 客户端内核入口。
//! P01 Step 7：IPC commands + 事件流接入（create_project/get_state/transition/pass_gate）。

mod commands;
mod events;

use commands::AppState;
use core_state::Db;
use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            // 本地库：应用数据目录下 devpilot.db（~/.devpilot 语义由各平台数据目录承载）
            let dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&dir)?;
            let db = Db::open(dir.join("devpilot.db"))?;
            app.manage(AppState { db });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_projects,
            commands::create_project,
            commands::get_state,
            commands::transition,
            commands::pass_gate,
            commands::enter_acceptance,
            commands::request_release,
            commands::run_security_scan,
            commands::get_acceptance_checklist,
            commands::regenerate_acceptance_checklist,
            commands::update_acceptance_item,
            commands::vault_save,
            commands::vault_load,
            commands::vault_clear,
            commands::meter_sync,
            commands::meter_reconcile,
            commands::create_approval,
            commands::list_unresolved_approvals,
            commands::submit_approval,
            commands::install_plan,
            commands::install_runtime,
            commands::read_project_file,
            commands::write_project_file,
            commands::run_task,
            commands::save_secret,
            commands::list_secrets,
            commands::load_secret,
            commands::delete_secret,
            commands::execute_build,
            commands::summarize_diff,
            commands::list_checkpoints,
            commands::rollback_to_checkpoint,
            commands::continue_task,
            commands::list_tasks,
            commands::list_rounds,
            commands::list_task_events,
            commands::load_agent_config,
            commands::save_agent_config,
            commands::save_spec_cards,
            commands::list_spec_cards,
            commands::update_spec_card,
            commands::save_plan_chunks,
            commands::list_plan_chunks,
            commands::update_plan_chunk,
            commands::approve_plan,
            commands::revoke_plan_approval,
        ])
        .run(tauri::generate_context!())
        .expect("DevPilot 客户端启动失败");
}

#[cfg(test)]
mod tests {
    /// 冒烟测试：8 个 crate 全部可被主程序互引（P01 Step 2 验收，FR-029 底座）。
    #[test]
    fn workspace_crates_linkable() {
        let names = [
            core_state::CRATE_NAME,
            core_orchestrator::CRATE_NAME,
            core_sandbox::CRATE_NAME,
            core_exec::CRATE_NAME,
            core_meter::CRATE_NAME,
            core_skills::CRATE_NAME,
            core_mcp::CRATE_NAME,
            core_cli::CRATE_NAME,
        ];
        assert_eq!(names.len(), 8);
        assert!(names.iter().all(|n| n.starts_with("core-")));
    }
}
