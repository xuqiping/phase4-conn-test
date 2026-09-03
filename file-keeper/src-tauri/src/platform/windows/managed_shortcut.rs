use std::fs;
use std::path::{Path, PathBuf};
use uuid::Uuid;

const FILE_ATTRIBUTE_REPARSE_POINT_VALUE: u32 = 0x400;

fn has_lnk_extension(path: &Path) -> bool {
    path.extension()
        .and_then(|value| value.to_str())
        .is_some_and(|value| value.eq_ignore_ascii_case("lnk"))
}

fn is_reparse_point(metadata: &fs::Metadata) -> bool {
    use std::os::windows::fs::MetadataExt;
    metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT_VALUE != 0
}

fn validate_regular_lnk(path: &Path) -> Result<fs::Metadata, String> {
    if !path.is_absolute() {
        return Err("快捷方式路径必须是绝对路径".to_string());
    }
    if !has_lnk_extension(path) {
        return Err("仅允许处理 Windows .lnk 快捷方式".to_string());
    }
    let metadata =
        fs::symlink_metadata(path).map_err(|_| "快捷方式不存在或无法访问".to_string())?;
    if !metadata.file_type().is_file()
        || metadata.file_type().is_symlink()
        || is_reparse_point(&metadata)
    {
        return Err("快捷方式必须是普通文件".to_string());
    }
    Ok(metadata)
}

pub fn import_shortcut_copy(source: &Path, managed_root: &Path) -> Result<PathBuf, String> {
    let source_metadata = validate_regular_lnk(source)?;
    if managed_root.exists() {
        let root_metadata = fs::symlink_metadata(managed_root)
            .map_err(|_| "无法检查快捷方式托管目录".to_string())?;
        if !root_metadata.is_dir()
            || root_metadata.file_type().is_symlink()
            || is_reparse_point(&root_metadata)
        {
            return Err("快捷方式托管目录不安全".to_string());
        }
    } else {
        fs::create_dir_all(managed_root).map_err(|_| "无法创建快捷方式托管目录".to_string())?;
    }

    let id = Uuid::new_v4().to_string();
    let temporary = managed_root.join(format!("{id}.tmp"));
    let published = managed_root.join(format!("{id}.lnk"));
    let copy_result = (|| {
        let copied = fs::copy(source, &temporary).map_err(|_| "复制快捷方式失败".to_string())?;
        if copied == 0 || copied != source_metadata.len() {
            return Err("快捷方式副本校验失败".to_string());
        }
        fs::rename(&temporary, &published).map_err(|_| "发布快捷方式副本失败".to_string())?;
        Ok(published.clone())
    })();
    if copy_result.is_err() {
        let _ = fs::remove_file(&temporary);
        let _ = fs::remove_file(&published);
    }
    copy_result
}

pub fn delete_managed_copy(candidate: &Path, managed_root: &Path) -> Result<(), String> {
    let metadata = validate_regular_lnk(candidate)?;
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return Err("拒绝删除链接或重解析文件".to_string());
    }
    let canonical_root = managed_root
        .canonicalize()
        .map_err(|_| "快捷方式托管目录不存在".to_string())?;
    let canonical_candidate = candidate
        .canonicalize()
        .map_err(|_| "托管快捷方式不存在".to_string())?;
    if canonical_candidate.parent() != Some(canonical_root.as_path()) {
        return Err("拒绝删除托管目录之外的文件".to_string());
    }
    fs::remove_file(&canonical_candidate).map_err(|_| "删除托管快捷方式失败".to_string())
}

pub fn resolve_shortcut_target(shortcut: &Path) -> Result<PathBuf, String> {
    validate_regular_lnk(shortcut)?;
    let shortcut = shortcut.to_path_buf();
    std::thread::spawn(move || resolve_shortcut_target_on_com_thread(&shortcut))
        .join()
        .map_err(|_| "解析快捷方式线程异常".to_string())?
}

fn resolve_shortcut_target_on_com_thread(shortcut: &Path) -> Result<PathBuf, String> {
    use std::os::windows::ffi::OsStrExt;
    use windows::core::{Interface, PCWSTR};
    use windows::Win32::Storage::FileSystem::WIN32_FIND_DATAW;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, IPersistFile, CLSCTX_INPROC_SERVER,
        COINIT_APARTMENTTHREADED, STGM_READ,
    };
    use windows::Win32::UI::Shell::{IShellLinkW, ShellLink, SLGP_RAWPATH};

    struct ComGuard;
    impl Drop for ComGuard {
        fn drop(&mut self) {
            unsafe { CoUninitialize() };
        }
    }

    unsafe {
        CoInitializeEx(None, COINIT_APARTMENTTHREADED)
            .ok()
            .map_err(|_| "初始化快捷方式解析器失败".to_string())?;
        let _guard = ComGuard;
        let shell_link: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)
            .map_err(|_| "创建快捷方式解析器失败".to_string())?;
        let persist: IPersistFile = shell_link
            .cast()
            .map_err(|_| "加载快捷方式解析器失败".to_string())?;
        let wide: Vec<u16> = shortcut.as_os_str().encode_wide().chain(Some(0)).collect();
        persist
            .Load(PCWSTR(wide.as_ptr()), STGM_READ)
            .map_err(|_| "快捷方式文件损坏或格式不受支持".to_string())?;
        let mut target = vec![0u16; 32_768];
        let mut find_data = WIN32_FIND_DATAW::default();
        shell_link
            .GetPath(&mut target, &mut find_data, SLGP_RAWPATH.0 as u32)
            .map_err(|_| "无法读取快捷方式目标".to_string())?;
        let length = target
            .iter()
            .position(|value| *value == 0)
            .unwrap_or(target.len());
        if length == 0 {
            return Err("快捷方式没有可用目标".to_string());
        }
        Ok(PathBuf::from(String::from_utf16_lossy(&target[..length])))
    }
}
