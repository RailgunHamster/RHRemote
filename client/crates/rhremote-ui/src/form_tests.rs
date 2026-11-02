use crate::{Commands,MainWindow,SettingRow};
use i_slint_backend_testing::ElementHandle;
use slint::{ComponentHandle,LogicalPosition,LogicalSize,ModelRc,VecModel,platform::{Key,PointerEventButton,WindowEvent}};
use std::{cell::RefCell,rc::Rc};

fn click(ui:&impl ComponentHandle,label:&str){
    let element=ElementHandle::find_by_accessible_label(ui,label).next().unwrap_or_else(||{
        let labels:Vec<_>=i_slint_backend_testing::ElementQuery::from_root(ui).find_all().into_iter().filter_map(|element|element.accessible_label().map(|label|label.to_string())).collect();
        panic!("Missing {label}; available labels: {labels:?}")
    });
    let p=element.absolute_position();let size=element.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
    ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
}

#[test]
fn window_picker_keeps_long_rows_inside_the_modal_and_routes_only_available_sources(){
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(i_slint_backend_testing::TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();let ui=MainWindow::new().unwrap();ui.show().unwrap();ui.window().set_size(LogicalSize::new(720.,520.));
    let source="rhrwindow:0123456789abcdef0123456789abcdef";let title="editor.exe · 1200 × 800 · ".to_string()+&"Long window title ".repeat(40);
    ui.set_window_picker_uuid("host".into());ui.set_window_picker_rows(ModelRc::new(VecModel::from(vec![crate::RemoteDisplay{id:source.into(),label:title.clone().into(),chosen:false,available:true},crate::RemoteDisplay{id:"unavailable".into(),label:"已最小化".into(),chosen:false,available:false}])));ui.set_window_picker_open(true);
    let calls=Rc::new(RefCell::new(Vec::new()));let sink=calls.clone();ui.global::<Commands>().on_trigger(move|id,arg|sink.borrow_mut().push((id.to_string(),arg.to_string())));
    let row=ElementHandle::find_by_accessible_label(&ui,&title).next().unwrap();let p=row.absolute_position();assert!(p.x>=16.&&p.y>=24.);assert!(p.x+row.size().width<=704.);assert!(p.y+row.size().height<=496.);
    click(&ui,"已最小化");assert!(calls.borrow().is_empty());click(&ui,&title);assert_eq!(&*calls.borrow(),&[("device.window.connect".to_owned(),format!("host|{source}"))]);calls.borrow_mut().clear();
    ui.set_window_picker_choice(0);crate::ui_keyboard::send(&ui,"Tab",false,false);assert_eq!(ui.get_window_picker_choice(),1);crate::ui_keyboard::send(&ui,"Tab",false,false);assert_eq!(ui.get_window_picker_choice(),3);crate::ui_keyboard::send(&ui,"Tab",true,false);assert_eq!(ui.get_window_picker_choice(),1);crate::ui_keyboard::send(&ui,"Enter",false,false);assert_eq!(&*calls.borrow(),&[("device.window.connect".to_owned(),format!("host|{source}"))]);calls.borrow_mut().clear();
    ui.set_window_picker_busy(true);crate::ui_keyboard::send(&ui,"Enter",false,false);assert_eq!(&*calls.borrow(),&[("device.windows.close".to_owned(),String::new())]);calls.borrow_mut().clear();ui.set_window_picker_busy(false);crate::ui_keyboard::send(&ui,"Escape",false,false);assert_eq!(&*calls.borrow(),&[("device.windows.close".to_owned(),String::new())]);
    if let Some(directory)=std::env::var_os("RHREMOTE_WINDOW_UI_EVIDENCE_DIR"){let path=std::path::PathBuf::from(directory).join("windows-window-picker196.png");assert!(!path.exists());ui.window().take_snapshot().unwrap();let frame=ui.window().take_snapshot().unwrap();image::save_buffer(path,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();}
    ui.hide().unwrap();
}

#[test]
fn device_header_and_profile_tabs_have_balanced_gutters_at_each_scale() {
    use i_slint_backend_testing::{AccessibleRole, TestingBackend, TestingBackendOptions};
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions {
        mock_time: true, threading: false, renderer_name: Some("software".into()),
    }))).unwrap();
    let region = |ui: &MainWindow, id: &str| {
        ElementHandle::find_by_element_id(ui, id).next().unwrap_or_else(|| panic!("Missing layout region {id}"))
    };
    let button = |ui: &MainWindow, label: &str, id: &str| {
        ElementHandle::find_by_accessible_label(ui, label)
            .find(|e| e.accessible_role() == Some(AccessibleRole::Button) && e.accessible_id().is_some_and(|value| value.starts_with(&format!("action|{id}|"))))
            .unwrap_or_else(|| panic!("Missing actual button {label}"))
    };
    for dark in [false, true] {
        for (width, height, size) in [(720., 560., "compact"), (1180., 780., "wide"), (1920., 1080., "large")] {
            for scale in [1., 1.25, 1.5, 2.] {
                for page in ["devices", "settings"] {
                    let ui = MainWindow::new().unwrap();
                    ui.global::<crate::Palette>().set_dark(dark);
                    ui.set_compact(width < 980.);
                    ui.set_page(page.into());
                    ui.set_selected_uuid("alignment-host".into());
                    ui.set_selected_name("winserver".into());
                    let navigation = if width < 980. { 64. + 224. } else { 184. + 276. };
                    ui.set_form_inset(((width - navigation - 800.) / 2.0_f32).max(24.));
                    ui.window().dispatch_event(WindowEvent::ScaleFactorChanged { scale_factor: scale });
                    ui.window().set_size(LogicalSize::new(width, height));
                    ui.show().unwrap();
                    ui.window().take_snapshot().unwrap();
                    let tabs = region(&ui, "DeviceContextHeader::context-tabs");
                    let overview = button(&ui, "概览", "device.select");
                    let settings = button(&ui, "连接设置", "device.settings.open");
                    let centered = |item: &ElementHandle, area: &ElementHandle, label: &str| {
                        let top = item.absolute_position().y - area.absolute_position().y;
                        let bottom = area.absolute_position().y + area.size().height - item.absolute_position().y - item.size().height;
                        assert!(top >= 6. && bottom >= 6. && (top - bottom).abs() <= 1.,
                            "{size}/{page}/{scale}/{dark} {label}: unbalanced or touching boundary, top={top}, bottom={bottom}");
                    };
                    if let Some(directory) = std::env::var_os("RHREMOTE_ALIGNMENT_EVIDENCE") {
                        let frame = ui.window().take_snapshot().unwrap();
                        let name = format!("{size}-{page}-{scale}-{dark}");
                        let image = std::path::PathBuf::from(&directory).join(format!("{name}.png"));
                        assert!(!image.exists());
                        image::save_buffer(image, frame.as_bytes(), frame.width(), frame.height(), image::ColorType::Rgba8).unwrap();
                        let bounds = serde_json::json!({"size":size,"page":page,"scale":scale,"dark":dark,
                            "tabRegion":{"y":tabs.absolute_position().y,"height":tabs.size().height},
                            "settingsButton":{"x":settings.absolute_position().x,"y":settings.absolute_position().y,"height":settings.size().height}});
                        std::fs::write(std::path::PathBuf::from(directory).join(format!("{name}.json")), serde_json::to_vec_pretty(&bounds).unwrap()).unwrap();
                    }
                    centered(&overview, &tabs, "概览");
                    centered(&settings, &tabs, "连接设置");
                    assert!((overview.absolute_position().x - navigation - ui.get_form_inset()).abs() <= 1.,
                        "The header and form must share their content column at {width}");
                    if page == "settings" {
                        let categories = region(&ui, "MainWindow::profile-header");
                        for label in ["画面", "音频", "输入", "网络", "高级"] {
                            centered(&button(&ui, label, "settings.tab"), &categories, label);
                        }
                        let first = button(&ui, "画面", "settings.tab");
                        assert!((first.absolute_position().x - overview.absolute_position().x).abs() <= 1.,
                            "The two tab rows must use the same left gutter at {width}");
                    }
                    let title = region(&ui, "DeviceContextHeader::device-title-row");
                    let status = region(&ui, "DeviceContextHeader::device-state");
                    let offset = (status.absolute_position().y + status.size().height / 2.) -
                        (title.absolute_position().y + title.size().height / 2.);
                    assert!(offset.abs() <= 1., "Device status is not vertically centered: {offset}");
                    ui.hide().unwrap();
                }
            }
        }
    }
}

