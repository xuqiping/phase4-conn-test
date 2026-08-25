use serde_json::Value;
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use uuid::Uuid;
use zip::write::FileOptions;

struct TestDir(PathBuf);

impl TestDir {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("file-keeper-office-test-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).expect("create test directory");
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn worker_command() -> Command {
    Command::new(env!("CARGO_BIN_EXE_office_ooxml_worker"))
}

fn run_worker(lines: &[Value]) -> Vec<Value> {
    let mut child = worker_command()
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn office worker");
    {
        let stdin = child.stdin.as_mut().expect("worker stdin");
        for line in lines {
            writeln!(stdin, "{}", line).expect("write JSONL request");
        }
    }
    let output = child.wait_with_output().expect("wait for worker");
    assert!(
        output.status.success(),
        "worker failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8(output.stdout)
        .expect("UTF-8 stdout")
        .lines()
        .map(|line| serde_json::from_str(line).expect("one JSON object per stdout line"))
        .collect()
}

fn create_ooxml(path: &Path, extra_entries: &[(&str, &[u8])], relationships: Option<&str>) {
    let package_extension = path
        .extension()
        .and_then(|value| value.to_str())
        .expect("fixture extension");
    create_ooxml_package(
        path,
        package_extension,
        extra_entries,
        relationships,
        true,
        true,
    );
}

fn create_ooxml_package(
    path: &Path,
    package_extension: &str,
    extra_entries: &[(&str, &[u8])],
    relationships: Option<&str>,
    include_main_part: bool,
    include_root_relationships: bool,
) {
    let (main_part, main_content_type, part_relationships) = match package_extension {
        "xlsx" => (
            "xl/workbook.xml",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            "xl/_rels/workbook.xml.rels",
        ),
        "xlsm" => (
            "xl/workbook.xml",
            "application/vnd.ms-excel.sheet.macroEnabled.main+xml",
            "xl/_rels/workbook.xml.rels",
        ),
        "docx" => (
            "word/document.xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
            "word/_rels/document.xml.rels",
        ),
        "docm" => (
            "word/document.xml",
            "application/vnd.ms-word.document.macroEnabled.main+xml",
            "word/_rels/document.xml.rels",
        ),
        "pptx" => (
            "ppt/presentation.xml",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
            "ppt/_rels/presentation.xml.rels",
        ),
        "pptm" => (
            "ppt/presentation.xml",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
            "ppt/_rels/presentation.xml.rels",
        ),
        other => panic!("unsupported synthetic package extension: {other}"),
    };
    let file = File::create(path).expect("create OOXML fixture");
    let mut archive = zip::ZipWriter::new(file);
    let options = FileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    archive
        .start_file("[Content_Types].xml", options)
        .expect("content types entry");
    write!(
        archive,
        r#"<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/{main_part}" ContentType="{main_content_type}"/></Types>"#
    )
    .expect("content types content");
    if include_root_relationships {
        archive
            .start_file("_rels/.rels", options)
            .expect("root relationship entry");
        write!(
            archive,
            r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="{main_part}"/></Relationships>"#
        )
        .expect("root relationship content");
    }
    if include_main_part {
        archive
            .start_file(main_part, options)
            .expect("main document part");
        archive
            .write_all(b"<?xml version=\"1.0\"?><synthetic/>")
            .expect("main document content");
    }
    archive
        .start_file("docProps/core.xml", options)
        .expect("synthetic metadata entry");
    archive
        .write_all(b"synthetic fixture; no document body")
        .expect("metadata content");
    if let Some(rels) = relationships {
        archive
            .start_file(part_relationships, options)
            .expect("relationship entry");
        archive
            .write_all(rels.as_bytes())
            .expect("relationship XML");
    }
    for (name, bytes) in extra_entries {
        archive.start_file(*name, options).expect("extra entry");
        archive.write_all(bytes).expect("extra content");
    }
    archive.finish().expect("finish OOXML fixture");
}

fn create_non_ooxml_zip(path: &Path) {
    let file = File::create(path).expect("create non-OOXML fixture");
    let mut archive = zip::ZipWriter::new(file);
    archive
        .start_file("plain.txt", FileOptions::default())
        .expect("plain ZIP entry");
    archive
        .write_all(b"synthetic non-OOXML zip")
        .expect("plain ZIP content");
    archive.finish().expect("finish non-OOXML fixture");
}

fn create_xlsx_with_metadata(path: &Path, content_types: &str, root_relationships: &str) {
    let file = File::create(path).expect("create custom metadata fixture");
    let mut archive = zip::ZipWriter::new(file);
    let options = FileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (name, bytes) in [
        ("[Content_Types].xml", content_types.as_bytes()),
        ("_rels/.rels", root_relationships.as_bytes()),
        ("xl/workbook.xml", b"<?xml version=\"1.0\"?><synthetic/>"),
    ] {
        archive.start_file(name, options).expect("metadata entry");
        archive.write_all(bytes).expect("metadata content");
    }
    archive.finish().expect("finish custom metadata fixture");
}

fn sha256(path: &Path) -> String {
    let mut file = File::open(path).expect("open fixture for hashing");
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 8192];
    loop {
        let count = file.read(&mut buffer).expect("read fixture for hashing");
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    format!("{:x}", hasher.finalize())
}

fn inspect_request(request_id: &str, path: &Path) -> Value {
    serde_json::json!({
        "requestId": request_id,
        "operation": "inspect",
        "path": path,
    })
}

#[test]
fn jsonl_contract_returns_one_machine_readable_response_per_request() {
    let dir = TestDir::new();
    let first = dir.path().join("first.xlsx");
    let second = dir.path().join("second.docx");
    create_ooxml(&first, &[], None);
    create_ooxml(&second, &[], None);

    let responses = run_worker(&[
        inspect_request("request-1", &first),
        inspect_request("request-2", &second),
    ]);

    assert_eq!(responses.len(), 2);
    assert_eq!(responses[0]["requestId"], "request-1");
    assert_eq!(responses[1]["requestId"], "request-2");
    assert_eq!(responses[0]["classification"], "SAFE_OOXML");
    assert_eq!(responses[0]["errorCode"], Value::Null);
    assert!(responses[0]["riskCodes"].is_array());
}

#[test]
fn standard_ooxml_without_risk_parts_is_safe_and_source_hash_is_unchanged() {
    let dir = TestDir::new();
    let path = dir.path().join("standard.pptx");
    create_ooxml(&path, &[], None);
    let before = sha256(&path);

    let response = run_worker(&[inspect_request("safe", &path)]).remove(0);

    assert_eq!(response["classification"], "SAFE_OOXML");
    assert_eq!(response["sourceSha256"], before);
    assert_eq!(sha256(&path), before);
}

#[test]
fn vba_signature_and_external_relationship_route_to_high_fidelity() {
    let dir = TestDir::new();
    let path = dir.path().join("risk.xlsx");
    let rels = r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink" Target="https://example.invalid/book.xlsx" TargetMode = 'External'/></Relationships>"#;
    create_ooxml(
        &path,
        &[
            ("xl/vbaProject.bin", b"synthetic-vba"),
            ("_xmlsignatures/sig1.xml", b"synthetic-signature"),
        ],
        Some(rels),
    );

    let response = run_worker(&[inspect_request("risk", &path)]).remove(0);
    let risks = response["riskCodes"].as_array().expect("risk code array");

    assert_eq!(response["classification"], "HIGH_FIDELITY_REQUIRED");
    for expected in [
        "OFFICE_VBA_PROJECT_PRESENT",
        "OFFICE_DIGITAL_SIGNATURE_PRESENT",
        "OFFICE_EXTERNAL_RELATIONSHIP_PRESENT",
    ] {
        assert!(
            risks.iter().any(|risk| risk == expected),
            "missing {expected}"
        );
    }
}

#[test]
fn macro_extension_and_legacy_binary_formats_route_to_high_fidelity() {
    let dir = TestDir::new();
    let macro_path = dir.path().join("macro.xlsm");
    let legacy_path = dir.path().join("legacy.doc");
    create_ooxml(&macro_path, &[], None);
    fs::write(&legacy_path, b"synthetic legacy marker").expect("write legacy fixture");

    let responses = run_worker(&[
        inspect_request("macro", &macro_path),
        inspect_request("legacy", &legacy_path),
    ]);

    assert_eq!(responses[0]["classification"], "HIGH_FIDELITY_REQUIRED");
    assert_eq!(responses[0]["riskCodes"][0], "OFFICE_MACRO_EXTENSION");
    assert_eq!(responses[1]["classification"], "HIGH_FIDELITY_REQUIRED");
    assert_eq!(responses[1]["riskCodes"][0], "OFFICE_LEGACY_BINARY_FORMAT");
}

#[test]
fn unsupported_extension_and_corrupt_ooxml_are_blocked_with_stable_codes() {
    let dir = TestDir::new();
    let unsupported = dir.path().join("notes.txt");
    let corrupt = dir.path().join("corrupt.xlsx");
    fs::write(&unsupported, b"synthetic text").expect("write unsupported fixture");
    fs::write(&corrupt, b"not a zip archive").expect("write corrupt fixture");

    let responses = run_worker(&[
        inspect_request("unsupported", &unsupported),
        inspect_request("corrupt", &corrupt),
    ]);

    assert_eq!(responses[0]["classification"], "BLOCKED");
    assert_eq!(responses[0]["errorCode"], "OFFICE_UNSUPPORTED_EXTENSION");
    assert_eq!(responses[1]["classification"], "BLOCKED");
    assert_eq!(responses[1]["errorCode"], "OFFICE_INVALID_OOXML_ZIP");
}

#[test]
fn zip_without_ooxml_content_types_is_blocked_as_an_invalid_package() {
    let dir = TestDir::new();
    let disguised_zip = dir.path().join("disguised.xlsx");
    create_non_ooxml_zip(&disguised_zip);

    let response = run_worker(&[inspect_request("disguised", &disguised_zip)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_INVALID_OOXML_PACKAGE");
}

#[test]
fn missing_extension_specific_main_part_is_blocked_with_stable_code() {
    let dir = TestDir::new();
    let path = dir.path().join("missing-main.xlsx");
    create_ooxml_package(&path, "xlsx", &[], None, false, true);

    let response = run_worker(&[inspect_request("missing-main", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_OOXML_MAIN_PART_MISSING");
}

#[test]
fn extension_and_package_type_mismatch_is_blocked_with_stable_code() {
    let dir = TestDir::new();
    let path = dir.path().join("mismatched.xlsx");
    create_ooxml_package(&path, "docx", &[], None, true, true);

    let response = run_worker(&[inspect_request("mismatched", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_OOXML_TYPE_MISMATCH");
}

#[test]
fn missing_root_relationships_is_blocked_as_an_invalid_package() {
    let dir = TestDir::new();
    let path = dir.path().join("missing-root-rels.docx");
    create_ooxml_package(&path, "docx", &[], None, true, false);

    let response = run_worker(&[inspect_request("missing-root-rels", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_INVALID_OOXML_PACKAGE");
}

#[test]
fn oversized_relationship_part_is_blocked_instead_of_silently_truncated() {
    let dir = TestDir::new();
    let path = dir.path().join("oversized-rels.xlsx");
    let padding = "a".repeat(4 * 1024 * 1024);
    let relationships = format!(
        r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><!--{padding}--><Relationship Id="external" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink" Target="https://example.invalid/tail.xlsx" TargetMode="External"/></Relationships>"#
    );
    create_ooxml(&path, &[], Some(&relationships));

    let response = run_worker(&[inspect_request("oversized-rels", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_RELATIONSHIP_TOO_LARGE");
}

#[test]
fn commented_fake_override_and_relationship_do_not_validate_the_package() {
    let dir = TestDir::new();
    let path = dir.path().join("comment-forgery.xlsx");
    let content_types = r#"<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><!-- <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/> --></Types>"#;
    let root_relationships = r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><!-- <Relationship Id="fake" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/> --></Relationships>"#;
    create_xlsx_with_metadata(&path, content_types, root_relationships);

    let response = run_worker(&[inspect_request("comment-forgery", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_OOXML_TYPE_MISMATCH");
}

#[test]
fn character_reference_decoded_to_external_routes_to_high_fidelity() {
    let dir = TestDir::new();
    let path = dir.path().join("encoded-external.xlsx");
    let relationships = r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="encoded" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/externalLink" Target="https://example.invalid/encoded.xlsx" TargetMode="Extern&#97;l"/></Relationships>"#;
    create_ooxml(&path, &[], Some(relationships));

    let response = run_worker(&[inspect_request("encoded-external", &path)]).remove(0);
    let risks = response["riskCodes"].as_array().expect("risk code array");

    assert_eq!(response["classification"], "HIGH_FIDELITY_REQUIRED");
    assert!(risks
        .iter()
        .any(|risk| risk == "OFFICE_EXTERNAL_RELATIONSHIP_PRESENT"));
}

#[test]
fn dtd_after_a_valid_override_is_rejected() {
    let dir = TestDir::new();
    let path = dir.path().join("content-types-with-dtd.xlsx");
    let content_types = r#"<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><!DOCTYPE Types [<!ENTITY external SYSTEM "file:///never-read">]></Types>"#;
    let root_relationships = r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;
    create_xlsx_with_metadata(&path, content_types, root_relationships);

    let response = run_worker(&[inspect_request("content-types-dtd", &path)]).remove(0);

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_INVALID_OOXML_PACKAGE");
}

#[test]
fn invalid_json_line_is_reported_without_echoing_input_content() {
    let secret_marker = "DO_NOT_ECHO_DOCUMENT_BODY";
    let mut child = worker_command()
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn office worker");
    writeln!(child.stdin.as_mut().unwrap(), "{{{secret_marker}").expect("write invalid JSON");
    let output = child.wait_with_output().expect("wait for worker");
    let stdout = String::from_utf8(output.stdout).expect("UTF-8 stdout");
    let response: Value = serde_json::from_str(stdout.trim()).expect("machine readable error");

    assert_eq!(response["classification"], "BLOCKED");
    assert_eq!(response["errorCode"], "OFFICE_INVALID_REQUEST_JSON");
    assert!(!stdout.contains(secret_marker));
}
