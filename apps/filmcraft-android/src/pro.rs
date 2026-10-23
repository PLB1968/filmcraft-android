//! Pro mode: the timeline as the desktop app (and Premiere) shows it, every track and every clip
//! as it is in the engine, and the track header controls.
//!
//! Unlike the phone model ([`crate::model`]) nothing is hidden or laid out for a phone: V1, V2…
//! and A1, A2… are the engine's own tracks, linked audio is a clip of its own on its track, and
//! V1 may have gaps. Both modes edit the same sequence, so a project opens in either.
//!
//! Every function is a thin wrapper over one engine command (or one undoable sequence edit), so
//! edits made here undo like any other. Track targeting, snapping and linked selection are
//! editor state rather than edits, as on the desktop: they change at once and are not undo steps.

use filmcraft_engine::Session;
use filmcraft_project::{ClipId, ItemKind, Label, MarkerId, MediaRef, Sequence, Track, TrackId, TrackItem, TrackKind};
use filmcraft_time::Tick;
use serde_json::json;

use crate::MobileError;
use crate::edits::{ex, settle};
use crate::model::{self, ClipKind};

/// One track and its header.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProTrack {
    /// The engine track.
    pub id: u64,
    /// "V1", "V2"… or "A1", "A2"…: its place, as the header shows it.
    pub code: String,
    /// The name the user gave it ("Video 1" by default).
    pub name: String,
    pub video: bool,
    /// 0-based among the tracks of its kind (V1 and A1 are 0).
    pub index: u32,
    pub locked: bool,
    pub sync_lock: bool,
    /// Video: Toggle Track Output (the eye). Audio tracks keep it on.
    pub output: bool,
    pub muted: bool,
    pub solo: bool,
    /// Targeted for navigation, paste and Match Frame (the track name's highlight).
    pub targeted: bool,
    /// Source patching: the Source monitor's video (or audio) goes to this track.
    pub source_patched: bool,
}

/// A clip on a track.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProClip {
    pub id: u64,
    /// The engine track it sits on.
    pub track: u64,
    /// The project item it plays.
    pub item: u64,
    pub kind: ClipKind,
    pub name: String,
    pub start: i64,
    pub duration: i64,
    pub source_in: i64,
    /// The media's own length (0 for stills, text and blanks, which have none): how far a slip or
    /// a trim may go.
    pub media_duration: i64,
    pub speed: f64,
    /// Clip > Enable.
    pub enabled: bool,
    /// The link group it shares with its partners (a video clip and its audio), if any.
    pub link: Option<u64>,
    pub selected: bool,
}

/// A sequence marker.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProMarker {
    pub id: u64,
    pub start: i64,
    /// 0 for a point marker.
    pub duration: i64,
    pub name: String,
    pub comment: String,
    /// The label colour's name ("Green", "Red"…: [`marker_colors`]).
    pub color: String,
}

/// The whole Pro timeline.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProTimeline {
    /// Changes with every edit, undo and redo.
    pub revision: u64,
    pub rate_num: i64,
    pub rate_den: i64,
    pub duration: i64,
    pub playhead: i64,
    /// V1 first (drawn at the bottom of the video half, as on the desktop).
    pub video_tracks: Vec<ProTrack>,
    /// A1 first (drawn at the top of the audio half).
    pub audio_tracks: Vec<ProTrack>,
    pub clips: Vec<ProClip>,
    pub mark_in: Option<i64>,
    pub mark_out: Option<i64>,
    pub markers: Vec<ProMarker>,
    pub snapping: bool,
    pub linked_selection: bool,
}

/// The marker colours, in the desktop's order.
pub fn marker_colors() -> Vec<String> {
    Label::ALL.iter().map(|l| l.name().to_string()).collect()
}

