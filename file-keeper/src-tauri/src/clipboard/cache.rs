use crate::clipboard::types::FileExtensionMode;
use std::path::Path;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CacheCandidate {
    pub id: String,
    pub bytes: i64,
    pub last_used_at: Option<i64>,
    pub created_at: i64,
    pub is_favorite: bool,
    pub is_pinned: bool,
}

pub fn extension_allowed(path: &str, mode: &FileExtensionMode, extensions: &[String]) -> bool {
    if matches!(mode, FileExtensionMode::AllowAll) || extensions.is_empty() {
        return true;
    }

    let extension = Path::new(path)
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("")
        .to_lowercase();

    let configured = extensions.iter().any(|item| {
        item.trim_start_matches('.')
            .eq_ignore_ascii_case(&extension)
    });

    match mode {
        FileExtensionMode::AllowAll => true,
        FileExtensionMode::AllowList => configured,
        FileExtensionMode::BlockList => !configured,
    }
}

pub fn within_item_size_limit(bytes: i64, limit_mb: i64) -> bool {
    if limit_mb <= 0 {
        return true;
    }
    bytes <= limit_mb * 1024 * 1024
}

pub fn cleanup_candidates(mut candidates: Vec<CacheCandidate>, bytes_to_free: i64) -> Vec<String> {
    candidates.sort_by(|left, right| {
        left.is_pinned
            .cmp(&right.is_pinned)
            .then(left.is_favorite.cmp(&right.is_favorite))
            .then(
                left.last_used_at
                    .unwrap_or(left.created_at)
                    .cmp(&right.last_used_at.unwrap_or(right.created_at)),
            )
            .then(left.created_at.cmp(&right.created_at))
    });

    let mut freed = 0;
    let mut ids = Vec::new();
    for candidate in candidates {
        if candidate.is_pinned || candidate.is_favorite {
            continue;
        }
        ids.push(candidate.id);
        freed += candidate.bytes;
        if freed >= bytes_to_free {
            break;
        }
    }
    ids
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_all_extensions_when_unconfigured() {
        assert!(extension_allowed(
            "report.exe",
            &FileExtensionMode::AllowAll,
            &[]
        ));
    }

    #[test]
    fn allow_list_only_allows_configured_extension() {
        assert!(extension_allowed(
            "report.pdf",
            &FileExtensionMode::AllowList,
            &["pdf".to_string()]
        ));
        assert!(!extension_allowed(
            "report.exe",
            &FileExtensionMode::AllowList,
            &["pdf".to_string()]
        ));
    }

    #[test]
    fn block_list_blocks_configured_extension() {
        assert!(!extension_allowed(
            "secret.key",
            &FileExtensionMode::BlockList,
            &["key".to_string()]
        ));
        assert!(extension_allowed(
            "report.docx",
            &FileExtensionMode::BlockList,
            &["key".to_string()]
        ));
    }

    #[test]
    fn item_size_limit_blocks_large_file_copy() {
        assert!(within_item_size_limit(100 * 1024 * 1024, 200));
        assert!(!within_item_size_limit(201 * 1024 * 1024, 200));
    }

    #[test]
    fn cleanup_prefers_unpinned_unfavorite_oldest_unused_items() {
        let candidates = vec![
            CacheCandidate {
                id: "pinned".to_string(),
                bytes: 10,
                last_used_at: None,
                created_at: 1,
                is_favorite: false,
                is_pinned: true,
            },
            CacheCandidate {
                id: "favorite".to_string(),
                bytes: 10,
                last_used_at: None,
                created_at: 2,
                is_favorite: true,
                is_pinned: false,
            },
            CacheCandidate {
                id: "new".to_string(),
                bytes: 10,
                last_used_at: Some(100),
                created_at: 3,
                is_favorite: false,
                is_pinned: false,
            },
            CacheCandidate {
                id: "old".to_string(),
                bytes: 10,
                last_used_at: None,
                created_at: 0,
                is_favorite: false,
                is_pinned: false,
            },
        ];

        assert_eq!(
            cleanup_candidates(candidates, 20),
            vec!["old".to_string(), "new".to_string()]
        );
    }
}
