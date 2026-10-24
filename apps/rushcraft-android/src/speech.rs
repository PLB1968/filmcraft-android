//! Captions made from what is said, and speech made from typed text, both on the phone.
//!
//! Captions: the sound of the clips is transcribed by Whisper ([`filmcraft_speech`]) on a
//! background thread, with progress and a way to stop (a long video takes minutes). Each media
//! file's transcript is kept in the project, so making captions again after an edit only listens
//! to media it hasn't heard. The words then become caption blocks on one caption track, sized for
//! the frame.
//!
//! Speech: the engine's narration commands (`tts.*`) with the built-in voices or the natural
//! voices (Kokoro-82M).
//!
//! The models are files the app downloads after asking ([`speech_models`], [`voice_package`]); they
//! go in `<data dir>/models` and are never part of the app.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use filmcraft_engine::Session;
use filmcraft_media::SharedSource;
use std::collections::BTreeMap;

use filmcraft_edit::transcript::{self as tx, CaptionRules, SeqWord};
use filmcraft_project::{CaptionFormat, CaptionStyle, CaptionTrack, ClipId, ItemId, ItemKind, Sequence, TrackId, TrackKind, Transcript};
use filmcraft_speech::{Options, SpeechError, Transcriber};
use filmcraft_time::{TICKS_PER_SECOND, Tick, TimeRange};
use serde_json::{Value, json};

use crate::edits::{self, ex};
use crate::{MobileError, model};

/// The speech models the phone offers: small enough to download and quick enough to run.
const PHONE_MODELS: [&str; 2] = ["whisper-tiny", "whisper-base"];

/// Seconds of sound read at a time while getting a clip ready to transcribe.
const READ_SECONDS: i64 = 30;

/// The dark edge around captions shown without their box (pixels at 1080 lines).
const CAPTION_OUTLINE: f32 = 3.0;

/// One file of a model.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ModelFile {
    pub name: String,
    pub url: String,
    pub sha256: String,
    pub size: u64,
}

/// A model the app can download: a speech model for captions, or the natural voices.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ModelPackage {
    pub id: String,
    pub name: String,
    pub description: String,
    /// Bytes of all its files.
    pub size: u64,
    /// Bytes not downloaded yet.
    pub missing: u64,
    pub installed: bool,
    pub license: String,
    pub source: String,
    /// The folder its files go in.
    pub dir: String,
    pub files: Vec<ModelFile>,
}

fn models_dir() -> Option<PathBuf> {
    filmcraft_engine::transcript::models_dir()
}

/// The speech models for captions, smallest first.
#[uniffi::export]
pub fn speech_models() -> Vec<ModelPackage> {
    use filmcraft_speech::models;
    let dir = models_dir();
    let d = dir.as_deref();
    PHONE_MODELS
        .iter()
        .filter_map(|id| models::find(id))
        .map(|m| ModelPackage {
            id: m.id.into(),
            name: m.name.into(),
            description: m.description.into(),
            size: m.size(),
            missing: d.map_or(m.size(), |d| models::missing_bytes(d, m)),
            installed: d.is_some_and(|d| models::installed(d, m)),
            license: m.license.into(),
            source: m.source.into(),
            dir: d.map(|d| models::model_dir(d, m).to_string_lossy().into_owned()).unwrap_or_default(),
            files: m.files.iter().map(|f| ModelFile { name: f.name.into(), url: f.url.into(), sha256: f.sha256.into(), size: f.size }).collect(),
        })
        .collect()
}

