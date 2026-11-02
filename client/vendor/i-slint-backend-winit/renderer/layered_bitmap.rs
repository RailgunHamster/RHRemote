// Shared by the opt-in presenter and RHRemote's unshown-window regression.
use windows::Win32::{
    Foundation::{COLORREF, HWND, POINT, SIZE},
    Graphics::Gdi::*,
    UI::WindowsAndMessaging::*,
};

pub(super) struct Dib {
    dc: HDC,
    bitmap: HBITMAP,
    previous: HGDIOBJ,
    bits: *mut u32,
    size: (u32, u32),
}
impl Dib {
    pub(super) fn new(width: u32, height: u32) -> Result<Self, String> {
        unsafe {
            if width == 0 || height == 0 || width.checked_mul(height).is_none_or(|n| n > 33_554_432)
            {
                return Err("Keyboard bitmap size is invalid".into());
            }
            let dc = CreateCompatibleDC(None);
            if dc.is_invalid() {
                return Err("Cannot create keyboard bitmap DC".into());
            }
            let info = BITMAPINFO {
                bmiHeader: BITMAPINFOHEADER {
                    biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
                    biWidth: width as i32,
                    biHeight: -(height as i32),
                    biPlanes: 1,
                    biBitCount: 32,
                    biCompression: BI_RGB.0,
                    ..Default::default()
                },
                ..Default::default()
            };
            let mut bits = std::ptr::null_mut();
            let bitmap = match CreateDIBSection(Some(dc), &info, DIB_RGB_COLORS, &mut bits, None, 0)
            {
                Ok(value) => value,
                Err(error) => {
                    let _ = DeleteDC(dc);
                    return Err(format!("Cannot create keyboard bitmap: {error}"));
                }
            };
            let previous = SelectObject(dc, bitmap.into());
            if bits.is_null() || previous.is_invalid() {
                let _ = DeleteObject(bitmap.into());
                let _ = DeleteDC(dc);
                return Err("Cannot select keyboard bitmap".into());
            }
            Ok(Self {
                dc,
                bitmap,
                previous,
                bits: bits.cast(),
                size: (width, height),
            })
        }
    }
    pub(super) fn present(
        &self,
        hwnd: HWND,
        width: u32,
        height: u32,
        pixels: &[u32],
    ) -> Result<(), String> {
        unsafe {
            if self.size != (width, height) || pixels.len() != width as usize * height as usize {
                return Err("Unexpected keyboard pixel count".into());
            }
            std::ptr::copy_nonoverlapping(pixels.as_ptr(), self.bits, pixels.len());
            let size = SIZE {
                cx: width as i32,
                cy: height as i32,
            };
            let source = POINT::default();
            let blend = BLENDFUNCTION {
                BlendOp: AC_SRC_OVER as u8,
                BlendFlags: 0,
                SourceConstantAlpha: 255,
                AlphaFormat: AC_SRC_ALPHA as u8,
            };
            UpdateLayeredWindow(
                hwnd,
                None,
                None,
                Some(&size),
                Some(self.dc),
                Some(&source),
                COLORREF(0),
                Some(&blend),
                ULW_ALPHA,
            )
            .map_err(|error| format!("Cannot present transparent keyboard: {error}"))
        }
    }
}
impl Drop for Dib {
    fn drop(&mut self) {
        unsafe {
            SelectObject(self.dc, self.previous);
            let _ = DeleteObject(self.bitmap.into());
            let _ = DeleteDC(self.dc);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::core::w;
    #[test]
    fn keyboard_pixels_reach_windows_with_independent_fill_and_letter_alpha() {
        unsafe {
            // An unshown, private test window. No desktop capture or user input.
            let hwnd = CreateWindowExW(
                WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                w!("STATIC"),
                w!("RHRemote alpha fixture"),
                WS_POPUP,
                0,
                0,
                8,
                4,
                None,
                None,
                None,
                None,
            )
            .unwrap();
            struct Window(HWND);
            impl Drop for Window {
                fn drop(&mut self) {
                    unsafe {
                        let _ = DestroyWindow(self.0);
                    }
                }
            }
            let _owned = Window(hwnd);
            let dib = Dib::new(8, 4).unwrap();
            let mut pixels = vec![0u32; 32];
            pixels[1] = 0x50080a0e;
            pixels[2] = 0xffffffff;
            pixels[3] = 0xff101724;
            dib.present(hwnd, 8, 4, &pixels).unwrap();
            assert_eq!(*dib.bits, 0);
            assert_eq!(*dib.bits.add(1), 0x50080a0e);
            assert_eq!(*dib.bits.add(2), 0xffffffff);
            assert_eq!(*dib.bits.add(3), 0xff101724);
            pixels[2] = 0;
            dib.present(hwnd, 8, 4, &pixels).unwrap();
            assert_eq!(*dib.bits.add(2), 0);
            assert!(!IsWindowVisible(hwnd).as_bool());
        }
    }
}
