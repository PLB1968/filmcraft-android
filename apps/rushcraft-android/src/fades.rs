//! Fades as keyframes: a clip's picture fades in from transparent (its Opacity) or its sound from
//! silence (its Volume) over the start of the clip, and out over the end, through a middle
//! keyframe that bends the ramp: it can sit anywhere in the fade (`mid_at`, 0..1 of its length)
//! at any level (`mid_level`, 0..1 of the full level). Three keyframes each side: the clip's edge
//! (nothing), the middle one, and the full level where the fade ends.

use filmcraft_engine::Session;
use filmcraft_project::{ClipId, Param, ParamValue, TrackItem, TrackKind};
use filmcraft_time::Tick;

use crate::{MobileError, edits, model, pro};

/// One fade of a clip.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Fade {
    /// Its length (ticks).
    pub length: i64,
    /// Where its middle keyframe is, 0..1 of the length from the clip's edge.
    pub mid_at: f64,
    /// How far up its middle keyframe is, 0..1 of the full level.
    pub mid_level: f64,
}

/// Silence, for a sound's Volume (dB).
const SILENT_DB: f64 = -60.0;

/// The parameter a fade keys: a picture's Opacity (0..100), a sound's Volume (dB).
fn param_mut(c: &mut TrackItem, video: bool) -> Option<&mut Param> {
    if video { c.effect_mut("opacity").and_then(|e| e.param_mut("opacity")) } else { c.effect_mut("volume").and_then(|e| e.param_mut("level")) }
}

fn param(c: &TrackItem, video: bool) -> Option<&Param> {
    if video { c.effect("opacity").and_then(|e| e.param("opacity")) } else { c.effect("volume").and_then(|e| e.param("level")) }
}

fn floor(video: bool) -> f64 {
    if video { 0.0 } else { SILENT_DB }
}

/// 0..1 of the full level `full`, as a value of the parameter.
fn level_value(video: bool, full: f64, frac: f64) -> f64 {
    let f = frac.clamp(0.0, 1.0);
    if video { full * f } else if f <= 1e-3 { SILENT_DB } else { (full + 20.0 * f.log10()).max(SILENT_DB) }
}

fn level_frac(video: bool, full: f64, v: f64) -> f64 {
    if video {
        if full <= 0.0 { 0.0 } else { (v / full).clamp(0.0, 1.0) }
    } else if v <= SILENT_DB + 0.01 {
        0.0
    } else {
        10f64.powf((v - full) / 20.0).clamp(0.0, 1.0)
    }
}

/// The keyframes of a fade at the clip's head (else its tail), as (sequence time, value), from
/// the edge inwards: the edge, then its middle and end (or just its end).
fn fade_keys(c: &TrackItem, p: &Param, video: bool, head: bool) -> Vec<(i64, f64)> {
    let mut keys: Vec<(i64, f64)> = p.keyframes.iter().filter_map(|k| Some((pro::sequence_time_of(c, k.time), k.value.as_f64()?))).collect();
    keys.sort_by_key(|k| k.0);
    if !head {
        keys.reverse();
    }
    let edge = if head { c.start.0 } else { c.end().0 };
    // a frame's worth either side of the edge counts as at it
    let near = (c.duration.0 / 200).max(Tick::from_seconds_f64(0.05).0);
    let Some(first) = keys.first() else { return Vec::new() };
    if (first.0 - edge).abs() > near || first.1 > floor(video) + 0.01 {
        return Vec::new();
    }
    // the edge, then up to two keyframes rising from it
    let mut out = vec![keys[0]];
    for k in keys.iter().skip(1).take(2) {
        if k.1 + 1e-6 < out.last().map_or(f64::MIN, |l| l.1) {
            break;
        }
        out.push(*k);
    }
    out
}