/// The natural voices for speech.
#[uniffi::export]
pub fn voice_package() -> ModelPackage {
    use filmcraft_tts::catalog;
    let dir = models_dir();
    let d = dir.as_deref();
    ModelPackage {
        id: catalog::PACKAGE_ID.into(),
        name: catalog::PACKAGE_NAME.into(),
        description: "Natural-sounding English voices.".into(),
        size: catalog::size(),
        missing: d.map_or(catalog::size(), catalog::missing_bytes),
        installed: d.is_some_and(catalog::installed),
        license: catalog::LICENSE.into(),
        source: catalog::SOURCE.into(),
        dir: d.map(|d| catalog::package_dir(d).to_string_lossy().into_owned()).unwrap_or_default(),
        files: catalog::FILES.iter().map(|f| ModelFile { name: f.name.into(), url: f.url.into(), sha256: f.sha256.into(), size: f.size }).collect(),
    }
}

/// A voice for speech.
#[derive(Debug, Clone, uniffi::Record)]
pub struct VoiceInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    pub female: bool,
    /// One of the natural voices (downloaded), not a built-in one.
    pub natural: bool,
    /// Usable now.
    pub installed: bool,
}

/// Every voice, built-in ones first.
#[uniffi::export]
pub fn voices() -> Vec<VoiceInfo> {
    filmcraft_tts::voices_in(models_dir().as_deref())
        .into_iter()
        .map(|v| VoiceInfo {
            id: v.id.into(),
            name: v.name.into(),
            description: v.description.into(),
            female: v.gender == filmcraft_tts::Gender::Female,
            natural: v.engine == "neural",
            installed: v.installed,
        })
        .collect()
}

/// What to say and how.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SpeechSettings {
    pub text: String,
    pub voice: String,
    /// Speaking rate, 0.5 to 2 (1 is normal).
    pub pace: f64,
    /// −2 (extra low) to 2 (extra high); 0 is the voice's own.
    pub pitch: i32,
}

/// Synthesized speech: mono 32-bit float samples, little-endian.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SpeechAudio {
    pub sample_rate: u32,
    pub pcm: Vec<u8>,
}

const PITCHES: [&str; 5] = ["extraLow", "low", "default", "high", "extraHigh"];

fn speech_params(st: &SpeechSettings) -> Value {
    let pitch = PITCHES.get(usize::try_from(st.pitch.clamp(-2, 2) + 2).unwrap_or(2)).copied().unwrap_or("default");
    let pace = if st.pace.is_finite() { st.pace.clamp(filmcraft_tts::MIN_PACE, filmcraft_tts::MAX_PACE) } else { 1.0 };
    json!({"text": st.text, "voice": st.voice, "pace": pace, "pitch": pitch})
}

/// Synthesize (or take from the engine's cache) and return the speech's length.
fn synthesize(s: &mut Session, p: &Value) -> Result<Tick, MobileError> {
    let v = ex(s, "tts.preview", p.clone())?;
    let secs = v["seconds"].as_f64().filter(|x| x.is_finite() && *x > 0.0).ok_or_else(|| MobileError::Command("There is nothing to say.".into()))?;
    Ok(Tick::from_seconds_f64(secs))
}

/// Hear `st` without adding it.
pub(crate) fn preview(s: &mut Session, st: &SpeechSettings) -> Result<SpeechAudio, MobileError> {
    synthesize(s, &speech_params(st))?;
    let a = s.tts_preview.clone().ok_or_else(|| MobileError::Command("There is nothing to say.".into()))?;
    let mut pcm = Vec::new();
    pcm.try_reserve(a.samples.len().saturating_mul(4)).map_err(|e| MobileError::Command(format!("The speech is too long: {e}")))?;
    for x in &a.samples {
        pcm.extend_from_slice(&x.to_le_bytes());
    }
    Ok(SpeechAudio { sample_rate: a.sample_rate, pcm })
}

/// Put speech at the playhead on the lowest free audio lane; its files go in `dir`. Returns the
/// new clip (selected).
pub(crate) fn add(s: &mut Session, st: &SpeechSettings, dir: &str) -> Result<u64, MobileError> {
    let p = speech_params(st);
    let len = synthesize(s, &p)?;
    let at = s.playhead();
    place(s, "Text to Speech", p, at, len, None, dir)
}