#[test]
fn sync_actions_are_visible_before_account_fields_in_both_window_sizes(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("sync".into());ui.set_sync_has_password(true);ui.set_sync_password_reusable(true);
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));
        let run=ElementHandle::find_by_accessible_label(&ui,"立即同步").next().unwrap();let url=ElementHandle::find_by_accessible_label(&ui,"WebDAV 目录地址（HTTP / HTTPS）").next().unwrap();
        let p=run.absolute_position();let size=run.size();assert!(p.y<url.absolute_position().y,"Sync is buried below the account form at {width}x{height}");assert!(p.y>=0.&&p.x>=0.&&p.y+size.height<=height&&p.x+size.width<=width);
        received.borrow_mut().clear();click(&ui,"立即同步");assert_eq!(&*received.borrow(),&[("sync.run".into(),"".into())]);
        ui.set_sync_busy(true);received.borrow_mut().clear();click(&ui,"同步中…");assert!(received.borrow().is_empty());ui.set_sync_busy(false);
    }ui.hide().unwrap();
}
fn click_in_form(ui:&MainWindow,label:&str){
    let scroll=ElementHandle::find_by_element_id(ui,"MainWindow::form-scroll").next().expect("form scroll area");
    let top=scroll.absolute_position().y;let bottom=top+scroll.size().height;
    scroll.scroll(0.,100000.);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    for _ in 0..16 {
        let target=ElementHandle::find_by_accessible_label(ui,label).next();
        if let Some(target)=target{
            let y=target.absolute_position().y;let end=y+target.size().height;
            if y>=top && end<=bottom {click(ui,label);return;}
            scroll.scroll(0.,if y<top {180.}else{-180.});
        }else{scroll.scroll(0.,-180.);}
        i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    }
    panic!("Form control is not reachable by scrolling: {label}");
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
fn connection_actions_stay_reachable_and_back_uses_the_top_left_header(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("connection".into());ui.set_connection_name("winserver".into());ui.set_connection_operation("connect".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,_|calls.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));ui.set_connection_busy(true);ui.set_connection_phase("starting".into());
        received.borrow_mut().clear();click(&ui,"取消连接");assert_eq!(&*received.borrow(),&["stream.disconnect"]);
        ui.set_connection_phase("cancelling".into());received.borrow_mut().clear();click(&ui,"正在清理…");assert!(received.borrow().is_empty());
        ui.set_connection_phase("failed".into());ui.set_connection_busy(false);ui.set_connection_error("连接失败的详细信息。".repeat(40).into());
        let retry=ElementHandle::find_by_accessible_label(&ui,"重试连接").next().unwrap();let p=retry.absolute_position();let size=retry.size();assert!(p.y<height/2.&&p.y+size.height<=height&&p.x+size.width<=width);let back=ElementHandle::find_by_accessible_label(&ui,"返回设备").next().unwrap();let at=back.absolute_position();assert!(at.y<150.&&at.x<width/2.);
        received.borrow_mut().clear();click(&ui,"重试连接");click(&ui,"返回设备");assert_eq!(&*received.borrow(),&["connection.retry","connection.dismiss"]);
    }ui.hide().unwrap();
}

