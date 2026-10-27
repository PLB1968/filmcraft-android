//! Pro mode's Motion panel: entrance and exit animations, Ken Burns, picture-in-picture, and
//! keyframing a clip's motion by dragging it on the Program monitor. Each is one undo step on the
//! clip's Motion and Opacity.

use filmcraft_engine::{EngineError, Session};
use filmcraft_geom::Vec2;
use filmcraft_project::{ClipId, Interpolation, Param, ParamValue, TrackItem, TrackKind};
use filmcraft_time::{TICKS_PER_SECOND, Tick};

use crate::placement::{content, motion_time};
use crate::{MobileError, edits, model};

/// An entrance (or, mirrored, an exit).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Anim {
    None,
    Fade,
    /// Moving leftwards: in from the right, out to the left.
    SlideLeft,
    SlideRight,
    SlideUp,
    SlideDown,
    /// Grows from nothing with a little overshoot.
    Pop,
    /// Grows from half size while fading in (out: grows on while fading).
    Zoom,
    /// Turns once while growing.
    Spin,
}

/// A slow move across a picture over the whole clip (a preset of [`KbMove`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KenBurns {
    None,
    ZoomIn,
    ZoomOut,
    /// The view moves left across the picture (the picture drifts right).
    PanLeft,
    PanRight,
    /// The view moves up the picture.
    PanUp,
    PanDown,
    /// From the top-left corner to the bottom-right.
    Diagonal,
    /// From the whole picture into a corner of it.
    ZoomCorner,
    /// One of the others, picked by the clip (so a run of photos varies).
    Random,
}

/// A Ken Burns view: the part of the picture the frame shows, its centre (`x`, `y`, 0..1 across
/// and down the picture) and its width (`size`, 0.2..1 of the widest view that fits inside it).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct KbView {
    pub x: f64,
    pub y: f64,
    pub size: f64,
}

/// A Ken Burns move: from view `start` to view `end`, between `from` and `to` (0..1 of the clip),
/// eased in and out (`smooth`) or at an even pace.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct KbMove {
    pub start: KbView,
    pub end: KbView,
    pub smooth: bool,
    pub from: f64,
    pub to: f64,
}

/// What the Ken Burns panel draws: the picture's and the frame's shapes, and the clip's move.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct KbInfo {
    pub picture_w: f64,
    pub picture_h: f64,
    pub frame_w: f64,
    pub frame_h: f64,
    pub current: Option<KbMove>,
}

/// Where picture-in-picture puts a clip.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Corner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    Center,
}

/// A clip's animated path on the frame, for drawing on the Program monitor.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct MotionPath {
    /// Whether Motion is keyframed (dragging adds keyframes at the playhead).
    pub recording: bool,
    /// The centre of what it shows along the clip (x, y pairs, frame pixels); empty when it
    /// doesn't move.
    pub path: Vec<f64>,
    /// The centre at each position keyframe (x, y pairs).
    pub keys: Vec<f64>,
}

/// What the presets need to know about the clip and the frame.
struct Geo {
    fw: f64,
    fh: f64,
    /// The picture's size on the frame at 100 %.
    w: f64,
    h: f64,
    frame: Tick,
}

const MOVING: [(&str, &str); 4] = [("motion", "position"), ("motion", "scale"), ("motion", "rotation"), ("opacity", "opacity")];

fn geo(s: &Session, id: u64) -> Result<Geo, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let (fw, fh) = (f64::from(seq.settings.width), f64::from(seq.settings.height));
    let f = model::find(seq, ClipId(id))
        .filter(|f| f.kind == TrackKind::Video)
        .ok_or_else(|| MobileError::Command("Pick a clip with a picture.".into()))?;
    let c = f.clip;
    let src = s.project.source_size(c.item).map(|(w, h)| (w.max(1), h.max(1))).unwrap_or((seq.settings.width.max(1), seq.settings.height.max(1)));
    let (sw, sh) = (f64::from(src.0), f64::from(src.1));
    let fit = if c.scale_to_frame { (fw / sw).min(fh / sh) } else { 1.0 };
    let b = content(s, c, src, motion_time(s, c));
    Ok(Geo { fw, fh, w: (b[2] - b[0]) * fit, h: (b[3] - b[1]) * fit, frame: s.sequence_rate().frame_duration() })
}

