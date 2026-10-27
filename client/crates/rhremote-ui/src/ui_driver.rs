use crate::{
    MainWindow, StreamPicker, StreamToolbar, UiTest,
    ui_test::{Bridge, Request},
};
use rhremote_core::Controller;
use serde_json::{Value, json};
use slint::ComponentHandle;
use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

enum Phase {
    Prepare,
    Before,
    Applied,
    After,
}
struct Pending {
    request: Request,
    phase: Phase,
    started: Instant,
}
pub(crate) struct Driver {
    bridge: Arc<Bridge>,
    samples: Arc<Mutex<Vec<Value>>>,
    pending: Option<Pending>,
    generation: i32,
}
impl Driver {
    pub fn new(
        bridge: Arc<Bridge>,
        ui: &MainWindow,
        toolbar: &StreamToolbar,
        picker: &StreamPicker,
    ) -> Self {
        let samples = Arc::new(Mutex::new(Vec::new()));
        macro_rules! attach{($component:expr,$window:literal)=>{{
            let samples=samples.clone();let global=$component.global::<UiTest>();global.set_enabled(bridge.enabled);
            global.on_report(move|id,kind,label,enabled,focused,checked,expanded,width,height,scroll_y,scroll_maximum|{
                let mut samples=samples.lock().unwrap();if samples.len()>=512{return;}
                samples.push(json!({"id":id.as_str(),"kind":kind.as_str(),"label":label.as_str(),"enabled":enabled,"focused":focused,"checked":if kind=="checkbox"{json!(checked)}else{Value::Null},"expanded":if kind=="combobox"{json!(expanded)}else{Value::Null},"logicalSize":{"width":width,"height":height},"scroll":if kind=="scroll"{json!({"y":scroll_y,"minimumY":-scroll_maximum,"maximumY":0.})}else{Value::Null},"window":$window}));
            });
        }}}
        attach!(ui, "main");
        attach!(toolbar, "toolbar");
        attach!(picker, "picker");
        Self {
            bridge,
            samples,
            pending: None,
            generation: 0,
        }
    }
    fn issue(
        &mut self,
        request: &Request,
        operation: &str,
        ui: &MainWindow,
        toolbar: &StreamToolbar,
        picker: &StreamPicker,
    ) {
        if operation == "inspect" {
            self.samples.lock().unwrap().clear();
        }
        self.generation = self.generation.wrapping_add(1);
        macro_rules! set {
            ($component:expr) => {{
                let g = $component.global::<UiTest>();
                g.set_operation(operation.into());
                g.set_target(request.control.clone().into());
                g.set_scroll_delta(request.pixels as f32);
                g.set_generation(self.generation);
            }};
        }
        match request.window.as_str() {
            "toolbar" => set!(toolbar),
            "picker" => set!(picker),
            _ => set!(ui),
        }
    }
    fn context(ui: &MainWindow) -> &'static str {
        if ui.get_sync_clear_prompt() {
            "sync-clear"
        } else if ui.get_navigation_prompt() {
            "unsaved"
        } else {
            ""
        }
    }
    fn sample(
        &mut self,
        request: &Request,
        ui: &MainWindow,
        toolbar: &StreamToolbar,
        picker: &StreamPicker,
    ) -> Result<Vec<Value>, String> {
        let window = match request.window.as_str() {
            "toolbar" => toolbar.window(),
            "picker" => picker.window(),
            _ => ui.window(),
        };
        // Rendering instantiates conditional controls and drains Slint's pending
        // property/focus handlers, including when the native window is occluded.
        drop(
            window
                .take_snapshot()
                .map_err(|e| format!("界面测试准备渲染失败：{e}"))?,
        );
        self.issue(request, "inspect", ui, toolbar, picker);
        drop(
            window
                .take_snapshot()
                .map_err(|e| format!("界面测试控件采样失败：{e}"))?,
        );
        let samples = std::mem::take(&mut *self.samples.lock().unwrap());
        if samples.is_empty() {
            Err("界面没有返回控件状态".into())
        } else {
            Ok(samples)
        }
    }
    fn blocked(ui: &MainWindow, id: &str) -> bool {
        match Self::context(ui) {
            "sync-clear" => !id.starts_with("action|sync.clear.resolve|"),
            "unsaved" => !id.starts_with("action|navigation.resolve|"),
            _ => false,
        }
    }
    fn complete(
        &self,
        request: &Request,
        ui: &MainWindow,
        toolbar: &StreamToolbar,
        picker: &StreamPicker,
        mut controls: Vec<Value>,
    ) -> Result<Value, String> {
        controls.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
        for item in &mut controls {
            item["blockedByModal"] = json!(
                request.window == "main" && Self::blocked(ui, item["id"].as_str().unwrap_or(""))
            );
        }
        let focused: Vec<_> = controls
            .iter()
            .filter(|item| item["focused"] == true)
            .map(|item| item["id"].clone())
            .collect();
        let expanded: Vec<_> = controls
            .iter()
            .filter(|item| item["expanded"] == true)
            .map(|item| item["id"].clone())
            .collect();
        if request.operation == "focus" && !focused.iter().any(|id| id == &request.control) {
            return Err("控件未获得焦点，请检查当前页面及弹出菜单".into());
        }
        use slint::winit_030::WinitWindowAccessor;
        let window = match request.window.as_str() {
            "toolbar" => toolbar.window(),
            "picker" => picker.window(),
            _ => ui.window(),
        };
        let active = window.with_winit_window(|window| window.has_focus());
        Ok(
            json!({"delivered":true,"controls":controls,"focused":focused,"expanded":expanded,"modal":if request.window=="main"{Self::context(ui)}else{""},"eventTarget":"application widgets","systemInputInjected":false,"windowActive":active}),
        )
    }
    pub fn tick(
        &mut self,
        ui: &MainWindow,
        toolbar: &StreamToolbar,
        picker: &StreamPicker,
        controller: &Controller,
    ) {
        if !self.bridge.enabled {
            return;
        }
        if let Some(mut pending) = self.pending.take() {
            if pending.started.elapsed() > Duration::from_secs(5) {
                self.bridge
                    .finish(&pending.request.id, Err("界面测试等待控件状态超时".into()));
                return;
            }
            match pending.phase {
                // Property change handlers and conditional controls settle after the
                // current event-loop turn. Never sample in the turn that changed them.
                Phase::Prepare => {
                    pending.phase = Phase::Before;
                    self.pending = Some(pending);
                }
                Phase::Before => {
                    let samples = match self.sample(&pending.request, ui, toolbar, picker) {
                        Ok(samples) => samples,
                        Err(error) => {
                            self.bridge.finish(&pending.request.id, Err(error));
                            return;
                        }
                    };
                    if pending.request.operation == "inspect" {
                        let result = self.complete(&pending.request, ui, toolbar, picker, samples);
                        self.bridge.finish(&pending.request.id, result);
                        return;
                    }
                    let matches: Vec<_> = samples
                        .iter()
                        .filter(|item| item["id"] == pending.request.control)
                        .collect();
                    let error = if matches.len() != 1 {
                        Some("控件不存在或标识不唯一".to_owned())
                    } else if matches[0]["enabled"] != true {
                        Some("该控件当前不可用".to_owned())
                    } else if pending.request.window == "main"
                        && Self::blocked(ui, &pending.request.control)
                    {
                        Some("当前对话框限制了该控件的焦点".to_owned())
                    } else if pending.request.operation == "scroll"
                        && matches[0]["kind"] != "scroll"
                    {
                        Some("该控件不是滚动区域".to_owned())
                    } else {
                        None
                    };
                    if let Some(error) = error {
                        self.bridge.finish(&pending.request.id, Err(error));
                        return;
                    }
                    self.issue(
                        &pending.request,
                        &pending.request.operation.clone(),
                        ui,
                        toolbar,
                        picker,
                    );
                    pending.phase = Phase::Applied;
                    self.pending = Some(pending);
                }
                Phase::Applied => {
                    pending.phase = Phase::After;
                    self.pending = Some(pending);
                }
                Phase::After => {
                    let samples = match self.sample(&pending.request, ui, toolbar, picker) {
                        Ok(samples) => samples,
                        Err(error) => {
                            self.bridge.finish(&pending.request.id, Err(error));
                            return;
                        }
                    };
                    let result = self.complete(&pending.request, ui, toolbar, picker, samples);
                    self.bridge.finish(&pending.request.id, result);
                }
            }
            return;
        }
        let Some(request) = self.bridge.take() else {
            return;
        };
        let state = controller.state();
        if request.window != "main"
            && (state["streaming"] != true && state["connecting"] != true
                || request.window == "picker"
                    && state["streamMenu"].as_str().unwrap_or("").is_empty())
        {
            self.bridge
                .finish(&request.id, Err("当前没有该会话窗口".into()));
            return;
        }
        let phase = if request.operation == "key" {
            if request.window == "main" {
                if let Some(action) = crate::ui_keyboard::send(
                    ui,
                    controller,
                    &request.key,
                    request.shift,
                    request.repeat,
                ) {
                    self.bridge.observe_action(action);
                }
            } else {
                crate::ui_keyboard::send_to_window(
                    if request.window == "toolbar" {
                        toolbar.window()
                    } else {
                        picker.window()
                    },
                    &request.key,
                    request.shift,
                    request.repeat,
                );
            }
            Phase::Applied
        } else {
            Phase::Prepare
        };
        self.pending = Some(Pending {
            request,
            phase,
            started: Instant::now(),
        });
    }
}
