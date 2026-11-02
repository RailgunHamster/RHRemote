// RHRemote opt-in. Ordinary windows continue using upstream softbuffer. No
// desktop/video pixels are read; only the existing UI buffer is presented.
use super::SoftBufferPixel;
use i_slint_core::platform::PlatformError;
use i_slint_renderer_software::{RepaintBufferType, SoftwareRenderer};
use raw_window_handle::{HasWindowHandle, RawWindowHandle};
use windows::{
    Win32::{Foundation::HWND, UI::WindowsAndMessaging::GetPropW},
    core::w,
};
#[path = "layered_bitmap.rs"]
mod bitmap;

pub(super) fn target(window: &winit::window::Window) -> Option<HWND> {
    let RawWindowHandle::Win32(handle) = window.window_handle().ok()?.as_raw() else {
        return None;
    };
    let hwnd = HWND(handle.hwnd.get() as *mut _);
    (!unsafe { GetPropW(hwnd, w!("RHRemote.KeyboardPerPixel")) }.is_invalid()).then_some(hwnd)
}
#[derive(Default)]
pub(super) struct Surface {
    size: (u32, u32),
    pixels: Vec<SoftBufferPixel>,
    dib: Option<bitmap::Dib>,
}
impl Surface {
    pub(super) fn render(
        &mut self,
        renderer: &SoftwareRenderer,
        window: &winit::window::Window,
        hwnd: HWND,
        width: u32,
        height: u32,
    ) -> Result<(), PlatformError> {
        if self.size != (width, height) {
            let count = width
                .checked_mul(height)
                .filter(|n| *n <= 33_554_432)
                .ok_or("Keyboard bitmap is too large")? as usize;
            let dib = bitmap::Dib::new(width, height)?;
            self.pixels = vec![SoftBufferPixel(0); count];
            self.dib = Some(dib);
            self.size = (width, height);
            renderer.set_repaint_buffer_type(RepaintBufferType::NewBuffer);
        }
        let damage = renderer.render(&mut self.pixels, width as usize);
        renderer.set_repaint_buffer_type(RepaintBufferType::ReusedBuffer);
        if damage.iter().next().is_none() {
            return Ok(());
        }
        let dib = self.dib.as_ref().ok_or("Keyboard bitmap is unavailable")?;
        // Native byte order is premultiplied BGRA. Reuse the cached DIB and copy
        // only on UI damage, never on receipt of a remote-video frame.
        window.pre_present_notify();
        dib.present(hwnd, width, height, bytemuck::cast_slice(&self.pixels))?;
        Ok(())
    }
}
