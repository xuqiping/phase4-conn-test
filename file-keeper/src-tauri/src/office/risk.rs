use quick_xml::events::Event;
use quick_xml::Reader;
use serde::{Deserialize, Serialize};
#[cfg(windows)]
use std::fs::OpenOptions;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

const COMPOUND_FILE_HEADER: [u8; 8] = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
const RELATIONSHIP_MAX_BYTES: u64 = 4 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeFileFormat {
    Xlsx,
    Csv,
    Xls,
    Xlsm,
    Docx,
    Doc,
    Docm,
    Pptx,
    Ppt,
    Pptm,
}

impl OfficeFileFormat {
    pub fn from_path(path: &Path) -> Option<Self> {
        match path.extension()?.to_str()?.to_ascii_lowercase().as_str() {
            "xlsx" => Some(Self::Xlsx),
            "csv" => Some(Self::Csv),
            "xls" => Some(Self::Xls),
            "xlsm" => Some(Self::Xlsm),
            "docx" => Some(Self::Docx),
            "doc" => Some(Self::Doc),
            "docm" => Some(Self::Docm),
            "pptx" => Some(Self::Pptx),
            "ppt" => Some(Self::Ppt),
            "pptm" => Some(Self::Pptm),
            _ => None,
        }
    }

    pub const fn requires_windows_office(self) -> bool {
        matches!(
            self,
            Self::Xls | Self::Xlsm | Self::Doc | Self::Docm | Self::Ppt | Self::Pptm
        )
    }

    const fn is_ooxml(self) -> bool {
        matches!(
            self,
            Self::Xlsx | Self::Xlsm | Self::Docx | Self::Docm | Self::Pptx | Self::Pptm
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeRiskCode {
    LegacyBinaryFormat,
    MacroEnabledFormat,
    VbaProjectPresent,
    DigitalSignaturePresent,
    ExternalRelationshipPresent,
    PasswordProtectedPackage,
    ReadOnlySource,
    FileLocked,
    InvalidPackage,
    RelationshipTooLarge,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficeRiskAssessment {
    pub risks: Vec<OfficeRiskCode>,
    pub blocked: bool,
    pub high_fidelity_required: bool,
}

pub fn assess_file_risks(
    path: &Path,
    format: OfficeFileFormat,
) -> Result<OfficeRiskAssessment, OfficeRiskCode> {
    let metadata = fs::metadata(path).map_err(|_| OfficeRiskCode::FileLocked)?;
    let mut risks = Vec::new();
    if metadata.permissions().readonly() {
        risks.push(OfficeRiskCode::ReadOnlySource);
    }
    if is_exclusively_locked(path) {
        risks.push(OfficeRiskCode::FileLocked);
    }
    if matches!(
        format,
        OfficeFileFormat::Xls | OfficeFileFormat::Doc | OfficeFileFormat::Ppt
    ) {
        risks.push(OfficeRiskCode::LegacyBinaryFormat);
    }
    if matches!(
        format,
        OfficeFileFormat::Xlsm | OfficeFileFormat::Docm | OfficeFileFormat::Pptm
    ) {
        risks.push(OfficeRiskCode::MacroEnabledFormat);
    }

    let mut blocked = risks.contains(&OfficeRiskCode::FileLocked);
    if format.is_ooxml() {
        match inspect_ooxml(path) {
            Ok(package_risks) => risks.extend(package_risks),
            Err(OfficeRiskCode::PasswordProtectedPackage) => {
                risks.push(OfficeRiskCode::PasswordProtectedPackage);
            }
            Err(error) => {
                risks.push(error);
                blocked = true;
            }
        }
    }

    let high_fidelity_required = format.requires_windows_office()
        || risks.iter().any(|risk| {
            matches!(
                risk,
                OfficeRiskCode::LegacyBinaryFormat
                    | OfficeRiskCode::MacroEnabledFormat
                    | OfficeRiskCode::VbaProjectPresent
                    | OfficeRiskCode::DigitalSignaturePresent
                    | OfficeRiskCode::ExternalRelationshipPresent
                    | OfficeRiskCode::PasswordProtectedPackage
            )
        });
    Ok(OfficeRiskAssessment {
        risks,
        blocked,
        high_fidelity_required,
    })
}

fn inspect_ooxml(path: &Path) -> Result<Vec<OfficeRiskCode>, OfficeRiskCode> {
    let mut file = File::open(path).map_err(|_| OfficeRiskCode::FileLocked)?;
    let mut header = [0_u8; 8];
    let read = file
        .read(&mut header)
        .map_err(|_| OfficeRiskCode::InvalidPackage)?;
    if read == header.len() && header == COMPOUND_FILE_HEADER {
        return Err(OfficeRiskCode::PasswordProtectedPackage);
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| OfficeRiskCode::InvalidPackage)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| OfficeRiskCode::InvalidPackage)?;
    let mut risks = Vec::new();
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|_| OfficeRiskCode::InvalidPackage)?;
        let name = entry.name().replace('\\', "/").to_ascii_lowercase();
        if name.ends_with("/vbaproject.bin") || name == "vbaproject.bin" {
            push_unique(&mut risks, OfficeRiskCode::VbaProjectPresent);
        }
        if name.starts_with("_xmlsignatures/") || name.ends_with("/vbaprojectsignature.bin") {
            push_unique(&mut risks, OfficeRiskCode::DigitalSignaturePresent);
        }
        if name.ends_with(".rels") {
            if entry.size() > RELATIONSHIP_MAX_BYTES {
                return Err(OfficeRiskCode::RelationshipTooLarge);
            }
            let mut xml = String::new();
            entry
                .read_to_string(&mut xml)
                .map_err(|_| OfficeRiskCode::InvalidPackage)?;
            if has_external_relationship(&xml)? {
                push_unique(&mut risks, OfficeRiskCode::ExternalRelationshipPresent);
            }
        }
    }
    Ok(risks)
}

fn has_external_relationship(xml: &str) -> Result<bool, OfficeRiskCode> {
    let mut reader = Reader::from_str(xml);
    loop {
        match reader
            .read_event()
            .map_err(|_| OfficeRiskCode::InvalidPackage)?
        {
            Event::Start(element) | Event::Empty(element)
                if element.local_name().as_ref() == b"Relationship" =>
            {
                for attribute in element.attributes() {
                    let attribute = attribute.map_err(|_| OfficeRiskCode::InvalidPackage)?;
                    if attribute.key.local_name().as_ref() == b"TargetMode"
                        && attribute
                            .decode_and_unescape_value(reader.decoder())
                            .map_err(|_| OfficeRiskCode::InvalidPackage)?
                            .eq_ignore_ascii_case("external")
                    {
                        return Ok(true);
                    }
                }
            }
            Event::DocType(_) => return Err(OfficeRiskCode::InvalidPackage),
            Event::Eof => return Ok(false),
            _ => {}
        }
    }
}

fn push_unique(risks: &mut Vec<OfficeRiskCode>, risk: OfficeRiskCode) {
    if !risks.contains(&risk) {
        risks.push(risk);
    }
}

#[cfg(windows)]
fn is_exclusively_locked(path: &Path) -> bool {
    use std::os::windows::fs::OpenOptionsExt;
    const GENERIC_READ: u32 = 0x8000_0000;
    OpenOptions::new()
        .read(true)
        .access_mode(GENERIC_READ)
        .share_mode(0)
        .open(path)
        .is_err()
}

#[cfg(not(windows))]
fn is_exclusively_locked(_path: &Path) -> bool {
    false
}
