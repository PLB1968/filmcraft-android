//! Pro mode's More menu: the undo history, Auto-Match Loudness, Scene Edit Detection, beat
//! markers and the Colour panel's Lumetri helpers.

use std::sync::Arc;

use filmcraft_engine::Session;
use filmcraft_project::{ClipId, EffectInstance, ParamValue, TrackKind};
use serde_json::json;

use crate::MobileError;
use crate::edits::ex;
use crate::model;

/// The undo history, as Edit ▸ History shows it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct UndoHistory {
    /// Steps that can be undone, oldest first (the last is the latest edit).
    pub undo: Vec<String>,
    /// Steps that can be redone, the next first.
    pub redo: Vec<String>,
}

pub(crate) fn history(s: &Session) -> UndoHistory {
    UndoHistory { undo: s.history.undo.iter().map(|u| u.0.clone()).collect(), redo: s.history.redo.iter().rev().map(|u| u.0.clone()).collect() }
}

/// A named choice (a LUT).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Choice {
    pub id: String,
    pub name: String,
}

/// The selected audio clips (a video clip's linked sound for a video clip), else every sound.
fn sounds(s: &Session, clips: &[u64]) -> Result<Vec<u64>, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let mut out = Vec::new();
    for &c in clips {
        let Some(f) = model::find(seq, ClipId(c)) else { continue };
        match f.kind {
            TrackKind::Audio => out.push(c),
            TrackKind::Video => out.extend(model::partner(seq, f.clip, TrackKind::Audio).map(|a| a.clip.id.0)),
        }
    }
    if out.is_empty() {
        out = seq.audio_tracks.iter().flat_map(|t| &t.items).map(|i| i.id.0).collect();
    }
    out.sort_unstable();
    out.dedup();
    Ok(out)
}

/// Auto-Match Loudness: make `clips` (else every sound) equally loud, to the target of their
/// audio type (sounds without one become Dialogue). One undo step; returns how many were matched.
pub(crate) fn match_loudness(s: &mut Session, clips: &[u64]) -> Result<u32, MobileError> {
    let ids = sounds(s, clips)?;
    if ids.is_empty() {
        return Err(MobileError::Command("There's no sound to match.".into()));
    }
    let untyped: Vec<u64> = {
        let (_, seq) = model::sequence(s)?;
        ids.iter().copied().filter(|&c| seq.find_item(ClipId(c)).is_some_and(|(_, it)| it.essential.is_none())).collect()
    };
    let r = s.edit_group("Auto-Match Loudness", |s| {
        if !untyped.is_empty() {
            s.execute("essentialSound.setType", json!({ "clips": untyped, "type": "dialogue" }))?;
        }
        s.execute("essentialSound.autoMatch", json!({ "clips": ids }))
    })?;
    let n = r["clips"].as_array().map(|a| a.iter().filter(|c| c.get("gainDb").is_some()).count()).unwrap_or(0);
    Ok(u32::try_from(n).unwrap_or(u32::MAX))
}

/// Scene Edit Detection: cut `clips` (video, else every video clip) at each change of shot, in
/// the background. Returns the job to watch; the cuts go on when it ends (one undo step).
pub(crate) fn detect_scenes(s: &mut Session, clips: &[u64]) -> Result<u64, MobileError> {
    let videos: Vec<u64> = {
        let (_, seq) = model::sequence(s)?;
        let sel: Vec<u64> = clips.iter().copied().filter(|&c| model::find(seq, ClipId(c)).is_some_and(|f| f.kind == TrackKind::Video)).collect();
        if sel.is_empty() { seq.video_tracks.iter().flat_map(|t| &t.items).map(|i| i.id.0).collect() } else { sel }
    };
    if videos.is_empty() {
        return Err(MobileError::Command("There's no video to look at.".into()));
    }
    // the command looks at the selection
    ex(s, "timeline.select", json!({ "clips": videos }))?;
    let r = ex(s, "clip.sceneEditDetection", json!({ "clips": videos, "applyCuts": true }))?;
    r["job"].as_u64().ok_or_else(|| MobileError::Command("Scene detection didn't start.".into()))
}

/// What beat markers need from the session, read under its lock.
pub(crate) struct BeatJob {
    project: Arc<filmcraft_project::Project>,
    provider: filmcraft_engine::media_pool::PoolProvider,
    item: filmcraft_project::TrackItem,
    sample_rate: u32,
}

/// Ready beat markers for audio clip `clip` (or a video clip's sound).
pub(crate) fn beat_job(s: &Session, clip: u64) -> Result<BeatJob, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let f = model::find(seq, ClipId(clip)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    let it = match f.kind {
        TrackKind::Audio => f.clip.clone(),
        TrackKind::Video => {
            model::partner(seq, f.clip, TrackKind::Audio).map(|a| a.clip.clone()).ok_or_else(|| MobileError::Command("That clip has no sound.".into()))?
        }
    };
    Ok(BeatJob {
        project: s.project.clone(),
        provider: s.media.provider(s.project.clone(), s.services.clone()),
        item: it,
        sample_rate: seq.settings.sample_rate.max(8000),
    })
}

