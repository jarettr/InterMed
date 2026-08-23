//! Fabric Loader-compatible JSON parsing shared by metadata and SBOM scans.

/// Escape literal control characters only inside quoted strings. Fabric
/// Loader's vendored `JsonReader` accepts these in `fabric.mod.json`, whereas
/// strict `serde_json` rejects them.
#[must_use]
pub fn escape_string_controls(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut in_string = false;
    let mut escaped = false;
    for ch in text.chars() {
        if !in_string {
            out.push(ch);
            if ch == '"' {
                in_string = true;
            }
            continue;
        }
        if escaped {
            if ch == '\n' {
                out.push('n');
            } else {
                out.push(ch);
            }
            escaped = false;
            continue;
        }
        match ch {
            '\\' => {
                out.push(ch);
                escaped = true;
            }
            '"' => {
                out.push(ch);
                in_string = false;
            }
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000c}' => out.push_str("\\f"),
            control if control <= '\u{001f}' => {
                use std::fmt::Write as _;
                let _ = write!(out, "\\u{:04x}", control as u32);
            }
            _ => out.push(ch),
        }
    }
    out
}

pub fn parse_value(text: &str) -> Result<serde_json::Value, serde_json::Error> {
    let escaped = escape_string_controls(text);
    serde_json::from_str(&escaped).or_else(|_| {
        let uncommented = strip_lenient_comments(&escaped);
        let arrays = fill_missing_array_values(&uncommented);
        serde_json::from_str(&arrays)
            .or_else(|_| serde_json::from_str(&quote_bare_array_values(&arrays)))
    })
}

/// Remove Gson-lenient line/block comments outside strings. Newlines are kept
/// so parse diagnostics and source provenance remain aligned with the input.
fn strip_lenient_comments(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut in_string = false;
    let mut escaped = false;
    let mut index = 0;
    while index < chars.len() {
        let ch = chars[index];
        if in_string {
            out.push(ch);
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            index += 1;
            continue;
        }
        if ch == '"' {
            in_string = true;
            out.push(ch);
            index += 1;
            continue;
        }
        if ch == '/' && chars.get(index + 1) == Some(&'/') {
            index += 2;
            while index < chars.len() && chars[index] != '\n' {
                index += 1;
            }
            continue;
        }
        if ch == '/' && chars.get(index + 1) == Some(&'*') {
            index += 2;
            while index + 1 < chars.len() && !(chars[index] == '*' && chars[index + 1] == '/') {
                if chars[index] == '\n' {
                    out.push('\n');
                }
                index += 1;
            }
            index = (index + 2).min(chars.len());
            continue;
        }
        if ch == '#' {
            index += 1;
            while index < chars.len() && chars[index] != '\n' {
                index += 1;
            }
            continue;
        }
        out.push(ch);
        index += 1;
    }
    out
}

/// Gson's lenient `JsonReader`, used by Fabric/Mixin metadata consumers, treats
/// a missing array element as JSON `null` (`[1,,2]`, `[,1]`, and `[1,]`). Keep
/// that compatibility narrowly scoped to arrays; missing object values and
/// other structural errors remain errors.
fn fill_missing_array_values(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut stack = Vec::new();
    let mut in_string = false;
    let mut escaped = false;
    let mut last_significant = None;

    for ch in text.chars() {
        if in_string {
            out.push(ch);
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
                last_significant = Some('"');
            }
            continue;
        }

        match ch {
            '"' => {
                in_string = true;
                out.push(ch);
            }
            '[' | '{' => {
                stack.push(ch);
                out.push(ch);
                last_significant = Some(ch);
            }
            ',' if stack.last() == Some(&'[') => {
                if matches!(last_significant, Some('[' | ',')) {
                    out.push_str("null");
                }
                out.push(ch);
                last_significant = Some(ch);
            }
            ']' if stack.last() == Some(&'[') => {
                if last_significant == Some(',') {
                    out.push_str("null");
                }
                stack.pop();
                out.push(ch);
                last_significant = Some(ch);
            }
            '}' if stack.last() == Some(&'{') => {
                stack.pop();
                out.push(ch);
                last_significant = Some(ch);
            }
            whitespace if whitespace.is_whitespace() => out.push(whitespace),
            _ => {
                out.push(ch);
                last_significant = Some(ch);
            }
        }
    }
    out
}

