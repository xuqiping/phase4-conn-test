use std::time::Duration;

use regex::Regex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkPreview {
    pub title: Option<String>,
    pub description: Option<String>,
}

pub fn parse_preview(html: &str) -> LinkPreview {
    LinkPreview {
        title: extract_title(html),
        description: extract_meta_description(html),
    }
}

fn extract_title(html: &str) -> Option<String> {
    let re = Regex::new(r"(?is)<title[^>]*>(.*?)</title>").unwrap();
    re.captures(html)
        .and_then(|captures| captures.get(1))
        .map(|value| clean_text(value.as_str()))
        .filter(|value| !value.is_empty())
}

fn extract_meta_description(html: &str) -> Option<String> {
    let re = Regex::new(
        r#"(?is)<meta\s+[^>]*name=[\"']description[\"'][^>]*content=[\"']([^\"']+)[\"'][^>]*>"#,
    )
    .unwrap();
    re.captures(html)
        .and_then(|captures| captures.get(1))
        .map(|value| clean_text(value.as_str()))
        .filter(|value| !value.is_empty())
}

fn clean_text(value: &str) -> String {
    value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

pub fn fetch_preview(url: &str) -> Result<LinkPreview, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(8))
        .user_agent("File Keeper link preview")
        .build()
        .map_err(|err| err.to_string())?;
    let body = client
        .get(url)
        .send()
        .and_then(|response| response.error_for_status())
        .map_err(|err| err.to_string())?
        .text()
        .map_err(|err| err.to_string())?;
    Ok(parse_preview(&body))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_title_and_description() {
        let html = r#"
          <html><head>
            <title>Example Title</title>
            <meta name="description" content="Example description">
          </head></html>
        "#;
        let preview = parse_preview(html);
        assert_eq!(preview.title.as_deref(), Some("Example Title"));
        assert_eq!(preview.description.as_deref(), Some("Example description"));
    }

    #[test]
    fn cleans_preview_text() {
        let html = r#"
          <html><head>
            <title>Example &amp; Title</title>
            <meta name="description" content="Text with   extra spaces">
          </head></html>
        "#;
        let preview = parse_preview(html);
        assert_eq!(preview.title.as_deref(), Some("Example & Title"));
        assert_eq!(
            preview.description.as_deref(),
            Some("Text with extra spaces")
        );
    }
}