/// Find the beats of a [`beat_job`]'s clip (slow: run without the session's lock). Returns
/// their sequence times.
pub(crate) fn find_beats(j: &BeatJob) -> Result<Vec<i64>, MobileError> {
    let _ = &j.project;
    let sr = j.sample_rate;
    let (a0, a1) = (j.item.start.to_units_floor(i64::from(sr)), j.item.end().to_units_floor(i64::from(sr)));
    let len = usize::try_from((a1 - a0).max(0)).unwrap_or(0);
    let mut l = Vec::with_capacity(len);
    let mut r = Vec::with_capacity(len);
    let chunk = sr as usize * 10;
    let mut pos = 0usize;
    while pos < len {
        let n = chunk.min(len - pos);
        match filmcraft_render::audio::clip_signal(&j.item, a0 + pos as i64, n, sr, &j.provider) {
            Some([a, b]) => {
                l.extend_from_slice(&a);
                r.extend_from_slice(&b);
            }
            None => {
                l.extend(std::iter::repeat_n(0.0, n));
                r.extend(std::iter::repeat_n(0.0, n));
            }
        }
        pos += n;
    }
    let a = filmcraft_audio_dsp::remix::analyze(&[&l, &r], sr).map_err(|_| MobileError::Command("No steady beat was found in that sound.".into()))?;
    Ok(a.beats.iter().map(|&b| filmcraft_time::Tick::from_units(a0 + b, i64::from(sr)).0).collect())
}

/// Put a marker on each beat (one undo step); returns how many.
pub(crate) fn add_beat_markers(s: &mut Session, beats: &[i64]) -> Result<u32, MobileError> {
    if beats.is_empty() {
        return Ok(0);
    }
    let n = s.edit_group("Beat Markers", |s| {
        for (k, &t) in beats.iter().enumerate() {
            s.execute("markers.add", json!({ "time": t, "name": format!("Beat {}", k + 1) }))?;
        }
        Ok(beats.len())
    })?;
    Ok(u32::try_from(n).unwrap_or(u32::MAX))
}

/// Give video clip `clip` a Lumetri Color (when it has none) for the Colour panel.
pub(crate) fn ensure_lumetri(s: &mut Session, clip: u64) -> Result<(), MobileError> {
    let has = {
        let (_, seq) = model::sequence(s)?;
        let f = model::find(seq, ClipId(clip)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
        if f.kind != TrackKind::Video {
            return Err(MobileError::Command("Colour is for pictures: pick a video clip.".into()));
        }
        f.clip.effect("lumetri").is_some()
    };
    if !has {
        ex(s, "effects.apply", json!({ "clips": [clip], "effect": "lumetri" }))?;
        if let Some(e) = s.history.undo.last_mut() {
            e.0 = ADD_LUMETRI.into();
        }
    }
    Ok(())
}

/// The undo step of the Lumetri Color the Colour panel adds.
const ADD_LUMETRI: &str = "Add Lumetri Color";

/// A Lumetri Color that changes nothing: every setting as it came, nothing animated or masked.
/// Even so it costs a full colour pass on every frame and the fast playback path, so it goes.
fn idle_lumetri(e: &EffectInstance) -> bool {
    e.effect == "lumetri"
        && e.masks.is_empty()
        && !e.is_animated()
        && e.def().is_some_and(|d| {
            d.params.iter().all(|p| {
                e.params.get(p.id).is_none_or(|v| match (&v.value, &p.default) {
                    (ParamValue::Vec2(_), ParamValue::Vec2(dd)) if dd.x.is_nan() => true,
                    (a, b) => a == b,
                })
            })
        })
}

/// The Colour panel closed on clip `clip`: a Lumetri Color it added and nothing changed comes off
/// again (its undo step too, when it is the last one). Returns whether it did.
pub(crate) fn tidy_lumetri(s: &mut Session, clip: u64) -> Result<bool, MobileError> {
    let idle = {
        let (_, seq) = model::sequence(s)?;
        model::find(seq, ClipId(clip)).is_some_and(|f| f.clip.effects.iter().any(idle_lumetri))
    };
    if !idle {
        return Ok(false);
    }
    if s.history.redo.is_empty() && s.history.undo.last().is_some_and(|e| e.0 == ADD_LUMETRI) {
        s.undo();
    } else {
        s.edit_sequence("Remove Lumetri Color", |q, _, _| {
            if let Some((_, c)) = q.find_item_mut(ClipId(clip)) {
                c.effects.retain(|e| !idle_lumetri(e));
            }
            Ok(())
        })?;
    }
    crate::edits::settle(s);
    Ok(true)
}

/// Take every Lumetri Color that changes nothing off the project's clips, quietly (no undo step):
/// projects from 0.0.12, where opening the Colour panel left one behind, play smoothly again.
pub(crate) fn drop_idle_lumetri(s: &mut Session) -> usize {
    let any = s.project.items.values().any(|i| {
        i.as_sequence().is_some_and(|q| q.video_tracks.iter().flat_map(|t| &t.items).any(|c| c.effects.iter().any(idle_lumetri)))
    });
    if !any {
        return 0;
    }
    let mut p = (*s.project).clone();
    let mut n = 0;
    for it in p.items.values_mut() {
        let Some(q) = it.as_sequence_mut() else { continue };
        for c in q.video_tracks.iter_mut().flat_map(|t| t.items.iter_mut()) {
            let before = c.effects.len();
            c.effects.retain(|e| !idle_lumetri(e));
            n += before - c.effects.len();
        }
    }
    s.project = Arc::new(p);
    s.bump_view();
    crate::edits::settle(s);
    n
}

/// The LUTs the Colour panel offers: camera log to Rec. 709 (Input LUT).
pub(crate) fn input_luts() -> Vec<Choice> {
    filmcraft_render::luts::builtins()
        .iter()
        .filter(|b| b.id.ends_with("-to-rec709"))
        .map(|b| Choice { id: format!("builtin:{}", b.id), name: b.label.clone() })
        .collect()
}

/// Set video clip `clip`'s Input LUT (`lut` from [`input_luts`]; empty takes it off).
pub(crate) fn set_input_lut(s: &mut Session, clip: u64, lut: &str) -> Result<(), MobileError> {
    ensure_lumetri(s, clip)?;
    ex(s, "lumetri.setInputLut", json!({ "clip": clip, "lut": lut }))?;
    Ok(())
}
