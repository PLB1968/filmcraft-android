//! Where a picture, video or text sits on the frame, and moving, scaling and turning it with a
//! finger on the preview (one undo step per gesture).

use filmcraft_engine::{EngineError, Session};
use filmcraft_geom::Vec2;
use filmcraft_project::graphic::LayerContent;
use filmcraft_project::{ClipId, ItemKind, ParamValue, TrackItem, TrackKind};
use filmcraft_render::graphic_clip;
use filmcraft_time::Tick;

use crate::text::Placement;
use crate::{MobileError, edits, model};

/// How small and large an item may get, as a fraction / multiple of the frame's width.
const MIN_WIDTH: f64 = 0.01;
const MAX_WIDTH: f64 = 5.0;
const MAX_TEXT_WIDTH: f64 = 2.0;

fn is_graphic(s: &Session, c: &TrackItem) -> bool {
    s.project.item(c.item).is_some_and(|p| matches!(p.kind, ItemKind::Graphic { .. }))
}

/// The media time clip `c`'s motion is read and keyed at: the playhead's, held inside the clip.
pub(crate) fn motion_time(s: &Session, c: &TrackItem) -> Tick {
    let last = (c.end() - Tick(1)).max(c.start);
    c.effect_time_at(s.playhead().clamp(c.start, last))
}

/// What a picture shows, in its own pixels (left, top, right, bottom): a graphic's layers (text
/// by its letters, not the box it wraps in), else the whole picture.
pub(crate) fn content(s: &Session, c: &TrackItem, src: (u32, u32), mt: Tick) -> [f64; 4] {
    let whole = [0.0, 0.0, f64::from(src.0), f64::from(src.1)];
    if !is_graphic(s, c) {
        return whole;
    }
    let mut b = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
    let mut grow = |p: Vec2| {
        if p.x.is_finite() && p.y.is_finite() {
            b = [b[0].min(p.x), b[1].min(p.y), b[2].max(p.x), b[3].max(p.y)];
        }
    };
    for (_, spec) in graphic_clip::item_layer_specs(c, mt, src) {
        match &spec.content {
            LayerContent::Text(t) if !t.vertical => {
                let m = graphic_clip::layer_matrix(&spec);
                for l in graphic_clip::text_layout(t).lines.iter().filter(|l| l.width > 0.0) {
                    let (x0, x1) = (f64::from(l.x), f64::from(l.x + l.width));
                    let (y0, y1) = (f64::from(l.baseline - l.ascent), f64::from(l.baseline + l.descent));
                    for p in [(x0, y0), (x1, y0), (x1, y1), (x0, y1)] {
                        grow(m.apply(Vec2::new(p.0, p.1)));
                    }
                }
            }
            _ => {
                let r = graphic_clip::layer_bounds(&spec);
                grow(Vec2::new(r[0], r[1]));
                grow(Vec2::new(r[2], r[3]));
            }
        }
    }
    if b[2] > b[0] && b[3] > b[1] { b } else { whole }
}

/// Where clip `id` sits on the frame at the playhead: its Motion (position, scale, rotation) and
/// the outline of what it shows.
pub(crate) fn placement(s: &mut Session, id: u64) -> Result<Placement, MobileError> {
    let (_, seq) = model::sequence(s)?;
    let (fw, fh) = (f64::from(seq.settings.width), f64::from(seq.settings.height));
    let f = model::find(seq, ClipId(id)).filter(|f| f.kind == TrackKind::Video).ok_or_else(|| MobileError::Command("That clip has no picture.".into()))?;
    let c = f.clip;
    let src = s.project.source_size(c.item).map(|(w, h)| (w.max(1), h.max(1))).unwrap_or((seq.settings.width.max(1), seq.settings.height.max(1)));
    let mt = motion_time(s, c);
    let (mut x, mut y, mut scale, mut rotation) = (fw / 2.0, fh / 2.0, 1.0, 0.0);
    if let Some(m) = c.effects.iter().find(|e| e.effect == "motion" && e.enabled) {
        let p = m.vec2_at("position", mt);
        if !p.x.is_nan() && !p.y.is_nan() {
            (x, y) = (p.x, p.y);
        }
        scale = m.f64_at("scale", mt) / 100.0;
        rotation = m.f64_at("rotation", mt);
    }
    let (sw, sh) = (f64::from(src.0), f64::from(src.1));
    let fit = if c.scale_to_frame { (fw / sw).min(fh / sh) } else { 1.0 };
    let b = content(s, c, src, mt);
    let m = filmcraft_render::motion_matrix(seq, c, src, None, mt);
    let corners = [(b[0], b[1]), (b[2], b[1]), (b[2], b[3]), (b[0], b[3])]
        .iter()
        .flat_map(|&(px, py)| {
            let q = m.apply(Vec2::new(px, py));
            [q.x, q.y]
        })
        .collect();
    Ok(Placement { x, y, width: (b[2] - b[0]) * fit, height: (b[3] - b[1]) * fit, scale, rotation, corners })
}

