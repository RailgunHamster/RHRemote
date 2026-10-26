use crate::{Commands,MainWindow,SettingRow};
use i_slint_backend_testing::ElementHandle;
use slint::{ComponentHandle,LogicalPosition,LogicalSize,ModelRc,VecModel,platform::{Key,PointerEventButton,WindowEvent}};
use std::{cell::RefCell,rc::Rc};

fn click(ui:&MainWindow,label:&str){
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
