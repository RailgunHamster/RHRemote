use slint::ComponentHandle;
use std::time::Duration;

slint::slint! {
    import { LineEdit } from "std-widgets.slint";
    export component FocusClipFixture inherits Window {
        width:240px;height:160px;
        out property <int> focused-index:-1;
        out property <length> offset:flick.content-y;
        flick:=Flickable {
            width:220px;height:120px;content-height:400px;
            FocusScope {x:0px;y:0px;width:180px;height:32px;focus-gained(_)=>{root.focused-index=0;}}
            Rectangle {x:0px;y:180px;width:210px;height:40px;clip:true;
                LineEdit {x:4px;y:4px;width:200px;height:32px;accessible-label:"Scrollable input";text:"existing";changed has-focus=>{if(self.has-focus){root.focused-index=1;}}}
                LineEdit {x:4px;y:80px;width:200px;height:32px;accessible-label:"Clipped input";changed has-focus=>{if(self.has-focus){root.focused-index=8;}}}
            }
            LineEdit {x:0px;y:260px;width:200px;height:32px;visible:false;changed has-focus=>{if(self.has-focus){root.focused-index=9;}}}
            LineEdit {x:0px;y:300px;width:200px;height:32px;enabled:false;changed has-focus=>{if(self.has-focus){root.focused-index=10;}}}
            FocusScope {x:0px;y:360px;width:180px;height:32px;focus-gained(_)=>{root.focused-index=2;}}
        }
    }
}

#[test]
fn tab_reveals_nested_input_but_skips_genuinely_clipped_hidden_and_disabled_controls() {
    i_slint_backend_testing::init_no_event_loop();
    let ui = FocusClipFixture::new().unwrap();
    ui.show().unwrap();
    for (shift, expected) in [
        (false, 0),
        (false, 1),
        (false, 2),
        (false, 0),
        (true, 2),
        (true, 1),
        (true, 0),
    ] {
        crate::ui_keyboard::send_to_window(ui.window(), "Tab", shift, false);
        i_slint_backend_testing::mock_elapsed_time(Duration::from_millis(20));
        assert_eq!(
            ui.get_focused_index(),
            expected,
            "Tab direction: shift={shift}"
        );
        if expected == 1 {
            assert!(
                ui.get_offset() < 0.,
                "Focused input did not scroll into view"
            );
            // The fixture's input occupies y=184..216 inside a 120 px viewport.
            // Assert the real Flickable offset reveals that whole rectangle.
            let y = 184. + ui.get_offset();
            assert!(y >= 0. && y + 32. <= 121.);
        }
    }
    ui.hide().unwrap();
}
