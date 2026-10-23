//! Transactional mode changes for an existing, driver-verified owned monitor.
//! After a write is attempted, errors are terminal; never retry through another helper.
use super::{Display, initial_capture_index, map_capture, query, topology};
use serde_json::{Value, json};
use std::{collections::HashSet, mem::size_of, path::Path, time::Instant};
use windows::{Win32::Graphics::Gdi::*, core::PCWSTR};

fn dimensions(request: &Value) -> Result<(u32, u32), String> {
    let number = |key| request[key].as_u64().and_then(|v| u32::try_from(v).ok());
    let (Some(width), Some(height)) = (number("width"), number("height")) else {
        return Err("Invalid virtual display dimensions".into());
    };
    if !(320..=8192).contains(&width)
        || !(320..=8192).contains(&height)
        || width % 2 != 0
        || height % 2 != 0
        || u64::from(width) * u64::from(height) > 64_000_000
    {
        return Err("Invalid virtual display dimensions".into());
    }
    Ok((width, height))
}
fn target(
    displays: &[Display],
    mode: &str,
    width: u32,
    height: u32,
) -> Result<Option<Display>, String> {
    if !matches!(mode, "virtual" | "auto")
        || mode == "auto" && displays.iter().any(|d| d.available && !d.is_virtual)
    {
        return Ok(None);
    }
    let owned: Vec<_> = displays.iter().filter(|d| d.owned).collect();
    if owned.is_empty() {
        return Ok(None);
    } // Creation stays with the existing helper.
    if owned.len() != 1
        || displays.iter().map(|d| &d.id).collect::<HashSet<_>>().len() != displays.len()
    {
        return Err("Virtual monitor ownership is ambiguous".into());
    }
    let d = owned[0];
    if d.width == width && d.height == height {
        return Ok(None);
    }
    if !d.driver_virtual || !d.is_virtual {
        return Err("The owned monitor is not backed by a supported virtual display driver".into());
    }
    if !d.available || d.rotation != 1 || d.primary && displays.len() > 1 {
        return Ok(None); // Preserve compatibility with the broader legacy path.
    }
    let right = i64::from(d.x) + i64::from(width);
    let bottom = i64::from(d.y) + i64::from(height);
    if right > i64::from(i32::MAX) || bottom > i64::from(i32::MAX) {
        return Err("Requested virtual monitor bounds exceed Windows coordinates".into());
    }
    for other in displays.iter().filter(|other| other.id != d.id) {
        if i64::from(d.x) < i64::from(other.x) + i64::from(other.width)
            && i64::from(other.x) < right
            && i64::from(d.y) < i64::from(other.y) + i64::from(other.height)
            && i64::from(other.y) < bottom
        {
            return Err("Requested virtual monitor size would overlap an existing monitor".into());
        }
    }
    Ok(Some(d.clone()))
}

