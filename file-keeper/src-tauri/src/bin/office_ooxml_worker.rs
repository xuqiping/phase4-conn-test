use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, BufRead, Read, Write};
use std::path::{Path, PathBuf};

const SAFE_OOXML: &str = "SAFE_OOXML";
const HIGH_FIDELITY_REQUIRED: &str = "HIGH_FIDELITY_REQUIRED";
const BLOCKED: &str = "BLOCKED";

const LEGACY_EXTENSIONS: &[&str] = &["xls", "doc", "ppt"];
const MACRO_EXTENSIONS: &[&str] = &["xlsm", "docm", "pptm"];
const STANDARD_EXTENSIONS: &[&str] = &["xlsx", "docx", "pptx"];

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InspectRequest {
    request_id: String,
    operation: String,
    path: PathBuf,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct InspectResponse {
    request_id: Option<String>,
    classification: &'static str,
    risk_codes: Vec<&'static str>,
    error_code: Option<&'static str>,
    source_sha256: Option<String>,
}

impl InspectResponse {
    fn blocked(request_id: Option<String>, error_code: &'static str) -> Self {
        Self {
            request_id,
            classification: BLOCKED,
            risk_codes: Vec::new(),
            error_code: Some(error_code),
            source_sha256: None,
        }
    }
}

fn main() {
    if std::env::args_os().len() != 1 {
        write_response(&InspectResponse::blocked(
            None,
            "OFFICE_COMMAND_LINE_ARGUMENTS_FORBIDDEN",
        ));
        return;
    }

    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let response = match line {
            Ok(line) => inspect_line(&line),
            Err(_) => InspectResponse::blocked(None, "OFFICE_STDIN_READ_FAILED"),
        };
        write_response(&response);
    }
}

fn write_response(response: &InspectResponse) {
    let stdout = io::stdout();
    let mut output = stdout.lock();
    if serde_json::to_writer(&mut output, response).is_ok() {
        let _ = output.write_all(b"\n");
        let _ = output.flush();
    }
}

fn inspect_line(line: &str) -> InspectResponse {
    let request = match serde_json::from_str::<InspectRequest>(line) {
        Ok(request) => request,
        Err(error) => {
            let code = if error.is_syntax() || error.is_eof() {
                "OFFICE_INVALID_REQUEST_JSON"
            } else {
                "OFFICE_INVALID_REQUEST_SCHEMA"
            };
            return InspectResponse::blocked(None, code);
        }
    };

    if request.operation != "inspect" {
        return InspectResponse::blocked(Some(request.request_id), "OFFICE_UNSUPPORTED_OPERATION");
    }
    inspect_path(request.request_id, &request.path)
}

fn inspect_path(request_id: String, path: &Path) -> InspectResponse {
    let extension = match path.extension().and_then(|value| value.to_str()) {
        Some(value) => value.to_ascii_lowercase(),
        None => return InspectResponse::blocked(Some(request_id), "OFFICE_UNSUPPORTED_EXTENSION"),
    };

    let before_hash = match sha256_file(path) {
        Ok(hash) => hash,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };

    if LEGACY_EXTENSIONS.contains(&extension.as_str()) {
        return finish_inspection(
            request_id,
            path,
            before_hash,
            vec!["OFFICE_LEGACY_BINARY_FORMAT"],
        );
    }

    if !STANDARD_EXTENSIONS.contains(&extension.as_str())
        && !MACRO_EXTENSIONS.contains(&extension.as_str())
    {
        return InspectResponse::blocked(Some(request_id), "OFFICE_UNSUPPORTED_EXTENSION");
    }

    let mut risk_codes = Vec::new();
    if MACRO_EXTENSIONS.contains(&extension.as_str()) {
        risk_codes.push("OFFICE_MACRO_EXTENSION");
    }

    let file = match File::open(path) {
        Ok(file) => file,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(archive) => archive,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_ZIP"),
    };

    let mut has_vba = false;
    let mut has_signature = false;
    let mut has_external_relationship = false;
    let mut has_content_types = false;
    for index in 0..archive.len() {
        let mut entry = match archive.by_index(index) {
            Ok(entry) => entry,
            Err(_) => {
                return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_ZIP_ENTRY")
            }
        };
        let name = entry.name().replace('\\', "/").to_ascii_lowercase();
        has_content_types |= name == "[content_types].xml";
        has_vba |= name.ends_with("/vbaproject.bin") || name == "vbaproject.bin";
        has_signature |= name.starts_with("_xmlsignatures/")
            || name.ends_with("/vbaprojectsignature.bin")
            || name == "vbaprojectsignature.bin";

        if name.ends_with(".rels") {
            let mut relationship_xml = String::new();
            if entry
                .by_ref()
                .take(4 * 1024 * 1024)
                .read_to_string(&mut relationship_xml)
                .is_err()
            {
                return InspectResponse::blocked(
                    Some(request_id),
                    "OFFICE_RELATIONSHIP_READ_FAILED",
                );
            }
            let compact_xml = relationship_xml
                .to_ascii_lowercase()
                .split_ascii_whitespace()
                .collect::<String>();
            has_external_relationship |= compact_xml.contains("targetmode=\"external\"")
                || compact_xml.contains("targetmode='external'");
        }
    }

    if !has_content_types {
        return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_PACKAGE");
    }

    if has_vba {
        risk_codes.push("OFFICE_VBA_PROJECT_PRESENT");
    }
    if has_signature {
        risk_codes.push("OFFICE_DIGITAL_SIGNATURE_PRESENT");
    }
    if has_external_relationship {
        risk_codes.push("OFFICE_EXTERNAL_RELATIONSHIP_PRESENT");
    }

    finish_inspection(request_id, path, before_hash, risk_codes)
}

fn finish_inspection(
    request_id: String,
    path: &Path,
    before_hash: String,
    risk_codes: Vec<&'static str>,
) -> InspectResponse {
    let after_hash = match sha256_file(path) {
        Ok(hash) => hash,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };
    if before_hash != after_hash {
        return InspectResponse::blocked(
            Some(request_id),
            "OFFICE_SOURCE_CHANGED_DURING_INSPECTION",
        );
    }

    InspectResponse {
        request_id: Some(request_id),
        classification: if risk_codes.is_empty() {
            SAFE_OOXML
        } else {
            HIGH_FIDELITY_REQUIRED
        },
        risk_codes,
        error_code: None,
        source_sha256: Some(after_hash),
    }
}

fn sha256_file(path: &Path) -> io::Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}
