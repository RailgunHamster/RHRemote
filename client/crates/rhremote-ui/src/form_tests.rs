use crate::{Commands,MainWindow,SettingRow};
use i_slint_backend_testing::ElementHandle;
use slint::{ComponentHandle,LogicalPosition,LogicalSize,ModelRc,VecModel,platform::{Key,PointerEventButton,WindowEvent}};
use std::{cell::RefCell,rc::Rc};

fn click(ui:&impl ComponentHandle,label:&str){
    let element=ElementHandle::find_by_accessible_label(ui,label).next().unwrap_or_else(||panic!("Missing {label}"));
    let p=element.absolute_position();let size=element.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
    ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
}
#[test]
fn real_buttons_and_category_tabs_keep_focus_for_keyboard_activation(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("updates".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move |id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));
    ui.show().unwrap();
    for(label,id)in [("返回","devices.open"),("更新","app.settings.tab")]{
        received.borrow_mut().clear();click(&ui,label);
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
        assert_eq!(received.borrow().iter().filter(|(action,_)|action==id).count(),2,"{label} lost keyboard focus");
    }
    ui.hide().unwrap();
}
#[test]
fn blocked_navigation_cannot_dispatch_from_pointer_or_keyboard_and_recovers_after_cleanup(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("connection".into());ui.set_connection_phase("starting".into());ui.set_connection_busy(true);
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_navigation_locked(false);click(&ui,"设置");received.borrow_mut().clear();
        ui.set_navigation_locked(true);ui.set_navigation_hint("连接期间无法切换页面".into());
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
        for label in ["远程设备","添加电脑","设置","深色模式"]{click(&ui,label);}assert!(received.borrow().is_empty(),"disabled navigation dispatched: {:?}",received.borrow());
        click(&ui,"取消连接");assert_eq!(&*received.borrow(),&["stream.disconnect"]);
        received.borrow_mut().clear();ui.set_connection_phase("cancelling".into());
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert!(received.borrow().is_empty(),"disabled cancellation dispatched: {:?}",received.borrow());ui.set_connection_phase("starting".into());
        ui.set_navigation_locked(false);ui.set_navigation_hint("".into());received.borrow_mut().clear();click(&ui,"设置");
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert_eq!(&*received.borrow(),&["app.settings.open","app.settings.open"]);
    }ui.hide().unwrap();
}
#[test]
fn settings_footer_remains_visible_with_long_content_and_disabled_save_cannot_submit(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_selected_name("winserver".into());
    ui.set_profile_fields(ModelRc::new(VecModel::from((0..30).map(|i|SettingRow{key:format!("field{i}").into(),label:format!("参数 {i}").into(),kind:"number".into(),input_text:"30".into(),..Default::default()}).collect::<Vec<_>>())));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move |id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));
        let save=ElementHandle::find_by_accessible_label(&ui,"保存").next().unwrap();let p=save.absolute_position();let size=save.size();
        assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width,"fixed save bar clipped at {width}x{height}");
        received.borrow_mut().clear();click(&ui,"保存");assert!(!received.borrow().iter().any(|id|id=="settings.save"));
    }
    ui.hide().unwrap();
}
#[test]
fn add_form_exposes_waiting_and_cancel_states_without_duplicate_submission(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("add".into());ui.set_add_address("100.73.139.109".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move |id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_add_phase("editing".into());received.borrow_mut().clear();
        let submit=ElementHandle::find_by_accessible_label(&ui,"添加").next().unwrap();let p=submit.absolute_position();let size=submit.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        click(&ui,"添加");assert_eq!(&*received.borrow(),&[("device.add.submit".into(),"100.73.139.109".into())]);
        ui.set_add_phase("probing".into());received.borrow_mut().clear();click(&ui,"正在查找…");assert!(received.borrow().is_empty());click(&ui,"取消");assert_eq!(received.borrow()[0].0,"device.add.cancel");
        ui.set_add_phase("editing".into());ui.set_add_address("".into());received.borrow_mut().clear();click(&ui,"添加");assert!(received.borrow().is_empty());ui.set_add_address("100.73.139.109".into());
    }
    ui.hide().unwrap();
}

#[test]
fn device_identity_has_its_own_editor_and_fixed_completion_controls(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("device-info".into());ui.set_info_name("工作机".into());ui.set_selected_uuid("host".into());
    ui.set_selected_device(crate::DeviceRow{host_name:"winserver".into(),..Default::default()});
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_info_error("名称保存失败，请检查后重试".into());
        let done=ElementHandle::find_by_accessible_label(&ui,"完成").next().unwrap();let p=done.absolute_position();let size=done.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"恢复原名");click(&ui,"取消");click(&ui,"完成");
        assert_eq!(received.borrow().iter().map(|(id,_)|id.as_str()).collect::<Vec<_>>(),["device.info.reset","device.info.cancel","device.info.save"]);
        received.borrow_mut().clear();click(&ui,"设备名称");ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
        assert_eq!(received.borrow().iter().filter(|(id,_)|id=="device.info.save").count(),1);
    }
    ui.hide().unwrap();
}