#[test]
fn ended_session_offers_reconnect_beside_its_status_and_back_at_the_top(){
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(i_slint_backend_testing::TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let ui=MainWindow::new().unwrap();ui.set_page("connection".into());ui.set_connection_name("Claw".into());ui.set_connection_operation("connect".into());ui.set_connection_phase("ended".into());ui.set_connection_title("已断开".into());ui.set_connection_busy(false);
    let calls=Rc::new(RefCell::new(Vec::new()));let output=calls.clone();ui.global::<Commands>().on_trigger(move|id,_|output.borrow_mut().push(id.to_string()));ui.show().unwrap();
    for(w,h,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(w,h));
        let retry=ElementHandle::find_by_accessible_label(&ui,"再次连接").next().unwrap();let p=retry.absolute_position();assert!(p.y<h/2.);assert!(p.x+retry.size().width<=w);
        let back=ElementHandle::find_by_accessible_label(&ui,"返回设备").next().unwrap();let b=back.absolute_position();assert!(b.y<150.&&b.x<w/2.);
        calls.borrow_mut().clear();click(&ui,"再次连接");click(&ui,"返回设备");assert_eq!(&*calls.borrow(),&["connection.retry","connection.dismiss"]);
        if let Ok(directory)=std::env::var("RHREMOTE_KEYBOARD_EVIDENCE") {std::fs::create_dir_all(&directory).unwrap();let frame=ui.window().take_snapshot().unwrap();image::save_buffer(std::path::PathBuf::from(&directory).join(format!("disconnected-{w}x{h}.png")),frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();}
    }
    ui.hide().unwrap();
    let keyboard=crate::FloatingKeyboardWindow::new().unwrap();keyboard.set_keys(crate::floating_keyboard::model(&crate::floating_keyboard::layout(1180.,780.)));keyboard.window().set_size(LogicalSize::new(1180.,780.));keyboard.show().unwrap();
    if let Ok(directory)=std::env::var("RHREMOTE_KEYBOARD_EVIDENCE"){let frame=keyboard.window().take_snapshot().unwrap();image::save_buffer(std::path::PathBuf::from(directory).join("keyboard-windows.png"),frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();}
    keyboard.hide().unwrap();
}

#[test]
fn floating_keyboard_keeps_opaque_glyphs_and_clear_gaps_over_light_dark_and_busy_images(){
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(i_slint_backend_testing::TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let keyboard=crate::FloatingKeyboardWindow::new().unwrap();
    keyboard.window().set_size(LogicalSize::new(1180.,780.));keyboard.show().unwrap();
    for(simple,name)in[(false,"full"),(true,"simple")]{
        let keys=if simple{crate::floating_keyboard::simple_layout(1180.,780.)}else{crate::floating_keyboard::layout(1180.,780.)};
        keyboard.set_keys(crate::floating_keyboard::model(&keys));
        for dark in [false,true]{
            keyboard.global::<crate::Palette>().set_dark(dark);
            let frame=keyboard.window().take_snapshot().unwrap();
            let pixels=image::RgbaImage::from_raw(frame.width(),frame.height(),frame.as_bytes().to_vec()).unwrap();
            assert_eq!(pixels.get_pixel(590,100)[3],0,"Unused remote area must be transparent");
            for key in &keys{
                let(x,y)=(key.x as u32,key.y as u32);
                let fill=pixels.get_pixel(x+8,y+5)[3];assert!((75..=90).contains(&fill),"{} fill {fill}",key.id);
                let mut white=0;let mut outline=0;
                for py in y+4..y+key.height as u32-4{for px in x+3..x+key.width as u32-3{
                    let p=pixels.get_pixel(px,py);if p[3]>=240&&p[0]>=220&&p[1]>=220&&p[2]>=220{white+=1;}if p[3]>=230&&p[0]<60&&p[1]<65&&p[2]<75{outline+=1;}
                }}
                // Tiny punctuation glyphs can consist entirely of antialiased
                // edge pixels. Check solid stems on representative labels.
                if matches!(key.id,"tab"|"enter"|"ctrl-left"){assert!(white>1,"{} has no opaque lettering",key.id);assert!(outline>1,"{} has no dark glyph outline",key.id);}
            }
            if !dark{if let Some(directory)=std::env::var_os("RHREMOTE_KEYBOARD_EVIDENCE"){
                std::fs::create_dir_all(&directory).unwrap();
                let mut comparison=image::RgbaImage::new(1180,if simple{780*3}else{350*3});
                for background in 0..3{
                    let mut canvas=image::RgbaImage::from_fn(1180,780,|x,y|{
                        let rgb=match background{0=>[255,255,255],1=>[17,23,34],_=>if(x/37+y/29)%2==0{[205,225,244]}else{[60,86,118]}};
                        let rgb=if y%32<2&&x%190<152{match background{0=>[160,170,180],1=>[105,125,155],_=>[120,145,170]}}else{rgb};image::Rgba([rgb[0],rgb[1],rgb[2],255])
                    });
                    image::imageops::overlay(&mut canvas,&pixels,0,0);
                    if simple{image::imageops::replace(&mut comparison,&canvas,0,(background*780)as i64);}else{let crop=image::imageops::crop_imm(&canvas,0,430,1180,350).to_image();image::imageops::replace(&mut comparison,&crop,0,(background*350)as i64);}
                }
                comparison.save(std::path::PathBuf::from(directory).join(format!("keyboard-contrast-{name}-windows.png"))).unwrap();
            }}
        }
    }keyboard.hide().unwrap();
}

#[test]
fn update_notes_render_as_blocks_and_history_controls_are_collapsed_until_requested(){
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(i_slint_backend_testing::TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("updates".into());ui.set_updates_managed(true);ui.set_app_version("0.2.18".into());ui.set_update_primary_text("检查更新".into());
    let source="# RHRemote 0.2.18\n\n## 更新说明\n\n- **Markdown** 正文\n- 历史记录默认折叠\n\n```txt\n代码示例\n```";
    ui.set_update_notes(source.into());ui.set_update_note_blocks(crate::release_notes::model(source));
    let items=serde_json::json!([{"version":"0.2.17","publishedAt":"2026-11-02T10:19:23Z","notes":"## 修复\n\n- 悬浮键盘"}]);ui.set_update_history(crate::release_notes::history(&items,&serde_json::json!([]),""));
    let calls=Rc::new(RefCell::new(vec![]));let received=calls.clone();ui.global::<Commands>().on_trigger(move|id,args|received.borrow_mut().push((id.to_string(),args.to_string())));ui.window().set_size(LogicalSize::new(1180.,1100.));ui.show().unwrap();
    for expanded in [false,true]{ui.set_update_history_expanded(expanded);ui.window().take_snapshot().unwrap();assert_eq!(ElementHandle::find_by_accessible_label(&ui,"▸ v0.2.17").next().is_some(),expanded);
        if let Some(directory)=std::env::var_os("RHREMOTE_MARKDOWN_EVIDENCE"){std::fs::create_dir_all(&directory).unwrap();let frame=ui.window().take_snapshot().unwrap();image::save_buffer(std::path::PathBuf::from(directory).join(if expanded{"windows-history-open.png"}else{"windows-history-closed.png"}),frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();}
    }
    click(&ui,"▸ v0.2.17");assert_eq!(calls.borrow().last().unwrap(),&("updates.history.entry.toggle".into(),"0.2.17".into()));
    ui.set_update_history(crate::release_notes::history(&items,&serde_json::json!(["0.2.17"]),""));ui.set_update_history_busy(true);ui.set_update_history_error("历史记录读取失败".into());ui.window().take_snapshot().unwrap();assert!(ElementHandle::find_by_accessible_label(&ui,"收起历史更新记录").next().is_some());ui.hide().unwrap();
}

#[test]
fn the_single_update_action_remains_visible_when_failure_details_are_long(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("global-settings".into());ui.set_settings_tab("updates".into());ui.set_updates_managed(true);ui.set_update_phase("error".into());ui.set_update_status("CNB / GitHub：请求超时。".repeat(100).into());ui.set_update_primary_text("重新检查".into());
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
fn floating_keyboard_switch_stays_visible_above_every_control_tab(){
    use i_slint_backend_testing::{TestingBackend,TestingBackendOptions};
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    let ui=crate::StreamToolbar::new().unwrap();ui.set_expanded(true);ui.set_device_name("winserver".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();
    ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));
    ui.show().unwrap();
    for dark in [false,true]{ui.global::<crate::Palette>().set_dark(dark);
        for width in [320.,355.,560.]{ui.window().set_size(LogicalSize::new(width,292.));
            for tab in ["input","displays","audio"]{ui.set_controls_tab(tab.into());
                for enabled in [false,true]{ui.set_floating_keyboard(enabled);ui.window().take_snapshot().unwrap();
                    let label=if enabled{"悬浮键盘：开"}else{"悬浮键盘：关"};
                    let all:Vec<_>=ElementHandle::find_by_accessible_label(&ui,label).filter(|e|e.accessible_role()==Some(i_slint_backend_testing::AccessibleRole::Button)).collect();assert_eq!(all.len(),1,"{label}: {tab}/{width}/{dark}");
                    let p=all[0].absolute_position();let size=all[0].size();
                    assert!(p.x>=0.&&p.y>=28.&&p.x+size.width<=width+0.1&&p.y+size.height<=88.,"Switch must remain in the visible header: {tab}/{width}/{dark}: {p:?}/{size:?}");
                    if width==355. {if let Some(directory)=std::env::var_os("RHREMOTE_FLOATING_ENTRY_EVIDENCE"){
                        let path=std::path::PathBuf::from(directory).join(format!("toolbar-{tab}-{dark}-{enabled}.png"));assert!(!path.exists());
                        let frame=ui.window().take_snapshot().unwrap();image::save_buffer(path,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();
                    }}
                    received.borrow_mut().clear();click(&ui,label);
                    assert_eq!(&*received.borrow(),&[("stream.keyboard.floating.set".into(),(!enabled).to_string())]);
                    ui.set_busy(true);received.borrow_mut().clear();click(&ui,label);assert!(received.borrow().is_empty());ui.set_busy(false);
                }
            }
        }
    }
    ui.hide().unwrap();
}

#[test]
fn floating_keyboard_failure_keeps_retry_and_close_visible_at_all_scales(){
    use i_slint_backend_testing::{TestingBackend,TestingBackendOptions};
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    for dark in [false,true]{for scale in [1.,1.5,2.25]{for width in [355.,560.]{
        let ui=crate::StreamToolbar::new().unwrap();ui.set_expanded(true);ui.set_floating_keyboard(true);ui.set_device_name("winserver".into());ui.global::<crate::Palette>().set_dark(dark);
        let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));
        ui.window().dispatch_event(WindowEvent::ScaleFactorChanged{scale_factor:scale});
        // The testing adapter's reported scale remains 1. Use physical dimensions
        // explicitly; logical set_size would accidentally shrink the viewport at 225%.
        ui.window().set_size(slint::PhysicalSize::new((width*scale)as u32,(292.*scale)as u32));ui.show().unwrap();
        for message in ["悬浮键盘未显示","窗口过小，放大窗口后重试","远程窗口暂不可用，稍后重试"]{
            ui.set_floating_keyboard_error(message.into());let frame=ui.window().take_snapshot().unwrap();
            assert_eq!((frame.width(),frame.height()),((width*scale)as u32,(292.*scale)as u32));
            for label in [message,"重试","关闭悬浮键盘","断开"]{
                let e=ElementHandle::find_by_accessible_label(&ui,label).next().unwrap();let p=e.absolute_position();let size=e.size();
                assert!(p.x>=0.&&p.y>=28.&&p.x+size.width<=width+0.1&&p.y+size.height<=292.1,"{label} clipped at {scale}/{width}/{dark}: {p:?}/{size:?}");
                if label=="重试"{assert!(p.x+size.width<=width-24.+0.1,"Scrollbar must not cover retry at {scale}/{width}/{dark}");}
            }
            for(label,enabled)in[("重试","true"),("关闭悬浮键盘","false")]{received.borrow_mut().clear();click(&ui,label);
                if received.borrow().is_empty(){
                    if let Some(directory)=std::env::var_os("RHREMOTE_KEYBOARD_FEEDBACK_EVIDENCE"){
                        let frame=ui.window().take_snapshot().unwrap();let path=std::path::PathBuf::from(directory).join(format!("failed-{width}-{scale}-{dark}-{enabled}.png"));image::save_buffer(path,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();
                    }
                }
                assert_eq!(&*received.borrow(),&[("stream.keyboard.floating.set".into(),enabled.into())],"{label} at {scale}/{width}/{dark}, {message}");}
            ui.set_busy(true);received.borrow_mut().clear();click(&ui,"重试");click(&ui,"关闭悬浮键盘");assert!(received.borrow().is_empty());ui.set_busy(false);
        }
        if let Some(directory)=std::env::var_os("RHREMOTE_KEYBOARD_FEEDBACK_EVIDENCE"){
            let frame=ui.window().take_snapshot().unwrap();let path=std::path::PathBuf::from(directory).join(format!("feedback-{width}-{scale}-{dark}.png"));assert!(!path.exists());image::save_buffer(path,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();
        }
        let handle=ElementHandle::find_by_accessible_label(&ui,"收起").next().unwrap();let before=handle.absolute_position();
        ui.set_expanded(false);ui.window().take_snapshot().unwrap();let handle=ElementHandle::find_by_accessible_label(&ui,"控制").next().unwrap();assert_eq!(handle.absolute_position(),before);assert_eq!(handle.size(),LogicalSize::new(72.,28.));
        let badge=ElementHandle::find_by_accessible_label(&ui,"悬浮键盘未显示").next().unwrap();let p=badge.absolute_position();assert!(p.x>=before.x&&p.x+badge.size().width<=before.x+72.&&p.y>=0.&&p.y+badge.size().height<=28.);
        received.borrow_mut().clear();click(&ui,"控制");assert_eq!(&*received.borrow(),&[("stream.controls.toggle".into(),"".into())]);
        ui.set_expanded(true);ui.set_floating_keyboard_error("".into());assert!(ElementHandle::find_by_accessible_label(&ui,"重试").next().is_none());
        ui.hide().unwrap();
    }}}
}

#[test]
fn picker_respects_availability_and_waits_for_success_before_dismissing(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamPicker::new().unwrap();ui.set_kind("displays".into());ui.set_labels(ModelRc::new(VecModel::from(vec!["不可用屏幕".into(),"显示器 2".into()])));ui.set_values(ModelRc::new(VecModel::from(vec!["missing".into(),"active".into()])));ui.set_enabled_items(ModelRc::new(VecModel::from(vec![false,true])));ui.set_chosen_items(ModelRc::new(VecModel::from(vec![false,true])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();ui.window().set_size(LogicalSize::new(320.,300.));click(&ui,"不可用屏幕");assert!(received.borrow().is_empty());click(&ui,"显示器 2");assert_eq!(&*received.borrow(),&[("stream.display.select".into(),"active".into())]);
    received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert_eq!(&*received.borrow(),&[("stream.display.select".into(),"active".into())]);ui.set_busy(true);received.borrow_mut().clear();click(&ui,"显示器 2");assert!(received.borrow().is_empty());click(&ui,"关闭");assert_eq!(received.borrow()[0].0,"stream.menu.close");ui.set_busy(false);ui.set_error("屏幕切换失败".into());received.borrow_mut().clear();click(&ui,"详情");assert_eq!(&*received.borrow(),&[("stream.menu.open".into(),"feedback".into())]);ui.set_kind("feedback".into());ui.set_error("详细错误。".repeat(100).into());let close=ElementHandle::find_by_accessible_label(&ui,"关闭").next().unwrap();let p=close.absolute_position();assert!(p.y>=0.&&p.y+close.size().height<=300.);ui.hide().unwrap();
}

#[test]
fn focused_session_menu_rows_do_not_dispatch_after_becoming_busy_or_unavailable(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamPicker::new().unwrap();
    ui.set_labels(ModelRc::new(VecModel::from(vec!["可操作项".into()])));ui.set_values(ModelRc::new(VecModel::from(vec!["active".into()])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(kind,action)in[("displays","stream.display.select"),("shortcuts","stream.shortcut")]{
        ui.set_kind(kind.into());
        for unavailable in [false,true]{
            ui.set_busy(false);ui.set_enabled_items(ModelRc::new(VecModel::from(vec![true])));click(&ui,"可操作项");received.borrow_mut().clear();
            if unavailable{ui.set_enabled_items(ModelRc::new(VecModel::from(vec![false])));}else{ui.set_busy(true);}
            for text in [slint::SharedString::from(Key::Return)," ".into()]{ui.window().dispatch_event(WindowEvent::KeyPressed{text:text.clone()});ui.window().dispatch_event(WindowEvent::KeyReleased{text});}
            assert!(received.borrow().is_empty(),"{kind} dispatched after disabling its focused row: {:?}",received.borrow());
            ui.set_busy(false);ui.set_enabled_items(ModelRc::new(VecModel::from(vec![true])));click(&ui,"可操作项");received.borrow_mut().clear();
            ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
            assert_eq!(&*received.borrow(),&[(action.into(),"active".into())]);
        }
    }ui.hide().unwrap();
}

#[test]
fn route_controls_have_named_actions_and_disable_boundaries_and_the_last_path(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("network".into());
    let routes=Rc::new(VecModel::from(vec![
        crate::RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,can_move_down:true,can_disable:true,..Default::default()},
        crate::RouteRow{key:"relay".into(),label:"自建中继".into(),enabled:true,can_move_up:true,can_disable:true,..Default::default()},
        crate::RouteRow{key:"public_direct".into(),label:"公网直连".into(),..Default::default()},
    ]));ui.set_network_routes(routes.clone().into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));received.borrow_mut().clear();
        click_in_form(&ui,"上移局域网");click_in_form(&ui,"下移自建中继");assert!(received.borrow().is_empty());
        click_in_form(&ui,"下移局域网");click_in_form(&ui,"上移自建中继");assert_eq!(&*received.borrow(),&[("settings.route.move".into(),"lan|down".into()),("settings.route.move".into(),"relay|up".into())]);
        received.borrow_mut().clear();click_in_form(&ui,"连接路径：公网直连");assert_eq!(&*received.borrow(),&[("settings.route.toggle".into(),"public_direct".into())]);
    }
    routes.set_vec(vec![crate::RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,toggle_blocked:true,..Default::default()}]);received.borrow_mut().clear();
    click_in_form(&ui,"连接路径：局域网");click_in_form(&ui,"上移局域网");click_in_form(&ui,"下移局域网");assert!(received.borrow().is_empty());
    for label in ["首选 IP 或主机名","备用地址","中继服务器"]{click_in_form(&ui,label);}
    ui.hide().unwrap();
}

#[test]
fn boolean_settings_identify_the_field_in_their_action_and_keyboard_activation(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("audio".into());
    ui.set_profile_fields(ModelRc::new(VecModel::from(vec![
        SettingRow{key:"playAudio".into(),label:"播放声音".into(),kind:"bool".into(),..Default::default()},
        SettingRow{key:"gamepad".into(),label:"手柄输入".into(),kind:"bool".into(),..Default::default()},
    ])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(label,key)in[("播放声音：关闭","playAudio"),("手柄输入：关闭","gamepad")]{
        received.borrow_mut().clear();click(&ui,label);ui.window().dispatch_event(WindowEvent::KeyPressed{text:" ".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:" ".into()});
        assert_eq!(&*received.borrow(),&[("settings.set".into(),format!("{key}=toggle")),("settings.set".into(),format!("{key}=toggle"))]);
    }ui.hide().unwrap();
}

#[test]
fn a_route_action_survives_background_refresh_between_pointer_press_and_release(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("network".into());
    let rows=vec![
        crate::RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,can_move_down:true,can_disable:true,..Default::default()},
        crate::RouteRow{key:"relay".into(),label:"自建中继".into(),enabled:true,can_move_up:true,can_disable:true,..Default::default()},
    ];crate::form_models::update_routes(&ui,rows.clone());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height,compact)in[(1180.,780.,false),(720.,560.,true)]{
        ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));click_in_form(&ui,"下移局域网");received.borrow_mut().clear();
        for _ in 0..10 {
            let button=ElementHandle::find_by_accessible_label(&ui,"下移局域网").next().unwrap();let p=button.absolute_position();let size=button.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
            ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
            i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(100));crate::form_models::update_routes(&ui,rows.clone());
            ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        }
        assert_eq!(&*received.borrow(),&vec![("settings.route.move".into(),"lan|down".into());10],"background refresh discarded a route action at {width}x{height}");
    }ui.hide().unwrap();
}

#[test]
fn updating_another_route_does_not_take_the_current_buttons_keyboard_focus(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("network".into());
    let mut rows=vec![
        crate::RouteRow{key:"lan".into(),label:"局域网".into(),enabled:true,can_move_down:true,can_disable:true,..Default::default()},
        crate::RouteRow{key:"relay".into(),label:"自建中继".into(),enabled:true,can_move_up:true,can_disable:true,..Default::default()},
    ];crate::form_models::update_routes(&ui,rows.clone());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    click_in_form(&ui,"下移局域网");received.borrow_mut().clear();rows[1].label="中继路径".into();crate::form_models::update_routes(&ui,rows);
    ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
    assert_eq!(&*received.borrow(),&[("settings.route.move".into(),"lan|down".into())]);ui.hide().unwrap();
}

#[test]
fn escape_closes_a_dropdown_before_requesting_navigation_from_the_form(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());
    ui.set_profile_fields(ModelRc::new(VecModel::from(vec![SettingRow{key:"codec".into(),label:"视频编码".into(),kind:"select".into(),option_labels:ModelRc::new(VecModel::from(vec!["H.264".into(),"HEVC".into()])),option_values:ModelRc::new(VecModel::from(vec!["H.264".into(),"HEVC".into()])),..Default::default()}])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(page,tab,label)in[("settings","","视频编码"),("global-settings","sync","自动同步间隔")]{
        ui.set_page(page.into());ui.set_settings_tab(tab.into());
        let dropdown=ElementHandle::find_by_accessible_label(&ui,label).find(|element|element.accessible_expandable()==Some(true)).expect("named dropdown");
        let p=dropdown.absolute_position();let size=dropdown.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        assert_eq!(dropdown.accessible_expanded(),Some(true),"Dropdown did not open on {page}");received.borrow_mut().clear();
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});
        assert_eq!(dropdown.accessible_expanded(),Some(false),"Escape did not close the dropdown on {page}");
        assert!(received.borrow().is_empty(),"Escape escaped an open dropdown on {page}: {:?}",received.borrow());
        ui.window().dispatch_event(WindowEvent::KeyPressRepeated{text:Key::Escape.into()});
        assert!(received.borrow().is_empty(),"Holding Escape navigated after closing the dropdown on {page}");
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});
        assert_eq!(&*received.borrow(),&[("navigation.back".into(),"".into())],"The form did not route Escape through the shared action after its popup closed on {page}");
    }
    ui.set_page("devices".into());received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert!(received.borrow().is_empty());ui.hide().unwrap();
}

