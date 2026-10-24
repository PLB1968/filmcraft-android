//! Pro mode's Motion panel: entrance and exit animations, Ken Burns, picture-in-picture, and
//! keyframing a clip's motion by dragging it on the Program monitor. Each is one undo step on the
//! clip's Motion and Opacity.

use filmcraft_engine::{EngineError, Session};
use filmcraft_geom::Vec2;
use filmcraft_project::{ClipId, Interpolation, Param, ParamValue, TrackItem, TrackKind};
use filmcraft_time::Tick;

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

/// A slow move across a picture over the whole clip.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum KenBurns {
    None,
    ZoomIn,
    ZoomOut,
    /// The view moves left across the picture (the picture drifts right).
    PanLeft,
    PanRight,
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

/// Ken Burns: a slow zoom or pan across clip `id` over its whole length; `amount` is how much
/// further it zooms (0.05 to 0.5). Replaces the clip's position and scale keyframes.
pub(crate) fn ken_burns(s: &mut Session, id: u64, kind: KenBurns, amount: f64) -> Result<(), MobileError> {
    let g = geo(s, id)?;
    let a = if amount.is_finite() { amount.clamp(0.05, 0.5) } else { 0.15 };
    edit(s, id, "Ken Burns", |c| {
        let (m0, m1) = (c.effect_time_at(c.start), c.effect_time_at(c.start + c.duration - g.frame));
        let Some(m) = c.effect("motion") else { return Ok(()) };
        let pos = m.param("position").map(|p| pos_at(p, m0, &g)).unwrap_or(Vec2::new(g.fw / 2.0, g.fh / 2.0));
        let cur = m.param("scale").map_or(100.0, |p| p.f64_at(m0)) / 100.0;
        for p in ["position", "scale"] {
            if let Some(prm) = param(c, "motion", p) {
                let v = prm.value_at(m0);
                prm.keyframes.clear();
                prm.value = v;
            }
        }
        // big enough to fill the frame, so no edge shows as it moves
        let cover = (g.fw / g.w.max(1.0)).max(g.fh / g.h.max(1.0));
        let s0 = cur.max(cover);
        let s1 = s0 * (1.0 + a);
        let slack = ((g.w * s1 - g.fw) / 2.0).max(0.0);
        let f = |v: f64| ParamValue::Float(v * 100.0);
        let at = |x: f64| ParamValue::Vec2(Vec2::new(x, pos.y));
        let (sc, ps) = match kind {
            KenBurns::ZoomIn => ((f(s0), f(s1)), None),
            KenBurns::ZoomOut => ((f(s1), f(s0)), None),
            KenBurns::PanLeft => ((f(s1), f(s1)), Some((at(pos.x - slack), at(pos.x + slack)))),
            KenBurns::PanRight => ((f(s1), f(s1)), Some((at(pos.x + slack), at(pos.x - slack)))),
            KenBurns::None => return Ok(()),
        };
        if let Some(prm) = param(c, "motion", "scale") {
            key(prm, m0, sc.0, Interpolation::Linear, 1.0 / 3.0);
            key(prm, m1, sc.1, Interpolation::Linear, 1.0 / 3.0);
        }
        if let (Some((p0, p1)), Some(prm)) = (ps, param(c, "motion", "position")) {
            key(prm, m0, p0, Interpolation::Linear, 1.0 / 3.0);
            key(prm, m1, p1, Interpolation::Linear, 1.0 / 3.0);
        }
        Ok(())
    })
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
