//! Proxies for smooth playback: small copies of the project's videos that the monitors play
//! instead of the originals (exports always read the originals).
//!
//! The phone makes one proxy at a time, in the background, from a queue kept here: with its own
//! video hardware when the app can ([`ProxyStatus::app_work`], an H.264 or HEVC file made by the
//! app), else with the engine (a ProRes file, much slower: every 4K frame is decoded in
//! software). A video the hardware can't convert is made by the engine instead. A finished
//! proxy is attached without an undo step: Undo takes back edits, not the copies made to play
//! them, so it is set on every undo and redo snapshot of the project too. Jobs are matched to
//! their media by the file's path, so a proxy that finishes after another project was opened
//! goes to the right clip (or nowhere).

use std::collections::{HashSet, VecDeque};
use std::sync::Arc;
use std::sync::atomic::Ordering;

use filmcraft_engine::{Session, proxies};
use filmcraft_media::MediaKind;
use filmcraft_project::{ItemId, ItemKind, MediaRef, Project};
use serde_json::json;

use crate::MobileError;
use crate::edits::ex;

/// The engine's proxies: small (a quarter of the size) and quick to decode, as every frame
/// stands alone.
const PRESET: &str = "prores_proxy_quarter";

/// Made without asking for videos at least this big on their long side (HD and up).
pub(crate) const AUTO_MIN_SIDE: u32 = 1920;

/// The proxies waiting to be made, by media file path.
#[derive(Default)]
pub(crate) struct ProxyQueue {
    running: Option<Running>,
    waiting: VecDeque<String>,
    /// Failed, or removed by hand: not made again without being asked.
    skip: HashSet<String>,
    /// The phone's hardware couldn't convert these: the engine makes them.
    engine_only: HashSet<String>,
}

struct Running {
    worker: Worker,
    media: String,
    out: String,
    name: String,
}

enum Worker {
    /// An engine job (its id).
    Engine(u64),
    /// The app, with the phone's video hardware; how far along it last said.
    App(f64),
}

/// A proxy for the app to make with the phone's video hardware: convert `media` to `out`,
/// then report with `proxy_done`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ProxyWork {
    pub item: u64,
    pub media: String,
    pub out: String,
}

/// How proxies are coming along.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ProxyStatus {
    /// The monitors play proxies (Toggle Proxies).
    pub enabled: bool,
    /// A proxy is being made or waits to be: ask again in a moment.
    pub busy: bool,
    /// A proxy was attached since the last call: the project changed.
    pub attached: bool,
    /// The item being made now and how far along (0..1).
    pub making: Option<u64>,
    pub progress: f64,
    /// Items waiting their turn.
    pub waiting: Vec<u64>,
    /// Problems to tell the user.
    pub messages: Vec<String>,
    /// The proxy the app is to make now (the same one until it reports it done).
    pub app_work: Option<ProxyWork>,
}

/// The file path of item `id` when it is a video file.
fn video_path(p: &Project, id: ItemId) -> Option<String> {
    let ItemKind::Media(m) = &p.item(id)?.kind else { return None };
    let MediaRef::File { path } = &m.media else { return None };
    (m.info.kind == MediaKind::Movie && m.info.video.is_some() && !m.offline).then(|| path.clone())
}

/// The item whose media is the file at `path`.
fn item_of(p: &Project, path: &str) -> Option<ItemId> {
    p.items.values().find(|i| matches!(&i.kind, ItemKind::Media(m) if matches!(&m.media, MediaRef::File { path: q } if q == path))).map(|i| i.id)
}

/// Whether item `id` has a proxy attached.
pub(crate) fn has_proxy(p: &Project, id: ItemId) -> bool {
    matches!(&p.item(id).map(|i| &i.kind), Some(ItemKind::Media(m)) if m.proxy.is_some())
}

/// Set (or clear) the proxy of the item playing `media` in the project and in every undo and
/// redo snapshot, without an undo step of its own. Returns whether the project changed.
fn set_quietly(s: &mut Session, media: &str, proxy: Option<&str>) -> bool {
    let Some(id) = item_of(&s.project, media) else { return false };
    let apply = |p: &Arc<Project>| -> Option<Arc<Project>> {
        if item_of(p, media) != Some(id) {
            return None;
        }
        let mut q = (**p).clone();
        let m = q.item_mut(id)?.as_media_mut()?;
        let next = proxy.map(|path| MediaRef::File { path: path.to_string() });
        if m.proxy == next {
            return None;
        }
        m.proxy = next;
        Some(Arc::new(q))
    };
    let Some(now) = apply(&s.project) else { return false };
    s.project = now;
    for step in s.history.undo.iter_mut().chain(s.history.redo.iter_mut()) {
        if let Some(q) = apply(&step.1) {
            step.1 = q;
        }
    }
    s.bump_view();
    true
}