#[test]
fn escape_after_a_page_transition_does_not_require_an_extra_pointer_click(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for page in ["connection","global-settings","settings"]{
        ui.set_page(page.into());ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});
        assert_eq!(&*received.borrow(),&[("navigation.back".into(),"".into())],"No Escape fallback after opening {page}");received.borrow_mut().clear();
    }ui.hide().unwrap();
}

#[test]
fn page_focus_fallback_does_not_take_the_new_forms_initial_text_focus(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(page,action)in[("add","device.add.input"),("device-info","device.info.input")]{
        ui.set_page(page.into());ui.window().dispatch_event(WindowEvent::KeyPressed{text:"1".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"1".into()});
        assert_eq!(&*received.borrow(),&[(action.into(),"1".into())],"Initial text focus was stolen on {page}");received.borrow_mut().clear();
    }ui.hide().unwrap();
}

#[test]
fn closing_an_unsaved_decision_restores_the_existing_editors_focus_without_test_mode(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());
    ui.set_profile_fields(ModelRc::new(VecModel::from(vec![SettingRow{key:"width".into(),label:"画面宽度".into(),kind:"number".into(),input_text:"".into(),..Default::default()}])));
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    assert!(!ui.global::<crate::UiTest>().get_enabled());
    let editor=ElementHandle::find_by_accessible_label(&ui,"画面宽度").find(|item|item.accessible_read_only().is_some()).expect("actual width editor");let p=editor.absolute_position();let size=editor.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
    ui.window().dispatch_event(WindowEvent::KeyPressed{text:"1".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"1".into()});assert_eq!(&*received.borrow(),&[("settings.input".into(),"width=1".into())]);
    ui.set_navigation_prompt(true);ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert_eq!(received.borrow().last().unwrap(),&("navigation.resolve".into(),"stay".into()));
    ui.set_navigation_prompt(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:"2".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"2".into()});
    assert_eq!(&*received.borrow(),&[("settings.input".into(),"width=12".into())]);ui.hide().unwrap();
}

