//! Shared physical keyboard layout. Modifier selection is local and one-shot;
//! actual remote chords always send and release every key as one operation.
use serde::Deserialize;
use serde_json::{Value,json};
use std::sync::LazyLock;

#[derive(Clone,Debug,Deserialize)]
pub struct Key {
    pub id:String,pub label:String,pub description:String,pub code:u8,
    pub row:f32,pub x:f32,pub width:f32,pub height:f32,
    #[serde(default)] pub modifier:String,
}
pub const JSON:&str=include_str!("../../../shared/keyboard.json");
pub static KEYS:LazyLock<Vec<Key>>=LazyLock::new(||serde_json::from_str(JSON).expect("compiled keyboard"));
pub fn key(id:&str)->Result<&'static Key,String>{KEYS.iter().find(|key|key.id==id).ok_or_else(||"unknown keyboard key".into())}
pub fn command(id:&str,modifiers:&[String])->Result<Value,String>{
    let key=key(id)?;let mut keys=Vec::new();
    for modifier in modifiers {
        let code=match modifier.as_str(){"shift"=>16,"ctrl"=>17,"alt"=>18,"meta"=>91,_=>return Err("unknown keyboard modifier".into())};
        if keys.contains(&code){return Err("duplicate keyboard modifier".into());}keys.push(code);
    }
    let secure=id=="delete"&&keys.contains(&17)&&keys.contains(&18);
    keys.push(key.code as i32);
    Ok(json!({"key":id,"modifiers":modifiers,"keys":keys,"secureAttention":secure}))
}
#[cfg(test)]mod tests{
    use super::*;
    #[test]fn complete_keyboard_has_unique_non_overlapping_keys(){
        assert_eq!(KEYS.len(),108);let mut ids=std::collections::BTreeSet::new();
        for(a,k)in KEYS.iter().enumerate(){assert!(ids.insert(&k.id));assert!(k.code>0&&k.width>0.&&k.height>0.&&k.x>=0.&&k.x+k.width<=23.&&k.row>=0.&&k.row+k.height<=6.);
            for b in KEYS.iter().skip(a+1){assert!(!(k.x<b.x+b.width&&k.x+k.width>b.x&&k.row<b.row+b.height&&k.row+k.height>b.row),"{} overlaps {}",k.id,b.id);}}
        for id in ["tab","f12","arrow-up","num-enter","num-0","media-play-pause"]{assert!(key(id).is_ok());}
    }
    #[test]fn chords_validate_modifiers_and_route_secure_attention(){
        assert_eq!(command("c",&["ctrl".into()]).unwrap()["keys"],json!([17,67]));
        assert_eq!(command("delete",&["ctrl".into(),"alt".into()]).unwrap()["secureAttention"],true);
        assert!(command("unknown",&[]).is_err());assert!(command("a",&["bad".into()]).is_err());assert!(command("a",&["ctrl".into(),"ctrl".into()]).is_err());
    }
}