#[test]
fn connection_footer_stays_reachable_and_only_exposes_actions_for_the_current_stage(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("connection".into());ui.set_connection_name("winserver".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_connection_busy(true);ui.set_connection_phase("starting".into());
        received.borrow_mut().clear();click(&ui,"取消连接");assert_eq!(&*received.borrow(),&["stream.disconnect"]);
        ui.set_connection_phase("cancelling".into());received.borrow_mut().clear();click(&ui,"正在清理…");assert!(received.borrow().is_empty());
        ui.set_connection_phase("failed".into());ui.set_connection_busy(false);ui.set_connection_error("连接失败的详细信息。".repeat(40).into());
        let retry=ElementHandle::find_by_accessible_label(&ui,"重试连接").next().unwrap();let p=retry.absolute_position();let size=retry.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"重试连接");click(&ui,"返回设备");assert_eq!(&*received.borrow(),&["connection.retry","connection.dismiss"]);
    }ui.hide().unwrap();
}

#[test]
fn the_single_update_action_remains_visible_when_failure_details_are_long(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("updates".into());ui.set_updates_managed(true);ui.set_update_phase("error".into());ui.set_update_status("Gitee / GitHub：请求超时。".repeat(100).into());ui.set_update_primary_text("重新检查".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_update_details(true);
        let button=ElementHandle::find_by_accessible_label(&ui,"重新检查").next().unwrap();let p=button.absolute_position();let size=button.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"重新检查");assert_eq!(&*received.borrow(),&["updates.primary"]);
        ui.set_update_details(false);received.borrow_mut().clear();click(&ui,"查看详情");assert_eq!(&*received.borrow(),&["updates.details.toggle"]);
        ui.set_updates_busy(true);received.borrow_mut().clear();click(&ui,"重新检查");assert!(received.borrow().is_empty());ui.set_updates_busy(false);
    }ui.hide().unwrap();
}

#[test]
fn rechecking_a_cached_update_is_available_during_a_stream_but_installation_is_not(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("updates".into());ui.set_updates_managed(true);ui.set_updates_available(true);ui.set_streaming(true);ui.set_update_primary_text("重新检查".into());ui.set_update_primary_installs(false);
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();click(&ui,"重新检查");assert_eq!(&*received.borrow(),&["updates.primary"]);
    ui.set_update_primary_installs(true);ui.set_update_primary_text("下载并安装".into());received.borrow_mut().clear();click(&ui,"下载并安装");assert!(received.borrow().is_empty());ui.set_streaming(false);click(&ui,"下载并安装");assert_eq!(&*received.borrow(),&["updates.primary"]);ui.hide().unwrap();
}

#[test]
fn pairing_uses_fixed_cancel_and_retry_controls_without_a_connect_action(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_connection_operation("pair".into());ui.set_connection_name("winserver · 办公电脑".into());ui.set_pair_pin("1234".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_page("pair".into());ui.set_connection_phase("pairing".into());ui.set_connection_busy(true);
        let cancel=ElementHandle::find_by_accessible_label(&ui,"取消配对").next().unwrap();let p=cancel.absolute_position();let size=cancel.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"取消配对");assert_eq!(&*received.borrow(),&["pair.cancel"]);
        ui.set_page("connection".into());ui.set_connection_phase("cancelling".into());received.borrow_mut().clear();click(&ui,"正在清理…");assert!(received.borrow().is_empty());assert!(ElementHandle::find_by_accessible_label(&ui,"重试配对").next().is_none());
        ui.set_connection_phase("failed".into());ui.set_connection_busy(false);ui.set_connection_error("配对码不正确。".repeat(80).into());
        assert!(ElementHandle::find_by_accessible_label(&ui,"重试连接").next().is_none());let retry=ElementHandle::find_by_accessible_label(&ui,"重试配对").next().unwrap();let p=retry.absolute_position();assert!(p.y>=height-100.&&p.y+retry.size().height<=height);
        received.borrow_mut().clear();click(&ui,"重试配对");click(&ui,"返回设备");assert_eq!(&*received.borrow(),&["connection.retry","connection.dismiss"]);
    }ui.hide().unwrap();
}

#[test]
fn copy_preview_keeps_its_commands_fixed_and_dispatches_one_explicit_accept_action(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("profile-copy".into());ui.set_copy_source("OPPO 平板 · 1234".into());ui.set_copy_target("工作机".into());
    ui.set_copy_fields(ModelRc::new(VecModel::from((0..40).map(|i|crate::CopyField{label:format!("配置参数 {i}").into(),value:"较长的配置内容 ".repeat(10).into()}).collect::<Vec<_>>())));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));
        let apply=ElementHandle::find_by_accessible_label(&ui,"复制到草稿").next().unwrap();let p=apply.absolute_position();let size=apply.size();assert!(p.y>=height-100.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"复制到草稿");assert_eq!(&*received.borrow(),&["settings.copy.apply"]);received.borrow_mut().clear();click(&ui,"取消");assert_eq!(&*received.borrow(),&["settings.copy.cancel"]);
        ui.set_copy_source("".into());received.borrow_mut().clear();click(&ui,"复制到草稿");assert!(received.borrow().is_empty());ui.set_copy_source("OPPO 平板 · 1234".into());
    }ui.hide().unwrap();
}

