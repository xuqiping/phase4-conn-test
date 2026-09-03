// Isolated harness: runs clipboard storage tests without compiling unrelated
// desktop/Office unit-test modules from the main Tauri binary.
#[path = "../src/clipboard/search.rs"]
mod clipboard_search;
#[path = "../src/clipboard/types.rs"]
mod clipboard_types;

mod clipboard {
    pub mod search {
        pub use crate::clipboard_search::*;
    }

    pub mod types {
        pub use crate::clipboard_types::*;
    }
}

#[path = "../src/clipboard/storage.rs"]
mod storage;
