use regex::Regex;
use url::Url;

pub fn normalize_search_text(input: &str) -> String {
    input
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

pub fn normalize_url(input: &str) -> Option<String> {
    let mut url = Url::parse(input.trim()).ok()?;
    url.set_fragment(None);
    Some(url.to_string())
}

pub fn detect_color(input: &str) -> Option<(String, String)> {
    let trimmed = input.trim();
    detect_hex_color(trimmed).or_else(|| detect_rgb_color(trimmed))
}

fn detect_hex_color(input: &str) -> Option<(String, String)> {
    let hex = input.strip_prefix('#')?;
    if hex.len() != 6 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }

    let normalized = format!("#{}", hex.to_uppercase());
    let r = u8::from_str_radix(&hex[0..2], 16).ok()?;
    let g = u8::from_str_radix(&hex[2..4], 16).ok()?;
    let b = u8::from_str_radix(&hex[4..6], 16).ok()?;
    Some((normalized, format!("rgb({}, {}, {})", r, g, b)))
}

fn detect_rgb_color(input: &str) -> Option<(String, String)> {
    let re = Regex::new(r"^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$").unwrap();
    let captures = re.captures(input)?;
    let r = captures.get(1)?.as_str().parse::<u8>().ok()?;
    let g = captures.get(2)?.as_str().parse::<u8>().ok()?;
    let b = captures.get(3)?.as_str().parse::<u8>().ok()?;
    Some((
        format!("#{:02X}{:02X}{:02X}", r, g, b),
        format!("rgb({}, {}, {})", r, g, b),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_text_for_search() {
        assert_eq!(normalize_search_text("  Hello\nWorld  "), "hello world");
    }

    #[test]
    fn normalizes_url_by_lowering_host_and_dropping_fragment() {
        assert_eq!(
            normalize_url("HTTPS://Example.COM/path?a=1#section").as_deref(),
            Some("https://example.com/path?a=1")
        );
    }

    #[test]
    fn detects_hex_color() {
        assert_eq!(
            detect_color("#22c55e"),
            Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string()))
        );
    }

    #[test]
    fn detects_rgb_color() {
        assert_eq!(
            detect_color("rgb(34, 197, 94)"),
            Some(("#22C55E".to_string(), "rgb(34, 197, 94)".to_string()))
        );
    }
}
