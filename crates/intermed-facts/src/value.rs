use super::*;

/// A single typed term value attached to a [`Fact`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum AttrValue {
    Str(String),
    Int(i64),
    Float(f64),
    Bool(bool),
}

impl AttrValue {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            AttrValue::Str(s) => Some(s),
            _ => None,
        }
    }

    /// Read as `f64`. Only native `Float` and `Int` values are accepted; string
    /// attributes (including numeric-looking text) are **not** coerced.
    pub fn as_f64(&self) -> Option<f64> {
        match self {
            AttrValue::Float(f) => Some(*f),
            AttrValue::Int(i) => Some(*i as f64),
            AttrValue::Str(_) | AttrValue::Bool(_) => None,
        }
    }
}

impl From<&str> for AttrValue {
    fn from(v: &str) -> Self {
        AttrValue::Str(v.to_string())
    }
}
impl From<String> for AttrValue {
    fn from(v: String) -> Self {
        AttrValue::Str(v)
    }
}
impl From<i64> for AttrValue {
    fn from(v: i64) -> Self {
        AttrValue::Int(v)
    }
}
impl From<f64> for AttrValue {
    fn from(v: f64) -> Self {
        AttrValue::Float(v)
    }
}
impl From<bool> for AttrValue {
    fn from(v: bool) -> Self {
        AttrValue::Bool(v)
    }
}

/// Where a fact came from, for provenance / `--explain` (Phase 2).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SourceRef {
    /// File or archive the fact was observed in (relative to the target root
    /// where possible).
    pub locator: String,
    /// Optional 1-based line number (for log/text sources).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<u32>,
    /// Optional inner path (e.g. `fabric.mod.json` inside a jar).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub inner: Option<String>,
}

impl SourceRef {
    pub fn file(locator: impl Into<String>) -> Self {
        Self {
            locator: locator.into(),
            line: None,
            inner: None,
        }
    }
    pub fn at_line(locator: impl Into<String>, line: u32) -> Self {
        Self {
            locator: locator.into(),
            line: Some(line),
            inner: None,
        }
    }
    pub fn inside(locator: impl Into<String>, inner: impl Into<String>) -> Self {
        Self {
            locator: locator.into(),
            line: None,
            inner: Some(inner.into()),
        }
    }
}

/// A monotonically assigned identifier, unique within a [`FactStore`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct FactId(pub u64);

impl std::fmt::Display for FactId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "f{}", self.0)
    }
}

/// An observed, atomic statement about the target.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Fact {
    pub id: FactId,
    /// Predicate name; see [`kind`].
    pub kind: String,
    /// Primary subject of the statement (e.g. a mod id). May be empty for
    /// environment-level facts.
    pub subject: String,
    /// Named terms.
    pub attributes: BTreeMap<String, AttrValue>,
    /// Provenance.
    pub source: SourceRef,
    /// 0.0..=1.0 — how certain the extractor is.
    pub confidence: f32,
    /// Id of the collector that produced this fact.
    pub extractor: String,
}

impl Fact {
    /// Read a string-valued attribute.
    pub fn attr(&self, key: &str) -> Option<&str> {
        self.attributes.get(key).and_then(AttrValue::as_str)
    }

    /// Read a bool-valued attribute.
    pub fn attr_bool(&self, key: &str) -> Option<bool> {
        match self.attributes.get(key)? {
            AttrValue::Bool(b) => Some(*b),
            _ => None,
        }
    }

    /// Read an int-valued attribute.
    pub fn attr_int(&self, key: &str) -> Option<i64> {
        match self.attributes.get(key)? {
            AttrValue::Int(i) => Some(*i),
            _ => None,
        }
    }

    /// Read a numeric attribute as `f64` (`Float` or `Int` only). Use this for
    /// thresholds that must compare numerically; store values as numbers, not
    /// formatted strings.
    pub fn attr_f64(&self, key: &str) -> Option<f64> {
        self.attributes.get(key).and_then(AttrValue::as_f64)
    }
}
