use crate::{Controller, now_ms, sync_model};
use serde_json::{Value, json};

impl Controller {
    pub(crate) fn dispatch_update(&self, id: &str) -> Result<Value, String> {
        let mut s = self.state.lock().unwrap();
        if s.updates_busy {
            return Err("更新操作正在进行".into());
        }
        if id == "updates.install" && (s.streaming || s.connecting) {
            return Err("请先断开远程连接".into());
        }
        s.updates_busy = true;
        s.revision += 1;
        drop(s);
        let result = self.platform.execute(id, &json!({}));
        let mut s = self.state.lock().unwrap();
        s.updates_busy = false;
        s.revision += 1;
        let result = match result {
            Ok(v) => v,
            Err(e) => {
                s.status = e.clone();
                return Err(e);
            }
        };
        s.updates = result.clone();
        if id == "updates.install" {
            s.window_action = "window.close".into();
        }
        Ok(result)
    }
    pub(crate) fn dispatch_global(&self, id: &str, args: Value) -> Result<Value, String> {
        if id == "app.settings.open" || id == "app.settings.tab" {
            let mut s = self.state.lock().unwrap();
            if id == "app.settings.tab" {
                let tab = args["tab"].as_str().ok_or("tab required")?;
                if !["appearance", "sync", "updates", "about"].contains(&tab) {
                    return Err("unknown settings tab".into());
                }
                s.settings_tab = tab.into();
            }
            s.page = "global-settings".into();
            s.revision += 1;
            return Ok(json!({"tab":s.settings_tab}));
        }
        let mut s = self.state.lock().unwrap();
        if s.sync_busy {
            return Err("同步操作正在进行".into());
        }
        let mut input = args;
        if id == "sync.run" || id == "sync.resolve" {
            if s.draft.is_some() {
                return Err("请先保存或取消设备设置".into());
            }
            input["shared"] = serde_json::to_value(sync_model::from_state(&s)).unwrap();
        }
        s.sync_busy = true;
        s.revision += 1;
        drop(s);
        let result = self.platform.execute(id, &input);
        let mut s = self.state.lock().unwrap();
        s.sync_busy = false;
        s.revision += 1;
        let result = match result {
            Ok(v) => v,
            Err(e) => {
                s.status = e.clone();
                return Err(e);
            }
        };
        if let Some(sync) = result.get("sync") {
            s.sync = sync.clone();
        } else if id == "sync.configure" || id == "sync.state" || id == "sync.clear" {
            s.sync = result.clone();
        }
        if let Some(shared) = result.get("shared") {
            let shared: sync_model::SharedConfig =
                serde_json::from_value(shared.clone()).map_err(|e| e.to_string())?;
            shared.validate()?;
            let mut next = s.saved.clone();
            next.apply_shared(&shared);
            if self.storage.exists() {
                let dir = self.storage.parent().unwrap().join("sync-backups");
                std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
                std::fs::copy(&self.storage, dir.join(format!("local-{}.json", now_ms())))
                    .map_err(|e| e.to_string())?;
                let mut files: Vec<_> = std::fs::read_dir(&dir)
                    .map_err(|e| e.to_string())?
                    .filter_map(Result::ok)
                    .filter(|e| e.file_name().to_string_lossy().starts_with("local-"))
                    .collect();
                files.sort_by_key(|f| f.file_name());
                let count = files.len().saturating_sub(20);
                for file in files.into_iter().take(count) {
                    let _ = std::fs::remove_file(file.path());
                }
            }
            self.persist(&next)?;
            s.saved = next;
            let devices: Vec<crate::Device> = shared
                .devices
                .into_iter()
                .map(|d| {
                    s.devices
                        .iter()
                        .find(|old| old.uuid == d.uuid)
                        .cloned()
                        .map(|mut old| {
                            old.name = d.name.clone();
                            old.address = d.address.clone();
                            old.display_name = d.display_name.clone();
                            old
                        })
                        .unwrap_or(crate::Device {
                            uuid: d.uuid,
                            name: d.name,
                            display_name: d.display_name,
                            address: d.address,
                            online: false,
                            paired: false,
                            is_self: false,
                        })
                })
                .collect();
            // Local identity and pairing are recomputed by the platform, never accepted from WebDAV.
            s.devices = devices.clone();
            s.status = "同步完成".into();
            s.revision += 1;
            drop(s);
            let restored = self
                .platform
                .execute("devices.restore", &json!({"devices":devices}))?;
            if let Some(rows) = restored.get("devices") {
                self.replace_devices(
                    serde_json::from_value(rows.clone()).map_err(|e| e.to_string())?,
                );
            }
            return Ok(json!({"sync":result["sync"],"conflicts":[]}));
        }
        s.status = if result["conflicts"]
            .as_array()
            .is_some_and(|a| !a.is_empty())
        {
            "配置冲突，请选择保留哪一端的冲突项"
        } else if id == "sync.configure" {
            "同步设置已保存"
        } else {
            ""
        }
        .into();
        // Public action results omit the synced document and all credential input.
        Ok(result)
    }
}
