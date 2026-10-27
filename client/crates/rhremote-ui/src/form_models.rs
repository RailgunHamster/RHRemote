use crate::{MainWindow,RouteRow,SyncConflictRow};
use slint::{Model,ModelRc,VecModel};

pub(crate) fn update_routes(ui:&MainWindow,rows:Vec<RouteRow>){
    let current=ui.get_network_routes();
    if let Some(model)=current.as_any().downcast_ref::<VecModel<RouteRow>>(){
        let same_order=model.row_count()==rows.len()&&rows.iter().enumerate().all(|(index,row)|model.row_data(index).is_some_and(|old|old.key==row.key));
        if same_order{
            for(index,row)in rows.into_iter().enumerate(){if model.row_data(index).as_ref()!=Some(&row){model.set_row_data(index,row);}}
            return;
        }
    }
    // Replacing a model destroys its delegates, including pointer grabs and focus.
    // Only structural changes may rebuild the route rows.
    ui.set_network_routes(ModelRc::new(VecModel::from(rows)));
}

pub(crate) fn update_sync_conflicts(ui:&MainWindow,items:&serde_json::Value){
    let rows:Vec<SyncConflictRow>=items.as_array().into_iter().flatten().map(|item|SyncConflictRow{
        label:item["label"].as_str().unwrap_or("其他配置").into(),
        local:item["local"].as_str().unwrap_or("").into(),
        remote:item["remote"].as_str().unwrap_or("").into(),
    }).collect();
    let current=ui.get_sync_conflict_items();
    if current.row_count()==rows.len()&&rows.iter().enumerate().all(|(index,row)|current.row_data(index).as_ref()==Some(row)){return;}
    ui.set_sync_conflict_items(ModelRc::new(VecModel::from(rows)));
}