fn kind_of(s: &Session, track: TrackKind, c: &TrackItem) -> ClipKind {
    let mut kind = if track == TrackKind::Audio { ClipKind::Audio } else { ClipKind::Video };
    match s.project.item(c.item).map(|i| &i.kind) {
        Some(ItemKind::Media(m)) => {
            if m.offline || matches!(&m.media, MediaRef::File { path } if !std::path::Path::new(path).exists()) {
                return ClipKind::Missing;
            }
            if matches!(m.media, MediaRef::Generator(filmcraft_media::Generator::BlackVideo)) {
                kind = ClipKind::Blank;
            } else if track == TrackKind::Video && m.info.kind == filmcraft_media::MediaKind::Still {
                kind = ClipKind::Image;
            }
        }
        Some(ItemKind::Graphic { .. }) if track == TrackKind::Video => kind = ClipKind::Text,
        _ => {}
    }
    kind
}

fn track_info(t: &Track, index: usize, targeting: &filmcraft_engine::Targeting) -> ProTrack {
    let video = t.kind == TrackKind::Video;
    let dest = if video { targeting.video_dest } else { targeting.audio_dest };
    ProTrack {
        id: t.id.0,
        code: format!("{}{}", if video { "V" } else { "A" }, index + 1),
        name: t.name.clone(),
        video,
        index: u32::try_from(index).unwrap_or(u32::MAX),
        locked: t.locked,
        sync_lock: t.sync_lock,
        output: t.enabled,
        muted: t.muted,
        solo: t.solo,
        targeted: targeting.targeted.contains(&t.id),
        source_patched: dest == Some(t.id),
    }
}

fn media_duration(s: &Session, c: &TrackItem) -> i64 {
    match s.project.item(c.item).map(|i| &i.kind) {
        Some(ItemKind::Media(m)) if m.info.kind != filmcraft_media::MediaKind::Still => m.info.duration.0,
        _ => 0,
    }
}

fn clips_of(s: &Session, seq: &Sequence) -> Vec<ProClip> {
    let mut out = Vec::new();
    for (kind, tracks) in [(TrackKind::Video, &seq.video_tracks), (TrackKind::Audio, &seq.audio_tracks)] {
        for t in tracks {
            for c in &t.items {
                let name = if c.name.is_empty() { s.project.item(c.item).map(|i| i.name.clone()).unwrap_or_default() } else { c.name.clone() };
                out.push(ProClip {
                    id: c.id.0,
                    track: t.id.0,
                    item: c.item.0,
                    kind: kind_of(s, kind, c),
                    name,
                    start: c.start.0,
                    duration: c.duration.0,
                    source_in: c.source_in.0,
                    media_duration: media_duration(s, c),
                    speed: c.speed.abs(),
                    enabled: c.enabled,
                    link: c.link,
                    selected: s.state.selection.contains(&c.id),
                });
            }
        }
    }
    out
}

/// The open sequence as Pro mode shows it.
pub(crate) fn timeline(s: &Session) -> Result<ProTimeline, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let rate = s.sequence_rate();
    let tg = s.targeting();
    Ok(ProTimeline {
        revision: s.revision,
        rate_num: rate.num,
        rate_den: rate.den,
        duration: seq.duration().0,
        playhead: s.playhead().0,
        video_tracks: seq.video_tracks.iter().enumerate().map(|(i, t)| track_info(t, i, &tg)).collect(),
        audio_tracks: seq.audio_tracks.iter().enumerate().map(|(i, t)| track_info(t, i, &tg)).collect(),
        clips: clips_of(s, seq),
        mark_in: seq.mark_in.map(|t| t.0),
        mark_out: seq.mark_out.map(|t| t.0),
        markers: seq
            .markers
            .iter()
            .map(|m| ProMarker {
                id: m.id.0,
                start: m.start.0,
                duration: m.duration.0,
                name: m.name.clone(),
                comment: m.comment.clone(),
                color: m.color.name().to_string(),
            })
            .collect(),
        snapping: s.state.snapping,
        linked_selection: s.state.linked_selection,
    })
}

// ---- tracks ----

/// Add `video` video tracks above the top one and `audio` audio tracks below the bottom one.
pub(crate) fn add_tracks(s: &mut Session, video: u32, audio: u32) -> Result<(), MobileError> {
    if video == 0 && audio == 0 {
        return Ok(());
    }
    ex(s, "sequence.addTracks", json!({"video": video, "audio": audio, "videoAfter": "last", "audioAfter": "last"}))?;
    Ok(())
}