/// Change clip `id`'s Motion and Opacity in one undo step called `label`.
fn edit(s: &mut Session, id: u64, label: &str, f: impl FnOnce(&mut TrackItem) -> Result<(), EngineError>) -> Result<(), MobileError> {
    s.edit_sequence_as(label, None, |q, _, _| {
        let c = q.find_item_mut(ClipId(id)).map(|x| x.1).ok_or_else(|| EngineError::Other("That clip is no longer on the timeline.".into()))?;
        f(c)
    })?;
    edits::settle(s);
    Ok(())
}

fn param<'a>(c: &'a mut TrackItem, effect: &str, p: &str) -> Option<&'a mut Param> {
    let e = c.effects.iter_mut().find(|e| e.effect == effect)?;
    e.enabled = true;
    e.param_mut(p)
}

/// Position, with Motion's "centre of the frame" default made a real point.
fn pos_at(p: &Param, t: Tick, g: &Geo) -> Vec2 {
    match p.value_at(t) {
        ParamValue::Vec2(v) if !v.x.is_nan() && !v.y.is_nan() => v,
        _ => Vec2::new(g.fw / 2.0, g.fh / 2.0),
    }
}

/// Drop `p`'s keyframes between media times `a` and `b` (either order); a parameter left
/// without any keeps `still`.
fn clear(p: &mut Param, a: Tick, b: Tick, still: ParamValue) {
    let (lo, hi) = (a.min(b), a.max(b));
    let had = p.is_animated();
    p.keyframes.retain(|k| k.time < lo || k.time > hi);
    if had && !p.is_animated() {
        p.value = still;
    }
}

/// A keyframe at media time `t`, leaving with `interp`.
fn key(p: &mut Param, t: Tick, v: ParamValue, interp: Interpolation, influence: f64) {
    p.put_keyframe(t, v);
    if let Ok(i) = p.keyframes.binary_search_by_key(&t, |k| k.time) {
        let k = &mut p.keyframes[i];
        k.interp = interp;
        k.in_influence = influence;
        k.out_influence = influence;
    }
}

/// One animated value: (fraction of the window, value) steps; the window's inner edge holds the
/// clip's own value.
type Steps = Vec<(f64, ParamValue)>;

fn steps(a: Anim, entering: bool, g: &Geo, pos: Vec2, scale: f64, rot: f64, op: f64) -> [Steps; 4] {
    let f = ParamValue::Float;
    let v2 = |x: f64, y: f64| ParamValue::Vec2(Vec2::new(x, y));
    // (from the outside to the clip's own) for an entrance; mirrored in time for an exit
    let (mut p, mut s, mut r, mut o): (Steps, Steps, Steps, Steps) = (vec![], vec![], vec![], vec![]);
    let sign = if entering { -1.0 } else { 1.0 };
    let k = scale / 100.0;
    let slide = |dx: f64, dy: f64| vec![(0.0, v2(pos.x + sign * dx * (g.fw + g.w * k) / 2.0, pos.y + sign * dy * (g.fh + g.h * k) / 2.0)), (1.0, v2(pos.x, pos.y))];
    match a {
        Anim::None => {}
        Anim::Fade => o = vec![(0.0, f(0.0)), (1.0, f(op))],
        Anim::SlideLeft => p = slide(-1.0, 0.0),
        Anim::SlideRight => p = slide(1.0, 0.0),
        Anim::SlideUp => p = slide(0.0, -1.0),
        Anim::SlideDown => p = slide(0.0, 1.0),
        Anim::Pop => {
            s = vec![(0.0, f(0.0)), (0.7, f(scale * 1.12)), (1.0, f(scale))];
            o = vec![(0.0, f(0.0)), (0.3, f(op))];
        }
        Anim::Zoom => {
            s = vec![(0.0, f(scale * if entering { 0.5 } else { 1.6 })), (1.0, f(scale))];
            o = vec![(0.0, f(0.0)), (1.0, f(op))];
        }
        Anim::Spin => {
            r = vec![(0.0, f(rot + sign * 360.0)), (1.0, f(rot))];
            s = vec![(0.0, f(0.0)), (1.0, f(scale))];
        }
    }
    if !entering {
        for v in [&mut p, &mut s, &mut r, &mut o] {
            for st in v.iter_mut() {
                st.0 = 1.0 - st.0;
            }
            v.reverse();
        }
    }
    [p, s, r, o]
}

