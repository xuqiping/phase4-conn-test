// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod clipboard;
mod commands;
mod platform;
mod types;
mod utils;

use commands::files::{open_file, validate_path, show_in_folder};
use commands::processes::{find_file_processes, close_process, close_file_processes};
use commands::process_management::{get_running_processes, close_app_process, close_app_processes, kill_app_process, kill_app_processes, activate_app_window};
use commands::icons::get_file_icon;
use commands::clipboard::{
    add_clipboard_file_for_testing,
    add_clipboard_html_for_testing,
    add_clipboard_image_for_testing,
    add_clipboard_text_for_testing,
    clear_clipboard_history,
    clipboard_database_path,
    copy_clipboard_item,
    copy_clipboard_items,
    delete_clipboard_item,
    get_clipboard_item_detail,
    get_clipboard_items,
    get_clipboard_settings,
    get_clipboard_storage_usage,
    paste_clipboard_item,
    remember_clipboard_target_window,
    rebuild_clipboard_index,
    retry_link_preview,
    search_clipboard_items,
    set_clipboard_ocr_text_for_testing,
    start_clipboard_monitor,
    stop_clipboard_monitor,
    update_clipboard_item_note,
    update_clipboard_settings,
};
use commands::screenshot::{capture_screenshot_region, get_screenshot_ocr_status};
use commands::work_report::{fetch_git_logs, show_work_report_notification, export_report_markdown};
use clipboard::ClipboardService;
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::menu::{MenuBuilder, MenuItemBuilder};
use tauri::Manager;

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::new().build())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let database_path = clipboard_database_path(&app.handle())?;
            let clipboard_service = ClipboardService::new(database_path)
                .map_err(|err| Box::<dyn std::error::Error>::from(err))?;
            app.manage(clipboard_service);
            // Build tray menu
            let show_item = MenuItemBuilder::with_id("show", "显示窗口").build(app)?;
            let hide_item = MenuItemBuilder::with_id("hide", "隐藏窗口").build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "退出").build(app)?;
            let menu = MenuBuilder::new(app)
                .item(&show_item)
                .item(&hide_item)
                .separator()
                .item(&quit_item)
                .build()?;

            // Build tray icon
            let _tray = TrayIconBuilder::new()
          .icon(app.default_window_icon().unwrap().clone())
       .tooltip("File Keeper")
             .menu(&menu)
                .on_menu_event(|app, event| {
                    match event.id().as_ref() {
                        "show" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                        "hide" => {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.hide();
                            }
                        }
                        "quit" => {
                            std::process::exit(0);
                        }
                        _ => {}
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                })
                .build(app)?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            open_file,
         validate_path,
            show_in_folder,
            find_file_processes,
            close_process,
          close_file_processes,
            get_file_icon,
            get_running_processes,
        close_app_process,
            close_app_processes,
            kill_app_process,
            kill_app_processes,
            activate_app_window,
            start_clipboard_monitor,
            stop_clipboard_monitor,
            get_clipboard_items,
            search_clipboard_items,
            get_clipboard_item_detail,
            add_clipboard_text_for_testing,
            add_clipboard_file_for_testing,
            add_clipboard_image_for_testing,
            add_clipboard_html_for_testing,
            set_clipboard_ocr_text_for_testing,
            copy_clipboard_item,
            copy_clipboard_items,
            paste_clipboard_item,
            remember_clipboard_target_window,
            delete_clipboard_item,
            update_clipboard_item_note,
            clear_clipboard_history,
            get_clipboard_settings,
            update_clipboard_settings,
            get_clipboard_storage_usage,
            rebuild_clipboard_index,
            retry_link_preview,
            capture_screenshot_region,
            get_screenshot_ocr_status,
            fetch_git_logs,
            show_work_report_notification,
            export_report_markdown
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
