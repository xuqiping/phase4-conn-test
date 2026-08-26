#[path = "../office/protocol.rs"]
mod protocol;

use protocol::{
    WorkerEventKind, WorkerOperation, WorkerProgress, WorkerProgressPhase, WorkerRequest,
    WorkerResponse, OFFICE_WORKER_LINE_MAX_BYTES, OFFICE_WORKER_PROTOCOL_VERSION,
};
use quick_xml::encoding::Decoder;
use quick_xml::events::{BytesStart, Event};
use quick_xml::name::ResolveResult;
use quick_xml::reader::NsReader;
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::File;
use std::io::{self, BufRead, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::time::{Duration, Instant, SystemTime};

const SAFE_OOXML: &str = "SAFE_OOXML";
const HIGH_FIDELITY_REQUIRED: &str = "HIGH_FIDELITY_REQUIRED";
const LEGACY_EXTENSIONS: &[&str] = &["xls", "doc", "ppt"];
const MACRO_EXTENSIONS: &[&str] = &["xlsm", "docm", "pptm"];
const STANDARD_EXTENSIONS: &[&str] = &["xlsx", "docx", "pptx"];
const SOURCE_MAX_BYTES: u64 = 2 * 1024 * 1024 * 1024;
const ZIP_ENTRY_MAX_COUNT: usize = 100_000;
const XML_ENTRY_MAX_BYTES: u64 = 4 * 1024 * 1024;
const XML_TOTAL_MAX_BYTES: u64 = 6 * 1024 * 1024;
const ZIP_COMPRESSION_RATIO_MAX: u64 = 1_000;
const SCAN_TIMEOUT: Duration = Duration::from_secs(30);
const CONTENT_TYPES_NAMESPACE: &[u8] =
    b"http://schemas.openxmlformats.org/package/2006/content-types";
const RELATIONSHIPS_NAMESPACE: &[u8] =
    b"http://schemas.openxmlformats.org/package/2006/relationships";
const KNOWN_MAIN_PARTS: &[&str] = &[
    "xl/workbook.xml",
    "word/document.xml",
    "ppt/presentation.xml",
];

struct OoxmlPackageSpec {
    main_part: &'static str,
    main_content_type: &'static str,
}

enum XmlEntryReadError {
    TooLarge,
    Invalid,
    Timeout,
}

struct RelationshipScan {
    has_expected_office_document: bool,
    has_external: bool,
}

struct RelationshipAttributes {
    target: Option<String>,
    relationship_type: Option<String>,
    target_mode: Option<String>,
}

struct SourceSnapshot {
    len: u64,
    modified: SystemTime,
}

enum HashError {
    Io,
    Timeout,
}

type InspectResponse = WorkerResponse;

fn main() {
    if std::env::args_os().len() != 1 {
        write_response(&InspectResponse::blocked(
            None,
            "OFFICE_COMMAND_LINE_ARGUMENTS_FORBIDDEN",
        ));
        return;
    }

    let stdin = io::stdin();
    let mut input = stdin.lock();
    loop {
        match read_bounded_line(&mut input, OFFICE_WORKER_LINE_MAX_BYTES) {
            Ok(Some(Ok(line))) => {
                let (response, shutdown) = match std::str::from_utf8(&line) {
                    Ok(line) => inspect_line(line),
                    Err(_) => (
                        InspectResponse::blocked(None, "OFFICE_INVALID_REQUEST_JSON"),
                        false,
                    ),
                };
                write_response(&response);
                if shutdown {
                    break;
                }
            }
            Ok(Some(Err(()))) => {
                write_response(&InspectResponse::blocked(None, "OFFICE_REQUEST_TOO_LARGE"))
            }
            Ok(None) => break,
            Err(_) => {
                write_response(&InspectResponse::blocked(None, "OFFICE_STDIN_READ_FAILED"));
                break;
            }
        }
    }
}

fn read_bounded_line<R: BufRead>(
    reader: &mut R,
    max_bytes: usize,
) -> io::Result<Option<Result<Vec<u8>, ()>>> {
    let mut line = Vec::new();
    let mut too_large = false;
    let mut saw_bytes = false;
    loop {
        let buffer = reader.fill_buf()?;
        if buffer.is_empty() {
            return if !saw_bytes {
                Ok(None)
            } else if too_large {
                Ok(Some(Err(())))
            } else {
                Ok(Some(Ok(line)))
            };
        }
        saw_bytes = true;
        let newline = buffer.iter().position(|byte| *byte == b'\n');
        let consumed = newline.map_or(buffer.len(), |index| index + 1);
        let content_len = newline.unwrap_or(buffer.len());
        if !too_large {
            let remaining = max_bytes.saturating_sub(line.len());
            if content_len > remaining {
                too_large = true;
            } else {
                line.extend_from_slice(&buffer[..content_len]);
            }
        }
        reader.consume(consumed);
        if newline.is_some() {
            return if too_large {
                Ok(Some(Err(())))
            } else {
                Ok(Some(Ok(line)))
            };
        }
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

fn inspect_line(line: &str) -> (InspectResponse, bool) {
    let request = match serde_json::from_str::<WorkerRequest>(line) {
        Ok(request) => request,
        Err(error) => {
            let code = if error.is_syntax() || error.is_eof() {
                "OFFICE_INVALID_REQUEST_JSON"
            } else {
                "OFFICE_INVALID_REQUEST_SCHEMA"
            };
            return (InspectResponse::blocked(None, code), false);
        }
    };

    let request_id = request.request_id;
    match request.operation {
        WorkerOperation::Handshake => {
            if request.protocol_version != Some(OFFICE_WORKER_PROTOCOL_VERSION) {
                return (
                    InspectResponse::blocked(
                        Some(request_id),
                        "OFFICE_WORKER_PROTOCOL_VERSION_MISMATCH",
                    ),
                    false,
                );
            }
            (
                InspectResponse::control(
                    Some(request_id),
                    WorkerEventKind::Ready,
                    Some(std::process::id()),
                ),
                false,
            )
        }
        WorkerOperation::Heartbeat => (
            InspectResponse::control(
                Some(request_id),
                WorkerEventKind::Heartbeat,
                Some(std::process::id()),
            ),
            false,
        ),
        WorkerOperation::Cancel => {
            let mut response = InspectResponse::control(
                Some(request_id),
                WorkerEventKind::Cancelled,
                Some(std::process::id()),
            );
            response.task_id = request.task_id;
            (response, false)
        }
        WorkerOperation::Shutdown => (
            InspectResponse::control(
                Some(request_id),
                WorkerEventKind::ShuttingDown,
                Some(std::process::id()),
            ),
            true,
        ),
        WorkerOperation::Inspect => {
            let Some(path) = request.path else {
                return (
                    InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_REQUEST_SCHEMA"),
                    false,
                );
            };
            if request.task_id.is_some() {
                write_response(&InspectResponse {
                    request_id: Some(request_id.clone()),
                    task_id: request.task_id.clone(),
                    event: WorkerEventKind::Progress,
                    protocol_version: Some(OFFICE_WORKER_PROTOCOL_VERSION),
                    worker_pid: Some(std::process::id()),
                    progress: Some(WorkerProgress {
                        phase: WorkerProgressPhase::Inspect,
                        completed: 0,
                        total: 1,
                    }),
                    classification: None,
                    risk_codes: Vec::new(),
                    error_code: None,
                    source_sha256: None,
                });
            }
            let mut response = inspect_path(request_id, &path);
            response.task_id = request.task_id;
            (response, false)
        }
        WorkerOperation::Unsupported => (
            InspectResponse::blocked(Some(request_id), "OFFICE_UNSUPPORTED_OPERATION"),
            false,
        ),
    }
}

fn inspect_path(request_id: String, path: &Path) -> InspectResponse {
    let extension = match path.extension().and_then(|value| value.to_str()) {
        Some(value) => value.to_ascii_lowercase(),
        None => return InspectResponse::blocked(Some(request_id), "OFFICE_UNSUPPORTED_EXTENSION"),
    };

    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };
    let source_snapshot = match source_snapshot(&file) {
        Ok(snapshot) => snapshot,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };
    if source_snapshot.len > SOURCE_MAX_BYTES {
        return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_TOO_LARGE");
    }
    let deadline = Instant::now() + SCAN_TIMEOUT;
    let before_hash = match sha256_file(&mut file, deadline) {
        Ok(hash) => hash,
        Err(HashError::Timeout) => {
            return InspectResponse::blocked(Some(request_id), "OFFICE_SCAN_TIMEOUT")
        }
        Err(HashError::Io) => {
            return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED")
        }
    };

    if LEGACY_EXTENSIONS.contains(&extension.as_str()) {
        return finish_inspection(
            request_id,
            file,
            source_snapshot,
            before_hash,
            vec!["OFFICE_LEGACY_BINARY_FORMAT"],
            deadline,
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

    if file.seek(SeekFrom::Start(0)).is_err() {
        return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED");
    }
    let mut archive = match zip::ZipArchive::new(file) {
        Ok(archive) => archive,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_ZIP"),
    };

    let mut has_vba = false;
    let mut has_signature = false;
    let mut has_external_relationship = false;
    if archive.len() > ZIP_ENTRY_MAX_COUNT {
        return InspectResponse::blocked(Some(request_id), "OFFICE_ZIP_ENTRY_LIMIT_EXCEEDED");
    }
    let package_spec = match ooxml_package_spec(&extension) {
        Some(spec) => spec,
        None => return InspectResponse::blocked(Some(request_id), "OFFICE_INTERNAL_ROUTE_ERROR"),
    };
    let mut content_types_xml = None;
    let mut root_relationships_xml = None;
    let mut has_expected_main_part = false;
    let mut has_other_known_main_part = false;
    let mut normalized_names = HashSet::with_capacity(archive.len());
    let mut metadata_xml_total_bytes = 0_u64;
    for index in 0..archive.len() {
        if Instant::now() >= deadline {
            return InspectResponse::blocked(Some(request_id), "OFFICE_SCAN_TIMEOUT");
        }
        let mut entry = match archive.by_index(index) {
            Ok(entry) => entry,
            Err(_) => {
                return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_ZIP_ENTRY")
            }
        };
        let raw_name = entry.name();
        if !is_canonical_zip_entry_name(raw_name) {
            return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_ZIP_ENTRY_NAME");
        }
        let name = raw_name.to_ascii_lowercase();
        if !normalized_names.insert(name.clone()) {
            return InspectResponse::blocked(Some(request_id), "OFFICE_DUPLICATE_ZIP_ENTRY");
        }
        if compression_ratio_exceeded(entry.size(), entry.compressed_size()) {
            return InspectResponse::blocked(
                Some(request_id),
                "OFFICE_ZIP_COMPRESSION_RATIO_EXCEEDED",
            );
        }
        let is_scanned_xml_metadata = name == "[content_types].xml" || name.ends_with(".rels");
        if is_scanned_xml_metadata {
            metadata_xml_total_bytes = match metadata_xml_total_bytes.checked_add(entry.size()) {
                Some(total) if total <= XML_TOTAL_MAX_BYTES => total,
                _ => {
                    return InspectResponse::blocked(Some(request_id), "OFFICE_XML_BUDGET_EXCEEDED")
                }
            };
        }
        if name == "[content_types].xml" {
            content_types_xml = match read_xml_entry(&mut entry, deadline) {
                Ok(xml) => Some(xml),
                Err(XmlEntryReadError::Timeout) => {
                    return InspectResponse::blocked(Some(request_id), "OFFICE_SCAN_TIMEOUT")
                }
                Err(_) => {
                    return InspectResponse::blocked(
                        Some(request_id),
                        "OFFICE_INVALID_OOXML_PACKAGE",
                    )
                }
            };
        }
        if KNOWN_MAIN_PARTS.contains(&name.as_str()) {
            if name == package_spec.main_part {
                has_expected_main_part = true;
            } else {
                has_other_known_main_part = true;
            }
        }
        has_vba |= name.ends_with("/vbaproject.bin") || name == "vbaproject.bin";
        has_signature |= name.starts_with("_xmlsignatures/")
            || name.ends_with("/vbaprojectsignature.bin")
            || name == "vbaprojectsignature.bin";

        if name.ends_with(".rels") {
            let relationship_xml = match read_xml_entry(&mut entry, deadline) {
                Ok(xml) => xml,
                Err(XmlEntryReadError::TooLarge) => {
                    return InspectResponse::blocked(
                        Some(request_id),
                        "OFFICE_RELATIONSHIP_TOO_LARGE",
                    )
                }
                Err(XmlEntryReadError::Invalid) => {
                    return InspectResponse::blocked(
                        Some(request_id),
                        "OFFICE_RELATIONSHIP_READ_FAILED",
                    )
                }
                Err(XmlEntryReadError::Timeout) => {
                    return InspectResponse::blocked(Some(request_id), "OFFICE_SCAN_TIMEOUT")
                }
            };
            let scan = match scan_relationships(
                &relationship_xml,
                (name == "_rels/.rels").then_some(package_spec.main_part),
            ) {
                Ok(scan) => scan,
                Err(_) => {
                    return InspectResponse::blocked(
                        Some(request_id),
                        "OFFICE_RELATIONSHIP_READ_FAILED",
                    )
                }
            };
            if name == "_rels/.rels" {
                root_relationships_xml = Some(scan.has_expected_office_document);
            }
            has_external_relationship |= scan.has_external;
        }
    }

    let Some(content_types_xml) = content_types_xml else {
        return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_PACKAGE");
    };
    let Some(root_relationships_match) = root_relationships_xml else {
        return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_PACKAGE");
    };
    if !has_expected_main_part {
        return InspectResponse::blocked(
            Some(request_id),
            if has_other_known_main_part {
                "OFFICE_OOXML_TYPE_MISMATCH"
            } else {
                "OFFICE_OOXML_MAIN_PART_MISSING"
            },
        );
    }

    let content_types_match = match content_types_match(&content_types_xml, &package_spec) {
        Ok(matches) => matches,
        Err(_) => {
            return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_PACKAGE")
        }
    };
    if !content_types_match || !root_relationships_match {
        return InspectResponse::blocked(Some(request_id), "OFFICE_OOXML_TYPE_MISMATCH");
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

    let file = archive.into_inner();
    finish_inspection(
        request_id,
        file,
        source_snapshot,
        before_hash,
        risk_codes,
        deadline,
    )
}

fn finish_inspection(
    request_id: String,
    mut file: File,
    before_snapshot: SourceSnapshot,
    before_hash: String,
    risk_codes: Vec<&'static str>,
    deadline: Instant,
) -> InspectResponse {
    let after_hash = match sha256_file(&mut file, deadline) {
        Ok(hash) => hash,
        Err(HashError::Timeout) => {
            return InspectResponse::blocked(Some(request_id), "OFFICE_SCAN_TIMEOUT")
        }
        Err(HashError::Io) => {
            return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED")
        }
    };
    let after_snapshot = match source_snapshot(&file) {
        Ok(snapshot) => snapshot,
        Err(_) => return InspectResponse::blocked(Some(request_id), "OFFICE_SOURCE_READ_FAILED"),
    };
    if before_hash != after_hash
        || before_snapshot.len != after_snapshot.len
        || before_snapshot.modified != after_snapshot.modified
    {
        return InspectResponse::blocked(
            Some(request_id),
            "OFFICE_SOURCE_CHANGED_DURING_INSPECTION",
        );
    }

    InspectResponse {
        request_id: Some(request_id),
        task_id: None,
        event: WorkerEventKind::Result,
        protocol_version: Some(OFFICE_WORKER_PROTOCOL_VERSION),
        worker_pid: Some(std::process::id()),
        progress: None,
        classification: Some(if risk_codes.is_empty() {
            SAFE_OOXML.to_string()
        } else {
            HIGH_FIDELITY_REQUIRED.to_string()
        }),
        risk_codes: risk_codes.into_iter().map(str::to_string).collect(),
        error_code: None,
        source_sha256: Some(after_hash),
    }
}

fn source_snapshot(file: &File) -> io::Result<SourceSnapshot> {
    let metadata = file.metadata()?;
    Ok(SourceSnapshot {
        len: metadata.len(),
        modified: metadata.modified()?,
    })
}

fn sha256_file(file: &mut File, deadline: Instant) -> Result<String, HashError> {
    file.seek(SeekFrom::Start(0)).map_err(|_| HashError::Io)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        if Instant::now() >= deadline {
            return Err(HashError::Timeout);
        }
        let count = file.read(&mut buffer).map_err(|_| HashError::Io)?;
        if count == 0 {
            break;
        }
        hasher.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn is_canonical_zip_entry_name(name: &str) -> bool {
    if name.is_empty()
        || name.contains('\\')
        || name.starts_with('/')
        || name.as_bytes().get(1) == Some(&b':')
    {
        return false;
    }
    name.split('/').all(|segment| {
        !segment.is_empty() && segment != "." && segment != ".." && !segment.contains(':')
    })
}

fn compression_ratio_exceeded(size: u64, compressed_size: u64) -> bool {
    size > 0
        && (compressed_size == 0
            || compressed_size
                .checked_mul(ZIP_COMPRESSION_RATIO_MAX)
                .is_some_and(|maximum_size| size > maximum_size))
}

fn ooxml_package_spec(extension: &str) -> Option<OoxmlPackageSpec> {
    match extension {
        "xlsx" => Some(OoxmlPackageSpec {
            main_part: "xl/workbook.xml",
            main_content_type:
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
        }),
        "xlsm" => Some(OoxmlPackageSpec {
            main_part: "xl/workbook.xml",
            main_content_type: "application/vnd.ms-excel.sheet.macroenabled.main+xml",
        }),
        "docx" => Some(OoxmlPackageSpec {
            main_part: "word/document.xml",
            main_content_type:
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
        }),
        "docm" => Some(OoxmlPackageSpec {
            main_part: "word/document.xml",
            main_content_type: "application/vnd.ms-word.document.macroenabled.main+xml",
        }),
        "pptx" => Some(OoxmlPackageSpec {
            main_part: "ppt/presentation.xml",
            main_content_type:
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
        }),
        "pptm" => Some(OoxmlPackageSpec {
            main_part: "ppt/presentation.xml",
            main_content_type: "application/vnd.ms-powerpoint.presentation.macroenabled.main+xml",
        }),
        _ => None,
    }
}

fn read_xml_entry(
    entry: &mut zip::read::ZipFile<'_>,
    deadline: Instant,
) -> Result<String, XmlEntryReadError> {
    if entry.size() > XML_ENTRY_MAX_BYTES {
        return Err(XmlEntryReadError::TooLarge);
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        if Instant::now() >= deadline {
            return Err(XmlEntryReadError::Timeout);
        }
        let count = entry
            .read(&mut buffer)
            .map_err(|_| XmlEntryReadError::Invalid)?;
        if count == 0 {
            break;
        }
        if bytes.len() + count > XML_ENTRY_MAX_BYTES as usize {
            return Err(XmlEntryReadError::TooLarge);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    if bytes.len() as u64 > XML_ENTRY_MAX_BYTES {
        return Err(XmlEntryReadError::TooLarge);
    }
    String::from_utf8(bytes).map_err(|_| XmlEntryReadError::Invalid)
}

fn attribute_value(
    element: &BytesStart<'_>,
    decoder: Decoder,
    wanted: &[u8],
) -> Result<Option<String>, ()> {
    for attribute in element.attributes() {
        let attribute = attribute.map_err(|_| ())?;
        if attribute.key.as_ref() == wanted {
            return attribute
                .decode_and_unescape_value(decoder)
                .map(|value| Some(value.into_owned()))
                .map_err(|_| ());
        }
    }
    Ok(None)
}

fn relationship_values(
    element: &BytesStart<'_>,
    decoder: Decoder,
) -> Result<RelationshipAttributes, ()> {
    Ok(RelationshipAttributes {
        target: attribute_value(element, decoder, b"Target")?,
        relationship_type: attribute_value(element, decoder, b"Type")?,
        target_mode: attribute_value(element, decoder, b"TargetMode")?,
    })
}

fn scan_relationships(xml: &str, expected_target: Option<&str>) -> Result<RelationshipScan, ()> {
    const OFFICE_DOCUMENT_RELATIONSHIP: &str =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";

    let mut reader = NsReader::from_str(xml);
    let mut scan = RelationshipScan {
        has_expected_office_document: false,
        has_external: false,
    };
    let mut depth = 0_usize;
    let mut root_seen = false;
    let mut root_closed = false;
    let mut root_is_normative = false;

    loop {
        let (namespace, event) = reader.read_resolved_event().map_err(|_| ())?;
        match event {
            Event::Start(element) => {
                if root_closed {
                    return Err(());
                }
                if depth == 0 {
                    if root_seen {
                        return Err(());
                    }
                    root_seen = true;
                    root_is_normative = element.local_name().as_ref() == b"Relationships"
                        && namespace_matches(&namespace, RELATIONSHIPS_NAMESPACE);
                } else if depth == 1
                    && root_is_normative
                    && element.local_name().as_ref() == b"Relationship"
                    && namespace_matches(&namespace, RELATIONSHIPS_NAMESPACE)
                {
                    update_relationship_scan(
                        &mut scan,
                        &element,
                        reader.decoder(),
                        expected_target,
                        OFFICE_DOCUMENT_RELATIONSHIP,
                    )?;
                }
                depth += 1;
            }
            Event::Empty(element) => {
                if root_closed {
                    return Err(());
                }
                if depth == 0 {
                    if root_seen {
                        return Err(());
                    }
                    root_seen = true;
                    root_closed = true;
                    root_is_normative = element.local_name().as_ref() == b"Relationships"
                        && namespace_matches(&namespace, RELATIONSHIPS_NAMESPACE);
                } else if depth == 1
                    && root_is_normative
                    && element.local_name().as_ref() == b"Relationship"
                    && namespace_matches(&namespace, RELATIONSHIPS_NAMESPACE)
                {
                    update_relationship_scan(
                        &mut scan,
                        &element,
                        reader.decoder(),
                        expected_target,
                        OFFICE_DOCUMENT_RELATIONSHIP,
                    )?;
                }
            }
            Event::End(_) => {
                depth = depth.checked_sub(1).ok_or(())?;
                if depth == 0 {
                    root_closed = true;
                }
            }
            Event::DocType(_) => return Err(()),
            Event::Text(text)
                if depth == 0 && !text.decode().map_err(|_| ())?.trim().is_empty() =>
            {
                return Err(())
            }
            Event::Eof => {
                return if root_seen && root_closed && root_is_normative && depth == 0 {
                    Ok(scan)
                } else {
                    Err(())
                }
            }
            _ => {}
        }
    }
}

fn namespace_matches(namespace: &ResolveResult<'_>, expected: &[u8]) -> bool {
    matches!(namespace, ResolveResult::Bound(value) if value.as_ref() == expected)
}

fn update_relationship_scan(
    scan: &mut RelationshipScan,
    element: &BytesStart<'_>,
    decoder: Decoder,
    expected_target: Option<&str>,
    office_document_relationship: &str,
) -> Result<(), ()> {
    let attributes = relationship_values(element, decoder)?;
    scan.has_external |= attributes.target_mode.as_deref() == Some("External");
    scan.has_expected_office_document |= expected_target.is_some_and(|expected| {
        attributes.target.as_deref() == Some(expected)
            && attributes.relationship_type.as_deref() == Some(office_document_relationship)
    });
    Ok(())
}

fn content_types_match(xml: &str, package_spec: &OoxmlPackageSpec) -> Result<bool, ()> {
    let mut reader = NsReader::from_str(xml);
    let expected_part_name = format!("/{}", package_spec.main_part);
    let mut has_expected_override = false;
    let mut depth = 0_usize;
    let mut root_seen = false;
    let mut root_closed = false;
    let mut root_is_normative = false;

    loop {
        let (namespace, event) = reader.read_resolved_event().map_err(|_| ())?;
        match event {
            Event::Start(element) => {
                if root_closed {
                    return Err(());
                }
                if depth == 0 {
                    if root_seen {
                        return Err(());
                    }
                    root_seen = true;
                    root_is_normative = element.local_name().as_ref() == b"Types"
                        && namespace_matches(&namespace, CONTENT_TYPES_NAMESPACE);
                } else if depth == 1
                    && root_is_normative
                    && element.local_name().as_ref() == b"Override"
                    && namespace_matches(&namespace, CONTENT_TYPES_NAMESPACE)
                {
                    has_expected_override |= override_matches(
                        &element,
                        reader.decoder(),
                        &expected_part_name,
                        package_spec.main_content_type,
                    )?;
                }
                depth += 1;
            }
            Event::Empty(element) => {
                if root_closed {
                    return Err(());
                }
                if depth == 0 {
                    if root_seen {
                        return Err(());
                    }
                    root_seen = true;
                    root_closed = true;
                    root_is_normative = element.local_name().as_ref() == b"Types"
                        && namespace_matches(&namespace, CONTENT_TYPES_NAMESPACE);
                } else if depth == 1
                    && root_is_normative
                    && element.local_name().as_ref() == b"Override"
                    && namespace_matches(&namespace, CONTENT_TYPES_NAMESPACE)
                {
                    has_expected_override |= override_matches(
                        &element,
                        reader.decoder(),
                        &expected_part_name,
                        package_spec.main_content_type,
                    )?;
                }
            }
            Event::End(_) => {
                depth = depth.checked_sub(1).ok_or(())?;
                if depth == 0 {
                    root_closed = true;
                }
            }
            Event::DocType(_) => return Err(()),
            Event::Text(text)
                if depth == 0 && !text.decode().map_err(|_| ())?.trim().is_empty() =>
            {
                return Err(())
            }
            Event::Eof => {
                return if root_seen && root_closed && depth == 0 {
                    Ok(root_is_normative && has_expected_override)
                } else {
                    Err(())
                }
            }
            _ => {}
        }
    }
}

fn override_matches(
    element: &BytesStart<'_>,
    decoder: Decoder,
    expected_part_name: &str,
    expected_content_type: &str,
) -> Result<bool, ()> {
    let part_name = attribute_value(element, decoder, b"PartName")?;
    let content_type = attribute_value(element, decoder, b"ContentType")?;
    Ok(part_name.as_deref() == Some(expected_part_name)
        && content_type.is_some_and(|value| value.eq_ignore_ascii_case(expected_content_type)))
}
