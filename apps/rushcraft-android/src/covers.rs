//! Clips a move covered, remembered so that moving the covering clip away again brings them back
//! as they were (their In and Out, effects and all), into whatever room is free there. Kept with
//! the project, beside its file.

use filmcraft_engine::Session;
use filmcraft_project::{ClipId, Sequence, TrackId, TrackItem};
use filmcraft_time::Tick;
use serde_json::{Value, json};

use crate::MobileError;

/// Clip `original` on track `track`, as it was before clip `coverer` was moved over it.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct Cover {
    pub coverer: u64,
    pub track: u64,
    pub original: TrackItem,
}

fn end(c: &TrackItem) -> i64 {
    c.start.0 + c.duration.0
}

/// Every clip of the open sequence, with its track.
fn snapshot(s: &Session) -> Vec<(TrackId, TrackItem)> {
    s.active_sequence()
        .map(|q| q.video_tracks.iter().chain(&q.audio_tracks).flat_map(|t| t.items.iter().map(move |c| (t.id, c.clone()))).collect())
        .unwrap_or_default()
}

/// The clips a move moved: `clip`, and any that went along (its linked sound…), told apart from
/// clips the move trimmed by keeping their length and footage.
fn moved(before: &[(TrackId, TrackItem)], after: &[(TrackId, TrackItem)], clip: u64) -> Vec<u64> {
    let mut out = vec![clip];
    for (t, c) in before {
        if let Some((nt, x)) = after.iter().find(|(_, x)| x.id == c.id)
            && x.duration == c.duration
            && x.source_in == c.source_in
            && (x.start != c.start || nt != t)
            && !out.contains(&c.id.0)
        {
            out.push(c.id.0);
        }
    }
    out
}

/// What this move covered: each clip, other than the moved ones, that is now shorter, split or
/// gone where a moved clip landed on its track.
fn covered(before: &[(TrackId, TrackItem)], after: &[(TrackId, TrackItem)], moved: &[u64]) -> Vec<Cover> {
    let mut out = Vec::new();
    for (t, c) in before {
        if moved.contains(&c.id.0) || c.reverse {
            continue;
        }
        let same = after.iter().any(|(nt, x)| x.id == c.id && nt == t && x.start == c.start && x.duration == c.duration && x.source_in == c.source_in);
        if same {
            continue;
        }
        let by = after.iter().find(|(nt, x)| nt == t && moved.contains(&x.id.0) && x.start.0 < end(c) && end(x) > c.start.0);
        if let Some((_, x)) = by {
            out.push(Cover { coverer: x.id.0, track: t.0, original: c.clone() });
        }
    }
    out
}

/// Bring back what clips `moved` covered before, into the room free where each one was. A clip
/// only partly free stays remembered for the rest; one whose room is broken up by other clips is
/// left as it is.
fn heal(q: &mut Sequence, covers: &mut Vec<Cover>, moved: &[u64]) {
    covers.retain(|cv| {
        if !moved.contains(&cv.coverer) {
            return true;
        }
        let o = &cv.original;
        let Some(track) = q.video_tracks.iter_mut().chain(q.audio_tracks.iter_mut()).find(|t| t.id.0 == cv.track) else {
            return false;
        };
        if track.locked {
            return false;
        }
        let (a0, b0) = (o.start.0, end(o));
        let overlaps = |c: &TrackItem| c.start.0 < b0 && end(c) > a0;
        // what's left of it (the same footage, not a moved clip), and what else is in the way
        let pieces: Vec<ClipId> = track.items.iter().filter(|c| overlaps(c) && c.item == o.item && !moved.contains(&c.id.0)).map(|c| c.id).collect();
        let mut taken: Vec<(i64, i64)> =
            track.items.iter().filter(|c| overlaps(c) && !pieces.contains(&c.id)).map(|c| (c.start.0.max(a0), end(c).min(b0))).collect();
        taken.sort_unstable();
        // the free room in its old place
        let mut free = Vec::new();
        let mut at = a0;
        for (s, e) in taken {
            if s > at {
                free.push((at, s));
            }
            at = at.max(e);
        }
        if at < b0 {
            free.push((at, b0));
        }
        let [(a, b)] = free[..] else {
            // no room yet (keep it for later), or room in bits: leave it be
            return free.is_empty();
        };
        let mut back = o.clone();
        back.start = Tick(a);
        back.duration = Tick(b - a);
        back.source_in = Tick(o.source_in.0 + ((a - a0) as f64 * o.speed).round() as i64);
        track.items.retain(|c| !pieces.contains(&c.id));
        // crossfades on the pieces it absorbs go with them
        track.transitions.retain(|tr| ![tr.from, tr.to].iter().flatten().any(|id| *id != o.id && pieces.contains(id)));
        track.items.push(back);
        track.items.sort_by_key(|c| c.start);
        // all of it back: forgotten; part of it: the rest when there's room
        !(a == a0 && b == b0)
    });
}

/// Run move `f` (one undo step `label`, with any healing) for clip `clip`: what clips it moved
/// covered on earlier moves comes back where there's room, and what it covers now is remembered.
pub(crate) fn move_healing(
    s: &mut Session,
    covers: &mut Vec<Cover>,
    clip: u64,
    label: &str,
    f: impl FnOnce(&mut Session) -> filmcraft_engine::Result<()>,
) -> Result<(), MobileError> {
    let before = snapshot(s);
    let mut fresh = Vec::new();
    s.edit_group(label, |s| {
        f(s)?;
        let after = snapshot(s);
        let moved = moved(&before, &after, clip);
        fresh = covered(&before, &after, &moved);
        if covers.iter().any(|c| moved.contains(&c.coverer)) {
            s.edit_sequence("Restore Covered Clips", |q, _, _| {
                heal(q, covers, &moved);
                Ok(())
            })?;
        }
        Ok(())
    })?;
    covers.extend(fresh);
    Ok(())
}

/// The remembered covers as saved beside the project.
pub(crate) fn to_json(covers: &[Cover]) -> Value {
    json!(covers.iter().map(|c| json!({"coverer": c.coverer, "track": c.track, "original": c.original})).collect::<Vec<_>>())
}

pub(crate) fn from_json(v: &Value) -> Vec<Cover> {
    v.as_array()
        .map(|a| {
            a.iter()
                .filter_map(|c| {
                    Some(Cover {
                        coverer: c["coverer"].as_u64()?,
                        track: c["track"].as_u64()?,
                        original: serde_json::from_value(c["original"].clone()).ok()?,
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Where a project's covers are kept: beside its file.
pub(crate) fn path_for(project: &str) -> String {
    format!("{project}.covers.json")
}