/// Give clip `id` entrance `enter` and exit `exit`, each `seconds` long (at most half the clip).
/// Replaces any keyframes in the clip's first and last `seconds`.
pub(crate) fn animate(s: &mut Session, id: u64, enter: Anim, exit: Anim, seconds: f64) -> Result<(), MobileError> {
    if !seconds.is_finite() || seconds <= 0.0 {
        return Err(MobileError::Command("The animation needs a length.".into()));
    }
    let g = geo(s, id)?;
    edit(s, id, "Animate", |c| {
        let len = c.duration - g.frame;
        let d = Tick::from_seconds_f64(seconds).min(Tick(len.0 / 2)).max(Tick(1));
        for (entering, a) in [(true, enter), (false, exit)] {
            // the window in the timeline: its outer edge, and the inner edge the clip's own look holds at
            let (outer, inner) = if entering { (c.start, c.start + d) } else { (c.start + len, c.start + len - d) };
            let mt = |c: &TrackItem, frac: f64| {
                let t = if entering { outer + Tick((d.0 as f64 * frac).round() as i64) } else { inner + Tick((d.0 as f64 * frac).round() as i64) };
                c.effect_time_at(t)
            };
            let (m_out, m_in) = (c.effect_time_at(outer), c.effect_time_at(inner));
            // the clip's own values where the animation ends (or starts)
            let pos = c.effect("motion").and_then(|m| m.param("position")).map(|p| pos_at(p, m_in, &g)).unwrap_or(Vec2::new(g.fw / 2.0, g.fh / 2.0));
            let num = |c: &TrackItem, e: &str, p: &str, d: f64| c.effect(e).and_then(|e| e.param(p)).map_or(d, |p| p.f64_at(m_in));
            let (scale, rot, op) = (num(c, "motion", "scale", 100.0), num(c, "motion", "rotation", 0.0), num(c, "opacity", "opacity", 100.0));
            let own = [ParamValue::Vec2(pos), ParamValue::Float(scale), ParamValue::Float(rot), ParamValue::Float(op)];
            for ((e, p), still) in MOVING.iter().zip(own.iter()) {
                if let Some(prm) = param(c, e, p) {
                    clear(prm, m_out, m_in, still.clone());
                }
            }
            let plan = steps(a, entering, &g, pos, scale, rot, op);
            for (((e, p), st), own) in MOVING.iter().zip(plan).zip(own) {
                if st.is_empty() {
                    continue;
                }
                let times: Vec<Tick> = st.iter().map(|(fr, _)| mt(c, *fr)).collect();
                let Some(prm) = param(c, e, p) else { continue };
                // the clip's own value at the inner edge, so the rest of the clip is untouched
                let inner_frac = if entering { 1.0 } else { 0.0 };
                if !st.iter().any(|(fr, _)| (*fr - inner_frac).abs() < 1e-9) {
                    key(prm, m_in, own, Interpolation::Linear, 1.0 / 3.0);
                }
                for ((_, v), t) in st.into_iter().zip(times) {
                    key(prm, t, v, Interpolation::Linear, 0.6);
                }
                // settle softly into the clip's own look on the way in, leave it softly on the way out
                if let Ok(i) = prm.keyframes.binary_search_by_key(&m_in, |k| k.time) {
                    prm.keyframes[i].interp = if entering { Interpolation::EaseIn } else { Interpolation::EaseOut };
                }
            }
        }
        Ok(())
    })
}

/// The widest view (frame-shaped) that fits inside the picture, in picture pixels at 100 %.
fn widest(g: &Geo) -> f64 {
    let aspect = g.fw / g.fh.max(1.0);
    if g.w / g.h.max(1.0) > aspect { g.h * aspect } else { g.w }
}

