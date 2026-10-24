//! The control UI does not need a GPU context. Streaming remains in the native engine.
use serde_json::{Value, json};

pub fn renderer() -> &'static str {
    if cfg!(feature = "ui-opengl") {
        "femtovg"
    } else {
        "software"
    }
}
pub fn metadata() -> Value {
    json!({"backend":"winit","renderer":renderer(),"videoRenderer":"separate_native_process","openGlDiagnosticBuild":cfg!(feature="ui-opengl")})
}

#[cfg(windows)]
mod region {
    use super::*;
    use windows::Win32::{
        Foundation::{HWND, POINT, RECT},
        Graphics::Gdi::*,
        UI::WindowsAndMessaging::*,
    };

    struct OwnedRegion(HRGN);
    impl Drop for OwnedRegion {
        fn drop(&mut self) {
            unsafe {
                let _ = DeleteObject(self.0.into());
            }
        }
    }
    impl OwnedRegion {
        fn round(
            left: i32,
            top: i32,
            right: i32,
            bottom: i32,
            radius: i32,
        ) -> Result<Self, String> {
            let region =
                unsafe { CreateRoundRectRgn(left, top, right, bottom, radius * 2, radius * 2) };
            if region.is_invalid() {
                Err("Cannot create control window region".into())
            } else {
                Ok(Self(region))
            }
        }
        fn empty() -> Result<Self, String> {
            let region = unsafe { CreateRectRgn(0, 0, 0, 0) };
            if region.is_invalid() {
                Err("Cannot inspect control window region".into())
            } else {
                Ok(Self(region))
            }
        }
        fn contains(&self, x: i32, y: i32) -> bool {
            unsafe { PtInRegion(self.0, x, y).as_bool() }
        }
    }
    #[derive(Clone, Copy)]
    struct Geometry {
        width: i32,
        height: i32,
        x: i32,
        y: i32,
        scale: f32,
        expanded: bool,
    }
    impl Geometry {
        fn toggle(&self) -> (i32, i32, i32) {
            let w = ((72.0 * self.scale) as i32).min(self.width).max(1);
            let h = ((28.0 * self.scale) as i32).min(self.height).max(1);
            (self.x + (self.width - w) / 2, w, h)
        }
        fn region(&self) -> Result<OwnedRegion, String> {
            if self.width < 1 || self.height < 1 || !self.scale.is_finite() || self.scale <= 0.0 {
                return Err("Invalid control window geometry".into());
            }
            let (left, w, h) = self.toggle();
            let shape = OwnedRegion::round(
                left,
                self.y,
                left + w,
                self.y + h,
                (10.0 * self.scale) as i32,
            )?;
            if self.expanded && self.height > h {
                let panel = OwnedRegion::round(
                    self.x,
                    self.y + h,
                    self.x + self.width,
                    self.y + self.height,
                    (12.0 * self.scale) as i32,
                )?;
                if unsafe { CombineRgn(Some(shape.0), Some(shape.0), Some(panel.0), RGN_OR) }
                    == RGN_ERROR
                {
                    return Err("Cannot combine control window region".into());
                }
            }
            Ok(shape)
        }
        fn points(&self, shape: &OwnedRegion) -> Value {
            let (left, w, h) = self.toggle();
            json!({"toggleInside":shape.contains(left+w/2,self.y+h/2),"topSidesExcluded":self.width<=w||(!shape.contains(self.x,self.y+h/2)&&!shape.contains(self.x+self.width-1,self.y+h/2)),"panelInside":!self.expanded||self.height<=h||shape.contains(self.x+self.width/2,self.y+h+(self.height-h)/2)})
        }
    }
    pub(super) fn apply(
        hwnd: HWND,
        scale: f32,
        expanded: bool,
        previous: &Value,
    ) -> Result<Value, String> {
        let mut pid = 0;
        unsafe {
            GetWindowThreadProcessId(hwnd, Some(&mut pid));
        }
        if pid != std::process::id() {
            return Err("Control window is not owned by this UI".into());
        }
        let (mut client, mut window, mut origin) =
            (RECT::default(), RECT::default(), POINT::default());
        unsafe {
            GetClientRect(hwnd, &mut client).map_err(|e| e.to_string())?;
            GetWindowRect(hwnd, &mut window).map_err(|e| e.to_string())?;
            if !ClientToScreen(hwnd, &mut origin).as_bool() {
                return Err("Cannot locate control window client area".into());
            }
        }
        let g = Geometry {
            width: client.right - client.left,
            height: client.bottom - client.top,
            x: origin.x - window.left,
            y: origin.y - window.top,
            scale,
            expanded,
        };
        let key = json!({"hwnd":format!("{}",hwnd.0 as usize),"width":g.width,"height":g.height,"offsetX":g.x,"offsetY":g.y,"scale":scale,"expanded":expanded});
        if previous["applied"] == true && previous["key"] == key {
            return Ok(previous.clone());
        }
        let shape = g.region()?;
        if unsafe { SetWindowRgn(hwnd, Some(shape.0), true) } == 0 {
            return Err("Cannot apply control window shape".into());
        }
        // SetWindowRgn takes ownership only after success.
        std::mem::forget(shape);
        let actual = OwnedRegion::empty()?;
        if unsafe { GetWindowRgn(hwnd, actual.0) } == RGN_ERROR {
            return Err("Cannot read back control window shape".into());
        }
        let points = g.points(&actual);
        if points["toggleInside"] != true
            || points["topSidesExcluded"] != true
            || points["panelInside"] != true
        {
            return Err("Control window hit region does not match its visible controls".into());
        }
        Ok(json!({"applied":true,"mode":"window_region","key":key,"points":points}))
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        #[test]
        fn expanded_toolbar_excludes_top_sides_and_retains_both_control_rows() {
            for scale in [1.0, 1.25, 1.5, 2.0] {
                let g = Geometry {
                    width: (1020.0 * scale) as i32,
                    height: (144.0 * scale) as i32,
                    x: 0,
                    y: 0,
                    scale,
                    expanded: true,
                };
                let shape = g.region().unwrap();
                assert_eq!(
                    g.points(&shape),
                    json!({"toggleInside":true,"topSidesExcluded":true,"panelInside":true})
                );
                assert!(shape.contains(g.width / 2, (70.0 * scale) as i32));
                assert!(shape.contains(g.width / 2, (120.0 * scale) as i32));
                assert!(!shape.contains(0, 0));
                assert!(!shape.contains(g.width / 2, g.height));
            }
        }
        #[test]
        fn collapsed_and_client_offset_regions_match_the_toggle() {
            let g = Geometry {
                width: 90,
                height: 35,
                x: 8,
                y: 5,
                scale: 1.25,
                expanded: false,
            };
            let shape = g.region().unwrap();
            assert!(shape.contains(53, 22));
            assert!(!shape.contains(0, 0));
            assert!(!shape.contains(53, 40));
            assert_eq!(g.points(&shape)["toggleInside"], true);
        }
        #[test]
        fn narrow_and_invalid_sizes_are_bounded() {
            for width in [40, 90, 800] {
                let g = Geometry {
                    width,
                    height: 180,
                    x: 0,
                    y: 0,
                    scale: 1.25,
                    expanded: true,
                };
                let shape = g.region().unwrap();
                assert_eq!(g.points(&shape)["panelInside"], true);
            }
            assert!(
                Geometry {
                    width: 0,
                    height: 35,
                    x: 0,
                    y: 0,
                    scale: 1.25,
                    expanded: true
                }
                .region()
                .is_err()
            );
        }
    }
}

#[cfg(windows)]
pub fn toolbar_shape(
    hwnd: windows::Win32::Foundation::HWND,
    scale: f32,
    expanded: bool,
    previous: &Value,
) -> Value {
    if renderer() != "software" {
        return json!({"mode":"gpu_alpha"});
    }
    region::apply(hwnd, scale, expanded, previous)
        .unwrap_or_else(|error| json!({"applied":false,"mode":"window_region","error":error}))
}
