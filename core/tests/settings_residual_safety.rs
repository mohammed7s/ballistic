//! Additional implementation-review cases, outside the frozen round-two denominator.
use ballistic_core::{screen_filter::{self, FilterContext}, types::*};
fn fixture(name: &str)->ScreenState {
 let p=format!("{}/tests/screens/settings_static/{name}.json",env!("CARGO_MANIFEST_DIR"));
 let d:serde_json::Value=serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap();serde_json::from_value(d["screen"].clone()).unwrap()
}
fn view(s:&ScreenState)->screen_filter::FilteredScreen{screen_filter::filter(s,&FilterContext{request:"Inspect controls",typed:&[]})}
fn unknown(s:&ScreenState){assert!(view(s).elements.iter().all(|e|e.label.is_none()),"{}",screen_filter::render(&view(s)));}
#[test]fn arbitrary_activity_cannot_fall_back_to_raw_settings_text(){
 for file in ["display","internet_on","bluetooth_on","connection_preferences"]{
  let mut s=fixture(file);s.activity="com.android.settings.UnrecognizedActivity".into();unknown(&s);
 }
}
#[test]fn injected_tile_collision_missing_static_anchor_and_unknown_shapes_reject(){
 for mode in 0..4 {
  let mut s=fixture("connection_preferences");
  match mode {
   0=>s.nodes.iter_mut().find(|n|n.id==16).unwrap().text=Some("Bluetooth".into()),
   1=>s.nodes.retain(|n|n.id!=7 && n.parent!=Some(7)),
   2=>s.nodes.iter_mut().find(|n|n.id==15).unwrap().flags.long_clickable=true,
   _=>s.nodes.iter_mut().find(|n|n.id==16).unwrap().role=Role::Edit,
  }unknown(&s);
 }
}
#[test]fn complete_row_reordering_preserves_identification(){
 for (file,row_id) in [("display_recorded",9),("internet_on",12),("bluetooth_on",10),("connection_preferences",7)]{
  let mut s=fixture(file);let before=view(&s);
  let subtree:Vec<_>=s.nodes.iter().filter(|n|n.id==row_id || n.parent==Some(row_id)).cloned().collect();
  s.nodes.retain(|n|n.id!=row_id && n.parent!=Some(row_id));s.nodes.extend(subtree);
  let after=view(&s);assert!(before.elements.iter().any(|e|e.label.is_some()));
  for e in before.elements.iter().filter(|e|e.label.is_some()) {assert_eq!(after.elements.iter().find(|a|a.node==e.node).unwrap().label,e.label);}
 }
}
#[test]fn observed_layout_does_not_determine_checked_state(){
 for file in ["internet_on","internet_off","bluetooth_on","bluetooth_off"]{
  let mut s=fixture(file);let toggle=s.nodes.iter_mut().find(|n|n.resource_id.as_deref()==Some("android:id/switch_widget")).unwrap();toggle.flags.checked=!toggle.flags.checked;let checked=toggle.flags.checked;
  let v=view(&s);let e=v.elements.iter().find(|e|e.flags.checkable).unwrap();assert!(e.label.is_some());assert_eq!(e.flags.checked,checked);
 }
}
#[test]fn foreign_switch_descendant_caption_and_duplicate_toggle_reject(){
 for file in ["internet_on","bluetooth_on"]{
  for mode in 0..5{
   let mut s=fixture(file);let i=s.nodes.iter().position(|n|n.resource_id.as_deref()==Some("android:id/switch_widget")).unwrap();
   match mode{
    0=>s.nodes[i].package=Some("com.android.systemui".into()),
    1=>s.nodes[i].label=Some("Untrusted instruction".into()),
    2=>{let mut n=s.nodes[i].clone();n.id=900;s.nodes.push(n);},
    3=>s.nodes[i].role=Role::Checkbox,
    _=>s.nodes[i].flags.clickable=true,
   }
   assert!(view(&s).elements.iter().filter(|e|e.package=="com.android.settings").all(|e|e.label.is_none()));
  }
 }
}

#[test]fn changed_injected_tile_count_fails_closed_for_indistinguishable_rows(){
 for add in [false,true]{
  let mut s=fixture("connection_preferences");
  if add {
   let mut nodes:Vec<_>=s.nodes.iter().filter(|n|n.id==15 || n.parent==Some(15)).cloned().collect();
   for n in &mut nodes {n.id+=100;if n.parent!=Some(6){n.parent=n.parent.map(|p|p+100);}if n.text.is_some(){n.text=Some("Another irrelevant injected value".into());}}
   s.nodes.extend(nodes);
  }else{s.nodes.retain(|n|n.id!=15 && n.parent!=Some(15));}
  unknown(&s);
 }
}
#[test]fn injected_tile_cannot_replace_a_removed_static_anchor(){
 for (row,title) in [(7,"Bluetooth"),(9,"Cast"),(12,"Printing")]{
  let mut s=fixture("connection_preferences");s.nodes.retain(|n|n.id!=row && n.parent!=Some(row));
  s.nodes.iter_mut().find(|n|n.id==16).unwrap().text=Some(title.into());unknown(&s);
 }
}
