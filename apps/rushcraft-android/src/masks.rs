//! Pro mode's Masks panel: blur a face or an area, show only an area, or limit an effect to an
//! area, with an ellipse or rectangle moved and sized on the Program monitor (and tracked); and
//! green screen (Ultra Key).

use filmcraft_engine::Session;
use filmcraft_geom::{Affine, Vec2};
use filmcraft_project::{ClipId, MaskPath, TrackItem, TrackKind};
use serde_json::{Value, json};

use crate::edits::{self, ex};
use crate::placement::motion_time;
use crate::{MobileError, model};

/// What a new mask does.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MaskPurpose {
    /// Blur what's inside (a face, a number plate).
    Blur,
    /// Show only what's inside (the rest of the clip is see-through).
    ShowOnly,
    /// Limit one of the clip's effects to what's inside.
    Effect,
}

/// A mask's shape.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MaskShape {
    Ellipse,
    Rectangle,
}

/// A mask on a clip, for the Masks panel and the Program monitor.
#[derive(Debug, Clone, uniffi::Record)]
pub struct MaskInfo {
    /// The effect it is on (its index in Effect Controls) and its place among that effect's masks.
    pub effect: u32,
    pub mask: u32,
    /// "Blur", "Show only" or the effect's name.
    pub label: String,
    pub inverted: bool,
    /// Clip pixels.
    pub feather: f64,
    pub expansion: f64,
    /// Its outline on the frame at the playhead (x, y pairs, frame pixels).
    pub outline: Vec<f64>,
    /// Its middle on the frame.
    pub x: f64,
    pub y: f64,
    /// Whether its path has keyframes (tracked or moved over time).
    pub animated: bool,
}

fn clip_of(seq: &filmcraft_project::Sequence, clip: u64) -> Result<&TrackItem, MobileError> {
    model::find(seq, ClipId(clip))
        .filter(|f| f.kind == TrackKind::Video)
        .map(|f| f.clip)
        .ok_or_else(|| MobileError::Command("Pick a clip with a picture.".into()))
}

/// Clip `c`'s source pixels → frame pixels, at the playhead.
fn to_frame(s: &Session, seq: &filmcraft_project::Sequence, c: &TrackItem) -> (Affine, (u32, u32)) {
    let src = s.project.source_size(c.item).map(|(w, h)| (w.max(1), h.max(1))).unwrap_or((seq.settings.width.max(1), seq.settings.height.max(1)));
    (filmcraft_render::motion_matrix(seq, c, src, None, motion_time(s, c)), src)
}

/// Add a `shape` mask that does `purpose` to clip `clip` (`effect`: the effect it limits, for
/// [`MaskPurpose::Effect`]), in the middle of the picture. Returns its effect and mask index.
pub(crate) fn add(s: &mut Session, clip: u64, purpose: MaskPurpose, shape: MaskShape, effect: Option<u32>) -> Result<(u32, u32), MobileError> {
    let (w, h) = {
        let (_, seq) = model::sequence(s)?;
        let c = clip_of(seq, clip)?;
        let (_, src) = to_frame(s, seq, c);
        (f64::from(src.0), f64::from(src.1))
    };
    let label = match purpose {
        MaskPurpose::Blur => "Blur Area",
        MaskPurpose::ShowOnly => "Show Only Area",
        MaskPurpose::Effect => "Mask Effect",
    };
    let r = s.edit_group(label, |s| {
        let target: Value = match purpose {
            MaskPurpose::Blur => {
                // a strong blur of its own, limited to the mask
                s.execute("effects.apply", json!({ "clips": [clip], "effect": "gaussian_blur" }))?;
                let ei = s
                    .active_sequence()
                    .and_then(|q| q.find_item(ClipId(clip)))
                    .and_then(|(_, it)| it.effects.iter().rposition(|e| e.effect == "gaussian_blur"))
                    .unwrap_or(0);
                s.execute("effects.setParam", json!({ "clip": clip, "effect": ei, "param": "blurriness", "value": 60.0 }))?;
                json!(ei)
            }
            MaskPurpose::ShowOnly => json!("opacity"),
            MaskPurpose::Effect => json!(effect.ok_or_else(|| filmcraft_engine::EngineError::Other("Pick the effect to mask.".into()))?),
        };
        let side = w.min(h) * 0.4;
        let size = match shape {
            MaskShape::Ellipse => [side, side * 1.25],
            MaskShape::Rectangle => [side * 1.4, side],
        };
        let shape = match shape {
            MaskShape::Ellipse => "ellipse",
            MaskShape::Rectangle => "rectangle",
        };
        let v = s.execute("masks.add", json!({ "clip": clip, "effect": target, "shape": shape, "center": [w / 2.0, h / 2.0], "size": size }))?;
        let (ei, mi) = (v["effect"].as_u64().unwrap_or(0), v["mask"].as_u64().unwrap_or(0));
        // a soft edge: a blurred face blends in, a shown area fades out
        s.execute("masks.set", json!({ "clip": clip, "effect": ei, "mask": mi, "feather": side * 0.08 }))?;
        Ok((ei, mi))
    })?;
    edits::settle(s);
    Ok((u32::try_from(r.0).unwrap_or(0), u32::try_from(r.1).unwrap_or(0)))
}

