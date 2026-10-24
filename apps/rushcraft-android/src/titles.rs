//! Pro mode's Titles panel: the built-in graphics templates (lower thirds, titles, credits, end
//! cards, callouts), placed at the playhead with an entrance and exit, and their editable fields.

use filmcraft_engine::Session;
use filmcraft_project::gtemplate::base64_decode;
use serde_json::{Value, json};

use crate::edits::{self, ex};
use crate::motion::{self, Anim};
use crate::{MobileError, model};

/// A title template.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TitleTemplate {
    pub id: String,
    pub name: String,
    pub category: String,
    pub description: String,
}

/// What a field of a placed title edits.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FieldKind {
    Text,
    Color,
    Slider,
    Checkbox,
}

/// One editable field of a placed title.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TitleField {
    pub id: String,
    pub name: String,
    pub kind: FieldKind,
    /// Text: the text. Color: `#rrggbb`. Slider: the number. Checkbox: `true` / `false`.
    pub value: String,
    pub min: f64,
    pub max: f64,
}

/// The templates, lower thirds first.
pub(crate) fn templates(s: &mut Session) -> Result<Vec<TitleTemplate>, MobileError> {
    let v = ex(s, "graphics.template.list", json!({}))?;
    let str_of = |e: &Value, k: &str| e[k].as_str().unwrap_or_default().to_string();
    let mut out: Vec<TitleTemplate> = v
        .as_array()
        .map(|a| {
            a.iter()
                .map(|e| TitleTemplate { id: str_of(e, "id"), name: str_of(e, "name"), category: str_of(e, "category"), description: str_of(e, "description") })
                .filter(|t| !t.id.is_empty())
                .collect()
        })
        .unwrap_or_default();
    let rank = |c: &str| ["Lower Thirds", "Titles", "End Cards", "End Credits", "Callouts"].iter().position(|x| *x == c).unwrap_or(9);
    out.sort_by_key(|t| rank(&t.category));
    Ok(out)
}

/// Template `id` drawn `width` pixels wide, as a PNG.
pub(crate) fn thumbnail(s: &mut Session, id: &str, width: u32) -> Result<Vec<u8>, MobileError> {
    let v = ex(s, "graphics.template.thumbnail", json!({"template": id, "width": width.clamp(64, 1024)}))?;
    v["pngBase64"].as_str().and_then(base64_decode).ok_or_else(|| MobileError::Command("The preview could not be drawn.".into()))
}

/// The entrance and exit a new title of `category` gets.
fn default_anims(category: &str) -> (Anim, Anim) {
    match category {
        "Lower Thirds" => (Anim::SlideRight, Anim::Fade),
        "Titles" => (Anim::Pop, Anim::Fade),
        "End Cards" => (Anim::Zoom, Anim::None),
        "Callouts" => (Anim::Pop, Anim::Fade),
        // credits and tickers move by themselves
        _ => (Anim::None, Anim::None),
    }
}

/// Place template `id` at the playhead on the lowest free lane above the main track, with its
/// usual entrance and exit; it becomes the selection. Returns the clip.
pub(crate) fn add(s: &mut Session, id: &str) -> Result<u64, MobileError> {
    let t = templates(s)?.into_iter().find(|t| t.id == id).ok_or_else(|| MobileError::Command("That title is no longer available.".into()))?;
    let rate = s.sequence_rate();
    let at = rate.snap(s.playhead());
    // the template's length is only known once placed: look for room for 10 seconds
    let end = at + filmcraft_time::Tick::from_seconds_f64(10.0);
    let track = edits::free_lane_index(s, filmcraft_project::TrackKind::Video, 1, None, at, end, None)?;
    let before = s.history.undo.len();
    let r = ex(s, "graphics.template.apply", json!({"template": id, "time": at.0, "track": track}))?;
    let clip = r["clip"].as_u64().ok_or_else(|| MobileError::Command("The title could not be added.".into()))?;
    let (enter, exit) = default_anims(&t.category);
    if enter != Anim::None || exit != Anim::None {
        motion::animate(s, clip, enter, exit, 0.5)?;
    }
    // one undo step for the lane, the title and its animation
    if s.history.undo.len() > before + 1 {
        s.history.undo.truncate(before + 1);
    }
    if let Some(e) = s.history.undo.get_mut(before) {
        e.0 = format!("Add {}", t.name);
    }
    ex(s, "timeline.select", json!({"clips": [clip]}))?;
    Ok(clip)
}

/// The editable fields of title clip `clip` (none for other clips).
pub(crate) fn fields(s: &mut Session, clip: u64) -> Result<Vec<TitleField>, MobileError> {
    {
        let (_, seq) = model::sequence(s)?;
        let it = model::find(seq, filmcraft_project::ClipId(clip)).map(|f| f.clip);
        if !it.is_some_and(|c| c.graphic.as_ref().is_some_and(|g| g.template.is_some())) {
            return Ok(Vec::new());
        }
    }
    let v = ex(s, "graphics.template.controls", json!({"clip": clip}))?;
    let out = v["controls"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|c| {
                    let kind = match c["kind"].as_str()? {
                        "text" => FieldKind::Text,
                        "color" => FieldKind::Color,
                        "slider" => FieldKind::Slider,
                        "checkbox" => FieldKind::Checkbox,
                        // fonts and positions: the Type panel and the Program monitor
                        _ => return None,
                    };
                    let value = match &c["value"] {
                        Value::String(t) => t.clone(),
                        Value::Bool(b) => b.to_string(),
                        Value::Number(n) => n.to_string(),
                        _ => String::new(),
                    };
                    Some(TitleField {
                        id: c["id"].as_str()?.to_string(),
                        name: c["name"].as_str().unwrap_or_default().to_string(),
                        kind,
                        value,
                        min: c["min"].as_f64().unwrap_or(0.0),
                        max: c["max"].as_f64().unwrap_or(100.0),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(out)
}

/// Set field `field` of title clip `clip` (`value` as [`TitleField::value`]). `merge` folds a
/// slider drag or typing into one undo step.
pub(crate) fn set_field(s: &mut Session, clip: u64, field: &str, value: &str, merge: bool) -> Result<(), MobileError> {
    let f = fields(s, clip)?.into_iter().find(|f| f.id == field).ok_or_else(|| MobileError::Command("That title has no such field.".into()))?;
    let v = match f.kind {
        FieldKind::Text => json!(value),
        FieldKind::Color => {
            let ok = value.len() == 7 && value.starts_with('#') && value[1..].chars().all(|c| c.is_ascii_hexdigit());
            if !ok {
                return Err(MobileError::Command("A colour is #rrggbb.".into()));
            }
            json!(value)
        }
        FieldKind::Slider => json!(value.parse::<f64>().map_err(|_| MobileError::Command("That isn't a number.".into()))?),
        FieldKind::Checkbox => json!(value == "true"),
    };
    let before = s.history.undo.len();
    let top = s.history.undo.last().map(|e| e.0.clone());
    ex(s, "graphics.template.set", json!({"clip": clip, "values": {field: v}}))?;
    // typing and dragging: one step per field until something else happens
    let label = format!("Edit {}", f.name);
    if merge && before > 0 && top.as_deref() == Some(label.as_str()) && s.history.undo.len() == before + 1 {
        // each step keeps the state before it: dropping the newest leaves one step for the drag
        s.history.undo.pop();
    } else if s.history.undo.len() > before
        && let Some(e) = s.history.undo.last_mut()
    {
        e.0 = label;
    }
    edits::settle(s);
    Ok(())
}