fn place(s: &mut Session, label: &str, mut p: Value, at: Tick, len: Tick, want: Option<usize>, dir: &str) -> Result<u64, MobileError> {
    let r = s.edit_group(label, |s| {
        let lane = edits::free_lane_index(s, TrackKind::Audio, 1, want, at, at + len, None).map_err(|e| filmcraft_engine::EngineError::Other(e.to_string()))?;
        p["track"] = json!(format!("A{}", lane + 1));
        p["time"] = json!(at.0);
        p["dir"] = json!(dir);
        s.execute("tts.create", p)
    })?;
    r["clip"].as_u64().ok_or_else(|| MobileError::Command("The speech wasn't added.".into()))
}

/// The settings a speech clip was made with (`None` for any other clip).
pub(crate) fn settings_of(s: &Session, clip: u64) -> Option<SpeechSettings> {
    let (_, seq) = model::sequence(s).ok()?;
    let f = model::find(seq, ClipId(clip))?;
    let n = s.project.narrations.get(&f.clip.item)?;
    let pitch = PITCHES.iter().position(|p| *p == n.pitch.id()).and_then(|i| i32::try_from(i).ok()).map_or(0, |i| i - 2);
    Some(SpeechSettings { text: n.text.clone(), voice: n.voice.clone(), pace: n.pace, pitch })
}

/// Say something else, or in another voice: the clip is made again where it was (its lane if it
/// still fits there), as long as the new speech.
pub(crate) fn edit(s: &mut Session, clip: u64, st: &SpeechSettings, dir: &str) -> Result<u64, MobileError> {
    let (start, track) = {
        let (_, seq) = model::sequence(s)?;
        let f = model::find(seq, ClipId(clip)).filter(|f| f.kind == TrackKind::Audio).ok_or_else(|| MobileError::Command("That clip is gone.".into()))?;
        (f.clip.start, f.track)
    };
    let p = speech_params(st);
    let len = synthesize(s, &p)?;
    let r = s.edit_group("Edit Speech", |s| {
        s.execute("edit.clear", json!({"clips": [clip]}))?;
        let lane = edits::free_lane_index(s, TrackKind::Audio, 1, Some(track), start, start + len, None)
            .map_err(|e| filmcraft_engine::EngineError::Other(e.to_string()))?;
        let mut p = p;
        p["track"] = json!(format!("A{}", lane + 1));
        p["time"] = json!(start.0);
        p["dir"] = json!(dir);
        s.execute("tts.create", p)
    })?;
    r["clip"].as_u64().ok_or_else(|| MobileError::Command("The speech wasn't changed.".into()))
}

// ---- captions ----

/// How making captions is going.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CaptionStatus {
    /// 0 to 1.
    pub progress: f64,
    /// What it is doing, for the user.
    pub stage: String,
    pub finished: bool,
    /// Why it stopped early ("cancelled" when asked to).
    pub error: Option<String>,
    /// Captions made (0: nobody speaks).
    pub captions: u32,
}

/// One media file to listen to.
struct Work {
    item: ItemId,
    source: SharedSource,
    samples: i64,
}

/// A caption job: a thread transcribing, then its result folded into the project on the next
/// [`status`] call (on the caller's thread, which holds the session).
pub(crate) struct CaptionJob {
    progress: AtomicU64,
    stage: Mutex<String>,
    cancel: AtomicBool,
    heard: Mutex<Option<Result<Vec<(ItemId, Transcript)>, String>>>,
    applied: Mutex<Option<Result<u32, String>>>,
}

impl CaptionJob {
    fn set(&self, progress: f64, stage: Option<&str>) {
        self.progress.store(progress.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
        if let Some(st) = stage {
            *self.stage.lock().unwrap_or_else(PoisonError::into_inner) = st.to_string();
        }
    }
    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }
    pub(crate) fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }
}