/// Delete a track and the clips on it. The last video or audio track stays.
pub(crate) fn delete_track(s: &mut Session, track: u64) -> Result<(), MobileError> {
    let (_, seq) = model::sequence(s)?;
    let t = seq.track(TrackId(track)).ok_or_else(|| MobileError::Command("That track is gone.".into()))?;
    let left = if t.kind == TrackKind::Video { seq.video_tracks.len() } else { seq.audio_tracks.len() };
    if left <= 1 {
        let what = if t.kind == TrackKind::Video { "video" } else { "audio" };
        return Err(MobileError::Command(format!("A sequence keeps at least one {what} track.")));
    }
    ex(s, "sequence.deleteTrack", json!({"track": track}))?;
    Ok(())
}

/// The header switches of a track (each `None` leaves that one as it is); one undo step.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct TrackSwitches {
    #[uniffi(default)]
    pub locked: Option<bool>,
    #[uniffi(default)]
    pub sync_lock: Option<bool>,
    #[uniffi(default)]
    pub output: Option<bool>,
    #[uniffi(default)]
    pub muted: Option<bool>,
    #[uniffi(default)]
    pub solo: Option<bool>,
    #[uniffi(default)]
    pub name: Option<String>,
}

pub(crate) fn set_track(s: &mut Session, track: u64, sw: &TrackSwitches) -> Result<(), MobileError> {
    let mut p = json!({"track": track});
    if let Some(v) = sw.locked {
        p["locked"] = json!(v);
    }
    if let Some(v) = sw.sync_lock {
        p["syncLock"] = json!(v);
    }
    if let Some(v) = sw.output {
        p["enabled"] = json!(v);
    }
    if let Some(v) = sw.muted {
        p["muted"] = json!(v);
    }
    if let Some(v) = sw.solo {
        p["solo"] = json!(v);
    }
    if let Some(v) = &sw.name {
        let v = v.trim();
        if v.is_empty() {
            return Err(MobileError::Command("A track needs a name.".into()));
        }
        p["name"] = json!(v);
    }
    ex(s, "timeline.setTrack", p)?;
    Ok(())
}

/// Target a track (or not) for navigation, paste and Match Frame.
pub(crate) fn set_targeted(s: &mut Session, track: u64, on: bool) -> Result<(), MobileError> {
    ex(s, "timeline.setTargeting", json!({"track": track, "targeted": on}))?;
    Ok(())
}

/// Patch the Source monitor's video (a video track) or audio (an audio track) to `track`, or
/// unpatch it.
pub(crate) fn set_source_patch(s: &mut Session, track: u64, on: bool) -> Result<(), MobileError> {
    ex(s, "timeline.setTargeting", json!({"track": track, "sourcePatch": on}))?;
    Ok(())
}

// ---- timeline switches ----

pub(crate) fn set_snapping(s: &mut Session, on: bool) -> Result<bool, MobileError> {
    Ok(ex(s, "sequence.snap", json!({"on": on}))?["snapping"].as_bool().unwrap_or(on))
}

pub(crate) fn set_linked_selection(s: &mut Session, on: bool) -> Result<bool, MobileError> {
    Ok(ex(s, "sequence.linkedSelection", json!({"on": on}))?["linkedSelection"].as_bool().unwrap_or(on))
}

// ---- in, out and markers ----

/// Mark the sequence In (or Out) at `time`, or clear it with `None`.
pub(crate) fn set_mark(s: &mut Session, is_in: bool, time: Option<i64>) -> Result<(), MobileError> {
    match (is_in, time) {
        (true, Some(t)) => ex(s, "markers.markIn", json!({"time": t.max(0)}))?,
        (false, Some(t)) => ex(s, "markers.markOut", json!({"time": t.max(0)}))?,
        (true, None) => ex(s, "markers.clearIn", json!({}))?,
        (false, None) => ex(s, "markers.clearOut", json!({}))?,
    };
    Ok(())
}

pub(crate) fn clear_in_out(s: &mut Session) -> Result<(), MobileError> {
    ex(s, "markers.clearInOut", json!({}))?;
    Ok(())
}

