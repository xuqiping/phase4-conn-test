use crate::types::process::ProcessCategory;
use std::collections::HashMap;

/// Load all process mappings into a HashMap for fast lookup
pub fn load_process_mappings() -> HashMap<String, ProcessCategory> {
    let mut mappings = HashMap::new();

    add_browser_mappings(&mut mappings);
    add_office_mappings(&mut mappings);
    add_explorer_mappings(&mut mappings);
    add_terminal_mappings(&mut mappings);
    add_archive_mappings(&mut mappings);

    mappings
}

/// Add browser process mappings (30+ browsers)
fn add_browser_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let browsers = vec![
        // Chromium-based browsers
        "chrome.exe",
        "msedge.exe",
        "brave.exe",
        "opera.exe",
        "vivaldi.exe",
        "chromium.exe",
        "yandex.exe",
        "360chrome.exe",
      "360se.exe",
        "sogouexplorer.exe",
        "liebao.exe",
     "maxthon.exe",
        "qqbrowser.exe",
        "ucbrowser.exe",
        "coccoc.exe",
        "cent.exe",
        "slimjet.exe",
     "torch.exe",
        "comodo_dragon.exe",
        "epic.exe",

        // Firefox-based browsers
        "firefox.exe",
        "waterfox.exe",
        "palemoon.exe",
        "seamonkey.exe",
        "icecat.exe",
        "librewolf.exe",

        // Safari and WebKit
     "safari.exe",

        // Other browsers
        "iexplore.exe",
        "microsoftedge.exe",
        "microsoftedgecp.exe",
      "tor.exe",
    "falkon.exe",
        "midori.exe",
        "qutebrowser.exe",
    ];

    for browser in browsers {
        mappings.insert(browser.to_lowercase(), ProcessCategory::Browser);
    }
}
/// Add office application mappings
fn add_office_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let office_apps = vec![
      "winword.exe",      // Microsoft Word
        "excel.exe",        // Microsoft Excel
        "powerpnt.exe",     // Microsoft PowerPoint
        "onenote.exe",      // Microsoft OneNote
        "outlook.exe",      // Microsoft Outlook
        "msaccess.exe",     // Microsoft Access
    ];

    for app in office_apps {
      mappings.insert(app.to_lowercase(), ProcessCategory::Office);
    }
}

/// Add file explorer mappings
fn add_explorer_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let explorers = vec![
        "explorer.exe",     // Windows Explorer
    ];

    for explorer in explorers {
        mappings.insert(explorer.to_lowercase(), ProcessCategory::Explorer);
    }
}

/// Add terminal/console application mappings
fn add_terminal_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let terminals = vec![
        "cmd.exe",
        "powershell.exe",
        "pwsh.exe",
        "windowsterminal.exe",
        "wt.exe",
        "conhost.exe",
        "bash.exe",
        "wsl.exe",
        "ubuntu.exe",
        "debian.exe",
        "kali.exe",
      "opensuse.exe",
      "alacritty.exe",
        "hyper.exe",
        "terminus.exe",
        "cmder.exe",
        "consolez.exe",
        "mintty.exe",
    ];

    for terminal in terminals {
        mappings.insert(terminal.to_lowercase(), ProcessCategory::Terminal);
    }
}

/// Add archive/compression tool mappings
fn add_archive_mappings(mappings: &mut HashMap<String, ProcessCategory>) {
    let archive_tools = vec![
        "7zfm.exe",         // 7-Zip
        "7zg.exe",          // 7-Zip GUI
    "winrar.exe",       // WinRAR
        "winzip.exe",       // WinZip
        "peazip.exe",       // PeaZip
        "bandizip.exe",     // Bandizip
        "haozip.exe",       // HaoZip
        "360zip.exe",       // 360 Zip
      "2345zip.exe",      // 2345 Zip
     "kuaizip.exe",      // KuaiZip
        "winmount.exe",     // WinMount
        "poweriso.exe",     // PowerISO
        "ultraiso.exe",     // UltraISO
        "daemon.exe",       // DAEMON Tools
        "alcohol.exe",      // Alcohol 120%
        "imgburn.exe",      // ImgBurn
        "nero.exe",         // Nero
      "ashampoo.exe",     // Ashampoo
    ];

    for tool in archive_tools {
        mappings.insert(tool.to_lowercase(), ProcessCategory::Archive);
    }
}