/// View `v` kept inside the picture: no edge of the picture ever shows.
fn inside(v: KbView, g: &Geo) -> KbView {
    let size = if v.size.is_finite() { v.size.clamp(0.2, 1.0) } else { 1.0 };
    let ww = size * widest(g);
    let wh = ww * g.fh / g.fw.max(1.0);
    let hx = (ww / 2.0 / g.w.max(1.0)).min(0.5);
    let hy = (wh / 2.0 / g.h.max(1.0)).min(0.5);
    let fin = |a: f64| if a.is_finite() { a } else { 0.5 };
    KbView { x: fin(v.x).clamp(hx, 1.0 - hx), y: fin(v.y).clamp(hy, 1.0 - hy), size }
}

/// Motion's scale (%) and position for view `v`.
fn view_motion(v: KbView, g: &Geo) -> (f64, Vec2) {
    let s = g.fw / (v.size * widest(g)).max(1.0);
    (s * 100.0, Vec2::new(g.fw / 2.0 + (0.5 - v.x) * g.w * s, g.fh / 2.0 + (0.5 - v.y) * g.h * s))
}

/// The view Motion's scale (%) and position show.
fn motion_view(scale: f64, pos: Vec2, g: &Geo) -> KbView {
    let s = (scale / 100.0).max(1e-6);
    KbView { x: 0.5 - (pos.x - g.fw / 2.0) / (g.w * s).max(1.0), y: 0.5 - (pos.y - g.fh / 2.0) / (g.h * s).max(1.0), size: g.fw / s / widest(g).max(1.0) }
}

/// Preset `kind` as a move `amount` closer in (0.05 to 0.5), varied for clip `seed` (Random,
/// Zoom Into a Corner).
pub(crate) fn preset(kind: KenBurns, amount: f64, seed: u64) -> Option<KbMove> {
    let a = if amount.is_finite() { amount.clamp(0.05, 0.5) } else { 0.15 };
    let z = 1.0 / (1.0 + a);
    let v = |x: f64, y: f64, size: f64| KbView { x, y, size };
    let corner = [(0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75)][(seed % 4) as usize];
    let (start, end) = match kind {
        KenBurns::None => return None,
        KenBurns::ZoomIn => (v(0.5, 0.5, 1.0), v(0.5, 0.5, z)),
        KenBurns::ZoomOut => (v(0.5, 0.5, z), v(0.5, 0.5, 1.0)),
        KenBurns::PanLeft => (v(1.0, 0.5, z), v(0.0, 0.5, z)),
        KenBurns::PanRight => (v(0.0, 0.5, z), v(1.0, 0.5, z)),
        KenBurns::PanUp => (v(0.5, 1.0, z), v(0.5, 0.0, z)),
        KenBurns::PanDown => (v(0.5, 0.0, z), v(0.5, 1.0, z)),
        KenBurns::Diagonal => (v(0.0, 0.0, z), v(1.0, 1.0, z)),
        KenBurns::ZoomCorner => (v(0.5, 0.5, 1.0), v(corner.0, corner.1, z)),
        KenBurns::Random => {
            const PICKS: [KenBurns; 8] =
                [KenBurns::ZoomIn, KenBurns::ZoomOut, KenBurns::PanLeft, KenBurns::PanRight, KenBurns::PanUp, KenBurns::PanDown, KenBurns::Diagonal, KenBurns::ZoomCorner];
            // a cheap, steady scramble of the seed
            let h = seed.wrapping_mul(0x9E37_79B9_7F4A_7C15).rotate_left(17);
            return preset(PICKS[(h % 8) as usize], a, h / 8);
        }
    };
    Some(KbMove { start, end, smooth: true, from: 0.0, to: 1.0 })
}

/// Ken Burns preset `kind` on clip `id` over its whole length, eased; `amount` is how much
/// further it zooms (0.05 to 0.5). Replaces the clip's position and scale keyframes.
pub(crate) fn ken_burns(s: &mut Session, id: u64, kind: KenBurns, amount: f64) -> Result<(), MobileError> {
    match preset(kind, amount, id) {
        Some(m) => set_ken_burns(s, id, m),
        None => edit(s, id, "Ken Burns", |c| {
            for p in ["position", "scale"] {
                if let Some(prm) = param(c, "motion", p) {
                    let v = prm.value_at(prm.keyframes.first().map_or(Tick::ZERO, |k| k.time));
                    prm.keyframes.clear();
                    prm.value = v;
                }
            }
            Ok(())
        }),
    }
}