/// Add a marker at `time`; returns it.
pub(crate) fn add_marker(s: &mut Session, time: i64, name: &str) -> Result<u64, MobileError> {
    let r = ex(s, "markers.add", json!({"time": time.max(0), "name": name}))?;
    r["marker"].as_u64().ok_or_else(|| MobileError::Command("The marker wasn't added.".into()))
}

/// Change a marker's name, comment and colour (a name from [`marker_colors`]).
pub(crate) fn edit_marker(s: &mut Session, marker: u64, name: &str, comment: &str, color: &str) -> Result<(), MobileError> {
    let mut p = json!({"marker": marker, "name": name, "comment": comment});
    if !color.is_empty() {
        p["color"] = json!(color);
    }
    ex(s, "markers.edit", p)?;
    Ok(())
}

/// Delete one marker (one undo step).
pub(crate) fn delete_marker(s: &mut Session, marker: u64) -> Result<(), MobileError> {
    let id = MarkerId(marker);
    let (_, seq) = model::sequence(s)?;
    if !seq.markers.iter().any(|m| m.id == id) {
        return Err(MobileError::Command("That marker is gone.".into()));
    }
    s.edit_sequence("Clear Marker", |q, _, _| {
        q.markers.retain(|m| m.id != id);
        Ok(())
    })?;
    settle(s);
    Ok(())
}

/// Move the playhead to the next (or previous) marker; returns where it is.
pub(crate) fn go_to_marker(s: &mut Session, next: bool) -> Result<i64, MobileError> {
    ex(s, if next { "markers.goNext" } else { "markers.goPrev" }, json!({}))?;
    Ok(s.playhead().0)
}

// ---- clips ----

/// Select clips (their linked partners too while Linked Selection is on); `add` keeps the
/// selection and toggles these in or out of it.
pub(crate) fn select_clips(s: &mut Session, clips: &[u64], add: bool) -> Result<(), MobileError> {
    ex(s, "timeline.select", json!({"clips": clips, "toggle": add}))?;
    Ok(())
}

