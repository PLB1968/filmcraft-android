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
    /// Where its keyframes are on the sequence (shown as marks on the clip).
    pub keyframes: Vec<i64>,
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
    /// Transitions on every track (crossfades on cuts, fades at clip ends).
    pub transitions: Vec<ProTransition>,
}

/// A transition on a track: a crossfade on a cut (`from` and `to`), or a fade at one end.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProTransition {
    pub id: u64,
    pub track: u64,
    pub from: Option<u64>,
    pub to: Option<u64>,
    pub start: i64,
    pub duration: i64,
    pub name: String,
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
                    keyframes: keyframe_times(c),
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
        transitions: seq
            .video_tracks
            .iter()
            .chain(&seq.audio_tracks)
            .flat_map(|t| {
                t.transitions.iter().map(|tr| ProTransition {
                    id: tr.id.0,
                    track: t.id.0,
                    from: tr.from.map(|c| c.0),
                    to: tr.to.map(|c| c.0),
                    start: tr.start.0,
                    duration: tr.duration.0,
                    name: tr.effect.def().map(|d| d.name.to_string()).unwrap_or_default(),
                })
            })
            .collect(),
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

// ---- phase 3: the Project panel, the Source monitor, three-point editing ----

/// What a Project panel item is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum BinKind {
    Video,
    Audio,
    Image,
    /// Black video, colour mattes and the like.
    Generated,
    Missing,
}

/// One item in the Project panel (the media imported into the project).
#[derive(Debug, Clone, uniffi::Record)]
pub struct BinItem {
    pub id: u64,
    pub name: String,
    pub kind: BinKind,
    /// The media's length; 0 for stills.
    pub duration: i64,
    pub width: u32,
    pub height: u32,
    pub has_video: bool,
    pub has_audio: bool,
    /// The media file, empty for generated media.
    pub path: String,
    /// The item's own In and Out (set in the Source monitor).
    pub mark_in: Option<i64>,
    pub mark_out: Option<i64>,
    /// How many clips on the open sequence play it.
    pub uses: u32,
}

/// The Project panel: every media item, in the order it was imported.
pub(crate) fn bin(s: &Session) -> Vec<BinItem> {
    let used = |id: filmcraft_project::ItemId| -> u32 {
        let Some(seq) = s.active_sequence() else { return 0 };
        let n = seq.video_tracks.iter().chain(&seq.audio_tracks).flat_map(|t| &t.items).filter(|c| c.item == id).count();
        u32::try_from(n).unwrap_or(u32::MAX)
    };
    let mut items: Vec<_> = s
        .project
        .items
        .values()
        .filter_map(|i| {
            let ItemKind::Media(m) = &i.kind else { return None };
            let path = match &m.media {
                MediaRef::File { path } => path.clone(),
                MediaRef::Generator(_) => String::new(),
            };
            let still = m.info.kind == filmcraft_media::MediaKind::Still;
            let kind = if m.offline || (!path.is_empty() && !std::path::Path::new(&path).exists()) {
                BinKind::Missing
            } else if path.is_empty() {
                BinKind::Generated
            } else if still {
                BinKind::Image
            } else if m.info.video.is_none() {
                BinKind::Audio
            } else {
                BinKind::Video
            };
            let (width, height) = m.info.video.as_ref().map(|v| (v.width, v.height)).unwrap_or((0, 0));
            Some((
                i.created,
                BinItem {
                    id: i.id.0,
                    name: i.name.clone(),
                    kind,
                    duration: if still { 0 } else { m.info.duration.0 },
                    width,
                    height,
                    has_video: m.info.video.is_some(),
                    has_audio: m.info.has_audio(),
                    path,
                    mark_in: m.mark_in.map(|t| t.0),
                    mark_out: m.mark_out.map(|t| t.0),
                    uses: used(i.id),
                },
            ))
        })
        .collect();
    items.sort_by_key(|(created, b)| (*created, b.id));
    items.into_iter().map(|(_, b)| b).collect()
}

/// The clip in the Source monitor.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SourceInfo {
    pub item: u64,
    pub name: String,
    /// Where the clip can be scrubbed: `start` to `end` (ticks of its own time).
    pub start: i64,
    pub end: i64,
    pub rate_num: i64,
    pub rate_den: i64,
    pub playhead: i64,
    pub mark_in: Option<i64>,
    pub mark_out: Option<i64>,
    pub has_video: bool,
    pub has_audio: bool,
}

