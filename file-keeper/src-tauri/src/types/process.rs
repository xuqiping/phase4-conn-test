use serde::{Deserialize, Serialize};

/// Process category enum with 13+ variants
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ProcessCategory {
    Browser,
    Office,
    Explorer,
    Terminal,
    Archive,
    Document,
    Media,
    Image,
    Communication,
    Download,
    Game,
    System,
    Other,
}

impl ProcessCategory {
    pub fn as_str(&self) -> &'static str {
      match self {
            ProcessCategory::Browser => "Browser",
            ProcessCategory::Office => "Office",
            ProcessCategory::Explorer => "Explorer",
            ProcessCategory::Terminal => "Terminal",
            ProcessCategory::Archive => "Archive",
            ProcessCategory::Document => "Document",
            ProcessCategory::Media => "Media",
            ProcessCategory::Image => "Image",
            ProcessCategory::Communication => "Communication",
            ProcessCategory::Download => "Download",
            ProcessCategory::Game => "Game",
            ProcessCategory::System => "System",
            ProcessCategory::Other => "Other",
        }
    }
}

/// Process information structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    pub pid: u32,
    pub name: String,
    pub window_title: String,
    pub category: ProcessCategory,
    pub memory_mb: f64,
    pub cpu_usage: f32,
    pub window_handle: usize,
}

/// Process mapping structure for category recognition
#[derive(Debug, Clone)]
pub struct ProcessMapping {
    pub process_name: String,
    pub category: ProcessCategory,
}

impl ProcessMapping {
    pub fn new(process_name: impl Into<String>, category: ProcessCategory) -> Self {
        Self {
            process_name: process_name.into(),
            category,
        }
    }
}