/// Load speech model `model` for one job. It is not kept: it takes hundreds of megabytes, captions
/// are made now and then, and loading takes only seconds.
fn transcriber(model: &str) -> Result<Arc<dyn Transcriber>, String> {
    let dir = models_dir().ok_or("The app has no folder for speech models.")?;
    filmcraft_speech::load(&dir, model).map_err(|e| match e {
        SpeechError::NotInstalled(_) => "The speech model isn't downloaded.".to_string(),
        e => e.to_string(),
    })
}

/// Start making captions with speech model `model` in `language` ("" or "auto" to detect it).
pub(crate) fn start(s: &Session, model: &str, language: &str) -> Result<Arc<CaptionJob>, MobileError> {
    if !PHONE_MODELS.contains(&model) {
        return Err(MobileError::Command(format!("Unknown speech model {model}.")));
    }
    let language = Some(language.trim().to_lowercase()).filter(|l| !l.is_empty() && l != "auto");
    let (_, seq) = model::sequence(s)?;
    let mut work: Vec<Work> = Vec::new();
    let mut any = false;
    for it in seq.audio_tracks.iter().flat_map(|t| t.items.iter()).filter(|it| it.enabled) {
        let Some((item, _, _)) = s.project.resolve_media(it.item) else { continue };
        if work.iter().any(|w| w.item == item) {
            continue;
        }
        let dur = match s.project.item(item).map(|p| &p.kind) {
            Some(ItemKind::Media(m)) => m.duration(),
            _ => continue,
        };
        let Some(source) = s.source(item) else { continue };
        if !source.info().has_audio() {
            continue;
        }
        any = true;
        // heard before by this model (in this language): keep that
        let heard = s.project.transcripts.get(&item).is_some_and(|t| t.source == model && language.as_ref().is_none_or(|l| *l == t.language));
        if !heard {
            work.push(Work { item, source, samples: dur.to_units_floor(i64::from(filmcraft_speech::SAMPLE_RATE)).max(0) });
        }
    }
    if !any {
        return Err(MobileError::Command("There's no sound to make captions from.".into()));
    }
    let job = Arc::new(CaptionJob {
        progress: AtomicU64::new(0),
        stage: Mutex::new("Getting ready…".into()),
        cancel: AtomicBool::new(false),
        heard: Mutex::new(None),
        applied: Mutex::new(None),
    });
    let j = job.clone();
    let model = model.to_string();
    std::thread::Builder::new()
        .name("filmcraft-captions".into())
        .spawn(move || {
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| listen(&j, &model, language, work)))
                .unwrap_or_else(|_| Err("Making captions failed.".to_string()));
            *j.heard.lock().unwrap_or_else(PoisonError::into_inner) = Some(r);
        })
        .map_err(|e| MobileError::Command(format!("Captions couldn't start: {e}")))?;
    Ok(job)
}

/// The thread's work: load the model, read each file's sound, transcribe it.
fn listen(job: &CaptionJob, model: &str, language: Option<String>, work: Vec<Work>) -> Result<Vec<(ItemId, Transcript)>, String> {
    if work.is_empty() {
        return Ok(Vec::new());
    }
    job.set(0.0, Some("Getting the speech model ready…"));
    let t = transcriber(model)?;
    // 5 % for loading, the rest shared by the files in proportion to their length
    let total = work.iter().map(|w| w.samples.max(1) as f64).sum::<f64>().max(1.0);
    let mut before = 0.05;
    let mut out = Vec::new();
    let sr = filmcraft_speech::SAMPLE_RATE;
    for w in work {
        if job.cancelled() {
            return Err("cancelled".into());
        }
        let share = 0.95 * w.samples.max(1) as f64 / total;
        job.set(before, Some("Reading the sound…"));
        let mut audio: Vec<f32> = Vec::new();
        let step = READ_SECONDS.saturating_mul(i64::from(sr));
        let mut at = 0i64;
        while at < w.samples {
            if job.cancelled() {
                return Err("cancelled".into());
            }
            let n = usize::try_from((w.samples - at).min(step)).unwrap_or(0);
            if n == 0 {
                break;
            }
            let buf = w.source.audio(at, n, sr).map_err(|e| format!("The sound couldn't be read: {e}"))?;
            let mono = filmcraft_speech::downmix(&buf.channels);
            audio.try_reserve(mono.len()).map_err(|e| format!("The sound is too long: {e}"))?;
            audio.extend_from_slice(&mono);
            at = at.saturating_add(step);
        }
        job.set(before, Some("Listening…"));
        let opts = Options { language: language.clone(), diarize: false, max_speakers: 1 };
        let mut progress = |f: f32, _: &str| {
            job.set(before + share * f64::from(f.clamp(0.0, 1.0)), None);
            !job.cancelled()
        };
        let mut tr = t.transcribe(&audio, &opts, &mut progress).map_err(|e| match e {
            SpeechError::Cancelled => "cancelled".to_string(),
            e => e.to_string(),
        })?;
        tr.normalize();
        out.push((w.item, tr));
        before += share;
    }
    Ok(out)
}

