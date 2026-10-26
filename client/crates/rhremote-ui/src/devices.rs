use crate::{DeviceRow, MainWindow};
use slint::{Model, ModelRc, VecModel};

pub fn filter_devices(rows: Vec<DeviceRow>, query: &str) -> Vec<DeviceRow> {
    let query = query.trim().to_lowercase();
    rows.into_iter().filter(|d| query.is_empty() || [&d.name, &d.host_name, &d.address]
        .iter().any(|s| s.to_lowercase().contains(&query))).collect()
}

pub fn update_devices(ui: &MainWindow, rows: Vec<DeviceRow>) {
    let current = ui.get_devices();
    if let Some(model) = current.as_any().downcast_ref::<VecModel<DeviceRow>>() {
        let same_order = model.row_count() == rows.len()
            && rows.iter().enumerate().all(|(i, row)| model.row_data(i).is_some_and(|old| old.uuid == row.uuid));
        if same_order {
            for (i, row) in rows.into_iter().enumerate() {
                if model.row_data(i).as_ref() != Some(&row) {
                    model.set_row_data(i, row);
                }
            }
            return;
        }
    }
    // Adding, removing or reordering devices really does change card identity.
    // Ordinary session/status polls must keep the existing TouchArea instances.
    ui.set_devices(ModelRc::new(VecModel::from(rows)));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Commands;
    use i_slint_backend_testing::ElementHandle;
    use slint::{ComponentHandle, LogicalPosition, LogicalSize, platform::{PointerEventButton, WindowEvent}};
    use std::{cell::Cell, rc::Rc, time::Duration};

    #[test]
    fn search_matches_alias_hostname_and_address_without_reordering() {
        let rows = vec![
            DeviceRow {uuid:"a".into(),name:"工作机".into(),host_name:"DESKTOP-A".into(),address:"192.168.0.100".into(),..Default::default()},
            DeviceRow {uuid:"b".into(),name:"服务器".into(),host_name:"winserver".into(),address:"100.73.139.109".into(),..Default::default()},
        ];
        for query in ["工作", "desktop-A", "0.100"] {
            let result = filter_devices(rows.clone(), query);
            assert_eq!(result.len(), 1);
            assert_eq!(result[0].uuid, "a");
        }
        assert_eq!(filter_devices(rows.clone(), "  SERVER ")[0].uuid, "b");
        assert_eq!(filter_devices(rows.clone(), "  "), rows);
        assert!(filter_devices(rows, "missing").is_empty());
    }
    #[test]
    fn connect_and_device_selection_fit_normal_and_narrow_windows() {
        i_slint_backend_testing::init_no_event_loop();
        let ui=MainWindow::new().unwrap();
        let row=DeviceRow {uuid:"server".into(),name:"远程服务器".into(),host_name:"winserver".into(),address:"192.168.0.104".into(),paired:true,online:true,..Default::default()};
        update_devices(&ui,vec![row.clone()]);ui.set_selected_uuid(row.uuid.clone());ui.set_selected_device(row);ui.set_selected_name("远程服务器".into());
        ui.show().unwrap();
        for (width,height,compact) in [(1180.,780.,false),(720.,560.,true)] {
            ui.set_compact(compact);ui.window().set_size(LogicalSize::new(width,height));
            let row=ElementHandle::find_by_accessible_label(&ui,"选择设备 远程服务器").next().unwrap();
            let button=ElementHandle::find_by_accessible_label(&ui,"连接").next().unwrap();
            let origin=button.absolute_position();let size=button.size();
            assert!(size.width>=100. && size.height>=32.);
            assert!(origin.x>=row.absolute_position().x+row.size().width);
            assert!(origin.x+size.width<=width && origin.y+size.height<=height);
        }
        ui.hide().unwrap();
    }
    #[test]
    fn connect_click_survives_background_state_updates_between_press_and_release() {
        i_slint_backend_testing::init_no_event_loop();
        let ui = MainWindow::new().unwrap();
        ui.window().set_size(LogicalSize::new(1180., 780.));
        let rows = vec![DeviceRow {uuid:"test-server".into(),name:"winserver".into(),host_name:"winserver".into(),address:"192.168.0.104".into(),status:"已配对".into(),online:true,paired:true,is_self:false}];
        update_devices(&ui, rows.clone());
        ui.set_selected_uuid(rows[0].uuid.clone());
        ui.set_selected_device(rows[0].clone());
        let clicks = Rc::new(Cell::new(0));
        let received = clicks.clone();
        ui.global::<Commands>().on_trigger(move |id, _| {
            if id == "device.connect" {received.set(received.get()+1);}
        });
        ui.show().unwrap();
        let button = ElementHandle::find_by_accessible_label(&ui, "连接").next().unwrap();
        let origin = button.absolute_position();
        let size = button.size();
        let position = LogicalPosition::new(origin.x+size.width/2., origin.y+size.height/2.);
        ui.window().dispatch_event(WindowEvent::PointerMoved{position});
        ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
        ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        assert_eq!(clicks.get(), 1, "baseline click must hit the real button");
        for _ in 0..20 {
            let button = ElementHandle::find_by_accessible_label(&ui, "连接").next().expect("connect button");
            let origin = button.absolute_position();
            let size = button.size();
            let position = LogicalPosition::new(origin.x+size.width/2., origin.y+size.height/2.);
            ui.window().dispatch_event(WindowEvent::PointerMoved{position});
            ui.window().dispatch_event(WindowEvent::PointerPressed{position,button:PointerEventButton::Left});
            i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(100));
            // This is what the production 100 ms state timer did on every revision.
            update_devices(&ui, rows.clone());
            ui.window().dispatch_event(WindowEvent::PointerReleased{position,button:PointerEventButton::Left});
        }
        assert_eq!(clicks.get(), 21, "a background poll discarded a pressed connection button");
        ui.hide().unwrap();
    }
}