/// Points along `p`'s outline (its cubic segments sampled), in clip pixels.
fn outline_of(p: &MaskPath) -> Vec<Vec2> {
    let n = p.vertices.len();
    let mut out = Vec::new();
    let segs = if p.closed { n } else { n.saturating_sub(1) };
    for i in 0..segs {
        let a = &p.vertices[i];
        let b = &p.vertices[(i + 1) % n];
        let (p0, p1, p2, p3) = (a.p, a.p + a.t_out, b.p + b.t_in, b.p);
        let straight = a.t_out == Vec2::ZERO && b.t_in == Vec2::ZERO;
        let steps = if straight { 1 } else { 12 };
        for k in 0..steps {
            let t = k as f64 / steps as f64;
            let u = 1.0 - t;
            out.push(p0 * (u * u * u) + p1 * (3.0 * u * u * t) + p2 * (3.0 * u * t * t) + p3 * (t * t * t));
        }
    }
    out
}

/// The masks on clip `clip`, as the Program monitor draws them.
pub(crate) fn list(s: &Session, clip: u64) -> Result<Vec<MaskInfo>, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let Ok(c) = clip_of(seq, clip) else { return Ok(Vec::new()) };
    let (m, _) = to_frame(s, seq, c);
    let mt = motion_time(s, c);
    let mut out = Vec::new();
    for (ei, e) in c.effects.iter().enumerate() {
        for (mi, mk) in e.masks.iter().enumerate() {
            let path = mk.path_at(mt);
            let pts: Vec<Vec2> = outline_of(&path).into_iter().map(|p| m.apply(p)).collect();
            let n = pts.len().max(1) as f64;
            let (x, y) = pts.iter().fold((0.0, 0.0), |(x, y), p| (x + p.x / n, y + p.y / n));
            let label = match e.effect.as_str() {
                "opacity" => "Show only".to_string(),
                "gaussian_blur" => "Blur".to_string(),
                _ => e.def().map_or_else(|| e.effect.clone(), |d| d.name.to_string()),
            };
            out.push(MaskInfo {
                effect: u32::try_from(ei).unwrap_or(0),
                mask: u32::try_from(mi).unwrap_or(0),
                label,
                inverted: mk.inverted,
                feather: mk.feather.f64_at(mt),
                expansion: mk.expansion.f64_at(mt),
                outline: pts.iter().flat_map(|p| [p.x, p.y]).collect(),
                x,
                y,
                animated: mk.path.is_animated(),
            });
        }
    }
    Ok(out)
}

/// Move mask `mask` of effect `effect` on clip `clip` by (`dx`, `dy`) frame pixels and scale it
/// by `scale` about its middle (at the playhead: a keyframe there when its path is animated).
/// `merge` folds a gesture into one undo step.
#[allow(clippy::too_many_arguments)]
pub(crate) fn transform(s: &mut Session, clip: u64, effect: u32, mask: u32, dx: f64, dy: f64, scale: f64, merge: bool) -> Result<(), MobileError> {
    if ![dx, dy, scale].iter().all(|v| v.is_finite()) || scale <= 0.0 {
        return Err(MobileError::Command("The mask's move must be numbers.".into()));
    }
    let path = {
        let (_, seq) = model::sequence(s)?;
        let c = clip_of(seq, clip)?;
        let (m, _) = to_frame(s, seq, c);
        let mk = c.effects.get(effect as usize).and_then(|e| e.masks.get(mask as usize)).ok_or_else(|| MobileError::Command("That mask is gone.".into()))?;
        let mut path = mk.path_at(motion_time(s, c));
        // a frame-pixel move in clip pixels (Motion's scale and rotation undone)
        let inv = m.inverse().unwrap_or(Affine::IDENTITY);
        let d = inv.apply(Vec2::new(dx, dy)) - inv.apply(Vec2::ZERO);
        let n = path.vertices.len().max(1) as f64;
        let mid = path.vertices.iter().fold(Vec2::ZERO, |a, v| a + v.p * (1.0 / n));
        for v in &mut path.vertices {
            v.p = mid + (v.p - mid) * scale + d;
            v.t_in = v.t_in * scale;
            v.t_out = v.t_out * scale;
        }
        path
    };
    let mut q = json!({ "clip": clip, "effect": effect, "mask": mask, "path": filmcraft_engine::masks::path_to_json(&path) });
    if merge {
        q["merge"] = json!(format!("mask-move:{clip}:{effect}:{mask}"));
    }
    ex(s, "masks.set", q)?;
    Ok(())
}