/// Windows and the failure-injection backend share the complete transaction.
trait ModeControl {
    type Mode;
    fn inventory(&mut self) -> Result<Vec<Display>, String>;
    fn plan(
        &mut self,
        display: &Display,
        width: u32,
        height: u32,
    ) -> Result<(Self::Mode, Self::Mode), String>;
    fn validate(&mut self, display: &Display, mode: &Self::Mode) -> Result<(), String>;
    fn apply(&mut self, display: &Display, mode: &Self::Mode) -> Result<(), String>;
    fn capture(&mut self) -> Result<Value, String>;
}
struct WindowsControl<'a> {
    owned: &'a str,
}
fn device(display: &Display) -> Result<Vec<u16>, String> {
    if display.gdi.is_empty() || display.gdi.contains('\0') {
        return Err("The selected monitor has no concrete GDI device name".into());
    }
    Ok(display.gdi.encode_utf16().chain(Some(0)).collect())
}
fn empty_mode() -> DEVMODEW {
    DEVMODEW {
        dmSize: size_of::<DEVMODEW>() as u16,
        ..Default::default()
    }
}
impl ModeControl for WindowsControl<'_> {
    type Mode = DEVMODEW;
    fn inventory(&mut self) -> Result<Vec<Display>, String> {
        query(self.owned)
    }
    fn plan(
        &mut self,
        display: &Display,
        width: u32,
        height: u32,
    ) -> Result<(DEVMODEW, DEVMODEW), String> {
        let name = device(display)?;
        let mut original = empty_mode();
        if !unsafe {
            EnumDisplaySettingsW(PCWSTR(name.as_ptr()), ENUM_CURRENT_SETTINGS, &mut original)
        }
        .as_bool()
        {
            return Err("Cannot read the current virtual display mode".into());
        }
        if original.dmPelsWidth != display.width
            || original.dmPelsHeight != display.height
            || display.refresh_denominator == 0
            || u64::from(display.refresh_numerator)
                != u64::from(original.dmDisplayFrequency) * u64::from(display.refresh_denominator)
        {
            return Err("Current virtual mode differs from its topology snapshot".into());
        }
        let mut supported = false;
        for index in 0..2048 {
            let mut mode = empty_mode();
            if !unsafe {
                EnumDisplaySettingsW(
                    PCWSTR(name.as_ptr()),
                    ENUM_DISPLAY_SETTINGS_MODE(index),
                    &mut mode,
                )
            }
            .as_bool()
            {
                break;
            }
            if mode.dmPelsWidth == width
                && mode.dmPelsHeight == height
                && mode.dmDisplayFrequency == 60
                && mode.dmBitsPerPel >= 32
            {
                supported = true;
                break;
            }
        }
        if !supported {
            return Err("The virtual driver does not support this resolution at 60 Hz".into());
        }
        // Use the driver's current DEVMODE, change only the four fields used by
        // the existing helper, and never save to the display registry.
        original.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_POSITION;
        original.Anonymous1.Anonymous2.dmPosition.x = display.x;
        original.Anonymous1.Anonymous2.dmPosition.y = display.y;
        let mut candidate = original;
        candidate.dmPelsWidth = width;
        candidate.dmPelsHeight = height;
        candidate.dmDisplayFrequency = 60;
        Ok((original, candidate))
    }
    fn validate(&mut self, display: &Display, mode: &DEVMODEW) -> Result<(), String> {
        let name = device(display)?;
        let result = unsafe {
            ChangeDisplaySettingsExW(PCWSTR(name.as_ptr()), Some(mode), None, CDS_TEST, None)
        };
        if result != DISP_CHANGE_SUCCESSFUL {
            return Err(format!("Virtual display validation failed: {}", result.0));
        }
        Ok(())
    }
    fn apply(&mut self, display: &Display, mode: &DEVMODEW) -> Result<(), String> {
        let name = device(display)?;
        let result = unsafe {
            ChangeDisplaySettingsExW(PCWSTR(name.as_ptr()), Some(mode), None, CDS_TYPE(0), None)
        };
        if result != DISP_CHANGE_SUCCESSFUL {
            return Err(format!("Virtual display mode change failed: {}", result.0));
        }
        Ok(())
    }
    fn capture(&mut self) -> Result<Value, String> {
        crate::displays::capture_order()
    }
}
fn other_topology(displays: &[Display], id: &str) -> Vec<Display> {
    displays.iter().filter(|d| d.id != id).cloned().collect()
}
fn verify(
    before: &[Display],
    after: &[Display],
    old: &Display,
    width: u32,
    height: u32,
) -> Result<(), String> {
    if before.len() != after.len()
        || topology(&other_topology(before, &old.id)) != topology(&other_topology(after, &old.id))
    {
        return Err("Another monitor changed during virtual display preparation".into());
    }
    let matching: Vec<_> = after.iter().filter(|d| d.id == old.id).collect();
    if matching.len() != 1 {
        return Err("The owned virtual monitor disappeared or became ambiguous".into());
    }
    let now = matching[0];
    if now.gdi != old.gdi
        || now.x != old.x
        || now.y != old.y
        || now.rotation != old.rotation
        || now.primary != old.primary
        || !now.owned
        || !now.driver_virtual
        || !now.available
        || now.width != width
        || now.height != height
        || now.refresh_denominator == 0
        || u64::from(now.refresh_numerator) != 60 * u64::from(now.refresh_denominator)
    {
        return Err("Virtual display mode verification failed".into());
    }
    Ok(())
}
#[derive(Debug)]
struct Resized {
    displays: Vec<Display>,
    selected: Display,
    capture: Value,
    validation_ms: f64,
    apply_ms: f64,
    capture_ms: f64,
    verification_ms: f64,
}
fn transaction<B: ModeControl>(
    backend: &mut B,
    before: &[Display],
    old: &Display,
    width: u32,
    height: u32,
) -> Result<Resized, String> {
    let validation = Instant::now();
    if topology(before) != topology(&backend.inventory()?) {
        return Err("Display topology changed before mode validation".into());
    }
    let (original, candidate) = backend.plan(old, width, height)?;
    backend.validate(old, &candidate)?;
    if topology(before) != topology(&backend.inventory()?) {
        return Err("Display topology changed during mode validation".into());
    }
    let validation_ms = validation.elapsed().as_secs_f64() * 1000.0;
    // An API can report failure after doing work. Inspect and roll back even
    // when apply() itself fails, not just later verification failures.
    let applying = Instant::now();
    let result: Result<Resized, String> = (|| {
        backend.apply(old, &candidate)?;
        let apply_ms = applying.elapsed().as_secs_f64() * 1000.0;
        let verifying = Instant::now();
        let after = backend.inventory()?;
        verify(before, &after, old, width, height)?;
        let capture_started = Instant::now();
        let capture = backend.capture()?;
        let capture_ms = capture_started.elapsed().as_secs_f64() * 1000.0;
        let mut displays = backend.inventory()?;
        if topology(&after) != topology(&displays) {
            return Err("Display topology changed during fresh capture enumeration".into());
        }
        verify(before, &displays, old, width, height)?;
        map_capture(&mut displays, &capture)?;
        let selected = displays
            .iter()
            .find(|d| d.id == old.id && d.available)
            .ok_or("The resized virtual monitor is not eligible for capture")?
            .clone();
        Ok(Resized {
            displays,
            selected,
            capture,
            validation_ms,
            apply_ms,
            capture_ms,
            verification_ms: verifying.elapsed().as_secs_f64() * 1000.0 - capture_ms,
        })
    })();
    match result {
        Ok(done) => Ok(done),
        Err(failure) => {
            let rollback: Result<(), String> = (|| {
                let current = backend.inventory()?;
                if topology(before) == topology(&current) {
                    return Ok(());
                }
                // Never overwrite an operator's concurrent mode or topology.
                verify(before, &current, old, width, height)?;
                backend.apply(old, &original)?;
                if topology(before) != topology(&backend.inventory()?) {
                    return Err("Original topology was not restored".to_string());
                }
                Ok(())
            })();
            match rollback {
                Ok(()) => Err(format!(
                    "{failure}; original virtual mode retained or restored"
                )),
                Err(_) => Err(format!(
                    "{failure}; rollback needs on-host inspection because the topology changed or became unavailable"
                )),
            }
        }
    }
}

