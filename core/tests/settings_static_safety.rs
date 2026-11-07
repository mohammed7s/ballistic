//! Supplemental review cases after the frozen development matrix. Not held-out evidence.
use ballistic_core::{screen_filter::{self, FilterContext}, types::*};

fn network() -> ScreenState {
    let raw: serde_json::Value = serde_json::from_str(include_str!("screens/settings_static/network.json")).unwrap();
    serde_json::from_value(raw["screen"].clone()).unwrap()
}

#[test]
fn typed_history_cannot_promote_a_settings_summary_or_remote_name() {
    let mut s = network();
    s.nodes.iter_mut().find(|n| n.id == 9).unwrap().text = Some("Previously typed private text".into());
    let v = screen_filter::filter(&s,&FilterContext{request:"Inspect controls",typed:&["Previously typed private text".into()]});
    assert!(!screen_filter::render(&v).contains("Previously typed"));
    assert!(v.contents.iter().any(|c|c.text.contains("Previously typed")));
    assert_eq!(v.elements.iter().find(|e|e.node==7).unwrap().label.as_deref(),Some("Internet"));
}

#[test]
fn visible_optional_row_with_hidden_title_is_not_identified() {
    let mut s=network();
    let row=s.nodes.iter_mut().find(|n|n.id==33).unwrap();
    row.flags.visible=true;row.bounds=Bounds{x1:0,y1:2000,x2:1000,y2:2200};
    let v=screen_filter::filter(&s,&FilterContext{request:"Inspect controls",typed:&[]});
    assert!(v.elements.iter().find(|e|e.node==33).unwrap().label.is_none());
    assert_eq!(v.elements.iter().find(|e|e.node==7).unwrap().label.as_deref(),Some("Internet"));
}

#[test]
fn unknown_or_missing_package_ownership_never_completes_a_page_signature() {
    for id in [1,2,3,6,7,8,9,18] {
        let mut s=network();s.nodes.iter_mut().find(|n|n.id==id).unwrap().package=None;
        let v=screen_filter::filter(&s,&FilterContext{request:"Inspect controls",typed:&[]});
        assert!(v.elements.iter().filter(|e|e.package=="com.android.settings").all(|e|e.label.is_none()));
    }
}

#[test]
fn gear_widget_or_nested_foreign_overlay_invalidates_static_page_proof() {
    for package in ["com.android.settings","com.android.systemui"] {
        let mut s=network();let mut extra=s.nodes.iter().find(|n|n.id==8).unwrap().clone();
        extra.id=900;extra.parent=Some(7);extra.package=Some(package.into());
        extra.resource_id=Some("com.android.settings:id/settings_button".into());
        extra.text=Some("Hostile overlay text".into());s.nodes.push(extra);
        let v=screen_filter::filter(&s,&FilterContext{request:"Inspect controls",typed:&[]});
        assert!(v.elements.iter().filter(|e|e.package=="com.android.settings").all(|e|e.label.is_none()));
        assert!(!screen_filter::render(&v).contains("Hostile overlay"));
    }
}
