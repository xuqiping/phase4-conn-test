#![cfg(target_os = "windows")]

#[path = "../src/platform/windows/managed_shortcut.rs"]
mod managed_shortcut;

use managed_shortcut::{delete_managed_copy, import_shortcut_copy};
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;

fn temp_root() -> PathBuf {
    let root = std::env::temp_dir().join(format!("file-keeper-shortcut-test-{}", Uuid::new_v4()));
    fs::create_dir_all(&root).unwrap();
    root
}

#[test]
fn imports_with_random_name_and_removes_temporary_file() {
    let root = temp_root();
    let source = root.join("source.lnk");
    let managed = root.join("managed");
    fs::write(&source, b"shortcut fixture").unwrap();

    let imported = import_shortcut_copy(&source, &managed).unwrap();

    assert_eq!(imported.parent(), Some(managed.as_path()));
    assert_eq!(
        imported.extension().and_then(|value| value.to_str()),
        Some("lnk")
    );
    assert_ne!(imported.file_name(), source.file_name());
    assert_eq!(fs::read(&imported).unwrap(), b"shortcut fixture");
    assert!(fs::read_dir(&managed).unwrap().all(|entry| !entry
        .unwrap()
        .file_name()
        .to_string_lossy()
        .ends_with(".tmp")));

    fs::remove_dir_all(root).unwrap();
}

#[test]
fn deletes_only_direct_regular_lnk_children_of_managed_root() {
    let root = temp_root();
    let managed = root.join("managed");
    let outside = root.join("outside.lnk");
    let valid = managed.join("valid.lnk");
    let wrong_extension = managed.join("keep.exe");
    let nested = managed.join("nested").join("nested.lnk");
    fs::create_dir_all(nested.parent().unwrap()).unwrap();
    fs::write(&valid, b"valid").unwrap();
    fs::write(&outside, b"outside").unwrap();
    fs::write(&wrong_extension, b"exe").unwrap();
    fs::write(&nested, b"nested").unwrap();

    assert!(delete_managed_copy(&valid, &managed).is_ok());
    assert!(!valid.exists());
    assert!(delete_managed_copy(&outside, &managed).is_err());
    assert!(delete_managed_copy(&wrong_extension, &managed).is_err());
    assert!(delete_managed_copy(&nested, &managed).is_err());
    assert!(delete_managed_copy(&managed, &managed).is_err());
    assert!(outside.exists());
    assert!(wrong_extension.exists());
    assert!(nested.exists());

    fs::remove_dir_all(root).unwrap();
}

#[test]
fn rejects_non_lnk_sources_without_creating_managed_output() {
    let root = temp_root();
    let source = root.join("presentation.pptx");
    let managed = root.join("managed");
    fs::write(&source, b"pptx").unwrap();

    assert!(import_shortcut_copy(&source, &managed).is_err());
    assert!(!managed.exists());

    fs::remove_dir_all(root).unwrap();
}

#[test]
fn rejects_symbolic_link_candidates_when_windows_allows_fixture_creation() {
    use std::os::windows::fs::symlink_file;

    let root = temp_root();
    let managed = root.join("managed");
    let outside = root.join("outside.lnk");
    let linked = managed.join("linked.lnk");
    fs::create_dir_all(&managed).unwrap();
    fs::write(&outside, b"outside").unwrap();

    if symlink_file(&outside, &linked).is_ok() {
        assert!(delete_managed_copy(&linked, &managed).is_err());
        assert!(outside.exists());
    }

    fs::remove_dir_all(root).unwrap();
}