/// How job `job` is doing; once the speech is heard, makes the captions (one undo step).
pub(crate) fn status(s: &mut Session, job: &CaptionJob) -> CaptionStatus {
    let heard = job.heard.lock().unwrap_or_else(PoisonError::into_inner).take();
    if let Some(r) = heard {
        let applied = match r {
            Ok(t) => make_captions(s, t).map_err(|e| e.to_string()),
            Err(e) => Err(e),
        };
        *job.applied.lock().unwrap_or_else(PoisonError::into_inner) = Some(applied);
    }
    let progress = f64::from_bits(job.progress.load(Ordering::Relaxed));
    let stage = job.stage.lock().unwrap_or_else(PoisonError::into_inner).clone();
    match job.applied.lock().unwrap_or_else(PoisonError::into_inner).as_ref() {
        Some(Ok(n)) => CaptionStatus { progress: 1.0, stage: "Done".into(), finished: true, error: None, captions: *n },
        Some(Err(e)) => CaptionStatus { progress, stage, finished: true, error: Some(e.clone()), captions: 0 },
        None => CaptionStatus { progress, stage, finished: false, error: None, captions: 0 },
    }
}

/// The caption look for a frame: text about 5.5 % of the short side, lines that fit its width.
fn caption_layout(width: u32, height: u32) -> (f64, usize) {
    let (w, h) = (f64::from(width.max(1)), f64::from(height.max(1)));
    let px = 0.055 * w.min(h);
    // the style's size is for a 1080-line frame and grows with the frame's height
    let size = px * 1080.0 / h;
    // an average letter is a little over half the size wide; keep a margin at the sides
    let chars = (w * 0.85 / (px * 0.55)).floor().clamp(12.0, 42.0) as usize;
    (size, chars)
}

/// How far around a word on a higher lane a lower lane's word is taken to be talking over it.
const WORD_REACH: Tick = Tick(TICKS_PER_SECOND / 4);

