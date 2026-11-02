use crate::*;
use slint::{ComponentHandle,LogicalPosition,LogicalSize,ModelRc,VecModel,platform::{WindowEvent,PointerEventButton}};
use i_slint_backend_testing::ElementHandle;
use std::{cell::RefCell,rc::Rc};
fn point(ui:&MainWindow,label:&str)->LogicalPosition{let e=ElementHandle::find_by_accessible_label(ui,label).next().unwrap_or_else(||panic!("Missing {label}"));let p=e.absolute_position();LogicalPosition::new(p.x+e.size().width/2.,p.y+e.size().height/2.)}
fn gesture(ui:&MainWindow,source:&str,target:&str){let from=point(ui,source);let mut to=point(ui,target);to.y+=12.;ui.window().dispatch_event(WindowEvent::PointerPressed{position:from,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerMoved{position:to});i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));ui.window().dispatch_event(WindowEvent::PointerReleased{position:to,button:PointerEventButton::Left});}
#[test]
fn actual_grips_drop_devices_and_routes_through_the_same_named_api(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.window().set_size(LogicalSize::new(1180.,780.));ui.set_selected_uuid("b".into());ui.set_selected_name("B".into());ui.set_page("devices".into());ui.set_device_total(3);
    ui.set_devices(ModelRc::new(VecModel::from(["a","b","c"].into_iter().map(|id|DeviceRow{uuid:id.into(),name:id.to_uppercase().into(),..Default::default()}).collect::<Vec<_>>())));
    let calls=Rc::new(RefCell::new(Vec::new()));let sink=calls.clone();ui.global::<Commands>().on_trigger(move|id,arg|sink.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    gesture(&ui,"拖动设备 A 排序","拖动设备 C 排序");assert_eq!(&*calls.borrow(),&[("device.move".into(),"a|c|after".into())]);calls.borrow_mut().clear();
    ui.set_page("global-settings".into());gesture(&ui,"拖动设备 C 排序","拖动设备 A 排序");assert_eq!(&*calls.borrow(),&[("device.move".into(),"c|a|after".into())]);calls.borrow_mut().clear();
    ui.set_navigation_prompt(true);gesture(&ui,"拖动设备 A 排序","拖动设备 C 排序");assert!(calls.borrow().is_empty());ui.set_navigation_prompt(false);
    ui.set_page("settings".into());ui.set_profile_tab("network".into());ui.set_profile_draft_id("draft-a".into());ui.set_network_routes(ModelRc::new(VecModel::from(vec![RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,..Default::default()},RouteRow{key:"relay".into(),label:"自建中继".into(),enabled:true,..Default::default()}])));
    let scroll=ElementHandle::find_by_element_id(&ui,"MainWindow::form-scroll").next().unwrap();let top=scroll.absolute_position().y;
    let y=point(&ui,"拖动连接路径 局域网 排序").y;scroll.scroll(0.,top+30.-y);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    gesture(&ui,"拖动连接路径 局域网 排序","拖动连接路径 自建中继 排序");assert_eq!(&*calls.borrow(),&[("settings.route.move".into(),"lan|relay|after".into())]);ui.hide().unwrap();
}
