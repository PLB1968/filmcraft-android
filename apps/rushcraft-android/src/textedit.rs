//! Edit by Text: the open sequence's transcript as words to read, tap and delete. Deleting words
//! cuts the sequence there (picture and sound together, every unlocked track) and closes it up;
//! filler words and long pauses go in one step each. The transcript itself is made on the phone
//! (`speech::start_transcript`).

use filmcraft_edit::transcript::{self as tx, SeqWord};
use filmcraft_engine::Session;
use filmcraft_project::ItemKind;
use filmcraft_time::{TICKS_PER_SECOND, Tick, TimeRange};

use crate::{MobileError, model};

/// One word of the transcript.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TextWord {
    pub text: String,
    /// Sequence time.
    pub start: i64,
    pub end: i64,
    /// A filler ("um", "uh"…), shown dimmer.
    pub filler: bool,
}

/// The transcript as Edit by Text shows it.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct TextView {
    pub words: Vec<TextWord>,
    /// The first word of each paragraph (a new one after a pause or a change of speaker).
    pub paragraphs: Vec<u32>,
    /// Each paragraph's speaker, when known.
    pub speakers: Vec<String>,
    /// Sound on the timeline with no transcript yet (new clips since the last transcription).
    pub untranscribed: bool,
}

/// A run of words to delete, `from` to `to` (word indices, inclusive).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct WordRange {
    pub from: u32,
    pub to: u32,
}

/// Words that are filler wherever they are, and words that are filler only on request (they are
/// often real words: "like", "so").
const OPTIONAL_FILLERS: &[&str] = &["like", "you know", "so", "basically", "actually", "i mean", "kind of", "sort of"];

fn words(s: &Session) -> Vec<SeqWord> {
    filmcraft_engine::transcript::sequence_words(s)
}

/// The transcript of the open sequence.
pub(crate) fn view(s: &Session) -> Result<TextView, MobileError> {
    let w = words(s);
    let fillers: Vec<String> = tx::DEFAULT_FILLERS.iter().map(|f| f.to_string()).collect();
    let mut is_filler = vec![false; w.len()];
    for r in tx::find_fillers(&w, &fillers) {
        for i in r {
            if let Some(f) = is_filler.get_mut(i) {
                *f = true;
            }
        }
    }
    let paras = tx::paragraphs(&w, Tick::from_seconds_f64(1.5));
    let (_, seq) = model::sequence(s)?;
    // a clip whose sound has no transcript yet
    let untranscribed = seq.audio_tracks.iter().flat_map(|t| t.items.iter()).filter(|it| it.enabled).any(|it| {
        s.project.resolve_media(it.item).is_some_and(|(item, _, _)| {
            matches!(s.project.item(item).map(|p| &p.kind), Some(ItemKind::Media(m)) if m.info.has_audio()) && !s.project.transcripts.contains_key(&item)
        })
    });
    Ok(TextView {
        words: w.iter().zip(&is_filler).map(|(x, &filler)| TextWord { text: x.text.clone(), start: x.start.0, end: x.end.0, filler }).collect(),
        paragraphs: paras.iter().map(|r| u32::try_from(r.start).unwrap_or(0)).collect(),
        speakers: paras.iter().map(|r| w.get(r.start).and_then(|x| x.speaker.clone()).unwrap_or_default()).collect(),
        untranscribed,
    })
}

/// Cut `ranges` out (one undo step `label`) and close up; returns the seconds removed.
fn cut(s: &mut Session, label: &str, ranges: Vec<TimeRange>) -> Result<f64, MobileError> {
    if ranges.is_empty() {
        return Ok(0.0);
    }
    let total = s.edit_sequence(label, |q, ctx, _| Ok(tx::ripple_delete_ranges(q, ranges, ctx)))?;
    crate::edits::settle(s);
    Ok(total.0 as f64 / TICKS_PER_SECOND as f64)
}

/// Delete the words in `ranges` (frame-snapped outward): the sequence is cut there and closes up.
/// One undo step; returns the seconds removed.
pub(crate) fn delete_words(s: &mut Session, ranges: &[WordRange]) -> Result<f64, MobileError> {
    let w = words(s);
    let rate = s.sequence_rate();
    let mut cuts = Vec::new();
    for r in ranges {
        let (a, b) = (r.from.min(r.to) as usize, r.from.max(r.to) as usize);
        let t = tx::word_range(&w, a, b, rate).ok_or_else(|| MobileError::Command("Those words are no longer in the transcript.".into()))?;
        cuts.push(t);
    }
    cut(s, "Delete Words", cuts)
}

/// Remove every filler word ("um", "uh"…; with `more`, also "like", "you know", "so"…) in one
/// step. Returns how many went.
pub(crate) fn remove_fillers(s: &mut Session, more: bool) -> Result<u32, MobileError> {
    let w = words(s);
    let mut fillers: Vec<String> = tx::DEFAULT_FILLERS.iter().map(|f| f.to_string()).collect();
    if more {
        fillers.extend(OPTIONAL_FILLERS.iter().map(|f| f.to_string()));
    }
    let hits = tx::find_fillers(&w, &fillers);
    let n = u32::try_from(hits.len()).unwrap_or(u32::MAX);
    let ranges = tx::filler_ranges(&w, &hits, s.sequence_rate());
    cut(s, "Remove Filler Words", ranges)?;
    Ok(n)
}

/// Remove every pause between words longer than `min_seconds`, leaving a short breath (0.15 s)
/// on each side. One step; returns how many went.
pub(crate) fn remove_pauses(s: &mut Session, min_seconds: f64) -> Result<u32, MobileError> {
    let w = words(s);
    let min = Tick::from_seconds_f64(min_seconds.clamp(0.2, 10.0));
    let ranges = tx::find_pauses(&w, min, Tick::from_seconds_f64(0.15), s.sequence_rate());
    let n = u32::try_from(ranges.len()).unwrap_or(u32::MAX);
    cut(s, "Remove Pauses", ranges)?;
    Ok(n)
}