/// None means no applicable mode change; every attempted transaction returns a
/// terminal result, including failures, to prevent a second PowerShell mutation.
pub(super) fn prepare(
    request: &Value,
    before: &[Display],
    owned: &str,
    root: &Path,
    desktop: &str,
    started: Instant,
    query_ms: f64,
) -> Option<Value> {
    let mode = request["mode"].as_str()?;
    if !matches!(mode, "auto" | "virtual") {
        return None;
    }
    let result: Result<Option<Value>, String> = (|| {
        let (width, height) = dimensions(request)?;
        let Some(selected) = target(before, mode, width, height)? else {
            return Ok(None);
        };
        let initial_index = initial_capture_index(root)?;
        let mut backend = WindowsControl { owned };
        let resized = transaction(&mut backend, before, &selected, width, height)?;
        Ok(Some(
            json!({"ok":true,"schema":"rhremote.displays/v1","displays":resized.displays,
            "selected":resized.selected,"created":false,"captureDiagnostics":resized.capture["diagnostics"],
            "inputDesktop":desktop,"initialCaptureIndex":initial_index,
            "timings":{"path":"native","modeChanged":true,"queryMs":query_ms,"modeValidationMs":resized.validation_ms,
                "modeApplyMs":resized.apply_ms,"modeVerificationMs":resized.verification_ms,"captureMs":resized.capture_ms,
                "captureBreakdown":resized.capture["timings"],"nativeMs":started.elapsed().as_secs_f64()*1000.0}}),
        ))
    })();
    match result {
        Ok(value) => value,
        Err(error) => Some(
            json!({"ok":false,"error":error,"timings":{"path":"native","modeChangeError":true,"queryMs":query_ms,"nativeMs":started.elapsed().as_secs_f64()*1000.0}}),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn screen(id: &str, owned: bool, x: i32) -> Display {
        Display {
            id: id.into(),
            gdi: id.into(),
            owned,
            driver_virtual: owned,
            is_virtual: owned,
            x,
            y: 0,
            width: if owned { 2520 } else { 1920 },
            height: if owned { 1680 } else { 1080 },
            available: true,
            primary: x == 0,
            rotation: 1,
            refresh_numerator: 60,
            refresh_denominator: 1,
            ..Default::default()
        }
    }
    struct Fake {
        displays: Vec<Display>,
        applied: usize,
        captures: usize,
        fail_validate: bool,
        fail_apply_before: bool,
        fail_apply_after: bool,
        fail_capture: bool,
        omit_capture: bool,
        foreign_capture: bool,
        foreign_validate: bool,
    }
    impl Fake {
        fn new() -> Self {
            Self {
                displays: vec![screen("physical", false, 0), screen("owned", true, 1920)],
                applied: 0,
                captures: 0,
                fail_validate: false,
                fail_apply_before: false,
                fail_apply_after: false,
                fail_capture: false,
                omit_capture: false,
                foreign_capture: false,
                foreign_validate: false,
            }
        }
    }
    impl ModeControl for Fake {
        type Mode = Display;
        fn inventory(&mut self) -> Result<Vec<Display>, String> {
            Ok(self.displays.clone())
        }
        fn plan(
            &mut self,
            d: &Display,
            width: u32,
            height: u32,
        ) -> Result<(Display, Display), String> {
            let mut candidate = d.clone();
            candidate.width = width;
            candidate.height = height;
            candidate.refresh_numerator = 60;
            candidate.refresh_denominator = 1;
            Ok((d.clone(), candidate))
        }
        fn validate(&mut self, _: &Display, _: &Display) -> Result<(), String> {
            if self.foreign_validate {
                self.displays[0].x = 77;
            }
            if self.fail_validate {
                Err("Injected CDS_TEST rejection".into())
            } else {
                Ok(())
            }
        }
        fn apply(&mut self, d: &Display, mode: &Display) -> Result<(), String> {
            self.applied += 1;
            if self.fail_apply_before {
                return Err("Injected apply failure before write".into());
            }
            *self.displays.iter_mut().find(|v| v.id == d.id).unwrap() = mode.clone();
            if self.fail_apply_after {
                self.fail_apply_after = false;
                return Err("Injected apply failure after write".into());
            }
            Ok(())
        }
        fn capture(&mut self) -> Result<Value, String> {
            self.captures += 1;
            if self.foreign_capture {
                self.displays[0].x = 77;
            }
            if self.fail_capture {
                return Err("Injected capture failure".into());
            }
            Ok(
                json!({"names":self.displays.iter().filter(|d|!self.omit_capture||!d.owned).map(|d|d.gdi.clone()).collect::<Vec<_>>(),"diagnostics":[],"timings":{}}),
            )
        }
    }
    fn run(fake: &mut Fake) -> Result<Resized, String> {
        let before = fake.displays.clone();
        let old = before[1].clone();
        transaction(fake, &before, &old, 1920, 1080)
    }
    #[test]
    fn resize_verifies_only_owned_target_and_enumerates_capture_once() {
        let mut f = Fake::new();
        let original = f.displays[0].clone();
        let result = run(&mut f).unwrap();
        assert_eq!(result.selected.width, 1920);
        assert_eq!(result.selected.height, 1080);
        assert_eq!(result.selected.capture_index, 2);
        assert_eq!(f.applied, 1);
        assert_eq!(f.captures, 1);
        assert_eq!(topology(&[original]), topology(&f.displays[..1]));
    }
    #[test]
    fn portrait_and_landscape_keep_position_and_rotation() {
        for (w, h) in [(1680, 2520), (3840, 2160)] {
            let mut f = Fake::new();
            let before = f.displays.clone();
            let r = transaction(&mut f, &before, &before[1], w, h).unwrap();
            assert_eq!(
                (
                    r.selected.width,
                    r.selected.height,
                    r.selected.x,
                    r.selected.y,
                    r.selected.rotation
                ),
                (w, h, 1920, 0, 1)
            );
        }
    }
    #[test]
    fn scope_preserves_auto_physical_and_falls_back_only_for_creation_or_special_layout() {
        let f = Fake::new();
        assert!(target(&f.displays, "auto", 1920, 1080).unwrap().is_none());
        assert!(
            target(&f.displays, "physical", 1920, 1080)
                .unwrap()
                .is_none()
        );
        assert!(
            target(&f.displays, "virtual", 2520, 1680)
                .unwrap()
                .is_none()
        );
        assert!(
            target(&f.displays[..1], "virtual", 1920, 1080)
                .unwrap()
                .is_none()
        );
        let mut solo = vec![screen("owned", true, 0)];
        assert!(target(&solo, "auto", 1920, 1080).unwrap().is_some());
        solo.push(screen("other", false, 2520));
        assert!(target(&solo, "virtual", 1920, 1080).unwrap().is_none());
    }
    #[test]
    fn unowned_driver_and_ambiguous_ownership_are_rejected() {
        let mut f = Fake::new();
        f.displays[1].driver_virtual = false;
        assert!(target(&f.displays, "virtual", 1920, 1080).is_err());
        f.displays[1].driver_virtual = true;
        f.displays.push(screen("second_owned", true, 4440));
        assert!(target(&f.displays, "virtual", 1920, 1080).is_err());
    }
    #[test]
    fn growing_into_another_monitor_and_coordinate_overflow_are_rejected() {
        let mut f = Fake::new();
        f.displays.push(screen("right", false, 4440));
        assert!(target(&f.displays, "virtual", 3840, 2160).is_err());
        f.displays[1].x = i32::MAX - 500;
        assert!(target(&f.displays, "virtual", 1920, 1080).is_err());
    }
    #[test]
    fn invalid_dimensions_cannot_wrap_or_exceed_the_request_limit() {
        for (w, h) in [
            (u64::MAX, 1080),
            (0, 1080),
            (1921, 1080),
            (8192, 8192),
            (1920, 1),
        ] {
            assert!(dimensions(&json!({"width":w,"height":h})).is_err());
        }
        assert_eq!(
            dimensions(&json!({"width":1680,"height":2520})).unwrap(),
            (1680, 2520)
        );
    }
    #[test]
    fn failed_validation_makes_no_mode_change() {
        let mut f = Fake::new();
        let old = f.displays.clone();
        f.fail_validate = true;
        assert!(run(&mut f).is_err());
        assert_eq!(f.applied, 0);
        assert_eq!(topology(&old), topology(&f.displays));
    }
    #[test]
    fn failed_apply_is_inspected_both_before_and_after_partial_work() {
        let mut f = Fake::new();
        let old = f.displays.clone();
        f.fail_apply_before = true;
        assert!(run(&mut f).unwrap_err().contains("retained or restored"));
        assert_eq!(f.applied, 1);
        assert_eq!(topology(&old), topology(&f.displays));
        f.fail_apply_before = false;
        f.fail_apply_after = true;
        f.applied = 0;
        assert!(run(&mut f).unwrap_err().contains("retained or restored"));
        assert_eq!(f.applied, 2);
        assert_eq!(topology(&old), topology(&f.displays));
    }
    #[test]
    fn capture_failure_and_ineligible_output_restore_the_original_mode() {
        for fail in [true, false] {
            let mut f = Fake::new();
            let before = f.displays.clone();
            f.fail_capture = fail;
            f.omit_capture = !fail;
            assert!(run(&mut f).unwrap_err().contains("retained or restored"));
            assert_eq!(f.applied, 2);
            assert_eq!(topology(&before), topology(&f.displays));
        }
    }
    #[test]
    fn concurrent_changes_are_preserved_instead_of_blind_rollback() {
        let mut f = Fake::new();
        f.foreign_capture = true;
        assert!(run(&mut f).unwrap_err().contains("inspection"));
        assert_eq!(f.applied, 1);
        assert_eq!(f.displays[0].x, 77);
        let mut f = Fake::new();
        f.foreign_validate = true;
        assert!(run(&mut f).unwrap_err().contains("during mode validation"));
        assert_eq!(f.applied, 0);
        assert_eq!(f.displays[0].x, 77);
    }
    #[test]
    fn topology_order_does_not_masquerade_as_a_layout_change() {
        let f = Fake::new();
        let mut reversed = f.displays.clone();
        reversed.reverse();
        assert_eq!(topology(&f.displays), topology(&reversed));
    }
}
