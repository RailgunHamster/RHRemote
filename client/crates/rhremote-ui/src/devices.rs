use crate::{DeviceRow, MainWindow};
use slint::{Model, ModelRc, VecModel};

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
    fn connect_click_survives_background_state_updates_between_press_and_release() {
        i_slint_backend_testing::init_no_event_loop();
        let ui = MainWindow::new().unwrap();
        ui.window().set_size(LogicalSize::new(1180., 780.));
        let rows = vec![DeviceRow {uuid:"test-server".into(),name:"winserver".into(),status:"已配对".into(),summary:"1920 × 1080".into(),online:true,paired:true,is_self:false}];
        update_devices(&ui, rows.clone());
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