pub(crate) fn source(s: &Session) -> Option<SourceInfo> {
    let item = s.state.source_item?;
    let v = filmcraft_engine::clip_ops::source_view(s, item)?;
    let pi = s.project.item(item)?;
    let (has_video, has_audio) = match &pi.kind {
        ItemKind::Media(m) => (m.info.video.is_some(), m.info.has_audio()),
        _ => (true, true),
    };
    Some(SourceInfo {
        item: item.0,
        name: pi.name.clone(),
        start: v.start.0,
        end: v.end.0,
        rate_num: v.rate.num,
        rate_den: v.rate.den,
        playhead: s.state.source_playhead.0,
        mark_in: v.mark_in.map(|t| t.0),
        mark_out: v.mark_out.map(|t| t.0),
        has_video,
        has_audio,
    })
}

/// Open a Project panel item in the Source monitor.
pub(crate) fn open_source(s: &mut Session, item: u64) -> Result<SourceInfo, MobileError> {
    ex(s, "source.open", json!({"item": item}))?;
    source(s).ok_or_else(|| MobileError::Command("That clip can't be opened.".into()))
}

pub(crate) fn close_source(s: &mut Session) -> Result<(), MobileError> {
    if s.state.source_item.is_some() {
        ex(s, "source.close", json!({}))?;
    }
    Ok(())
}

/// Move the Source monitor's playhead (snapped to the clip's frames); returns where it is.
pub(crate) fn set_source_playhead(s: &mut Session, time: i64) -> Result<i64, MobileError> {
    let r = ex(s, "source.setPlayhead", json!({"time": time.max(0)}))?;
    Ok(r["time"].as_i64().unwrap_or(0))
}

/// Mark the source clip's In (or Out) at `time`, or clear it (`None`); one undo step.
pub(crate) fn set_source_mark(s: &mut Session, is_in: bool, time: Option<i64>) -> Result<(), MobileError> {
    let item = s.state.source_item.ok_or_else(|| MobileError::Command("Open a clip in the Source monitor first.".into()))?;
    ex(s, "project.setMarks", json!({"item": item.0, if is_in { "in" } else { "out" }: time.map(|t| t.max(0))}))?;
    Ok(())
}

/// Insert (`overwrite` false) or Overwrite the source clip's In to Out into the sequence, at the
/// sequence In (or the playhead), on the patched tracks: three-point editing. Returns the new clips.
pub(crate) fn source_edit(s: &mut Session, overwrite: bool) -> Result<Vec<u64>, MobileError> {
    if s.state.source_item.is_none() {
        return Err(MobileError::Command("Open a clip in the Source monitor first.".into()));
    }
    let tg = s.targeting();
    if tg.video_dest.is_none() && tg.audio_dest.is_none() {
        return Err(MobileError::Command("Patch a source track (V or A beside the track names) first.".into()));
    }
    let r = ex(s, if overwrite { "source.overwrite" } else { "source.insert" }, json!({}))?;
    Ok(r["clips"].as_array().map(|a| a.iter().filter_map(serde_json::Value::as_u64).collect()).unwrap_or_default())
}

/// Lift (leave a gap) or Extract (close it) the sequence In to Out on the targeted tracks.
pub(crate) fn lift_extract(s: &mut Session, extract: bool) -> Result<(), MobileError> {
    let (_, seq) = model::sequence(s)?;
    if seq.mark_in.is_none() && seq.mark_out.is_none() {
        return Err(MobileError::Command("Mark an In or Out point on the sequence first.".into()));
    }
    ex(s, if extract { "sequence.extract" } else { "sequence.lift" }, json!({}))?;
    Ok(())
}

// ---- phase 4: Effect Controls and keyframes ----

/// How a parameter is edited.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ParamType {
    /// A number with a slider: `value[0]` between `min` and `max`.
    Number,
    /// A point (x, y in frame pixels): `value[0]`, `value[1]`; NaN while it is "auto" (centred).
    Point,
    /// Degrees.
    Angle,
    /// A checkbox: 0 or 1.
    Toggle,
    /// One of `choices`: its index.
    Choice,
    /// Red, green, blue, alpha, 0 to 1.
    Colour,
    /// Text, curves and masks: shown but edited elsewhere.
    Other,
}