/// The words heard through the open sequence's audio clips, in time order. In the engine's
/// sequence transcript a transcribed clip on a higher track hides everything under it (so the same
/// dialogue on two tracks reads once); on the phone a voice-over or speech often plays over a video
/// whose own sound has no words, so only the words themselves hide what a lower lane says at the
/// same moment.
fn phone_words(seq: &Sequence, transcripts: &BTreeMap<ItemId, Arc<Transcript>>) -> Vec<SeqWord> {
    let ticks = |t: f64| Tick(t.round() as i64);
    let mut out: Vec<SeqWord> = Vec::new();
    // what higher lanes say: (from, to) with a little reach on each side
    let mut taken: Vec<(Tick, Tick)> = Vec::new();
    for (ti, track) in seq.audio_tracks.iter().enumerate() {
        let mut mine = Vec::new();
        for it in &track.items {
            if !it.enabled || it.reverse || it.frame_hold.is_some() || !it.speed.is_finite() || it.speed <= 0.0 {
                continue;
            }
            let Some(tr) = transcripts.get(&it.item) else { continue };
            let speed = it.speed;
            let media_end = it.source_in + ticks(it.duration.0 as f64 * speed);
            let to_tl = |m: Tick| it.start + ticks((m - it.source_in).0 as f64 / speed);
            for wi in tr.words_in(TimeRange::from_bounds(it.source_in, media_end.max(it.source_in))) {
                let Some(w) = tr.words.get(wi) else { continue };
                let (a, b) = (to_tl(w.start), to_tl(w.end.max(w.start)));
                let mid = Tick(a.0 + (b.0 - a.0) / 2);
                if mid < it.start || mid >= it.end() || taken.iter().any(|&(f, t)| f <= mid && mid < t) {
                    continue;
                }
                let start = a.max(it.start);
                let end = b.min(it.end()).max(start);
                mine.push((start - WORD_REACH, end + WORD_REACH));
                out.push(SeqWord {
                    text: w.text.clone(),
                    start,
                    end,
                    clip: it.id,
                    item: it.item,
                    index: wi,
                    track: ti,
                    speaker: tr.speaker_name(w),
                    confidence: w.confidence,
                });
            }
        }
        taken.extend(mine);
    }
    out.sort_by_key(|w| (w.start, w.track));
    out
}

/// Fold transcripts into the project and turn the open sequence's words into captions, in place of
/// any made before (keeping their look). Returns how many; 0 (and nothing changed but the
/// transcripts) when nobody speaks.
pub(crate) fn make_captions(s: &mut Session, heard: Vec<(ItemId, Transcript)>) -> Result<u32, MobileError> {
    let (width, height) = {
        let (_, seq) = model::sequence(s)?;
        (seq.settings.width, seq.settings.height)
    };
    let (size, chars) = caption_layout(width, height);
    let n = s.edit_group("Captions", |s| {
        if !heard.is_empty() {
            s.edit("Transcribe", |pr, _| {
                for (item, t) in heard {
                    pr.transcripts.insert(item, Arc::new(t));
                }
                Ok(())
            })?;
        }
        let Some(seq) = s.active_sequence() else { return Ok(0) };
        let words = phone_words(seq, &s.project.transcripts);
        if words.is_empty() {
            return Ok(0);
        }
        let rules = CaptionRules { max_chars: chars, lines: 2, ..CaptionRules::default() };
        let blocks = tx::caption_blocks(&words, &rules, s.sequence_rate());
        // captions made again keep the look the user gave the old ones
        let style =
            seq.caption_tracks.first().map(|t| t.style.clone()).unwrap_or_else(|| CaptionStyle { size: size as f32, margin: 0.1, ..CaptionStyle::default() });
        let count = blocks.len();
        s.edit_sequence("Create Captions", |q, ctx, st| {
            let id = TrackId(ctx.alloc());
            let mut t = CaptionTrack::new(id, "Captions".into(), CaptionFormat::default());
            t.captions = tx::blocks_to_captions(&blocks, ctx);
            t.style = style;
            q.caption_tracks.clear();
            q.caption_tracks.push(t);
            st.caption_selection.clear();
            Ok(())
        })?;
        Ok(count)
    })?;
    edits::settle(s);
    Ok(u32::try_from(n).unwrap_or(u32::MAX))
}

// ---- editing captions ----

/// Where captions sit on the frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptionPosition {
    Top,
    Middle,
    Bottom,
}

/// The captions' look.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CaptionLook {
    /// Size relative to the default (1.0).
    pub scale: f64,
    pub position: CaptionPosition,
    /// A dark box behind the words.
    pub background: bool,
}

fn caption_track(s: &Session) -> Option<(u64, filmcraft_project::CaptionStyle)> {
    s.active_sequence()?.caption_tracks.first().map(|t| (t.id.0, t.style.clone()))
}