impl ProxyQueue {
    /// Make proxies of `items` (asked for by hand: one that failed before is tried again).
    pub(crate) fn add(&mut self, s: &Session, items: &[u64]) -> Result<(), MobileError> {
        let mut any = false;
        for &i in items {
            let Some(path) = video_path(&s.project, ItemId(i)) else { continue };
            any = true;
            self.skip.remove(&path);
            if has_proxy(&s.project, ItemId(i)) || self.waiting.contains(&path) || self.running.as_ref().is_some_and(|r| r.media == path) {
                continue;
            }
            self.waiting.push_back(path);
        }
        if any { Ok(()) } else { Err(MobileError::Command("Only videos get proxies.".into())) }
    }

    /// Take item `item`'s proxy away (and delete its file); it isn't made again on its own.
    pub(crate) fn remove(&mut self, s: &mut Session, item: u64) {
        let Some(path) = video_path(&s.project, ItemId(item)) else { return };
        self.waiting.retain(|w| *w != path);
        if let Some(r) = self.running.take_if(|r| r.media == path) {
            cancel(s, &r);
        }
        self.skip.insert(path.clone());
        let file = match s.project.item(ItemId(item)).map(|i| &i.kind) {
            Some(ItemKind::Media(m)) => match &m.proxy {
                Some(MediaRef::File { path }) => Some(path.clone()),
                _ => None,
            },
            _ => None,
        };
        set_quietly(s, &path, None);
        if let Some(f) = file {
            let _ = std::fs::remove_file(f);
        }
    }

    /// How far the app has got with the proxy of `media` (0..1).
    pub(crate) fn app_progress(&mut self, media: &str, progress: f64) {
        if let Some(Running { worker: Worker::App(p), media: m, .. }) = &mut self.running
            && m == media
        {
            *p = progress.clamp(0.0, 1.0);
        }
    }

    /// The app finished the proxy of `media`: attach it (`ok`), or have the engine make it.
    /// Returns whether a proxy was attached.
    pub(crate) fn app_done(&mut self, s: &mut Session, media: &str, ok: bool) -> bool {
        let Some(r) = self.running.take_if(|r| matches!(r.worker, Worker::App(_)) && r.media == media) else { return false };
        if ok && std::path::Path::new(&r.out).is_file() {
            return set_quietly(s, &r.media, Some(&r.out));
        }
        let _ = std::fs::remove_file(&r.out);
        log::warn!("the phone couldn't make a proxy of {}: the engine makes it", r.name);
        self.engine_only.insert(r.media.clone());
        self.waiting.push_front(r.media);
        false
    }