/// A keyframe of a parameter.
#[derive(Debug, Clone, uniffi::Record)]
pub struct KeyframeInfo {
    /// Where it sits in the clip's footage (what the engine keys on).
    pub media_time: i64,
    /// Where that is on the sequence.
    pub time: i64,
    /// "linear", "bezier", "autoBezier", "continuousBezier", "hold", "easeIn" or "easeOut".
    pub interpolation: String,
}

/// One parameter in Effect Controls, with its value at the playhead.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ParamInfo {
    pub id: String,
    pub name: String,
    pub kind: ParamType,
    pub value: Vec<f64>,
    pub min: f64,
    pub max: f64,
    /// The slider's usual range (typed values may go past it, up to `min`/`max`).
    pub soft_min: f64,
    pub soft_max: f64,
    pub unit: String,
    pub decimals: u8,
    pub choices: Vec<String>,
    /// The stopwatch is offered.
    pub animatable: bool,
    /// The stopwatch is on: the value comes from keyframes.
    pub animated: bool,
    pub keyframes: Vec<KeyframeInfo>,
}

/// One effect in Effect Controls.
#[derive(Debug, Clone, uniffi::Record)]
pub struct EffectInfo {
    /// The clip it is on (Volume sits on a video clip's linked audio).
    pub clip: u64,
    /// Its place among that clip's effects (what the edits name).
    pub index: u32,
    pub id: String,
    pub name: String,
    pub enabled: bool,
    /// Motion, Opacity, Time Remapping, Volume…: on every clip, never removed.
    pub fixed: bool,
    pub params: Vec<ParamInfo>,
}

fn interp_name(i: filmcraft_project::Interpolation) -> &'static str {
    use filmcraft_project::Interpolation as I;
    match i {
        I::Linear => "linear",
        I::Hold => "hold",
        I::Bezier => "bezier",
        I::AutoBezier => "autoBezier",
        I::ContinuousBezier => "continuousBezier",
        I::EaseIn => "easeIn",
        I::EaseOut => "easeOut",
    }
}

/// Sequence time of media time `mt` in clip `c` (the inverse of `source_time_at`).
fn sequence_time_of(c: &TrackItem, mt: Tick) -> i64 {
    let speed = if c.speed.abs() > 1e-9 { c.speed.abs() } else { 1.0 };
    let rel = if c.reverse {
        let full = (c.duration.0 as f64 * speed).round();
        (full - 1.0 - (mt.0 - c.source_in.0) as f64) / speed
    } else {
        (mt.0 - c.source_in.0) as f64 / speed
    };
    c.start.0 + rel.round() as i64
}

fn values_of(v: &filmcraft_project::ParamValue) -> Vec<f64> {
    use filmcraft_project::ParamValue as V;
    match v {
        V::Float(x) => vec![*x],
        V::Vec2(p) => vec![p.x, p.y],
        V::Color(c) => c.iter().map(|x| f64::from(*x)).collect(),
        V::Bool(b) => vec![if *b { 1.0 } else { 0.0 }],
        V::Choice(i) => vec![f64::from(*i)],
        _ => Vec::new(),
    }
}