/// The captions' look, if there are captions.
pub(crate) fn look(s: &Session) -> Option<CaptionLook> {
    let (_, style) = caption_track(s)?;
    let (_, seq) = model::sequence(s).ok()?;
    let (size, _) = caption_layout(seq.settings.width, seq.settings.height);
    let position = match style.anchor {
        filmcraft_project::CaptionAnchor::Top => CaptionPosition::Top,
        filmcraft_project::CaptionAnchor::Middle => CaptionPosition::Middle,
        filmcraft_project::CaptionAnchor::Bottom => CaptionPosition::Bottom,
    };
    Some(CaptionLook { scale: f64::from(style.size) / size.max(1.0), position, background: style.background })
}

/// Change the captions' look: one undo step, or with `merge` folded into the last change made with
/// `merge` (a slider being dragged).
pub(crate) fn set_look(s: &mut Session, l: &CaptionLook, merge: bool) -> Result<(), MobileError> {
    let (track, _) = caption_track(s).ok_or_else(|| MobileError::Command("There are no captions.".into()))?;
    let (_, seq) = model::sequence(s)?;
    let (size, _) = caption_layout(seq.settings.width, seq.settings.height);
    let scale = if l.scale.is_finite() { l.scale.clamp(0.5, 2.0) } else { 1.0 };
    let anchor = match l.position {
        CaptionPosition::Top => filmcraft_project::CaptionAnchor::Top,
        CaptionPosition::Middle => filmcraft_project::CaptionAnchor::Middle,
        CaptionPosition::Bottom => filmcraft_project::CaptionAnchor::Bottom,
    };
    let background = l.background;
    s.edit_sequence_as("Caption Style", merge.then_some("caption-look"), |q, _, _| {
        let t = q.caption_tracks.iter_mut().find(|t| t.id.0 == track).ok_or_else(|| filmcraft_engine::EngineError::Other("no captions".into()))?;
        t.style.size = (size * scale) as f32;
        t.style.anchor = anchor;
        t.style.background = background;
        // without the box, a dark edge keeps the words readable on bright pictures
        t.style.outline = if background { 0.0 } else { CAPTION_OUTLINE };
        t.style.outline_color = [0, 0, 0, 255];
        Ok(())
    })?;
    edits::settle(s);
    Ok(())
}

/// Change a caption's words; blank words delete it.
pub(crate) fn set_text(s: &mut Session, caption: u64, text: &str) -> Result<(), MobileError> {
    if text.trim().is_empty() {
        return delete(s, caption);
    }
    ex(s, "captions.setText", json!({"caption": caption, "text": text.trim()}))?;
    Ok(())
}

pub(crate) fn delete(s: &mut Session, caption: u64) -> Result<(), MobileError> {
    ex(s, "captions.delete", json!({"captions": [caption], "ripple": false}))?;
    Ok(())
}

/// Where a caption sits on the main track, taken before a main-track edit.
enum Anchor {
    /// Over main-track clip `clip` (showing `item`), at media time `source`, `offset` into the clip.
    Clip { clip: ClipId, item: ItemId, source: Tick, offset: Tick },
    /// This far after the end of the main track (negative: in a gap before it).
    End(Tick),
}

/// Every caption's [`Anchor`] and the undo step before a main-track edit; see [`follow_main`].
pub(crate) struct Anchors {
    at: Vec<(ClipId, Anchor)>,
    last_step: Option<Arc<filmcraft_project::Project>>,
}

pub(crate) fn anchors(s: &Session) -> Option<Anchors> {
    let seq = s.active_sequence()?;
    let main = seq.video_tracks.first()?;
    let at: Vec<(ClipId, Anchor)> = seq
        .caption_tracks
        .iter()
        .flat_map(|t| &t.captions)
        .map(|c| {
            let anchor = match main.item_at(c.start) {
                Some(it) => Anchor::Clip { clip: it.id, item: it.item, source: it.source_time_at(c.start), offset: c.start - it.start },
                None => Anchor::End(c.start - main.end()),
            };
            (c.id, anchor)
        })
        .collect();
    (!at.is_empty()).then(|| Anchors { at, last_step: s.history.undo.last().map(|e| e.1.clone()) })
}