/// Quote non-JSON bare tokens used as array strings by Gson's lenient reader.
/// This is common in older generated Mixin configs (`[FooMixin, BarMixin]`).
/// JSON literals and numbers are left untouched.
fn quote_bare_array_values(text: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut stack = Vec::new();
    let mut in_string = false;
    let mut escaped = false;
    let mut last_significant = None;
    let mut index = 0;

    while index < chars.len() {
        let ch = chars[index];
        if in_string {
            out.push(ch);
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
                last_significant = Some('"');
            }
            index += 1;
            continue;
        }

        let expects_array_value =
            stack.last() == Some(&'[') && matches!(last_significant, Some('[' | ','));
        if expects_array_value && (ch.is_alphabetic() || matches!(ch, '_' | '$')) {
            let start = index;
            while index < chars.len()
                && !chars[index].is_whitespace()
                && !matches!(chars[index], ',' | ']')
            {
                index += 1;
            }
            let token: String = chars[start..index].iter().collect();
            if matches!(token.as_str(), "true" | "false" | "null") {
                out.push_str(&token);
            } else {
                out.push('"');
                out.push_str(&token);
                out.push('"');
            }
            last_significant = Some('v');
            continue;
        }

        match ch {
            '"' => {
                in_string = true;
                out.push(ch);
            }
            '[' | '{' => {
                stack.push(ch);
                out.push(ch);
                last_significant = Some(ch);
            }
            ']' if stack.last() == Some(&'[') => {
                stack.pop();
                out.push(ch);
                last_significant = Some(ch);
            }
            '}' if stack.last() == Some(&'{') => {
                stack.pop();
                out.push(ch);
                last_significant = Some(ch);
            }
            whitespace if whitespace.is_whitespace() => out.push(whitespace),
            _ => {
                out.push(ch);
                last_significant = Some(ch);
            }
        }
        index += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn accepts_literal_newline_inside_description() {
        let value = super::parse_value("{\"description\":\"one\ntwo\"}").unwrap();
        assert_eq!(value["description"], "one\ntwo");
    }

    #[test]
    fn structural_errors_stay_errors() {
        assert!(super::parse_value("{\"id\":}").is_err());
    }

    #[test]
    fn accepts_gson_lenient_missing_array_values() {
        let value = super::parse_value("{\"mixins\":[\"A\",,\"B\",]}").unwrap();
        assert_eq!(value["mixins"], serde_json::json!(["A", null, "B", null]));
    }

    #[test]
    fn does_not_repair_missing_object_values() {
        assert!(super::parse_value("{\"id\":,\"name\":\"x\"}").is_err());
    }

    #[test]
    fn accepts_gson_lenient_bare_mixin_names() {
        let value =
            super::parse_value("{\"mixins\":[ServerResourcesMixin, ItemStackMixin]}").unwrap();
        assert_eq!(
            value["mixins"],
            serde_json::json!(["ServerResourcesMixin", "ItemStackMixin"])
        );
    }

    #[test]
    fn accepts_gson_lenient_comments_without_touching_string_urls() {
        let value = super::parse_value(
            "{\n// build conditional\n\"url\":\"https://example.invalid/x#y\",\n\"mixins\":[\"A\",/* generated */\"B\"]\n}",
        )
        .unwrap();
        assert_eq!(value["url"], "https://example.invalid/x#y");
        assert_eq!(value["mixins"], serde_json::json!(["A", "B"]));
    }
}