/// The upright rectangle (left, top, right, bottom; frame pixels, clamped to the frame) a picture
/// clip covers, ignoring rotation: what the look pickers crop their previews to.
pub(crate) fn clip_rect(project: &filmcraft_project::Project, seq: &filmcraft_project::Sequence, id: u64) -> Option<(f64, f64, f64, f64)> {
    let (fw, fh) = (f64::from(seq.settings.width), f64::from(seq.settings.height));
    let f = model::find(seq, ClipId(id)).filter(|f| f.kind == TrackKind::Video)?;
    let c = f.clip;
    let (sw, sh) = project.source_size(c.item).map(|(w, h)| (f64::from(w.max(1)), f64::from(h.max(1))))?;
    let mt = c.source_in;
    let (mut x, mut y, mut scale) = (fw / 2.0, fh / 2.0, 1.0);
    if let Some(m) = c.effects.iter().find(|e| e.effect == "motion" && e.enabled) {
        let p = m.vec2_at("position", mt);
        if !p.x.is_nan() && !p.y.is_nan() {
            (x, y) = (p.x, p.y);
        }
        scale = m.f64_at("scale", mt) / 100.0;
    }
    let fit = if c.scale_to_frame { (fw / sw).min(fh / sh) } else { 1.0 };
    let (w, h) = (sw * fit * scale, sh * fit * scale);
    let r = ((x - w / 2.0).max(0.0), (y - h / 2.0).max(0.0), (x + w / 2.0).min(fw), (y + h / 2.0).min(fh));
    (r.2 - r.0 >= 1.0 && r.3 - r.1 >= 1.0).then_some(r)
}

/// Move, scale and turn clip `id`: Motion's position at (`x`, `y`) frame pixels, `scale` × its
/// placed size, `rotation` degrees. Animated motion gets a keyframe at the playhead. `merge` folds
/// a gesture into one undo step.
pub(crate) fn set_placement(s: &mut Session, id: u64, x: f64, y: f64, scale: f64, rotation: f64, merge: bool) -> Result<(), MobileError> {
    if ![x, y, scale, rotation].iter().all(|v| v.is_finite()) {
        return Err(MobileError::Command("The placement must be numbers.".into()));
    }
    let now = placement(s, id)?;
    let (_, seq) = model::sequence(s)?;
    let fw = f64::from(seq.settings.width.max(1));
    let c = model::find(seq, ClipId(id)).map(|f| f.clip).ok_or_else(|| MobileError::Command("That clip is no longer on the timeline.".into()))?;
    let max = if is_graphic(s, c) { MAX_TEXT_WIDTH } else { MAX_WIDTH };
    let mt = motion_time(s, c);
    let base = now.width.max(1.0);
    let scale = scale.clamp(MIN_WIDTH * fw / base, max * fw / base);
    let key = format!("place:{id}");
    s.edit_sequence_as("Transform", merge.then_some(key.as_str()), |q, _, _| {
        let c = q.find_item_mut(ClipId(id)).map(|x| x.1).ok_or_else(|| EngineError::Other("clip gone".into()))?;
        let m = c.effects.iter_mut().find(|e| e.effect == "motion").ok_or_else(|| EngineError::Other("no motion".into()))?;
        let mut put = |p: &str, v: ParamValue| {
            if let Some(param) = m.param_mut(p) {
                param.set_at(mt, v);
            }
        };
        put("position", ParamValue::Vec2(Vec2::new(x, y)));
        put("scale", ParamValue::Float(scale * 100.0));
        put("rotation", ParamValue::Float(rotation));
        m.enabled = true;
        Ok(())
    })?;
    edits::settle(s);
    Ok(())
}
