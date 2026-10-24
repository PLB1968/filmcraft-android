//! Render previews ("Render effects"): the parts of the sequence whose effects, titles and
//! transitions are too heavy to draw live are rendered once to ProRes files (the engine's
//! [`filmcraft_engine::previews`]); playback then shows those instead ([`crate::render`]). An
//! edit changes only the parts it touches; they are rendered again.

use filmcraft_engine::Session;
use serde_json::{Value, json};

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

/// The render bar: the stretches with effects to render, and those already rendered.
pub(crate) fn render_bar(s: &Session) -> Vec<RenderSpan> {
    let Ok(v) = ex_query(s) else { return Vec::new() };
    let mut out: Vec<RenderSpan> = Vec::new();
    for g in v["segments"].as_array().into_iter().flatten() {
        let state = g["state"].as_str().unwrap_or("none");
        if state == "none" {
            continue;
        }
        let (start, end) = (g["start"].as_i64().unwrap_or(0), g["end"].as_i64().unwrap_or(0));
        match out.last_mut() {
            Some(l) if l.end == start && l.state == state => l.end = end,
            _ => out.push(RenderSpan { start, end, state: state.to_string() }),
        }
    }
    out
}

fn ex_query(s: &Session) -> Result<Value, MobileError> {
    filmcraft_engine::previews::bar_json(s).map_err(MobileError::from)
}

/// Start rendering every stretch that isn't rendered (within In/Out when they are set). Returns
/// the job to watch, or nothing when there is nothing to render.
pub(crate) fn start(s: &mut Session) -> Result<Option<u64>, MobileError> {
    if !render_bar(s).iter().any(|r| r.state != "green") {
        return Ok(None);
    }
    let v = ex(s, "sequence.renderEffectsInToOut", json!({}))?;
    Ok(v["job"].as_u64())
}