fn effects_of(c: &TrackItem, at: Tick) -> Vec<EffectInfo> {
    use filmcraft_project::ParamKind as K;
    let mt = c.effect_time_at(at.clamp(c.start, (c.end() - Tick(1)).max(c.start)));
    c.effects
        .iter()
        .enumerate()
        .filter_map(|(i, e)| {
            let def = e.def()?;
            // graphic layers are edited with the text tools
            if filmcraft_project::graphic::is_layer_id(def.id) {
                return None;
            }
            let params = def
                .params
                .iter()
                .map(|d| {
                    let prm = e.param(d.id);
                    let value = prm.map(|p| p.value_at(mt)).unwrap_or_else(|| d.default.clone());
                    let (kind, min, max, soft_min, soft_max, unit, decimals, choices) = match &d.kind {
                        K::Float { min, max, soft_min, soft_max, unit, decimals } => {
                            (ParamType::Number, *min, *max, *soft_min, *soft_max, (*unit).to_string(), *decimals, vec![])
                        }
                        K::Point => (ParamType::Point, f64::MIN, f64::MAX, 0.0, 0.0, String::new(), 1, vec![]),
                        K::Angle => (ParamType::Angle, -36_000.0, 36_000.0, -180.0, 180.0, "°".into(), 1, vec![]),
                        K::Bool => (ParamType::Toggle, 0.0, 1.0, 0.0, 1.0, String::new(), 0, vec![]),
                        K::Choice(opts) => (
                            ParamType::Choice,
                            0.0,
                            opts.len().saturating_sub(1) as f64,
                            0.0,
                            0.0,
                            String::new(),
                            0,
                            opts.iter().map(|o| (*o).to_string()).collect(),
                        ),
                        K::Color => (ParamType::Colour, 0.0, 1.0, 0.0, 1.0, String::new(), 2, vec![]),
                        _ => (ParamType::Other, 0.0, 0.0, 0.0, 0.0, String::new(), 0, vec![]),
                    };
                    let keyframes = prm
                        .map(|p| {
                            p.keyframes
                                .iter()
                                .map(|k| KeyframeInfo {
                                    media_time: k.time.0,
                                    time: sequence_time_of(c, k.time),
                                    interpolation: interp_name(k.interp).to_string(),
                                })
                                .collect()
                        })
                        .unwrap_or_default();
                    ParamInfo {
                        id: d.id.to_string(),
                        name: d.label.to_string(),
                        kind,
                        value: values_of(&value),
                        min,
                        max,
                        soft_min,
                        soft_max,
                        unit,
                        decimals,
                        choices,
                        animatable: d.animatable && kind != ParamType::Other,
                        animated: prm.is_some_and(filmcraft_project::Param::is_animated),
                        keyframes,
                    }
                })
                .collect();
            Some(EffectInfo {
                clip: c.id.0,
                index: u32::try_from(i).unwrap_or(u32::MAX),
                id: def.id.to_string(),
                name: def.name.to_string(),
                enabled: e.enabled,
                fixed: def.intrinsic,
                params,
            })
        })
        .collect()
}

/// Effect Controls for a clip: its effects (a video clip's linked sound's after them, Volume
/// among them) with every parameter's value at the playhead and its keyframes.
pub(crate) fn effect_controls(s: &Session, clip: u64) -> Result<Vec<EffectInfo>, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let f = model::find(seq, ClipId(clip)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    let at = s.playhead();
    let mut out = effects_of(f.clip, at);
    if f.kind == TrackKind::Video
        && let Some(a) = model::partner(seq, f.clip, TrackKind::Audio)
    {
        out.extend(effects_of(a.clip, at));
    }
    Ok(out)
}

/// The JSON value the engine takes for a parameter of `kind`.
fn param_json(kind: Option<&filmcraft_project::ParamKind>, value: &[f64]) -> Result<serde_json::Value, MobileError> {
    use filmcraft_project::ParamKind as K;
    let first = || value.first().copied().ok_or_else(|| MobileError::Command("A value is missing.".into()));
    Ok(match kind {
        Some(K::Point) => {
            let (x, y) = (first()?, value.get(1).copied().ok_or_else(|| MobileError::Command("A point needs x and y.".into()))?);
            json!([x, y])
        }
        Some(K::Bool) => json!(first()? >= 0.5),
        Some(K::Choice(_)) => json!(first()?.round().max(0.0) as u64),
        Some(K::Color) => {
            let c: Vec<f64> = (0..4).map(|i| value.get(i).copied().unwrap_or(1.0).clamp(0.0, 1.0)).collect();
            json!(c)
        }
        _ => json!(first()?),
    })
}

fn param_kind(s: &Session, clip: u64, effect: u32, param: &str) -> Option<filmcraft_project::ParamKind> {
    let (_, seq) = model::sequence(s).ok()?;
    let f = model::find(seq, ClipId(clip))?;
    let e = f.clip.effects.get(effect as usize)?;
    Some(e.def()?.param(param)?.kind.clone())
}

/// Set a parameter at the playhead (a keyframe there when its stopwatch is on); `merge` makes a
/// drag one undo step, `begin` starts a new one (the first change of a drag).
pub(crate) fn set_param(s: &mut Session, clip: u64, effect: u32, param: &str, value: &[f64], merge: bool, begin: bool) -> Result<(), MobileError> {
    let kind = param_kind(s, clip, effect, param);
    let v = param_json(kind.as_ref(), value)?;
    ex(s, "effects.setParam", json!({"clip": clip, "effect": effect, "param": param, "value": v, "merge": merge, "begin": begin}))?;
    Ok(())
}

/// The stopwatch: start animating (a first keyframe at the playhead) or stop (keyframes go, the
/// value at the playhead stays).
pub(crate) fn toggle_animation(s: &mut Session, clip: u64, effect: u32, param: &str) -> Result<(), MobileError> {
    ex(s, "effects.toggleAnimation", json!({"clip": clip, "effect": effect, "param": param}))?;
    Ok(())
}