/// A clip's fade at its head (else its tail), if it has one.
pub(crate) fn fade_of(c: &TrackItem, video: bool, head: bool) -> Option<Fade> {
    let p = param(c, video)?;
    let keys = fade_keys(c, p, video, head);
    let (edge, end) = (*keys.first()?, *keys.last()?);
    if keys.len() < 2 {
        return None;
    }
    let length = (end.0 - edge.0).abs();
    if length <= 0 {
        return None;
    }
    let (mid_at, mid_level) = if keys.len() == 3 {
        let m = keys[1];
        ((m.0 - edge.0).abs() as f64 / length as f64, level_frac(video, end.1, m.1))
    } else {
        (0.5, 0.5)
    };
    Some(Fade { length, mid_at: mid_at.clamp(0.0, 1.0), mid_level: mid_level.clamp(0.0, 1.0) })
}

/// Lay fade `f` on clip `c` at its head (else its tail): the old one's keyframes out, the new
/// ones in (none for a length of 0).
fn lay(c: &mut TrackItem, video: bool, head: bool, f: Fade) {
    let at = c.clone();
    let Some(p) = param_mut(c, video) else { return };
    let old = fade_keys(&at, p, video, head);
    // the full level: where the old fade reached, else the level the clip has there
    let full = old.last().filter(|_| old.len() >= 2).map(|k| k.1);
    for (t, _) in &old {
        let mt = at.source_time_at(Tick(*t).clamp(at.start, (at.end() - Tick(1)).max(at.start)));
        if let Some(k) = p.keyframes.iter().map(|k| k.time).min_by_key(|k| (k.0 - mt.0).abs()) {
            p.remove_keyframe_at(k);
        }
    }
    let len = f.length.clamp(0, at.duration.0);
    let inner = if head { at.start.0 + len } else { at.end().0 - len };
    let full = full.unwrap_or_else(|| {
        let v = if p.is_animated() { p.value_at(at.source_time_at(Tick(inner))) } else { p.value.clone() };
        v.as_f64().unwrap_or(if video { 100.0 } else { 0.0 })
    });
    if len <= 0 {
        return;
    }
    let edge = if head { at.start.0 } else { (at.end().0 - 1).max(at.start.0) };
    let dir = if head { 1 } else { -1 };
    let mid = edge + dir * ((len as f64 * f.mid_at.clamp(0.02, 0.98)).round() as i64);
    let mt = |t: i64| at.source_time_at(Tick(t).clamp(at.start, (at.end() - Tick(1)).max(at.start)));
    p.put_keyframe(mt(edge), ParamValue::Float(floor(video)));
    p.put_keyframe(mt(mid), ParamValue::Float(level_value(video, full, f.mid_level)));
    p.put_keyframe(mt(inner), ParamValue::Float(full));
}

/// Fade clip `clip` in (`head`) or out with `f`, its picture or its sound; with Linked Selection
/// on its linked partners fade the same. The old crossfade-style fade on that end goes. One undo
/// step.
pub(crate) fn set_fade(s: &mut Session, clip: u64, head: bool, f: Fade) -> Result<(), MobileError> {
    let (_, seq) = model::sequence(s)?;
    let found = model::find(seq, ClipId(clip)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    let link = found.clip.link.filter(|_| s.state.linked_selection);
    let rate = s.sequence_rate();
    let length = rate.snap(Tick(f.length.max(0))).0;
    let f = Fade { length, ..f };
    s.edit_sequence(if head { "Fade In" } else { "Fade Out" }, |q, _, _| {
        // the clip and its linked partners, with whether each is a picture
        let mut targets: Vec<(ClipId, bool)> = Vec::new();
        for (kind, tracks) in [(TrackKind::Video, &q.video_tracks), (TrackKind::Audio, &q.audio_tracks)] {
            for c in tracks.iter().flat_map(|t| &t.items) {
                if c.id.0 == clip || (link.is_some() && c.link == link) {
                    targets.push((c.id, kind == TrackKind::Video));
                }
            }
        }
        for (id, video) in targets {
            // the transition-style fade on that end, from before, goes
            for t in q.video_tracks.iter_mut().chain(q.audio_tracks.iter_mut()) {
                t.transitions.retain(|tr| !((head && tr.from.is_none() && tr.to == Some(id)) || (!head && tr.to.is_none() && tr.from == Some(id))));
            }
            if let Some((_, c)) = q.find_item_mut(id) {
                lay(c, video, head, f);
            }
        }
        Ok(())
    })?;
    edits::settle(s);
    Ok(())
}
