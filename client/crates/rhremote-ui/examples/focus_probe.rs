slint::slint! {
    import { LineEdit, Button, VerticalBox } from "std-widgets.slint";
    export component FocusProbe inherits Window {
        title: "RHRemote · 焦点诊断";
        preferred-width: 640px;
        preferred-height: 400px;
        default-font-family: "Microsoft YaHei UI";
        property <int> actions;
        property <string> owner: old-focus.has-focus ? "原结构持有焦点" : direct-focus.has-focus ? "直接结构持有焦点" : "文本框或标准按钮持有焦点";
        VerticalBox { padding:24px; spacing:16px;
            editor := LineEdit { init=>{self.focus();} text:"焦点测试"; accessible-label:"测试输入框"; }
            Rectangle {
                height:56px; background:old-focus.has-focus ? #edf2ff : #ffffff;
                border-width:1px; border-color:old-focus.has-focus ? #5274e8 : #b0b0b0;
                forward-focus:old-focus;
                accessible-role:button; accessible-label:"原结构";
                accessible-action-default=>{old-focus.focus();root.actions+=1;}
                Text {text:"原结构";horizontal-alignment:center;vertical-alignment:center;}
                old-focus:=FocusScope {key-pressed(event)=>{if(event.text==Key.Return || event.text==" "){root.actions+=1;return accept;}return reject;}}
                TouchArea {clicked=>{old-focus.focus();root.actions+=1;}}
            }
            Rectangle {
                height:56px; background:direct-focus.has-focus ? #edf2ff : #ffffff;
                border-width:1px; border-color:direct-focus.has-focus ? #5274e8 : #b0b0b0;
                forward-focus:direct-focus;
                Text {text:"直接结构";horizontal-alignment:center;vertical-alignment:center;accessible-role:none;}
                direct-focus:=FocusScope {
                    accessible-role:button;accessible-label:"直接结构";
                    accessible-action-default=>{self.focus();root.actions+=1;}
                    key-pressed(event)=>{if(event.text==Key.Return || event.text==" "){root.actions+=1;return accept;}return reject;}
                }
                TouchArea {clicked=>{direct-focus.focus();root.actions+=1;}}
            }
            Button {text:"Slint 标准按钮";clicked=>{root.actions+=1;}}
            Text {text:root.owner;accessible-label:"内部焦点状态";}
            Text {text:"动作次数："+root.actions;}
        }
    }
}

fn main() -> Result<(), slint::PlatformError> {
    FocusProbe::new()?.run()
}