#[test]
fn long_source_names_cannot_push_the_preview_button_out_of_the_form(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("advanced".into());ui.set_compact(true);ui.set_profile_sources_expanded(true);
    ui.set_profile_sources(ModelRc::new(VecModel::from(vec![crate::ProfileSource{client_id:"pad-1234".into(),label:"很长的客户端名称 ".repeat(50).into()}])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();ui.window().set_size(LogicalSize::new(720.,560.));
    let inspect=ElementHandle::find_by_accessible_label(&ui,"查看配置").next().unwrap();let p=inspect.absolute_position();let size=inspect.size();assert!(p.x>=0.&&p.x+size.width<=720.);click(&ui,"查看配置");assert_eq!(&*received.borrow(),&[("settings.copy.preview".into(),"pad-1234".into())]);
    ui.set_profile_sources(Default::default());received.borrow_mut().clear();click(&ui,"从其他客户端复制配置");assert!(received.borrow().is_empty());ui.hide().unwrap();
}

#[test]
fn session_tabs_keep_disconnect_reachable_and_pending_commands_do_not_send_twice(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamToolbar::new().unwrap();ui.set_device_name("winserver".into());ui.set_expanded(true);ui.set_controls_tab("input".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for width in [560.,355.]{ui.window().set_size(LogicalSize::new(width,292.));for tab in ["input","displays","audio"]{ui.set_controls_tab(tab.into());let disconnect=ElementHandle::find_by_accessible_label(&ui,"断开").next().unwrap();let p=disconnect.absolute_position();let size=disconnect.size();assert!(p.x>=0.&&p.x+size.width<=width&&p.y+size.height<=292.);received.borrow_mut().clear();click(&ui,"断开");assert_eq!(received.borrow()[0].0,"stream.disconnect");}
        ui.set_controls_tab("input".into());ui.set_busy(false);received.borrow_mut().clear();click(&ui,"Ctrl+Alt+Delete");assert_eq!(&*received.borrow(),&[("stream.shortcut".into(),"ctrl-alt-delete".into())]);ui.set_busy(true);received.borrow_mut().clear();click(&ui,"Ctrl+Alt+Delete");assert!(received.borrow().is_empty());click(&ui,"屏幕");assert_eq!(&*received.borrow(),&[("stream.controls.tab".into(),"displays".into())]);ui.set_busy(false);
        ui.set_failed(true);ui.set_status("屏幕切换失败。".repeat(100).into());let details=ElementHandle::find_by_accessible_label(&ui,"详情").next().unwrap();let p=details.absolute_position();let size=details.size();assert!(p.x+size.width<=width&&p.y+size.height<=292.);received.borrow_mut().clear();click(&ui,"详情");assert_eq!(&*received.borrow(),&[("stream.menu.open".into(),"feedback".into())]);ui.set_failed(false);
    }ui.hide().unwrap();
}

#[test]
fn picker_respects_availability_and_waits_for_success_before_dismissing(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamPicker::new().unwrap();ui.set_kind("displays".into());ui.set_labels(ModelRc::new(VecModel::from(vec!["不可用屏幕".into(),"显示器 2".into()])));ui.set_values(ModelRc::new(VecModel::from(vec!["missing".into(),"active".into()])));ui.set_enabled_items(ModelRc::new(VecModel::from(vec![false,true])));ui.set_chosen_items(ModelRc::new(VecModel::from(vec![false,true])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();ui.window().set_size(LogicalSize::new(320.,300.));click(&ui,"不可用屏幕");assert!(received.borrow().is_empty());click(&ui,"显示器 2");assert_eq!(&*received.borrow(),&[("stream.display.select".into(),"active".into())]);
    received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert_eq!(&*received.borrow(),&[("stream.display.select".into(),"active".into())]);ui.set_busy(true);received.borrow_mut().clear();click(&ui,"显示器 2");assert!(received.borrow().is_empty());click(&ui,"关闭");assert_eq!(received.borrow()[0].0,"stream.menu.close");ui.set_busy(false);ui.set_error("屏幕切换失败".into());received.borrow_mut().clear();click(&ui,"详情");assert_eq!(&*received.borrow(),&[("stream.menu.open".into(),"feedback".into())]);ui.set_kind("feedback".into());ui.set_error("详细错误。".repeat(100).into());let close=ElementHandle::find_by_accessible_label(&ui,"关闭").next().unwrap();let p=close.absolute_position();assert!(p.y>=0.&&p.y+close.size().height<=300.);ui.hide().unwrap();
}
