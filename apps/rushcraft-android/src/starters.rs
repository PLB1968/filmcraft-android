//! Project templates: Vlog, Short, Tutorial and Square starters, a new project's titles, end card
//! and guide markers (where the opening, the story and the call to action go), as its first state
//! (nothing to undo).

use filmcraft_engine::Session;
use filmcraft_time::Tick;
use serde_json::json;

use crate::{MobileError, titles};

/// A project template.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Starter {
    /// 16:9: an opening title, a lower third with your name, an end card.
    Vlog,
    /// 9:16: a big hook title for the first seconds and a Subscribe end card.
    Short,
    /// 16:9: chapter titles, a slot for a face cam, an end card.
    Tutorial,
    /// 1:1: a title and an end card.
    Square,
}

impl Starter {
    /// The frame it is made for.
    pub fn frame(self) -> (u32, u32) {
        match self {
            Starter::Vlog | Starter::Tutorial => (1920, 1080),
            Starter::Short => (1080, 1920),
            Starter::Square => (1080, 1080),
        }
    }
}

/// Put `starter`'s titles and markers on the (new, empty) project's sequence. Its titles keep
/// their animations; the result can't be undone away, like a new project's first media.
pub(crate) fn apply(s: &mut Session, starter: Starter) -> Result<(), MobileError> {
    let sec = |x: f64| Tick::from_seconds_f64(x).0;
    // (time, template, its text fields)
    type Title<'a> = (f64, &'a str, &'a [(&'a str, &'a str)]);
    let (items, markers): (Vec<Title>, Vec<(f64, &str)>) = match starter {
        Starter::Vlog => (
            vec![
                (0.0, "builtin:title-centered", &[("title", "MY VLOG"), ("subtitle", "episode one")]),
                (6.0, "builtin:lower-third-slab", &[("name", "Your Name"), ("role", "Your channel")]),
                (40.0, "builtin:end-card-subscribe", &[]),
            ],
            vec![(0.0, "Opening shot"), (6.0, "Say hi: your name shows here"), (12.0, "Your story"), (40.0, "End card")],
        ),
        Starter::Short => (
            vec![(0.0, "builtin:title-boxed", &[("title", "Wait for it…")]), (14.0, "builtin:end-card-subscribe", &[])],
            vec![(0.0, "Hook: the first two seconds"), (3.0, "The content"), (14.0, "Call to action")],
        ),
        Starter::Tutorial => (
            vec![
                (0.0, "builtin:title-centered", &[("title", "HOW TO"), ("subtitle", "step by step")]),
                (8.0, "builtin:title-boxed", &[("title", "Chapter One")]),
                (30.0, "builtin:title-boxed", &[("title", "Chapter Two")]),
                (60.0, "builtin:end-card-subscribe", &[]),
            ],
            vec![
                (0.0, "Intro"),
                (8.0, "Chapter 1: put a face cam on V2, then Motion › Picture in picture"),
                (30.0, "Chapter 2"),
                (60.0, "End card"),
            ],
        ),
        Starter::Square => (
            vec![(0.0, "builtin:title-centered", &[("title", "YOUR TITLE")]), (15.0, "builtin:end-card-subscribe", &[])],
            vec![(0.0, "Opening"), (4.0, "Your clips"), (15.0, "End card")],
        ),
    };
    for (at, id, fields) in items {
        s.set_playhead(Tick(sec(at)));
        let clip = titles::add(s, id)?;
        for (f, v) in fields {
            titles::set_field(s, clip, f, v, false)?;
        }
    }
    for (at, name) in markers {
        s.execute("markers.add", json!({ "time": sec(at), "name": name }))?;
    }
    s.set_playhead(Tick::ZERO);
    let _ = s.execute("timeline.select", json!({ "clips": [] }));
    // the template is where the project starts
    s.history.undo.clear();
    s.history.redo.clear();
    Ok(())
}