/// Change a mask's soft edge (`feather`, clip pixels), growth (`expansion`) and whether it works
/// outside instead (`inverted`).
pub(crate) fn set(s: &mut Session, clip: u64, effect: u32, mask: u32, feather: f64, expansion: f64, inverted: bool, merge: bool) -> Result<(), MobileError> {
    let mut q = json!({ "clip": clip, "effect": effect, "mask": mask, "feather": feather.max(0.0), "expansion": expansion, "inverted": inverted });
    if merge {
        q["merge"] = json!(format!("mask-set:{clip}:{effect}:{mask}"));
    }
    ex(s, "masks.set", q)?;
    Ok(())
}

/// Take a mask off (a Blur mask takes its blur with it).
pub(crate) fn remove(s: &mut Session, clip: u64, effect: u32, mask: u32) -> Result<(), MobileError> {
    let blur_alone = {
        let (_, seq) = model::sequence(s)?;
        let c = clip_of(seq, clip)?;
        c.effects.get(effect as usize).is_some_and(|e| e.effect == "gaussian_blur" && e.masks.len() == 1)
    };
    s.edit_group("Delete Mask", |s| {
        s.execute("masks.remove", json!({ "clip": clip, "effect": effect, "mask": mask }))?;
        if blur_alone {
            s.execute("effects.remove", json!({ "clip": clip, "index": effect }))?;
        }
        Ok(())
    })?;
    edits::settle(s);
    Ok(())
}

/// Follow what's under the mask from the playhead to the clip's end (in the background):
/// returns the job to watch; the tracked path is keyframed as it goes.
pub(crate) fn track(s: &mut Session, clip: u64, effect: u32, mask: u32) -> Result<u64, MobileError> {
    let v = ex(s, "masks.track", json!({ "clip": clip, "effect": effect, "mask": mask, "direction": "forward" }))?;
    v["job"].as_u64().ok_or_else(|| MobileError::Command("Tracking didn't start.".into()))
}

/// Green screen: key out `color` (ARGB; a green or blue backdrop) on clip `clip` with Ultra Key,
/// or take the key off (`None`). `strength` 0..1 is how much is keyed (Ultra Key's tolerance).
pub(crate) fn green_screen(s: &mut Session, clip: u64, color: Option<u32>, strength: f64) -> Result<(), MobileError> {
    let at = {
        let (_, seq) = model::sequence(s)?;
        let c = clip_of(seq, clip)?;
        c.effects.iter().position(|e| e.effect == "ultra_key")
    };
    let Some(argb) = color else {
        if let Some(i) = at {
            ex(s, "effects.remove", json!({ "clip": clip, "index": i }))?;
            edits::settle(s);
        }
        return Ok(());
    };
    let ch = |sh: u32| f64::from((argb >> sh) & 0xff) / 255.0;
    let rgba = [ch(16), ch(8), ch(0), 1.0];
    s.edit_group("Green Screen", |s| {
        let i = match at {
            Some(i) => i,
            None => {
                s.execute("effects.apply", json!({ "clips": [clip], "effect": "ultra_key" }))?;
                s.active_sequence().and_then(|q| q.find_item(ClipId(clip))).and_then(|(_, it)| it.effects.iter().position(|e| e.effect == "ultra_key")).unwrap_or(0)
            }
        };
        s.execute("effects.setParam", json!({ "clip": clip, "effect": i, "param": "key_color", "value": rgba }))?;
        s.execute("effects.setParam", json!({ "clip": clip, "effect": i, "param": "tolerance", "value": (strength.clamp(0.0, 1.0) * 100.0) }))?;
        Ok(())
    })?;
    edits::settle(s);
    Ok(())
}

/// Clip `clip`'s green screen colour (ARGB) and strength, if it has one.
pub(crate) fn green_screen_of(s: &Session, clip: u64) -> Option<(u32, f64)> {
    let (_, seq) = model::sequence(s).ok()?;
    let c = clip_of(seq, clip).ok()?;
    let e = c.effect("ultra_key")?;
    let col = match e.param("key_color").map(|p| &p.value) {
        Some(filmcraft_project::ParamValue::Color(c)) => *c,
        _ => [0.0, 0.8, 0.2, 1.0],
    };
    let b = |x: f32| (x.clamp(0.0, 1.0) * 255.0).round() as u32;
    Some((0xff00_0000 | (b(col[0]) << 16) | (b(col[1]) << 8) | b(col[2]), e.f64_at("tolerance", c.source_in) / 100.0))
}