/// A gentle Ken Burns (Random, varied by clip) on each of `clips` that is a still with no move of
/// its own: photos added while Settings › Ken Burns on photos is on.
pub(crate) fn burn_stills(s: &mut Session, clips: &[u64]) {
    for &id in clips {
        let Ok((_, seq)) = model::sequence(s) else { return };
        let Some(f) = model::find(seq, ClipId(id)) else { continue };
        let still = f.kind == TrackKind::Video
            && matches!(s.project.item(f.clip.item).map(|i| &i.kind), Some(filmcraft_project::ItemKind::Media(m)) if m.info.kind == filmcraft_media::MediaKind::Still);
        let moving = f.clip.effect("motion").and_then(|m| m.param("scale")).is_some_and(Param::is_animated);
        if still
            && !moving
            && let Some(m) = preset(KenBurns::Random, 0.12, id)
        {
            let _ = set_ken_burns(s, id, m);
        }
    }
}

/// Move `m` on clip `id`: its views kept inside the picture, keyframed on Motion's scale and
/// position at its start and end. One undo step.
pub(crate) fn set_ken_burns(s: &mut Session, id: u64, m: KbMove) -> Result<(), MobileError> {
    let g = geo(s, id)?;
    let (a, b) = (inside(m.start, &g), inside(m.end, &g));
    let mut from = if m.from.is_finite() { m.from.clamp(0.0, 0.95) } else { 0.0 };
    let mut to = if m.to.is_finite() { m.to.clamp(0.05, 1.0) } else { 1.0 };
    if to - from < 0.05 {
        (from, to) = (from.min(0.95), (from + 0.05).min(1.0));
    }
    let (interp, influence) = if m.smooth { (Interpolation::Bezier, 0.6) } else { (Interpolation::Linear, 1.0 / 3.0) };
    edit(s, id, "Ken Burns", |c| {
        let dur = c.duration.0.max(1);
        let at = |f: f64| {
            let t = c.start.0 + (dur as f64 * f).round() as i64;
            c.effect_time_at(Tick(t.min(c.start.0 + dur - g.frame.0).max(c.start.0)))
        };
        let (m0, m1) = (at(from), at(to));
        for p in ["position", "scale"] {
            if let Some(prm) = param(c, "motion", p) {
                prm.keyframes.clear();
            }
        }
        let ((s0, p0), (s1, p1)) = (view_motion(a, &g), view_motion(b, &g));
        if let Some(prm) = param(c, "motion", "scale") {
            key(prm, m0, ParamValue::Float(s0), interp, influence);
            key(prm, m1, ParamValue::Float(s1), interp, influence);
        }
        if let Some(prm) = param(c, "motion", "position") {
            key(prm, m0, ParamValue::Vec2(p0), interp, influence);
            key(prm, m1, ParamValue::Vec2(p1), interp, influence);
        }
        Ok(())
    })
}

/// The Ken Burns panel's picture: the shapes, and clip `id`'s move (when its scale is keyed at two
/// times, as a move leaves it).
pub(crate) fn ken_burns_info(s: &Session, id: u64) -> Result<KbInfo, MobileError> {
    let g = geo(s, id)?;
    let (_, seq) = model::sequence(s)?;
    let c = model::find(seq, ClipId(id)).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?.clip;
    let current = (|| {
        let m = c.effect("motion")?;
        let sc = m.param("scale")?;
        let [k0, k1] = &sc.keyframes[..] else { return None };
        let pos = m.param("position");
        let view = |t: Tick| motion_view(sc.f64_at(t), pos.map(|p| pos_at(p, t, &g)).unwrap_or(Vec2::new(g.fw / 2.0, g.fh / 2.0)), &g);
        let dur = c.duration.0.max(1) as f64;
        let frac = |t: Tick| ((crate::pro::sequence_time_of(c, t) - c.start.0) as f64 / dur).clamp(0.0, 1.0);
        let to = frac(k1.time);
        Some(KbMove {
            start: view(k0.time),
            end: view(k1.time),
            smooth: k0.interp != Interpolation::Linear,
            from: frac(k0.time),
            // the last keyframe sits a frame before the end
            to: if to > 0.97 { 1.0 } else { to },
        })
    })();
    Ok(KbInfo { picture_w: g.w, picture_h: g.h, frame_w: g.fw, frame_h: g.fh, current })
}