#[test]
fn navigation_controls_do_not_replace_the_form_editors_return_focus(){
    i_slint_backend_testing::init_no_event_loop();
    for label in ["远程设备","设置","添加电脑","概览","选择设备 claw","返回"]{
        let ui=MainWindow::new().unwrap();let sync=label=="返回";ui.set_page(if sync{"global-settings"}else{"settings"}.into());ui.set_settings_tab("sync".into());
        ui.set_devices(ModelRc::new(VecModel::from(vec![crate::DeviceRow{uuid:"claw".into(),name:"claw".into(),..Default::default()}])));
        ui.set_profile_fields(ModelRc::new(VecModel::from(vec![SettingRow{key:"width".into(),label:"画面宽度".into(),kind:"number".into(),input_text:"".into(),..Default::default()}])));
        let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
        let editor=ElementHandle::find_by_accessible_label(&ui,if sync{"WebDAV 目录地址（HTTP / HTTPS）"}else{"画面宽度"}).find(|item|item.accessible_read_only().is_some()).unwrap();let p=editor.absolute_position();let size=editor.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
        ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:"1".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"1".into()});
        click(&ui,label);ui.set_navigation_prompt(true);
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
        ui.set_navigation_prompt(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:"2".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"2".into()});
        assert_eq!(&*received.borrow(),&[(if sync{"sync.edit"}else{"settings.input"}.into(),if sync{"url=12"}else{"width=12"}.into())],"Navigation control {label} replaced the editor focus");ui.hide().unwrap();
    }
}