/// Where media time `source` of `item` now plays on the main track: in the same clip, or another
/// piece of the same footage (a split or a still cut it). A still or a reversed clip keeps its
/// offset instead. `None` when that footage is gone.
fn now_at(main: &filmcraft_project::Track, clip: ClipId, item: ItemId, source: Tick, offset: Tick) -> Option<Tick> {
    let shows = |it: &filmcraft_project::TrackItem| {
        let plain = it.frame_hold.is_none() && !it.reverse && it.speed.abs() > 1e-6;
        (plain && source >= it.source_in && source < it.source_out())
            .then(|| it.start + Tick(((source - it.source_in).0 as f64 / it.speed.abs()).round() as i64))
    };
    if let Some(it) = main.items.iter().find(|it| it.id == clip) {
        if it.frame_hold.is_some() || it.reverse {
            return (offset < it.duration).then_some(it.start + offset);
        }
        if let Some(t) = shows(it) {
            return Some(t);
        }
    }
    main.items.iter().filter(|it| it.item == item).find_map(shows)
}

/// After a main-track edit, move every caption to where the footage it was made from now plays
/// (the engine's ripple deletes, trims and speed changes leave captions where they were); a caption
/// whose footage was cut away goes. Folded into the edit's own undo step.
pub(crate) fn follow_main(s: &mut Session, before: Option<Anchors>) -> Result<(), MobileError> {
    let Some(before) = before else { return Ok(()) };
    let Some(seq) = s.active_sequence() else { return Ok(()) };
    let Some(main) = seq.video_tracks.first() else { return Ok(()) };
    let end = main.end();
    let to: std::collections::HashMap<ClipId, Option<Tick>> = before
        .at
        .iter()
        .map(|(id, a)| {
            let t = match *a {
                Anchor::Clip { clip, item, source, offset } => now_at(main, clip, item, source, offset),
                Anchor::End(d) => Some(end + d),
            };
            (*id, t.map(|t| t.max(Tick::ZERO)))
        })
        .collect();
    let moved = seq.caption_tracks.iter().flat_map(|t| &t.captions).any(|c| to.get(&c.id).is_some_and(|t| *t != Some(c.start)));
    if !moved {
        return Ok(());
    }
    let frame = s.sequence_rate().frame_duration();
    let edited = match (s.history.undo.last(), &before.last_step) {
        (Some(now), Some(was)) => !Arc::ptr_eq(&now.1, was),
        (now, was) => now.is_some() != was.is_some(),
    };
    let merge = s.history.merge_key.clone();
    s.edit_sequence("Captions", |q, _, _| {
        for t in &mut q.caption_tracks {
            t.captions.retain_mut(|c| match to.get(&c.id) {
                Some(Some(at)) => {
                    c.start = *at;
                    true
                }
                Some(None) => false,
                None => true,
            });
            t.sort();
            // a caption ends where the next one begins
            let next: Vec<Tick> = t.captions.iter().skip(1).map(|c| c.start).collect();
            for (c, n) in t.captions.iter_mut().zip(next) {
                if c.end() > n {
                    c.duration = (n - c.start).max(frame);
                }
            }
        }
        Ok(())
    })?;
    // the edit and its captions are one undo step
    if edited {
        s.history.undo.pop();
    }
    s.history.merge_key = merge;
    Ok(())
}

/// Remove every caption.
pub(crate) fn delete_all(s: &mut Session) -> Result<(), MobileError> {
    let old: Vec<u64> = s.active_sequence().map(|q| q.caption_tracks.iter().map(|t| t.id.0).collect()).unwrap_or_default();
    if old.is_empty() {
        return Ok(());
    }
    s.edit_group("Delete Captions", |s| {
        for t in old {
            s.execute("captions.deleteTrack", json!({"track": t}))?;
        }
        Ok(())
    })?;
    Ok(())
}