    /// Move things along: attach a finished proxy, queue new videos (when `auto`), start the next
    /// (for the app to make with the phone's video hardware when `hardware`).
    pub(crate) fn tick(&mut self, s: &mut Session, auto: bool, hardware: bool) -> ProxyStatus {
        let mut messages = Vec::new();
        let mut attached = false;
        let mut progress = 0.0;
        // the proxy being made: still going (how far), or done (its outcome)
        let done = self.running.as_ref().and_then(|r| match r.worker {
            Worker::App(p) => {
                progress = p;
                None
            }
            Worker::Engine(job) => match s.jobs.iter().find(|j| j.id == job) {
                Some(j) if !j.progress.finished.load(Ordering::Relaxed) => {
                    let total = j.progress.total.load(Ordering::Relaxed).max(1);
                    progress = (j.progress.done.load(Ordering::Relaxed) as f64 / total as f64).clamp(0.0, 1.0);
                    None
                }
                job => Some(job.and_then(|j| j.progress.error.lock().unwrap_or_else(|e| e.into_inner()).clone())),
            },
        });
        if let Some(error) = done
            && let Some(r) = self.running.take()
        {
            match error {
                None if std::path::Path::new(&r.out).is_file() => attached = set_quietly(s, &r.media, Some(&r.out)),
                e => {
                    let _ = std::fs::remove_file(&r.out);
                    if e.as_deref() != Some("cancelled") {
                        messages.push(format!("A proxy of {} couldn't be made.", r.name));
                    }
                    self.skip.insert(r.media);
                }
            }
        }
        if auto {
            let mut ids: Vec<_> = s.project.items.values().map(|i| (i.created, i.id)).collect();
            ids.sort();
            for (_, id) in ids {
                let Some(path) = video_path(&s.project, id) else { continue };
                let big = matches!(&s.project.item(id).map(|i| &i.kind), Some(ItemKind::Media(m)) if m.info.video.as_ref().is_some_and(|v| v.width.max(v.height) >= AUTO_MIN_SIDE));
                if big
                    && !has_proxy(&s.project, id)
                    && !self.skip.contains(&path)
                    && !self.waiting.contains(&path)
                    && self.running.as_ref().is_none_or(|r| r.media != path)
                {
                    self.waiting.push_back(path);
                }
            }
        }
        while self.running.is_none()
            && let Some(path) = self.waiting.pop_front()
        {
            let Some(id) = item_of(&s.project, &path) else { continue };
            if has_proxy(&s.project, id) || !std::path::Path::new(&path).is_file() {
                continue;
            }
            let name = s.project.item(id).map(|i| i.name.clone()).unwrap_or_default();
            if hardware && !self.engine_only.contains(&path) {
                let stem = std::path::Path::new(&path).file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| name.clone());
                let dir = proxies::proxy_dir(&path, None);
                let out = proxies::unique_path(&dir, &stem, "_Proxy", "mp4", &[]).to_string_lossy().into_owned();
                if std::fs::create_dir_all(&dir).is_ok() {
                    self.running = Some(Running { worker: Worker::App(0.0), media: path, out, name });
                    continue;
                }
            }
            match ex(s, "media.createProxies", json!({"items": [id.0], "preset": PRESET, "attach": false})) {
                Ok(v) => match (v["job"].as_u64(), v["outputs"][0]["path"].as_str()) {
                    (Some(job), Some(out)) => self.running = Some(Running { worker: Worker::Engine(job), media: path, out: out.to_string(), name }),
                    _ => {
                        self.skip.insert(path);
                    }
                },
                Err(e) => {
                    log::warn!("proxy of {name}: {e}");
                    messages.push(format!("A proxy of {name} couldn't be made."));
                    self.skip.insert(path);
                }
            }
        }
        let p = &s.project;
        ProxyStatus {
            enabled: s.media.use_proxies(),
            busy: self.running.is_some() || !self.waiting.is_empty(),
            attached,
            making: self.running.as_ref().and_then(|r| item_of(p, &r.media)).map(|i| i.0),
            progress,
            waiting: self.waiting.iter().filter_map(|w| item_of(p, w)).map(|i| i.0).collect(),
            messages,
            app_work: self
                .running
                .as_ref()
                .filter(|r| matches!(r.worker, Worker::App(_)))
                .and_then(|r| Some(ProxyWork { item: item_of(p, &r.media)?.0, media: r.media.clone(), out: r.out.clone() })),
        }
    }

    /// Stop the proxy being made and forget the waiting ones (the app is leaving the project).
    pub(crate) fn clear(&mut self, s: &Session) {
        self.waiting.clear();
        // a cancelled job deletes its unfinished file itself
        // (the app stops its own when its work is no longer asked for)
        if let Some(r) = self.running.take() {
            cancel(s, &r);
        }
    }
}

/// Stop an engine job making a proxy.
fn cancel(s: &Session, r: &Running) {
    if let Worker::Engine(job) = r.worker
        && let Some(j) = s.jobs.iter().find(|j| j.id == job)
    {
        j.progress.cancel.store(true, Ordering::Relaxed);
    }
}

/// Toggle Proxies: the monitors play proxies where there are some (or the originals).
pub(crate) fn set_enabled(s: &mut Session, on: bool) {
    // kept in the preferences too: the engine sets the pool from them whenever they change (a
    // save notes the project as recent), which would turn proxies back off
    s.prefs.media.enable_proxies = on;
    if s.media.use_proxies() != on {
        s.media.set_use_proxies(on);
        s.bump_view();
    }
}
