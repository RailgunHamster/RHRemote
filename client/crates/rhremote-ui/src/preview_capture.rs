use serde_json::{Value, json};
use std::path::Path;

pub fn capture(window: &slint::Window, path: &Path, mut context: Value) -> Result<Value, String> {
    if !window.is_visible() { return Err("目标窗口未显示".into()); }
    let pixels=window.take_snapshot().map_err(|e|format!("截图失败：{e}"))?;
    if pixels.width()==0 || pixels.height()==0 {return Err("截图尺寸为空".into());}
    let pending=path.with_extension("pending.png");
    image::save_buffer(&pending,pixels.as_bytes(),pixels.width(),pixels.height(),image::ColorType::Rgba8)
        .map_err(|e|format!("截图写入失败：{e}"))?;
    std::fs::rename(&pending,path).map_err(|e|format!("截图保存失败：{e}"))?;
    context["path"]=json!(path);
    context["width"]=json!(pixels.width());
    context["height"]=json!(pixels.height());
    Ok(context)
}
