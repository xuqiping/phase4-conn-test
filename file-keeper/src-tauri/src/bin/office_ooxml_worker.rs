use quick_xml::events::{BytesStart, Event};
use quick_xml::Reader;
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
const RELATIONSHIP_MAX_BYTES: u64 = 4 * 1024 * 1024;
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
    let package_spec = ooxml_package_spec(&extension).expect("validated OOXML extension");
    let mut content_types_xml = None;
    let mut root_relationships_xml = None;
    let mut has_expected_main_part = false;
    let mut has_other_known_main_part = false;
    for index in 0..archive.len() {
        let mut entry = match archive.by_index(index) {
            Ok(entry) => entry,
            Err(_) => {
                return InspectResponse::blocked(Some(request_id), "OFFICE_INVALID_OOXML_ZIP_ENTRY")
            }
        };
        let name = entry.name().replace('\\', "/").to_ascii_lowercase();
        if name == "[content_types].xml" {
            content_types_xml = match read_xml_entry(&mut entry) {
                Ok(xml) => Some(xml),
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
            let relationship_xml = match read_xml_entry(&mut entry) {
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

fn read_xml_entry(entry: &mut zip::read::ZipFile<'_>) -> Result<String, XmlEntryReadError> {
    if entry.size() > RELATIONSHIP_MAX_BYTES {
        return Err(XmlEntryReadError::TooLarge);
    }
    let mut bytes = Vec::new();
    entry
        .by_ref()
        .take(RELATIONSHIP_MAX_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| XmlEntryReadError::Invalid)?;
    if bytes.len() as u64 > RELATIONSHIP_MAX_BYTES {
        return Err(XmlEntryReadError::TooLarge);
    }
    String::from_utf8(bytes).map_err(|_| XmlEntryReadError::Invalid)
}

fn attribute_value(
    element: &BytesStart<'_>,
    reader: &Reader<&[u8]>,
    wanted: &[u8],
) -> Result<Option<String>, ()> {
    for attribute in element.attributes() {
        let attribute = attribute.map_err(|_| ())?;
        if attribute.key.local_name().as_ref() == wanted {
            return attribute
                .decode_and_unescape_value(reader.decoder())
                .map(|value| Some(value.into_owned()))
                .map_err(|_| ());
        }
    }
    Ok(None)
}

fn relationship_values(
    element: &BytesStart<'_>,
    reader: &Reader<&[u8]>,
) -> Result<RelationshipAttributes, ()> {
    Ok(RelationshipAttributes {
        target: attribute_value(element, reader, b"Target")?,
        relationship_type: attribute_value(element, reader, b"Type")?,
        target_mode: attribute_value(element, reader, b"TargetMode")?,
    })
}

fn scan_relationships(xml: &str, expected_target: Option<&str>) -> Result<RelationshipScan, ()> {
    const OFFICE_DOCUMENT_RELATIONSHIP: &str =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";

    let mut reader = Reader::from_str(xml);
    let mut scan = RelationshipScan {
        has_expected_office_document: false,
        has_external: false,
    };

    loop {
        match reader.read_event().map_err(|_| ())? {
            Event::Start(element) | Event::Empty(element)
                if element.local_name().as_ref() == b"Relationship" =>
            {
                let attributes = relationship_values(&element, &reader)?;
                scan.has_external |= attributes.target_mode.as_deref() == Some("External");
                scan.has_expected_office_document |= expected_target.is_some_and(|expected| {
                    attributes.target.as_deref() == Some(expected)
                        && attributes.relationship_type.as_deref()
                            == Some(OFFICE_DOCUMENT_RELATIONSHIP)
                });
            }
            Event::DocType(_) => return Err(()),
            Event::Eof => return Ok(scan),
            _ => {}
        }
    }
}

fn content_types_match(xml: &str, package_spec: &OoxmlPackageSpec) -> Result<bool, ()> {
    let mut reader = Reader::from_str(xml);
    let expected_part_name = format!("/{}", package_spec.main_part);
    let mut has_expected_override = false;

    loop {
        match reader.read_event().map_err(|_| ())? {
            Event::Start(element) | Event::Empty(element)
                if element.local_name().as_ref() == b"Override" =>
            {
                let part_name = attribute_value(&element, &reader, b"PartName")?;
                let content_type = attribute_value(&element, &reader, b"ContentType")?;
                if part_name.as_deref() == Some(expected_part_name.as_str())
                    && content_type.is_some_and(|value| {
                        value.eq_ignore_ascii_case(package_spec.main_content_type)
                    })
                {
                    has_expected_override = true;
                }
            }
            Event::DocType(_) => return Err(()),
            Event::Eof => return Ok(has_expected_override),
            _ => {}
        }
    }
}