#[test]
fn device_search_has_a_name_on_the_actual_accessible_editor(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.show().unwrap();
    let editors=ElementHandle::find_by_accessible_label(&ui,"搜索名称或地址").filter(|item|item.accessible_read_only().is_some()).count();
    assert_eq!(editors,1,"The API test label must also name the actual accessible editor");ui.hide().unwrap();
}

#[test]
fn dismissing_a_decision_with_an_unavailable_origin_keeps_the_page_keyboard_fallback(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_settings_dirty(true);
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();click(&ui,"保存");
    ui.set_navigation_prompt(true);ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
    ui.set_settings_dirty(false);ui.set_navigation_prompt(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});assert!(received.borrow().is_empty());
    ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert_eq!(&*received.borrow(),&[("navigation.back".into(),"".into())]);ui.hide().unwrap();
}

#[test]
fn completing_a_decision_into_a_different_page_preserves_the_new_forms_initial_focus(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();click(&ui,"概览");
    ui.set_navigation_prompt(true);ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});
    ui.set_page("add".into());ui.set_navigation_prompt(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:"1".into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:"1".into()});assert_eq!(&*received.borrow(),&[("device.add.input".into(),"1".into())]);ui.hide().unwrap();
}

#[test]
fn modal_selection_tracks_the_actual_buttons_focus_without_test_mode(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();
    ui.set_page("settings".into());ui.set_navigation_prompt(true);ui.show().unwrap();
    click(&ui,"放弃更改");assert_eq!(ui.get_navigation_choice(),1);
    click(&ui,"保存并继续");assert_eq!(ui.get_navigation_choice(),2);
    click(&ui,"继续编辑");assert_eq!(ui.get_navigation_choice(),0);
    ui.set_navigation_prompt(false);ui.set_sync_clear_prompt(true);
    click(&ui,"移除");assert_eq!(ui.get_sync_clear_choice(),1);
    let cancel=ElementHandle::find_by_accessible_label(&ui,"取消").last().expect("modal cancel button");
    let p=cancel.absolute_position();let size=cancel.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});assert_eq!(ui.get_sync_clear_choice(),0);
    ui.hide().unwrap();
}