/// Add/Remove Keyframe at the playhead.
pub(crate) fn toggle_keyframe(s: &mut Session, clip: u64, effect: u32, param: &str) -> Result<(), MobileError> {
    ex(s, "effects.addKeyframe", json!({"clip": clip, "effect": effect, "param": param}))?;
    Ok(())
}

pub(crate) fn delete_keyframe(s: &mut Session, clip: u64, effect: u32, param: &str, media_time: i64) -> Result<(), MobileError> {
    ex(s, "effects.deleteKeyframe", json!({"clip": clip, "effect": effect, "param": param, "mediaTime": media_time}))?;
    Ok(())
}

/// Move a keyframe to another place in the footage; `merge` makes a drag one undo step.
pub(crate) fn move_keyframe(s: &mut Session, clip: u64, effect: u32, param: &str, media_time: i64, to: i64, merge: bool) -> Result<(), MobileError> {
    ex(s, "effects.moveKeyframe", json!({"clip": clip, "effect": effect, "param": param, "mediaTime": media_time, "to": to.max(0), "merge": merge}))?;
    Ok(())
}

/// The interpolation leaving a keyframe: "linear", "bezier", "hold" (or "autoBezier",
/// "continuousBezier", "easeIn", "easeOut").
pub(crate) fn set_interpolation(s: &mut Session, clip: u64, effect: u32, param: &str, media_time: i64, interpolation: &str) -> Result<(), MobileError> {
    ex(s, "effects.setInterpolation", json!({"clip": clip, "effect": effect, "param": param, "mediaTime": media_time, "interpolation": interpolation}))?;
    Ok(())
}

pub(crate) fn reset_param(s: &mut Session, clip: u64, effect: u32, param: &str) -> Result<(), MobileError> {
    ex(s, "effects.resetParam", json!({"clip": clip, "effect": effect, "param": param}))?;
    Ok(())
}

/// The fx switch: turn an effect off or on.
pub(crate) fn toggle_effect(s: &mut Session, clip: u64, effect: u32) -> Result<(), MobileError> {
    ex(s, "effects.toggleEnabled", json!({"clip": clip, "index": effect}))?;
    Ok(())
}

/// Remove an applied effect (Motion, Opacity and the other fixed ones stay).
pub(crate) fn remove_effect(s: &mut Session, clip: u64, effect: u32) -> Result<(), MobileError> {
    ex(s, "effects.remove", json!({"clip": clip, "index": effect}))?;
    Ok(())
}

/// Where a clip's keyframes are on the sequence (every animated parameter's, once each), for
/// the marks on the timeline.
pub(crate) fn keyframe_times(c: &TrackItem) -> Vec<i64> {
    let mut t: Vec<i64> = c
        .effects
        .iter()
        .flat_map(|e| e.params.values())
        .flat_map(|p| p.keyframes.iter())
        .map(|k| sequence_time_of(c, k.time))
        .filter(|t| *t >= c.start.0 && *t <= c.end().0)
        .collect();
    t.sort_unstable();
    t.dedup();
    t
}

// ---- adding media where the timeline was double-tapped ----

