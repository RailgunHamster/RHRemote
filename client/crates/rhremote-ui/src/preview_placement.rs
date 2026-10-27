pub fn origin(enabled: bool, monitor: Option<&str>, position: Option<&str>) -> Result<Option<(i32,i32)>,String> {
    if !enabled {return Ok(None);}
    let monitor=monitor.filter(|s|!s.is_empty() && s.len()<=128).ok_or("隔离预览必须指定 RHREMOTE_TEST_MONITOR，不能默认显示到主屏")?;
    if !monitor.starts_with(r"\\.\DISPLAY") {return Err("测试显示器名称无效".into());}
    let (x,y)=position.and_then(|p|p.split_once(',')).ok_or("请通过 Start-RHRemoteUiPreview.ps1 指定副屏位置")?;
    let x=x.parse::<i32>().map_err(|_|"副屏横坐标无效")?;
    let y=y.parse::<i32>().map_err(|_|"副屏纵坐标无效")?;
    Ok(Some((x,y)))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn isolated_previews_require_explicit_monitor_and_coordinates_without_changing_normal_startup(){
        assert_eq!(origin(false,None,None).unwrap(),None);
        assert!(origin(true,None,None).is_err());
        assert!(origin(true,Some(r"\\.\DISPLAY2"),None).is_err());
        assert!(origin(true,Some("other"),Some("-3776,64")).is_err());
        assert!(origin(true,Some(r"\\.\DISPLAY2"),Some("0,not-a-number")).is_err());
        assert_eq!(origin(true,Some(r"\\.\DISPLAY2"),Some("-3776,64")).unwrap(),Some((-3776,64)));
    }
}
