//! Linux uses the desktop control model while retaining its own client identity and profiles.
use serde_json::{Value, json};

pub(crate) fn extend(catalog: &mut Value) {
    let Some(rows) = catalog.as_array_mut() else { return };
    for row in rows {
        let Some(platforms) = row["platforms"].as_array_mut() else { continue };
        if platforms.iter().any(|value| value == "windows") && !platforms.iter().any(|value| value == "linux") {
            platforms.push(json!("linux"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn extends_desktop_operations_without_exposing_android_only_actions() {
        let mut rows = json!([{"id":"desktop","platforms":["windows"]},{"id":"touch","platforms":["android"]},{"id":"shared","platforms":["windows","android"]}]);
        extend(&mut rows); let once = rows.clone(); extend(&mut rows); assert_eq!(rows, once);
        assert_eq!(rows[0]["platforms"], json!(["windows","linux"]));
        assert_eq!(rows[1]["platforms"], json!(["android"]));
        assert_eq!(rows[2]["platforms"], json!(["windows","android","linux"]));
        let mut malformed = Value::Null; extend(&mut malformed); assert!(malformed.is_null());
    }
    #[test]
    fn linux_profile_copy_preserves_desktop_settings() {
        let profile = crate::Profile::default();
        assert_eq!(serde_json::to_value(crate::profile_fields(&profile,Some("linux"))).unwrap(), serde_json::to_value(crate::profile_fields(&profile,Some("windows"))).unwrap());
        let catalog = crate::catalog();
        let action = catalog.as_array().unwrap().iter().find(|row| row["id"]=="device.connect").unwrap();
        assert!(action["platforms"].as_array().is_none_or(|values| values.iter().any(|value| value=="linux")));
    }
}
