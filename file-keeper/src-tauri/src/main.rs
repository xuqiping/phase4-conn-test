// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod commands;
mod platform;
mod utils;

use commands::files::{open_file, validate_path, show_in_folder};
use commands::processes::{find_file_processes, close_process, close_file_processes};

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::new().build())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
     .plugin(tauri_plugin_global_shortcut::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
         open_file,
            validate_path,
       show_in_folder,
            find_file_processes,
        close_process,
            close_file_processes
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