/// Picture-in-picture: clip `id` at `size` × the frame's width (0.15 to 0.9) in `corner`, a small
/// margin in. Its position, scale and rotation stop being animated.
pub(crate) fn picture_in_picture(s: &mut Session, id: u64, corner: Corner, size: f64) -> Result<(), MobileError> {
    let g = geo(s, id)?;
    let size = if size.is_finite() { size.clamp(0.15, 0.9) } else { 0.35 };
    let scale = size * g.fw / g.w.max(1.0);
    let (w, h) = (g.w * scale, g.h * scale);
    let m = g.fw.min(g.fh) * 0.04;
    let (l, r, t, b) = (m + w / 2.0, g.fw - m - w / 2.0, m + h / 2.0, g.fh - m - h / 2.0);
    let (x, y) = match corner {
        Corner::TopLeft => (l, t),
        Corner::TopRight => (r, t),
        Corner::BottomLeft => (l, b),
        Corner::BottomRight => (r, b),
        Corner::Center => (g.fw / 2.0, g.fh / 2.0),
    };
    edit(s, id, "Picture in Picture", |c| {
        for (p, v) in [("position", ParamValue::Vec2(Vec2::new(x, y))), ("scale", ParamValue::Float(scale * 100.0)), ("rotation", ParamValue::Float(0.0))] {
            if let Some(prm) = param(c, "motion", p) {
                prm.keyframes.clear();
                prm.value = v;
            }
        }
        Ok(())
    })
}

/// Start (or stop) keyframing clip `id`'s position, scale and rotation: on, each gets a
/// keyframe at the playhead and dragging on the Program monitor adds more; off, the keyframes
/// go and the look at the playhead stays.
pub(crate) fn set_recording(s: &mut Session, id: u64, on: bool) -> Result<(), MobileError> {
    let g = geo(s, id)?;
    let mt = {
        let (_, seq) = model::sequence(s)?;
        let c = model::find(seq, ClipId(id)).map(|f| f.clip).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
        motion_time(s, c)
    };
    edit(s, id, if on { "Keyframe Motion" } else { "Stop Keyframing Motion" }, |c| {
        for p in ["position", "scale", "rotation"] {
            let Some(prm) = param(c, "motion", p) else { continue };
            let v = if p == "position" { ParamValue::Vec2(pos_at(prm, mt, &g)) } else { prm.value_at(mt) };
            if on {
                if !prm.is_animated() {
                    prm.put_keyframe(mt, v);
                }
            } else {
                prm.keyframes.clear();
                prm.value = v;
            }
        }
        Ok(())
    })
}

/// Clip `id`'s path on the frame, for the Program monitor.
pub(crate) fn path(s: &Session, id: u64) -> Result<MotionPath, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let Some(c) = model::find(seq, ClipId(id)).filter(|f| f.kind == TrackKind::Video).map(|f| f.clip) else { return Ok(MotionPath::default()) };
    let Some(m) = c.effects.iter().find(|e| e.effect == "motion" && e.enabled) else { return Ok(MotionPath::default()) };
    let recording = m.param("position").is_some_and(Param::is_animated);
    let moves = ["position", "scale", "rotation"].iter().any(|p| m.param(p).is_some_and(Param::is_animated));
    if !moves {
        return Ok(MotionPath { recording, ..Default::default() });
    }
    let src = s.project.source_size(c.item).map(|(w, h)| (w.max(1), h.max(1))).unwrap_or((seq.settings.width.max(1), seq.settings.height.max(1)));
    let b = content(s, c, src, motion_time(s, c));
    let mid = Vec2::new((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0);
    let at = |mt: Tick| {
        let p = filmcraft_render::motion_matrix(seq, c, src, None, mt).apply(mid);
        [p.x, p.y]
    };
    const N: i64 = 48;
    let last = (c.duration - Tick(1)).max(Tick::ZERO);
    let path = (0..=N).flat_map(|i| at(c.effect_time_at(c.start + Tick(last.0 * i / N)))).collect();
    let keys = m.param("position").map(|p| p.keyframes.iter().flat_map(|k| at(k.time)).collect()).unwrap_or_default();
    Ok(MotionPath { recording, path, keys })
}

