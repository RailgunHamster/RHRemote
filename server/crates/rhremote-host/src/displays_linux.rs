//! Read-only X11 output preparation. Explicit selections keep their native identity.
use serde_json::{Value,json};
use std::{io::Read,path::Path,process::{Command,Stdio},time::{Duration,Instant}};

fn inventory(helper:&Path)->Result<Value,String> {
    let mut child=Command::new(helper).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().map_err(|_|"Linux 显示组件未安装".to_string())?;
    let output=child.stdout.take().ok_or("显示组件输出不可用")?;
    let (send,receive)=std::sync::mpsc::channel();
    std::thread::spawn(move||{let mut bytes=Vec::new();let result=output.take(131073).read_to_end(&mut bytes).map(|_|bytes);let _=send.send(result);});
    let until=Instant::now()+Duration::from_secs(3);
    loop {
        match child.try_wait() {
            Ok(Some(status))=>{
                if !status.success(){return Err("无法读取 X11 显示器，请确认服务使用正确的 DISPLAY 和 XAUTHORITY".into());}
                let bytes=receive.recv_timeout(Duration::from_millis(200)).map_err(|_|"显示组件响应未完成")?.map_err(|_|"显示组件响应读取失败")?;
                if bytes.len()>131072{return Err("显示器响应过大".into());}
                let value:Value=serde_json::from_slice(&bytes).map_err(|_|"显示器响应无效")?;
                if value["schema"]!="rhremote.displays/v1"||!value["displays"].is_array(){return Err("显示器响应格式不支持".into());}
                return Ok(value);
            },
            Err(error)=>{let _=child.kill();let _=child.wait();return Err(error.to_string());},
            Ok(None) if Instant::now()>=until=>{let _=child.kill();let _=child.wait();return Err("读取 X11 显示器超时".into());},
            _=>std::thread::sleep(Duration::from_millis(10)),
        }
    }
}

fn prepare(mut value:Value,request:&Value)->Result<Value,String> {
    if request["mode"]=="list" {return Ok(value);}
    let rows=value["displays"].as_array().ok_or("显示器列表不可用")?;
    let mode=request["mode"].as_str().ok_or("显示模式缺失")?;
    let selected=match mode {
        "physical"=>rows.iter().find(|row|row["available"]==true&&row["id"]==request["displayId"]),
        "virtual"=>rows.iter().find(|row|row["available"]==true&&row["owned"]==true&&row["virtual"]==true),
        "auto"=>rows.iter().filter(|row|row["available"]==true).min_by_key(|row|(row["virtual"]==true,row["primary"]!=true)),
        _=>return Err("不支持的显示模式".into()),
    }.cloned().ok_or(if mode=="virtual"{"当前 Linux 服务未启用独立桌面"}else{"所选 X11 显示器不可用，请刷新列表"})?;
    value["selected"]=selected;
    value["created"]=json!(false);
    value["initialCaptureIndex"]=json!(1);
    value["inputDesktop"]=json!("X11");
    Ok(value)
}

pub(crate) fn run(helper:&Path,request:Value)->Result<Value,String> {
    let started=Instant::now();
    let mut value=prepare(inventory(helper)?,&request)?;
    value["timings"]=json!({"hostRequestMs":started.elapsed().as_secs_f64()*1000.});
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture()->Value{json!({"displays":[{"id":"x11:DUMMY0","gdi":"rhrx11:DUMMY0","available":true,"virtual":true,"owned":true,"primary":true},{"id":"x11:HDMI-1","gdi":"rhrx11:HDMI-1","available":true,"virtual":false,"primary":true},{"id":"x11:DP-1","available":false}]})}
    #[test]
    fn automatic_mode_chooses_a_physical_screen_and_explicit_virtual_is_respected(){
        assert_eq!(prepare(fixture(),&json!({"mode":"auto"})).unwrap()["selected"]["id"],"x11:HDMI-1");
        assert_eq!(prepare(fixture(),&json!({"mode":"virtual"})).unwrap()["selected"]["id"],"x11:DUMMY0");
    }
    #[test]
    fn a_removed_or_disabled_explicit_output_never_falls_back_to_another_screen(){
        for id in ["x11:removed","x11:DP-1"]{assert!(prepare(fixture(),&json!({"mode":"physical","displayId":id})).is_err());}
        let selected=prepare(fixture(),&json!({"mode":"physical","displayId":"x11:HDMI-1"})).unwrap();
        assert_eq!(selected["selected"]["gdi"],"rhrx11:HDMI-1");
    }
}
