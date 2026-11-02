use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub const FILE: &str = "releases.history.json";
pub const LIMIT: usize = 2 * 1024 * 1024;
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Release {
    pub version: String,
    pub published_at: String,
    pub notes: String,
}
pub fn version(value: &str) -> Option<[u32; 3]> {
    let parts = value.split('.').collect::<Vec<_>>();
    if parts.len() != 3 {
        return None;
    }
    let mut result = [0; 3];
    for (i, part) in parts.iter().enumerate() {
        if part.is_empty()
            || !part.bytes().all(|c| c.is_ascii_digit())
            || part.len() > 1 && part.starts_with('0')
        {
            return None;
        }
        result[i] = part.parse().ok()?;
    }
    Some(result)
}
pub fn parse(bytes: &[u8]) -> Result<Vec<Release>, String> {
    if bytes.len() > LIMIT {
        return Err("更新历史过大".into());
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Feed {
        schema_version: u32,
        releases: Vec<Release>,
    }
    let feed: Feed = serde_json::from_slice(bytes).map_err(|_| "更新历史格式无效")?;
    if feed.schema_version != 1 || feed.releases.len() > 128 {
        return Err("更新历史格式不支持".into());
    }
    let mut seen = HashSet::new();
    for item in &feed.releases {
        if version(&item.version).is_none()
            || !seen.insert(item.version.clone())
            || item.published_at.len() > 80
            || item.notes.len() > 65536
        {
            return Err("更新历史条目无效".into());
        }
    }
    let mut items = feed.releases;
    items.sort_by_key(|item| std::cmp::Reverse(version(&item.version).unwrap()));
    Ok(items)
}
pub fn bundled() -> Vec<Release> {
    parse(include_bytes!("../../../shared/release-history.json"))
        .expect("Valid public release history")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn stable_history_is_sorted_and_rejects_duplicates_previews_and_excessive_notes() {
        let item = |v: &str| json!({"version":v,"publishedAt":"2026-11-02T10:19:23Z","notes":"# 标题\n\n- **修复**"});
        let feed = |items: Vec<serde_json::Value>| {
            serde_json::to_vec(&json!({"schemaVersion":1,"releases":items})).unwrap()
        };
        assert_eq!(
            parse(&feed(vec![item("0.2.9"), item("0.2.17")])).unwrap()[0].version,
            "0.2.17"
        );
        for items in [
            vec![item("0.2.17"), item("0.2.17")],
            vec![item("0.2.18-preview.1")],
            vec![json!({"version":"0.2.18","publishedAt":"","notes":"x".repeat(65537)})],
        ] {
            assert!(parse(&feed(items)).is_err());
        }
    }
    #[test]
    fn offline_catalog_contains_real_published_versions() {
        let data = bundled();
        assert!(data.iter().any(|r| r.version == "0.2.17"));
        assert!(data.iter().any(|r| r.version == "0.2.0"));
    }
}
