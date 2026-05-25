use crate::types::process::ProcessCategory;
use std::collections::HashMap;

/// Load all process mappings into a HashMap for fast lookup
pub fn load_process_mappings() -> HashMap<String, ProcessCategory> {
    let mut mappings = HashMap::new();

    add_browser_mappings(&mut mappings);

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