/// A speed ramp over a whole clip.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Ramp {
    /// Back to the clip's own speed (the ramp's keyframes go).
    None,
    /// Normal, a second of slow motion around the playhead (or the middle), normal again.
    SlowMoHit,
    /// Faster through the first part, slower through the next, then normal.
    SpeedUpThenSlow,
    /// Starts slow and comes up to speed.
    RampIn,
    /// Slows down towards the end.
    RampOut,
}

/// Give clip `id` speed ramp `ramp` (Time Remapping keyframes, editable in Effect Controls).
/// The picture ramps; the clip's sound can't follow, so it is muted while a ramp is on (and
/// heard again with [`Ramp::None`]). Returns whether the sound was muted.
pub(crate) fn speed_ramp(s: &mut Session, id: u64, ramp: Ramp) -> Result<bool, MobileError> {
    let (rel_at, partner) = {
        let (_, seq) = model::sequence(s)?;
        let f = model::find(seq, ClipId(id))
            .filter(|f| f.kind == TrackKind::Video)
            .ok_or_else(|| MobileError::Command("Pick a video clip for a speed ramp.".into()))?;
        let c = f.clip;
        let ph = s.playhead();
        let rel = if ph > c.start && ph < c.end() { (ph - c.start).0 as f64 / c.duration.0.max(1) as f64 } else { 0.5 };
        (rel, model::partner(seq, c, TrackKind::Audio).map(|a| (a.clip.id, a.clip.enabled)))
    };
    let sec = TICKS_PER_SECOND as f64;
    s.edit_sequence_as(if ramp == Ramp::None { "Remove Speed Ramp" } else { "Speed Ramp" }, None, |q, _, _| {
        let c = q.find_item_mut(ClipId(id)).map(|x| x.1).ok_or_else(|| EngineError::Other("That clip is no longer on the timeline.".into()))?;
        let (s0, base, dur) = (c.source_in.0 as f64, c.speed.abs().max(1e-6), c.duration.0 as f64);
        // keyframes at the media time the clip would show at that point without the ramp
        let at = |frac: f64| Tick((s0 + frac.clamp(0.0, 1.0) * dur * base).round() as i64);
        let plan: Vec<(f64, f64)> = match ramp {
            Ramp::None => vec![],
            Ramp::SlowMoHit => {
                // a second of 30 % around the moment, eased in and out over 0.3 s
                let half = (0.5 * sec / dur).min(0.25);
                let ease = (0.3 * sec / dur).min(0.15);
                let m = rel_at.clamp(half + ease, 1.0 - half - ease);
                vec![(m - half - ease, 100.0), (m - half, 30.0), (m + half, 30.0), (m + half + ease, 100.0)]
            }
            Ramp::SpeedUpThenSlow => vec![(0.0, 100.0), (0.3, 250.0), (0.6, 40.0), (0.85, 40.0), (1.0, 100.0)],
            Ramp::RampIn => vec![(0.0, 30.0), (0.4, 100.0)],
            Ramp::RampOut => vec![(0.6, 100.0), (1.0, 30.0)],
        };
        let e = c.effects.iter_mut().find(|e| e.effect == "time_remap").ok_or_else(|| EngineError::Other("no time remapping".into()))?;
        e.enabled = true;
        let p = e.param_mut("speed").ok_or_else(|| EngineError::Other("no speed".into()))?;
        p.keyframes.clear();
        p.value = ParamValue::Float(100.0);
        for (frac, v) in plan {
            key(p, at(frac), ParamValue::Float(v), Interpolation::Bezier, 0.5);
        }
        // the sound can't follow a ramp: muted while one is on, heard again without
        if let Some((a, _)) = partner
            && let Some((_, x)) = q.find_item_mut(a)
        {
            x.enabled = ramp == Ramp::None;
        }
        Ok(())
    })?;
    edits::settle(s);
    Ok(ramp != Ramp::None && partner.is_some_and(|(_, on)| on))
}