#[test]
fn disabling_a_focused_button_then_focusing_an_editor_does_not_leave_two_focus_markers(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_settings_dirty(true);
    ui.set_profile_fields(ModelRc::new(VecModel::from(vec![SettingRow{key:"width".into(),label:"画面宽度".into(),kind:"number".into(),input_text:"1920".into(),..Default::default()}])));
    let focused=Rc::new(RefCell::new(Vec::new()));let reports=focused.clone();let probe=ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_operation("inspect".into());probe.on_report(move|id,_,_,_,has_focus,_,_,_,_,_,_|{if has_focus{reports.borrow_mut().push(id.to_string());}});
    ui.show().unwrap();click(&ui,"保存");ui.set_settings_dirty(false);
    let editor=ElementHandle::find_by_accessible_label(&ui,"画面宽度").find(|item|item.accessible_read_only().is_some()).expect("actual width editor");let p=editor.absolute_position();let size=editor.size();let position=LogicalPosition::new(p.x+size.width/2.,p.y+size.height/2.);
    ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});ui.set_settings_dirty(true);probe.set_generation(1);
    i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    assert_eq!(&*focused.borrow(),&["input|画面宽度".to_owned()]);ui.hide().unwrap();
}

#[test]
fn tab_reaches_the_offscreen_relay_editor_after_the_route_buttons(){
    i_slint_backend_testing::init_no_event_loop();let ui=MainWindow::new().unwrap();ui.set_page("settings".into());ui.set_profile_tab("network".into());
    ui.set_network_summary("局域网：192.0.2.1\nTailscale：100.64.0.1\nTailscale：fd7a:115c:a1e0::1\n内置直连 / 中继：已配置".into());
    ui.set_network_routes(ModelRc::new(VecModel::from(rhremote_core::network::ROUTES.iter().enumerate().map(|(index,(key,label))|crate::RouteRow{key:(*key).into(),label:(*label).into(),enabled:true,can_move_up:index>0,can_move_down:index+1<rhremote_core::network::ROUTES.len(),can_disable:true,toggle_blocked:false}).collect::<Vec<_>>())));
    ui.set_profile_fields(ModelRc::new(VecModel::from(vec![SettingRow{key:"networkMode".into(),label:"连接网络".into(),kind:"select".into(),option_labels:ModelRc::new(VecModel::from(vec!["自动".into(),"外网".into()])),option_values:ModelRc::new(VecModel::from(vec!["auto".into(),"remote".into()])),..Default::default()}])));
    let focused=Rc::new(RefCell::new(Vec::new()));let reports=focused.clone();let probe=ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_operation("inspect".into());probe.on_report(move|id,_,_,_,has_focus,_,_,_,_,_,_|{if has_focus{reports.borrow_mut().push(id.to_string());}});
    ui.show().unwrap();ui.window().set_size(LogicalSize::new(1180.,780.));
    let scroll=ElementHandle::find_by_element_id(&ui,"MainWindow::form-scroll").next().unwrap();scroll.scroll(0.,100000.);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    let mut found=false;
    for generation in 1..=60{
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Tab.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Tab.into()});
        focused.borrow_mut().clear();probe.set_generation(generation);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
        if focused.borrow().iter().any(|id|id=="input|中继服务器"){found=true;break;}
    }
    assert!(found,"Tab skipped the relay editor while its internal clip was outside the scroll viewport");ui.hide().unwrap();
}

#[test]
fn toolbar_escape_collapses_the_panel_without_disconnecting_or_repeating(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamToolbar::new().unwrap();ui.set_expanded(true);
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();click(&ui,"快捷键 ▾");received.borrow_mut().clear();
    ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});
    assert_eq!(&*received.borrow(),&[("stream.controls.toggle".into(),"".into())]);
    ui.set_expanded(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressRepeated{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert!(received.borrow().is_empty());ui.hide().unwrap();
}

#[test]
fn picker_tab_does_not_stop_on_an_invisible_parent_scope(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamPicker::new().unwrap();ui.set_kind("shortcuts".into());ui.set_labels(ModelRc::new(VecModel::from(vec!["A".into(),"B".into()])));ui.set_values(ModelRc::new(VecModel::from(vec!["a".into(),"b".into()])));ui.show().unwrap();
    let focused=Rc::new(RefCell::new(Vec::new()));let reports=focused.clone();let probe=ui.global::<crate::UiTest>();probe.set_enabled(true);probe.on_report(move|id,_,_,_,active,_,_,_,_,_,_|{if active{reports.borrow_mut().push(id.to_string());}});probe.set_operation("focus".into());probe.set_target("action|stream.menu.close||关闭".into());probe.set_generation(1);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    probe.set_operation("inspect".into());
    for(index,expected)in["picker|shortcuts|a","picker|shortcuts|b","action|stream.menu.close||关闭"].iter().enumerate(){
        ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Tab.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Tab.into()});focused.borrow_mut().clear();probe.set_generation(index as i32+2);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));assert_eq!(&*focused.borrow(),&[expected.to_string()]);
    }ui.hide().unwrap();
}

#[test]
fn pending_toolbar_exposes_status_and_cancel_without_expanding_controls(){
    i_slint_backend_testing::init_no_event_loop();let ui=crate::StreamToolbar::new().unwrap();ui.set_expanded(false);ui.set_pending(true);ui.set_device_name("winserver".into());ui.set_status("等待远程画面".into());
    let received=Rc::new(RefCell::new(Vec::new()));let calls=received.clone();ui.global::<Commands>().on_trigger(move|id,arg|calls.borrow_mut().push((id.to_string(),arg.to_string())));ui.show().unwrap();
    for(width,height)in[(560.,116.),(355.,160.)]{
        ui.window().set_size(LogicalSize::new(width,height));
        let cancel=ElementHandle::find_by_accessible_label(&ui,"取消连接").next().expect("visible pending cancel");let p=cancel.absolute_position();let size=cancel.size();assert!(p.x>=0.&&p.y>=28.&&p.x+size.width<=width&&p.y+size.height<=height,"pending cancel outside toolbar: {p:?}, {size:?}");
        let status=ElementHandle::find_by_accessible_label(&ui,"等待远程画面").next().expect("visible stage");let p=status.absolute_position();let size=status.size();assert!(size.width>0.&&size.height>0.&&p.y+size.height<=height);
        received.borrow_mut().clear();click(&ui,"取消连接");assert_eq!(&*received.borrow(),&[("stream.disconnect".into(),"".into())]);
        ui.set_canceling(true);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Return.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Return.into()});click(&ui,"取消连接");assert!(received.borrow().is_empty(),"canceling must disable stale focused button");ui.set_canceling(false);
    }
    ui.set_expanded(true);click(&ui,"收起");received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert_eq!(&*received.borrow(),&[("stream.controls.toggle".into(),"".into())]);
    ui.set_expanded(false);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert_eq!(&*received.borrow(),&[("stream.disconnect".into(),"".into())]);
    ui.set_canceling(true);received.borrow_mut().clear();ui.window().dispatch_event(WindowEvent::KeyPressed{text:Key::Escape.into()});ui.window().dispatch_event(WindowEvent::KeyReleased{text:Key::Escape.into()});assert!(received.borrow().is_empty());ui.hide().unwrap();
}