/// Import files and put them on the timeline at `time`, one after another, overwriting what is
/// there (a drop onto the desktop timeline). Pictures go on `track` when it is a video track,
/// sound on it when it is an audio track; the other half goes to the patched track. One undo
/// step; returns the new clips.
pub(crate) fn place_media(s: &mut Session, paths: &[String], track: u64, time: i64) -> Result<Vec<u64>, MobileError> {
    let items = crate::edits::import(s, paths)?;
    let (_, seq) = model::sequence(s)?;
    let tapped = seq.track(TrackId(track)).ok_or_else(|| MobileError::Command("That track is gone.".into()))?;
    if tapped.locked {
        return Err(MobileError::Command(format!("{} is locked.", tapped.name)));
    }
    let tapped_video = tapped.kind == TrackKind::Video;
    let tg = s.targeting();
    let first_video = seq.video_tracks.first().map(|t| t.id);
    let first_audio = seq.audio_tracks.first().map(|t| t.id);
    let r = s.edit_group("Add Media", |s| {
        let mut at = Tick(time.max(0));
        let mut out = Vec::new();
        for &item in &items {
            let (has_video, has_audio) = s.project.source_streams(filmcraft_project::ItemId(item));
            let mut p = json!({"item": item, "time": at.0, "insert": false});
            match (tapped_video, has_video) {
                // the picture on the tapped video track (its sound to the patched audio track)
                (true, true) => p["track"] = json!(track),
                // sound alone, tapped on a video track: the patched (or first) audio track
                (true, false) => p["track"] = json!(tg.audio_dest.or(first_audio).map(|t| t.0)),
                // a picture tapped on an audio track: its sound there, the picture patched
                (false, true) if has_audio => p["audioTrack"] = json!(track),
                (false, true) => p["track"] = json!(tg.video_dest.or(first_video).map(|t| t.0)),
                (false, false) => p["track"] = json!(track),
            }
            let r = s.execute("timeline.place", p)?;
            let placed: Vec<u64> = r["clips"].as_array().map(|a| a.iter().filter_map(serde_json::Value::as_u64).collect()).unwrap_or_default();
            // the next file goes where this one ends
            if let Some(q) = s.active_sequence() {
                let end = q.video_tracks.iter().chain(&q.audio_tracks).flat_map(|t| &t.items).filter(|c| placed.contains(&c.id.0)).map(|c| c.end()).max();
                if let Some(e) = end {
                    at = e;
                }
            }
            out.extend(placed);
        }
        Ok(out)
    })?;
    Ok(r)
}

// ---- crossfades on cuts, and closing gaps ----

/// Whether clip `c` on its track has a neighbour starting exactly where it ends (a cut).
fn has_cut_after(seq: &Sequence, c: ClipId) -> bool {
    model::find(seq, c).is_some_and(|f| {
        let tracks = if f.kind == TrackKind::Video { &seq.video_tracks } else { &seq.audio_tracks };
        tracks.get(f.track).is_some_and(|t| t.items.iter().any(|o| o.id != c && o.start == f.clip.end()))
    })
}

/// Put a crossfade on the cut after clip `left`: a Cross Dissolve on the picture (`video`) and/or
/// a Constant Power crossfade on the sound (`audio`), `seconds` long (shorter when the clips have
/// too little footage beyond the cut). One undo step; returns how many crossfades were added.
pub(crate) fn crossfade(s: &mut Session, left: u64, video: bool, audio: bool, seconds: f64) -> Result<u32, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let f = model::find(seq, ClipId(left)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    // the picture and the sound of the clip before the cut
    let (v, a) = match f.kind {
        TrackKind::Video => (Some(f.clip.id), model::partner(seq, f.clip, TrackKind::Audio).map(|x| x.clip.id)),
        TrackKind::Audio => (model::partner(seq, f.clip, TrackKind::Video).map(|x| x.clip.id), Some(f.clip.id)),
    };
    let v = v.filter(|c| video && has_cut_after(seq, *c));
    let a = a.filter(|c| audio && has_cut_after(seq, *c));
    if v.is_none() && a.is_none() {
        return Err(MobileError::Command("There's no cut there to crossfade: the next clip must start right where this one ends.".into()));
    }
    let frame = s.sequence_rate().tick_of(1).0.max(1);
    let frames = ((seconds.clamp(0.04, 30.0) * filmcraft_time::TICKS_PER_SECOND as f64) / frame as f64).round().max(1.0) as i64;
    let n = s.edit_group("Crossfade", |s| {
        let mut n = 0;
        if let Some(c) = v {
            s.execute("sequence.applyVideoTransition", json!({"clip": c.0, "edge": "out", "effect": "cross_dissolve", "frames": frames}))?;
            n += 1;
        }
        if let Some(c) = a {
            s.execute("sequence.applyAudioTransition", json!({"clip": c.0, "edge": "out", "effect": "constant_power", "frames": frames}))?;
            n += 1;
        }
        Ok(n)
    })?;
    Ok(n)
}

pub(crate) fn remove_transition(s: &mut Session, transition: u64) -> Result<(), MobileError> {
    ex(s, "sequence.removeTransition", json!({"transitions": [transition]}))?;
    Ok(())
}

/// Close the gap on `track` at `time`: the clips after it move back to meet the clip before.
pub(crate) fn close_gap(s: &mut Session, track: u64, time: i64) -> Result<(), MobileError> {
    ex(s, "sequence.closeGap", json!({"track": track, "time": time.max(0)}))?;
    Ok(())
}
