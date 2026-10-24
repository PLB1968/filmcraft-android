//! Render previews ("Render effects"): the parts of the sequence whose effects are too heavy to
//! draw live are rendered once to ProRes files (the engine's
//! [`filmcraft_engine::previews`]); playback then shows those instead ([`crate::render`]). An
//! edit changes only the parts it touches; they are rendered again.

use filmcraft_engine::Session;
use filmcraft_engine::previews::BarState;
use filmcraft_project::{ItemId, Project};
use filmcraft_render::preview::{Need, Segment, effect_cost_ms};
use serde_json::json;

use crate::MobileError;
use crate::edits::ex;

/// A stretch of the render bar above the timeline.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RenderSpan {
    pub start: i64,
    pub end: i64,
    /// "green" (rendered), "yellow" (may play live) or "red" (needs rendering to play smoothly).
    pub state: String,
}

/// Whether segment `g` of sequence `seq` is worth a rendered preview: the engine thinks it would
/// drop frames (red) because of expensive effects or masks on a clip it shows. Plain clips,
/// cheap effects, titles and crossfades play live from their proxies with the phone's decoder,
/// which beats decoding a full-size preview in software, so they are never rendered: the
/// engine's estimate counts a 4K original's decoding, not its proxy's.
pub(crate) fn heavy(project: &Project, seq: ItemId, g: &Segment) -> bool {
    if g.need != Need::Render {
        return false;
    }
    let Some(q) = project.sequence(seq) else { return false };
    g.clips.iter().filter_map(|&c| q.find_item(c).map(|(_, it)| it)).any(|it| {
        it.has_opacity_masks()
            || it
                .effects
                .iter()
                .any(|e| e.enabled && e.def().is_some_and(|d| !d.intrinsic) && !filmcraft_project::graphic::is_layer(e) && effect_cost_ms(&e.effect) >= HEAVY_MS)
    })
}

/// An effect at least this costly (the engine's estimate, per HD frame) is worth rendering.
const HEAVY_MS: f64 = 20.0;

/// The render bar: red over stretches to render, green over rendered ones, yellow over effects
/// that play live.
pub(crate) fn render_bar(s: &Session) -> Vec<RenderSpan> {
    let Some(seq) = s.state.active_sequence else { return Vec::new() };
    let mut out: Vec<RenderSpan> = Vec::new();
    for g in s.previews.segments(&s.project, seq).iter() {
        if g.need == Need::None {
            continue;
        }
        let state = if heavy(&s.project, seq, g) {
            match s.previews.state_of(g) {
                BarState::Green => "green",
                _ => "red",
            }
        } else {
            "yellow"
        };
        let (start, end) = (g.start.0, g.end.0);
        match out.last_mut() {
            Some(l) if l.end == start && l.state == state => l.end = end,
            _ => out.push(RenderSpan { start, end, state: state.to_string() }),
        }
    }
    out
}

/// Start rendering the stretches that need it (red). Returns the job to watch, or nothing when
/// there is nothing to render.
pub(crate) fn start(s: &mut Session) -> Result<Option<u64>, MobileError> {
    let Some(seq) = s.state.active_sequence else { return Ok(None) };
    let segs = s.previews.segments(&s.project, seq);
    let todo: Vec<&Segment> = segs.iter().filter(|g| heavy(&s.project, seq, g) && s.previews.state_of(g) != BarState::Green).collect();
    if todo.is_empty() {
        return Ok(None);
    }
    // Render Selection over the clips those stretches show, the selection put back after
    let mut clips: Vec<u64> = todo.iter().flat_map(|g| g.clips.iter().map(|c| c.0)).collect();
    clips.sort_unstable();
    clips.dedup();
    let was = s.state.selection.clone();
    let _ = ex(s, "timeline.select", json!({ "clips": clips }));
    let r = ex(s, "sequence.renderSelection", json!({}));
    s.state.selection = was;
    Ok(r?["job"].as_u64())
}
