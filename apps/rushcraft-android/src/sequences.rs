//! A project's sequences: the timelines it holds (a wide edit and an upright short, say), opened
//! from tabs over the timeline or the Project panel, made, renamed, duplicated and deleted; and
//! nests: clips turned into a sequence of their own, or a sequence dropped into another as one
//! clip.

use filmcraft_engine::Session;
use filmcraft_project::{ItemId, ItemKind};
use serde_json::json;

use crate::MobileError;
use crate::edits::ex;

/// One of the project's sequences.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SequenceEntry {
    pub id: u64,
    pub name: String,
    pub width: u32,
    pub height: u32,
    pub duration: i64,
    /// The one the timeline shows.
    pub active: bool,
    /// Used as a clip (a nest) in another sequence.
    pub nested: bool,
}

/// Every sequence of the project, oldest first.
pub(crate) fn list(s: &Session) -> Vec<SequenceEntry> {
    let active = s.state.active_sequence;
    let used: Vec<ItemId> = s
        .project
        .items
        .values()
        .filter_map(|i| if let ItemKind::Sequence(q) = &i.kind { Some(q) } else { None })
        .flat_map(|q| q.video_tracks.iter().chain(&q.audio_tracks).flat_map(|t| t.items.iter().map(|c| c.item)))
        .collect();
    let mut out: Vec<(u64, SequenceEntry)> = s
        .project
        .items
        .values()
        .filter_map(|i| {
            let ItemKind::Sequence(q) = &i.kind else { return None };
            Some((
                i.created,
                SequenceEntry {
                    id: i.id.0,
                    name: i.name.clone(),
                    width: q.settings.width,
                    height: q.settings.height,
                    duration: q.duration().0,
                    active: active == Some(i.id),
                    nested: used.contains(&i.id),
                },
            ))
        })
        .collect();
    out.sort_by_key(|(created, e)| (*created, e.id));
    out.into_iter().map(|(_, e)| e).collect()
}

fn sequence_named(s: &Session, id: u64) -> Result<(), MobileError> {
    match s.project.item(ItemId(id)).map(|i| &i.kind) {
        Some(ItemKind::Sequence(_)) => Ok(()),
        _ => Err(MobileError::Command("That sequence is gone.".into())),
    }
}

/// Show sequence `id` in the timeline.
pub(crate) fn open(s: &mut Session, id: u64) -> Result<(), MobileError> {
    sequence_named(s, id)?;
    ex(s, "sequence.open", json!({"item": id}))?;
    Ok(())
}

/// A new, empty sequence (`width` × `height`, the open one's frame rate, one video and one
/// audio track), opened. Returns it.
pub(crate) fn create(s: &mut Session, name: &str, width: u32, height: u32) -> Result<u64, MobileError> {
    let rate = s.sequence_rate();
    let fps = rate.num as f64 / rate.den.max(1) as f64;
    let name = if name.trim().is_empty() { format!("Sequence {}", list(s).len() + 1) } else { name.trim().to_string() };
    ex(s, "file.newSequence", json!({"name": name, "width": width.clamp(16, 7680), "height": height.clamp(16, 7680), "fps": fps, "video": 1, "audio": 1}))?;
    s.state.active_sequence.map(|i| i.0).ok_or(MobileError::NoSequence)
}

pub(crate) fn rename(s: &mut Session, id: u64, name: &str) -> Result<(), MobileError> {
    sequence_named(s, id)?;
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err(MobileError::Command("A sequence needs a name.".into()));
    }
    s.edit("Rename Sequence", |p, _| {
        if let Some(i) = p.item_mut(ItemId(id)) {
            i.name = name;
        }
        Ok(())
    })?;
    Ok(())
}

/// A copy of sequence `id` ("… Copy"), opened. Returns it.
pub(crate) fn duplicate(s: &mut Session, id: u64) -> Result<u64, MobileError> {
    sequence_named(s, id)?;
    s.state.project_selection = vec![ItemId(id)];
    let r = ex(s, "edit.duplicate", json!({}))?;
    let copy = r["items"].as_array().and_then(|a| a.first()).and_then(serde_json::Value::as_u64).ok_or_else(|| MobileError::Command("The sequence wasn't copied.".into()))?;
    open(s, copy)?;
    Ok(copy)
}

/// Delete sequence `id` (a project keeps one); the timeline shows another if it was open.
pub(crate) fn delete(s: &mut Session, id: u64) -> Result<(), MobileError> {
    sequence_named(s, id)?;
    let all = list(s);
    if all.len() <= 1 {
        return Err(MobileError::Command("A project keeps at least one sequence.".into()));
    }
    if let Some(e) = all.iter().find(|e| e.id == id && e.nested) {
        return Err(MobileError::Command(format!("“{}” is used in another sequence: take it out there first.", e.name)));
    }
    if s.state.active_sequence == Some(ItemId(id)) {
        let other = all.iter().find(|e| e.id != id).map(|e| e.id).ok_or(MobileError::NoSequence)?;
        open(s, other)?;
    }
    s.state.open_sequences.retain(|x| *x != ItemId(id));
    ex(s, "project.delete", json!({"items": [id]}))?;
    Ok(())
}

/// The selected clips (and their linked partners) become one clip: a new sequence holding them,
/// in their place. Returns the new sequence.
pub(crate) fn nest(s: &mut Session, name: &str) -> Result<u64, MobileError> {
    if s.state.selection.is_empty() {
        return Err(MobileError::Command("Select the clips to nest first.".into()));
    }
    let before: Vec<u64> = list(s).iter().map(|e| e.id).collect();
    ex(s, "clip.nest", json!({"name": name.trim()}))?;
    list(s).into_iter().map(|e| e.id).find(|id| !before.contains(id)).ok_or_else(|| MobileError::Command("Nothing was nested.".into()))
}

/// The sequence a clip plays (a nest), if it is one.
pub(crate) fn nested_in(s: &Session, clip: u64) -> Option<u64> {
    let q = s.active_sequence()?;
    let c = q.video_tracks.iter().chain(&q.audio_tracks).flat_map(|t| &t.items).find(|c| c.id.0 == clip)?;
    matches!(s.project.item(c.item).map(|i| &i.kind), Some(ItemKind::Sequence(_))).then_some(c.item.0)
}