#[test]
fn recovery_failure_keeps_buttons_visible_and_uses_retry_dismiss_keyboard_actions(){
    use i_slint_backend_testing::{TestingBackend, TestingBackendOptions};
    slint::platform::set_platform(Box::new(TestingBackend::new(TestingBackendOptions{mock_time:true,threading:false,renderer_name:Some("software".into())}))).unwrap();
    for scale in [1.0,1.5,2.25] {
        for width in [355.,560.] {
          for dark in [false,true] {
            let ui=crate::StreamToolbar::new().unwrap();
            let calls=Rc::new(RefCell::new(Vec::new()));let received=calls.clone();
            ui.global::<Commands>().on_trigger(move|id,arg|received.borrow_mut().push((id.to_string(),arg.to_string())));
            ui.global::<crate::Palette>().set_dark(dark);
            ui.set_device_name("winserver · 工作电脑".into());ui.set_connection_failed(true);
            ui.set_connection_error("网络中断，自动恢复未能完成。请检查远程设备后重试。".repeat(30).into());
            ui.window().dispatch_event(WindowEvent::ScaleFactorChanged{scale_factor:scale});
            // TestingWindow::set_size uses scale=1 internally. Set its physical
            // buffer and the logical resize separately, as a real backend does.
            ui.window().set_size(slint::PhysicalSize::new((width*scale) as u32,(248.*scale) as u32));
            ui.window().dispatch_event(WindowEvent::Resized{size:LogicalSize::new(width,248.)});
            ui.show().unwrap();
            for expanded in [false,true] {
                ui.set_expanded(expanded);let frame=ui.window().take_snapshot().unwrap();
                assert_eq!((frame.width(),frame.height()),((width*scale) as u32,(248.*scale) as u32));
                let error=ElementHandle::find_by_accessible_label(&ui,&ui.get_connection_error()).next().unwrap();
                assert!(error.size().width<=width-32.+0.1 && error.size().height>248.,"long error must wrap inside a vertical scroll area at {scale}/{width}/{dark}: {:?}",error.size());
                let before_y=error.absolute_position().y;
                let footer_y=ElementHandle::find_by_accessible_label(&ui,"重试").next().unwrap().absolute_position().y;
                let scroll=ui.global::<crate::UiTest>();scroll.set_enabled(true);scroll.set_operation("scroll".into());scroll.set_target("scroll|connection-error".into());scroll.set_scroll_delta(-10000.);scroll.set_generation(scroll.get_generation()+1);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));ui.window().take_snapshot().unwrap();
                assert!(error.absolute_position().y<before_y-100.,"long error cannot scroll");
                assert_eq!(ElementHandle::find_by_accessible_label(&ui,"重试").next().unwrap().absolute_position().y,footer_y,"scroll must not move the recovery actions");
                scroll.set_scroll_delta(10000.);scroll.set_generation(scroll.get_generation()+1);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));ui.window().take_snapshot().unwrap();
                assert!((error.absolute_position().y-before_y).abs()<0.1);
                for(label,action)in[("返回设备","connection.dismiss"),("重试","connection.retry"),("关闭","navigation.back")] {
                    let button=ElementHandle::find_by_accessible_label(&ui,label).next().unwrap();let p=button.absolute_position();let size=button.size();
                    assert!(p.x>=0.&&p.y>=0.&&p.x+size.width<=width+0.1&&p.y+size.height<=248.1,"{label} outside recovery card at {scale}/{width}: {p:?}/{size:?}");
                    calls.borrow_mut().clear();click(&ui,label);assert_eq!(&*calls.borrow(),&[(action.into(),"".into())]);
                }
                assert!(ElementHandle::find_by_accessible_label(&ui,"Ctrl+Alt+Delete").next().is_none());
                calls.borrow_mut().clear();crate::ui_keyboard::send_to_window(ui.window(),"Escape",false,true);assert!(calls.borrow().is_empty());
                crate::ui_keyboard::send_to_window(ui.window(),"Escape",false,false);assert_eq!(&*calls.borrow(),&[("connection.dismiss".into(),"".into())]);
            }
    // Replaced controls must not retain keyboard focus on the old shortcut.
    ui.set_connection_failed(false);ui.set_expanded(true);click(&ui,"Ctrl+Alt+Delete");
    ui.set_connection_failed(true);calls.borrow_mut().clear();crate::ui_keyboard::send_to_window(ui.window(),"Enter",false,false);assert!(calls.borrow().is_empty());
    let probe=ui.global::<crate::UiTest>();probe.set_enabled(true);probe.set_operation("focus".into());probe.set_target("action|navigation.back||关闭".into());probe.set_generation(1);i_slint_backend_testing::mock_elapsed_time(std::time::Duration::from_millis(20));
    // A long error is a real reading target between the header and recovery actions.
    // Enter while reading must not dismiss/retry, and PageDown must move the text.
    let reading_top=ElementHandle::find_by_accessible_label(&ui,&ui.get_connection_error()).next().unwrap().absolute_position().y;
    calls.borrow_mut().clear();crate::ui_keyboard::send_to_window(ui.window(),"Tab",false,false);crate::ui_keyboard::send_to_window(ui.window(),"Enter",false,false);assert!(calls.borrow().is_empty());
    crate::ui_keyboard::send_to_window(ui.window(),"PageDown",false,false);ui.window().take_snapshot().unwrap();
    assert!(ElementHandle::find_by_accessible_label(&ui,&ui.get_connection_error()).next().unwrap().absolute_position().y<reading_top-1.,"Tab must reach the scrollable failure details");
    assert!(calls.borrow().is_empty());
    crate::ui_keyboard::send_to_window(ui.window(),"Tab",false,false);crate::ui_keyboard::send_to_window(ui.window(),"Enter",false,false);assert_eq!(&*calls.borrow(),&[("connection.dismiss".into(),"".into())]);
    calls.borrow_mut().clear();crate::ui_keyboard::send_to_window(ui.window(),"Tab",false,false);crate::ui_keyboard::send_to_window(ui.window(),"Enter",false,false);assert_eq!(&*calls.borrow(),&[("connection.retry".into(),"".into())]);
    if let Some(directory)=std::env::var_os("RHREMOTE_RECOVERY_EVIDENCE_DIR") {
        let path=std::path::PathBuf::from(directory).join(format!("failure-{width}-{scale}-{dark}.png"));assert!(!path.exists());
        let frame=ui.window().take_snapshot().unwrap();image::save_buffer(path,frame.as_bytes(),frame.width(),frame.height(),image::ColorType::Rgba8).unwrap();
    }
    ui.hide().unwrap();
          }
        }
    }
}