/// Move a clip to `start` on `track` (overwriting what is there, like a drag with the Selection
/// tool); linked partners follow while Linked Selection is on.
pub(crate) fn move_clip(s: &mut Session, clip: u64, track: u64, start: i64) -> Result<(), MobileError> {
    let (_, seq) = model::sequence(s)?;
    let f = model::find(seq, ClipId(clip)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    let to = seq.track(TrackId(track)).ok_or_else(|| MobileError::Command("That track is gone.".into()))?;
    if to.kind != f.kind {
        let what = if f.kind == TrackKind::Video { "video" } else { "audio" };
        return Err(MobileError::Command(format!("A {what} clip goes on a {what} track.")));
    }
    if to.locked {
        return Err(MobileError::Command(format!("{} is locked.", to.name)));
    }
    ex(s, "timeline.move", json!({"moves": [{"clip": clip, "track": track, "time": Tick(start.max(0)).0}], "insert": false}))?;
    Ok(())
}

/// Delete the selected clips (Clear), or delete them and close the gap they leave (Ripple
/// Delete).
pub(crate) fn delete_selection(s: &mut Session, ripple: bool) -> Result<(), MobileError> {
    if s.state.selection.is_empty() {
        return Err(MobileError::Command("Select a clip first.".into()));
    }
    ex(s, if ripple { "edit.rippleDelete" } else { "edit.clear" }, json!({}))?;
    Ok(())
}

// ---- tools (phase 2): each is one engine trim command, so one undo step ----

/// The Selection tool on an edge (`ripple`: the Ripple Edit tool): trim clip `clip`'s head or
/// tail by `delta` ticks. Returns the change made (the engine stops at the media's ends and the
/// neighbours).
pub(crate) fn trim_edge(s: &mut Session, clip: u64, head: bool, delta: i64, ripple: bool) -> Result<i64, MobileError> {
    let r = ex(
        s,
        "timeline.trim",
        json!({"clip": clip, "edge": if head { "in" } else { "out" }, "mode": if ripple { "ripple" } else { "regular" }, "delta": delta}),
    )?;
    Ok(r["delta"].as_i64().unwrap_or(0))
}

/// The Rolling Edit tool: move the cut between `left` and `right` by `delta`. Returns the change made.
pub(crate) fn roll(s: &mut Session, left: u64, right: u64, delta: i64) -> Result<i64, MobileError> {
    Ok(ex(s, "timeline.roll", json!({"left": left, "right": right, "delta": delta}))?["delta"].as_i64().unwrap_or(0))
}

/// The Slip tool: move a clip's footage under it by `delta` (its place and length stay).
pub(crate) fn slip(s: &mut Session, clip: u64, delta: i64) -> Result<(), MobileError> {
    ex(s, "timeline.slip", json!({"clip": clip, "delta": delta}))?;
    Ok(())
}

/// The Slide tool: move a clip by `delta` between its neighbours, which trim to make room.
pub(crate) fn slide(s: &mut Session, clip: u64, delta: i64) -> Result<(), MobileError> {
    ex(s, "timeline.slide", json!({"clip": clip, "delta": delta}))?;
    Ok(())
}

/// The Rate Stretch tool: drag a clip's head or tail by `delta`; its speed changes to fit the
/// same footage. Returns the new speed (1.0 = normal).
pub(crate) fn rate_stretch(s: &mut Session, clip: u64, head: bool, delta: i64) -> Result<f64, MobileError> {
    let r = ex(s, "timeline.rateStretch", json!({"clip": clip, "edge": if head { "in" } else { "out" }, "delta": delta}))?;
    Ok(r["speed"].as_f64().unwrap_or(1.0))
}

/// The Razor tool: cut clip `clip` (and its linked partners) at `time`, or, with no clip, every
/// clip on `track` there (every track when that is empty too). Returns how many cuts were made.
pub(crate) fn razor(s: &mut Session, time: i64, clip: Option<u64>, track: Option<u64>) -> Result<u32, MobileError> {
    let mut p = json!({"time": time.max(0)});
    if let Some(c) = clip {
        p["clip"] = json!(c);
    } else if let Some(t) = track {
        p["track"] = json!(t);
    }
    let r = ex(s, "timeline.razor", p)?;
    Ok(u32::try_from(r["cuts"].as_u64().unwrap_or(0)).unwrap_or(u32::MAX))
}

/// The Track Select Forward tool: select every clip on `track` (every track when empty) that
/// ends after `time`, as the desktop selects from the click on. Returns how many were selected.
pub(crate) fn select_forward(s: &mut Session, track: Option<u64>, time: i64) -> Result<u32, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let t = Tick(time.max(0));
    let clips: Vec<u64> = seq
        .video_tracks
        .iter()
        .chain(&seq.audio_tracks)
        .filter(|tr| track.is_none_or(|id| tr.id.0 == id))
        .flat_map(|tr| tr.items.iter().filter(|c| c.end() > t).map(|c| c.id.0))
        .collect();
    ex(s, "timeline.select", json!({"clips": clips}))?;
    Ok(u32::try_from(clips.len()).unwrap_or(u32::MAX))
}

/// Move clips together by `delta` on their own tracks (the Track Select Forward tool's drag);
/// linked partners follow while Linked Selection is on. Nothing moves before the start.
pub(crate) fn shift_clips(s: &mut Session, clips: &[u64], delta: i64) -> Result<(), MobileError> {
    let (_, seq) = model::sequence(s)?;
    let mut moves = Vec::new();
    for &c in clips {
        let f = model::find(seq, ClipId(c)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
        let tracks = if f.kind == TrackKind::Video { &seq.video_tracks } else { &seq.audio_tracks };
        let track = tracks.get(f.track).map(|t| t.id.0).ok_or_else(|| MobileError::Command("That track is gone.".into()))?;
        moves.push(json!({"clip": c, "track": track, "time": (f.clip.start.0 + delta).max(0)}));
    }
    if moves.is_empty() || delta == 0 {
        return Ok(());
    }
    ex(s, "timeline.move", json!({"moves": moves, "insert": false}))?;
    Ok(())
}
