//! The phone's filters, effects and transitions: FilmCraft's own looks built from the engine's
//! effects, each a fixed recipe the bridge can recognise again when it reads a clip back.
//!
//! - A **filter** is one Lumetri Color instance whose adjustments are the look's, scaled by the
//!   intensity (0–200, 100 = the look as designed), plus a Noise instance for looks with grain.
//!   The look's number and the intensity ride in the instance's HSL Secondary section, which stays
//!   switched off, so they never change the picture: `hsl_hue` = 10 × the look's number,
//!   `hsl_denoise` = intensity ÷ 2.
//! - An **effect** is one or two engine effects with a few parameters fixed (which tell the
//!   effect apart from its siblings) and one or more scaled by the intensity (0–100).
//! - A **transition** is an engine transition with fixed parameters (a push's direction, a wipe's
//!   angle…).

use filmcraft_project::{EffectInstance, ParamValue, TrackItem};

/// Engine Lumetri id.
pub(crate) const LUMETRI: &str = "lumetri";
/// Engine Noise id (the grain of grainy looks).
const NOISE: &str = "noise";

/// A filter, effect or transition for the pickers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct PresetInfo {
    pub id: String,
    pub name: String,
}

/// A look: deltas from neutral at intensity 100.
struct Look {
    id: &'static str,
    name: &'static str,
    temperature: f64,
    tint: f64,
    exposure: f64,
    contrast: f64,
    highlights: f64,
    shadows: f64,
    whites: f64,
    blacks: f64,
    saturation: f64,
    vibrance: f64,
    fade: f64,
    sharpen: f64,
    /// Split toning: offsets from neutral grey (r, g, b).
    shadow_tint: [f64; 3],
    highlight_tint: [f64; 3],
    /// Lumetri vignette amount (negative darkens).
    vignette: f64,
    /// Noise amount (%).
    grain: f64,
}

const NEUTRAL: Look = Look {
    id: "",
    name: "",
    temperature: 0.0,
    tint: 0.0,
    exposure: 0.0,
    contrast: 0.0,
    highlights: 0.0,
    shadows: 0.0,
    whites: 0.0,
    blacks: 0.0,
    saturation: 0.0,
    vibrance: 0.0,
    fade: 0.0,
    sharpen: 0.0,
    shadow_tint: [0.0; 3],
    highlight_tint: [0.0; 3],
    vignette: 0.0,
    grain: 0.0,
};

const TEAL: [f64; 3] = [-0.06, 0.02, 0.06];
const ORANGE: [f64; 3] = [0.08, 0.03, -0.06];

/// FilmCraft's looks, in the order the picker shows them.
const LOOKS: &[Look] = &[
    Look { id: "colorful", name: "Colorful", saturation: 35.0, vibrance: 25.0, contrast: 10.0, ..NEUTRAL },
    Look { id: "warm", name: "Warm", temperature: 30.0, tint: 5.0, saturation: 5.0, ..NEUTRAL },
    Look { id: "cool", name: "Cool", temperature: -30.0, tint: -3.0, ..NEUTRAL },
    Look { id: "washed_out", name: "Washed out", contrast: -30.0, blacks: 30.0, fade: 35.0, saturation: -25.0, ..NEUTRAL },
    Look { id: "grainy", name: "Grainy", grain: 18.0, contrast: 10.0, saturation: -10.0, ..NEUTRAL },
    Look { id: "details", name: "Details", sharpen: 45.0, contrast: 20.0, highlights: -15.0, shadows: 15.0, ..NEUTRAL },
    Look {
        id: "vacation",
        name: "Vacation",
        temperature: 15.0,
        saturation: 25.0,
        exposure: 0.15,
        highlight_tint: [0.08, 0.05, -0.06],
        shadow_tint: TEAL,
        ..NEUTRAL
    },
    Look { id: "cool_light", name: "Cool light", temperature: -20.0, exposure: 0.25, contrast: -10.0, highlights: 10.0, ..NEUTRAL },
    Look { id: "flat_green", name: "Flat & green", tint: -20.0, contrast: -25.0, saturation: -15.0, fade: 15.0, shadow_tint: [-0.03, 0.05, -0.02], ..NEUTRAL },
    Look { id: "vignette", name: "Vignette", vignette: -1.8, contrast: 5.0, ..NEUTRAL },
    Look { id: "noir", name: "Noir film", saturation: -100.0, contrast: 35.0, grain: 8.0, ..NEUTRAL },
    Look { id: "natural", name: "Natural", vibrance: 15.0, contrast: 5.0, shadows: 10.0, ..NEUTRAL },
    Look {
        id: "moody",
        name: "Moody",
        exposure: -0.2,
        contrast: 25.0,
        saturation: -25.0,
        temperature: -8.0,
        shadow_tint: [-0.03, -0.01, 0.06],
        vignette: -1.0,
        ..NEUTRAL
    },
    Look { id: "dramatic", name: "Dramatic", contrast: 45.0, highlights: -30.0, shadows: -20.0, vibrance: 10.0, vignette: -1.2, ..NEUTRAL },
    Look { id: "sunset", name: "Sunset", temperature: 45.0, tint: 12.0, saturation: 15.0, highlight_tint: [0.1, 0.03, -0.08], ..NEUTRAL },
    Look { id: "twilight", name: "Twilight", temperature: -35.0, tint: 18.0, exposure: -0.15, shadow_tint: [0.03, -0.03, 0.07], ..NEUTRAL },
    Look { id: "grainy_vcr", name: "Grainy VCR", grain: 25.0, saturation: -20.0, fade: 25.0, tint: 6.0, sharpen: -20.0, ..NEUTRAL },
    Look { id: "amber", name: "Amber", temperature: 35.0, saturation: -10.0, contrast: 10.0, highlight_tint: [0.09, 0.05, -0.08], ..NEUTRAL },
    Look { id: "dusk", name: "Dusk", exposure: -0.25, temperature: 15.0, tint: 10.0, contrast: 15.0, vignette: -0.8, ..NEUTRAL },
    Look {
        id: "vintage_film",
        name: "Vintage film",
        fade: 40.0,
        temperature: 18.0,
        saturation: -30.0,
        grain: 10.0,
        shadow_tint: [0.05, 0.02, -0.04],
        ..NEUTRAL
    },
    Look { id: "aged", name: "Aged", saturation: -55.0, temperature: 25.0, fade: 30.0, grain: 14.0, vignette: -1.2, ..NEUTRAL },
    Look { id: "bright", name: "Bright", exposure: 0.45, shadows: 20.0, contrast: -5.0, ..NEUTRAL },
    Look { id: "cool_matte", name: "Cool matte", temperature: -18.0, fade: 35.0, contrast: -15.0, saturation: -10.0, ..NEUTRAL },
    Look { id: "mix", name: "Mix", shadow_tint: TEAL, highlight_tint: ORANGE, contrast: 15.0, saturation: 10.0, ..NEUTRAL },
    Look { id: "contrast", name: "Contrast", contrast: 55.0, blacks: -15.0, whites: 15.0, ..NEUTRAL },
    Look { id: "matte", name: "Matte", fade: 45.0, contrast: -15.0, ..NEUTRAL },
    Look { id: "red_lift", name: "Red lift", shadow_tint: [0.08, -0.02, -0.02], tint: 8.0, saturation: 10.0, ..NEUTRAL },
    Look { id: "soft_mist", name: "Soft mist", exposure: 0.2, contrast: -35.0, highlights: 20.0, fade: 20.0, sharpen: -30.0, ..NEUTRAL },
    Look {
        id: "cinematic",
        name: "Cinematic",
        contrast: 20.0,
        saturation: -10.0,
        shadow_tint: TEAL,
        highlight_tint: [0.05, 0.02, -0.04],
        vignette: -0.8,
        ..NEUTRAL
    },
    Look { id: "instant", name: "Instant", fade: 25.0, temperature: 12.0, tint: 6.0, highlights: -20.0, saturation: -10.0, vignette: -0.6, ..NEUTRAL },
    Look { id: "vivid", name: "Vivid", saturation: 50.0, vibrance: 30.0, contrast: 15.0, ..NEUTRAL },
];

/// The filter picker's list (None is the picker's own first tile).
pub(crate) fn filters() -> Vec<PresetInfo> {
    LOOKS.iter().map(|l| PresetInfo { id: l.id.into(), name: l.name.into() }).collect()
}

fn set(e: &mut EffectInstance, id: &str, v: ParamValue) {
    if let Some(p) = e.param_mut(id) {
        p.value = v;
        p.keyframes.clear();
    }
}

fn instance(id: &str) -> Option<EffectInstance> {
    filmcraft_project::find_effect(id).map(|d| d.instance())
}

/// The effect instances of filter `id` at `amount` (0–200), or nothing for an unknown id.
pub(crate) fn filter_instances(id: &str, amount: f64) -> Option<Vec<EffectInstance>> {
    let (index, look) = LOOKS.iter().enumerate().find(|(_, l)| l.id == id)?;
    let amount = if amount.is_finite() { amount.clamp(0.0, 200.0) } else { 100.0 };
    let k = amount / 100.0;
    let mut lu = instance(LUMETRI)?;
    let f = |v: f64, lo: f64, hi: f64| ParamValue::Float(v.clamp(lo, hi));
    set(&mut lu, "temperature", f(look.temperature * k, -100.0, 100.0));
    set(&mut lu, "tint", f(look.tint * k, -100.0, 100.0));
    set(&mut lu, "exposure", f(look.exposure * k, -5.0, 5.0));
    set(&mut lu, "contrast", f(look.contrast * k, -100.0, 100.0));
    set(&mut lu, "highlights", f(look.highlights * k, -100.0, 100.0));
    set(&mut lu, "shadows", f(look.shadows * k, -100.0, 100.0));
    set(&mut lu, "whites", f(look.whites * k, -100.0, 100.0));
    set(&mut lu, "blacks", f(look.blacks * k, -100.0, 100.0));
    set(&mut lu, "saturation", f(100.0 + look.saturation * k, 0.0, 200.0));
    set(&mut lu, "vibrance", f(look.vibrance * k, -100.0, 100.0));
    set(&mut lu, "faded_film", f(look.fade * k, 0.0, 100.0));
    set(&mut lu, "sharpen", f(look.sharpen * k, -100.0, 100.0));
    let tint = |o: [f64; 3]| {
        let c = |x: f64| (0.5 + x * k).clamp(0.0, 1.0) as f32;
        ParamValue::Color([c(o[0]), c(o[1]), c(o[2]), 1.0])
    };
    set(&mut lu, "shadow_tint", tint(look.shadow_tint));
    set(&mut lu, "highlight_tint", tint(look.highlight_tint));
    set(&mut lu, "vignette_amount", f(look.vignette * k, -5.0, 5.0));
    // the look's name tag, in the switched-off HSL section
    set(&mut lu, "hsl_on", ParamValue::Bool(false));
    set(&mut lu, "hsl_hue", ParamValue::Float((index + 1) as f64 * 10.0));
    set(&mut lu, "hsl_denoise", ParamValue::Float(amount / 2.0));
    let mut out = vec![lu];
    if look.grain > 0.0 {
        let mut n = instance(NOISE)?;
        set(&mut n, "amount", f(look.grain * k, 0.0, 100.0));
        set(&mut n, "color", ParamValue::Bool(false));
        out.push(n);
    }
    Some(out)
}

fn float(e: &EffectInstance, id: &str) -> Option<f64> {
    match &e.param(id)?.value {
        ParamValue::Float(v) => Some(*v),
        _ => None,
    }
}

fn boolean(e: &EffectInstance, id: &str) -> Option<bool> {
    match &e.param(id)?.value {
        ParamValue::Bool(v) => Some(*v),
        _ => None,
    }
}

/// The index into [`LOOKS`] a Lumetri instance carries, if it is one of the phone's filters.
fn look_index(e: &EffectInstance) -> Option<usize> {
    if e.effect != LUMETRI || boolean(e, "hsl_on") != Some(false) {
        return None;
    }
    let tag = float(e, "hsl_hue")?;
    let n = (tag / 10.0).round();
    if n < 1.0 || (tag - n * 10.0).abs() > 0.01 {
        return None;
    }
    let i = n as usize - 1;
    (i < LOOKS.len()).then_some(i)
}

/// The filter on a clip and its intensity.
pub(crate) fn filter_of(c: &TrackItem) -> Option<(String, f64)> {
    let e = c.effects.iter().find(|e| look_index(e).is_some())?;
    let look = LOOKS.get(look_index(e)?)?;
    Some((look.id.to_string(), float(e, "hsl_denoise").unwrap_or(50.0) * 2.0))
}

/// Indices of a clip's filter instances (the tagged Lumetri and the Noise right after it).
pub(crate) fn filter_slots(c: &TrackItem) -> Vec<usize> {
    let Some(i) = c.effects.iter().position(|e| look_index(e).is_some()) else { return Vec::new() };
    let mut v = vec![i];
    if c.effects.get(i + 1).is_some_and(|e| e.effect == NOISE) {
        v.push(i + 1);
    }
    v
}

// ---------------------------------------------------------------------------------------------
// Effects

/// How an intensity (0–100) sets a parameter: `lo + (hi - lo) × intensity / 100`.
struct Scaled {
    param: &'static str,
    lo: f64,
    hi: f64,
}

/// One engine effect of a preset: fixed values and scaled ones.
struct Part {
    effect: &'static str,
    fixed: &'static [(&'static str, Fixed)],
    scaled: &'static [Scaled],
}

#[derive(Clone, Copy)]
enum Fixed {
    F(f64),
    Choice(u32),
    Bool(bool),
    Rgb(f32, f32, f32),
}

struct Effect {
    id: &'static str,
    name: &'static str,
    parts: &'static [Part],
}

const fn sc(param: &'static str, lo: f64, hi: f64) -> Scaled {
    Scaled { param, lo, hi }
}

use Fixed::{Bool as B, Choice as C, F, Rgb};

/// FilmCraft's effects, in the order the picker shows them.
const EFFECTS: &[Effect] = &[
    Effect { id: "grow", name: "Grow", parts: &[Part { effect: "grow", fixed: &[("from", F(100.0)), ("easing", C(3))], scaled: &[sc("to", 100.0, 160.0)] }] },
    Effect {
        id: "shrink",
        name: "Shrink",
        parts: &[Part { effect: "shrink", fixed: &[("to", F(100.0)), ("easing", C(3))], scaled: &[sc("from", 100.0, 160.0)] }],
    },
    Effect {
        id: "pulse",
        name: "Pulse",
        parts: &[Part { effect: "wiggle", fixed: &[("frequency", F(2.0)), ("amount", F(0.0)), ("rotation", F(0.0))], scaled: &[sc("scale", 0.0, 30.0)] }],
    },
    Effect {
        id: "easy_shake",
        name: "Easy shake",
        parts: &[Part {
            effect: "camera_shake",
            fixed: &[("frequency", F(2.0)), ("complexity", F(2.0)), ("seed", F(11.0))],
            scaled: &[sc("amount", 0.0, 60.0), sc("rotation", 0.0, 1.5), sc("zoom", 0.0, 4.0)],
        }],
    },
    Effect {
        id: "impact_shake",
        name: "Impact shake",
        parts: &[Part {
            effect: "camera_shake",
            fixed: &[("frequency", F(12.0)), ("complexity", F(4.0)), ("seed", F(23.0))],
            scaled: &[sc("amount", 0.0, 160.0), sc("rotation", 0.0, 4.0), sc("zoom", 0.0, 10.0), sc("motion_blur", 0.0, 60.0)],
        }],
    },
    Effect {
        id: "pulse_shake",
        name: "Pulse shake",
        parts: &[Part {
            effect: "camera_shake",
            fixed: &[("frequency", F(6.0)), ("complexity", F(1.0)), ("seed", F(37.0))],
            scaled: &[sc("amount", 0.0, 90.0), sc("zoom", 0.0, 12.0)],
        }],
    },
    Effect {
        id: "digital",
        name: "Digital",
        parts: &[
            Part { effect: "mosaic", fixed: &[("sharp", B(true))], scaled: &[sc("horizontal", 400.0, 60.0), sc("vertical", 400.0, 60.0)] },
            Part { effect: "rgb_split", fixed: &[("mode", C(0)), ("angle", F(0.0))], scaled: &[sc("amount", 0.0, 16.0)] },
        ],
    },
    Effect {
        id: "glitchy",
        name: "Glitchy",
        parts: &[Part {
            effect: "vr_digital_glitch",
            fixed: &[("rate", F(12.0)), ("seed", F(5.0))],
            scaled: &[sc("amplitude", 0.0, 80.0), sc("scanlines", 0.0, 40.0)],
        }],
    },
    Effect {
        id: "rgb_drift",
        name: "RGB drift",
        parts: &[Part { effect: "rgb_split", fixed: &[("mode", C(0)), ("angle", F(0.0))], scaled: &[sc("amount", 0.0, 40.0)] }],
    },
    Effect { id: "rgb_pulse", name: "RGB pulse", parts: &[Part { effect: "rgb_split", fixed: &[("mode", C(1))], scaled: &[sc("amount", 0.0, 50.0)] }] },
    Effect {
        id: "rgb_flicker",
        name: "RGB flicker",
        parts: &[
            Part { effect: "rgb_split", fixed: &[("mode", C(0)), ("angle", F(90.0))], scaled: &[sc("amount", 0.0, 30.0)] },
            Part { effect: "strobe", fixed: &[("period", F(0.25)), ("duration", F(0.04)), ("color", Rgb(1.0, 1.0, 1.0))], scaled: &[sc("blend", 0.0, 40.0)] },
        ],
    },
    Effect {
        id: "soft_leak",
        name: "Soft leak",
        parts: &[Part {
            effect: "light_leaks",
            fixed: &[("mode", C(0)), ("speed", F(0.5)), ("c1", Rgb(1.0, 0.55, 0.25)), ("c2", Rgb(1.0, 0.3, 0.45))],
            scaled: &[sc("intensity", 0.0, 90.0)],
        }],
    },
    Effect {
        id: "warm_flare",
        name: "Warm flare",
        parts: &[Part {
            effect: "light_leaks",
            fixed: &[("mode", C(1)), ("speed", F(1.0)), ("c1", Rgb(1.0, 0.6, 0.1)), ("c2", Rgb(1.0, 0.85, 0.3))],
            scaled: &[sc("intensity", 0.0, 80.0)],
        }],
    },
    Effect {
        id: "dreamy",
        name: "Dreamy",
        parts: &[Part { effect: "wonder_glow", fixed: &[("threshold", F(40.0)), ("radius", F(60.0)), ("mode", C(1))], scaled: &[sc("intensity", 0.0, 220.0)] }],
    },
    Effect {
        id: "retro_screen",
        name: "Retro screen",
        parts: &[
            Part { effect: "lens_distortion", fixed: &[("v_decentering", F(0.0)), ("h_decentering", F(0.0))], scaled: &[sc("curvature", 0.0, -30.0)] },
            Part { effect: "noise", fixed: &[("color", B(true)), ("grain_size", F(2.0))], scaled: &[sc("amount", 0.0, 30.0)] },
        ],
    },
    Effect {
        id: "security",
        name: "Security",
        parts: &[
            Part { effect: "black_white", fixed: &[], scaled: &[] },
            Part { effect: "noise", fixed: &[("color", B(false)), ("grain_size", F(3.0))], scaled: &[sc("amount", 0.0, 45.0)] },
        ],
    },
    Effect {
        id: "tube_glow",
        name: "Tube glow",
        parts: &[
            Part { effect: "wonder_glow", fixed: &[("threshold", F(55.0)), ("radius", F(30.0)), ("mode", C(0))], scaled: &[sc("intensity", 0.0, 160.0)] },
            Part { effect: "rgb_split", fixed: &[("mode", C(1))], scaled: &[sc("amount", 0.0, 12.0)] },
        ],
    },
    Effect {
        id: "gold_bokeh",
        name: "Gold bokeh",
        parts: &[
            Part { effect: "bokeh_blur", fixed: &[("shape", C(4)), ("threshold", F(60.0))], scaled: &[sc("amount", 0.0, 60.0), sc("highlight", 0.0, 90.0)] },
            Part { effect: "tint", fixed: &[("black", Rgb(0.1, 0.06, 0.0)), ("white", Rgb(1.0, 0.85, 0.5))], scaled: &[sc("amount", 0.0, 50.0)] },
        ],
    },
    Effect {
        id: "bokeh",
        name: "Bokeh",
        parts: &[Part {
            effect: "bokeh_blur",
            fixed: &[("shape", C(0)), ("threshold", F(70.0))],
            scaled: &[sc("amount", 0.0, 50.0), sc("highlight", 0.0, 70.0)],
        }],
    },
    Effect {
        id: "chroma_blur",
        name: "Chroma blur",
        parts: &[Part { effect: "channel_blur", fixed: &[("green", F(0.0)), ("alpha", F(0.0))], scaled: &[sc("red", 0.0, 60.0), sc("blue", 0.0, 30.0)] }],
    },
    Effect {
        id: "gaussian",
        name: "Gaussian",
        parts: &[Part { effect: "gaussian_blur", fixed: &[("dimensions", C(0))], scaled: &[sc("blurriness", 0.0, 60.0)] }],
    },
    Effect {
        id: "bokeh_blur",
        name: "Bokeh blur",
        parts: &[Part { effect: "bokeh_blur", fixed: &[("shape", C(5)), ("threshold", F(85.0))], scaled: &[sc("amount", 0.0, 100.0)] }],
    },
    Effect {
        id: "eye_focus",
        name: "Eye focus",
        parts: &[Part { effect: "focus_blur", fixed: &[("shape", C(0)), ("size", F(320.0)), ("feather", F(260.0))], scaled: &[sc("amount", 0.0, 60.0)] }],
    },
];

/// The effect picker's list.
pub(crate) fn effects() -> Vec<PresetInfo> {
    EFFECTS.iter().map(|e| PresetInfo { id: e.id.into(), name: e.name.into() }).collect()
}

fn fixed_value(v: Fixed) -> ParamValue {
    match v {
        Fixed::F(x) => ParamValue::Float(x),
        Fixed::Choice(c) => ParamValue::Choice(c),
        Fixed::Bool(b) => ParamValue::Bool(b),
        Fixed::Rgb(r, g, b) => ParamValue::Color([r, g, b, 1.0]),
    }
}

fn fixed_matches(e: &EffectInstance, id: &str, v: Fixed) -> bool {
    let Some(p) = e.param(id) else { return false };
    match (&p.value, v) {
        (ParamValue::Float(a), Fixed::F(b)) => (a - b).abs() < 1e-6,
        (ParamValue::Choice(a), Fixed::Choice(b)) => *a == b,
        (ParamValue::Bool(a), Fixed::Bool(b)) => *a == b,
        (ParamValue::Color(a), Fixed::Rgb(r, g, b)) => (a[0] - r).abs() < 1e-4 && (a[1] - g).abs() < 1e-4 && (a[2] - b).abs() < 1e-4,
        _ => false,
    }
}

fn part_matches(e: &EffectInstance, p: &Part) -> bool {
    e.effect == p.effect && p.fixed.iter().all(|&(id, v)| fixed_matches(e, id, v))
}

/// The effect instances of effect `id` at `amount` (0–100).
pub(crate) fn effect_instances(id: &str, amount: f64) -> Option<Vec<EffectInstance>> {
    let fx = EFFECTS.iter().find(|e| e.id == id)?;
    let k = if amount.is_finite() { amount.clamp(0.0, 100.0) / 100.0 } else { 0.5 };
    let mut out = Vec::new();
    for part in fx.parts {
        let mut e = instance(part.effect)?;
        for &(p, v) in part.fixed {
            set(&mut e, p, fixed_value(v));
        }
        for s in part.scaled {
            set(&mut e, s.param, ParamValue::Float(s.lo + (s.hi - s.lo) * k));
        }
        out.push(e);
    }
    Some(out)
}

/// Where a preset's parts sit in a clip's effects, if all of them do (consecutively).
fn effect_at(c: &TrackItem, fx: &Effect) -> Option<usize> {
    let n = fx.parts.len();
    (0..c.effects.len()).find(|&i| fx.parts.iter().enumerate().all(|(j, p)| c.effects.get(i + j).is_some_and(|e| part_matches(e, p)))).filter(|_| n > 0)
}

/// The effect on a clip and its intensity.
pub(crate) fn effect_of(c: &TrackItem) -> Option<(String, f64)> {
    // presets with more parts first: "digital" holds an RGB split that alone reads as "RGB drift"
    let mut order: Vec<&Effect> = EFFECTS.iter().collect();
    order.sort_by_key(|e| std::cmp::Reverse(e.parts.len()));
    for fx in order {
        let Some(i) = effect_at(c, fx) else { continue };
        let amount = fx
            .parts
            .iter()
            .enumerate()
            .find_map(|(j, p)| {
                let s = p.scaled.first()?;
                let v = float(c.effects.get(i + j)?, s.param)?;
                let span = s.hi - s.lo;
                (span.abs() > f64::EPSILON).then(|| ((v - s.lo) / span * 100.0).clamp(0.0, 100.0))
            })
            .unwrap_or(50.0);
        return Some((fx.id.to_string(), amount));
    }
    None
}

/// Indices of a clip's effect-preset instances.
pub(crate) fn effect_slots(c: &TrackItem) -> Vec<usize> {
    let Some((id, _)) = effect_of(c) else { return Vec::new() };
    let Some(fx) = EFFECTS.iter().find(|e| e.id == id) else { return Vec::new() };
    effect_at(c, fx).map(|i| (i..i + fx.parts.len()).collect()).unwrap_or_default()
}

// ---------------------------------------------------------------------------------------------
// Transitions

struct Transition {
    id: &'static str,
    name: &'static str,
    effect: &'static str,
    fixed: &'static [(&'static str, Fixed)],
}

const N: u32 = 0; // From North
const E: u32 = 1; // From East
const S: u32 = 2; // From South
const W: u32 = 3; // From West

/// FilmCraft's transitions, in the order the picker shows them.
const TRANSITIONS: &[Transition] = &[
    Transition { id: "dissolve", name: "Dissolve", effect: "cross_dissolve", fixed: &[] },
    Transition { id: "push_up", name: "Push up", effect: "push", fixed: &[("direction", C(S))] },
    Transition { id: "push_down", name: "Push down", effect: "push", fixed: &[("direction", C(N))] },
    Transition { id: "push_left", name: "Push left", effect: "push", fixed: &[("direction", C(E))] },
    Transition { id: "push_right", name: "Push right", effect: "push", fixed: &[("direction", C(W))] },
    Transition { id: "zoom_in", name: "Zoom in", effect: "cross_zoom", fixed: &[] },
    Transition { id: "zoom_out", name: "Zoom out", effect: "zoom_blur", fixed: &[] },
    Transition { id: "twist_left", name: "Twist left", effect: "spin_motion", fixed: &[("spin", C(1)), ("rotation", F(90.0))] },
    Transition { id: "twist_right", name: "Twist right", effect: "spin_motion", fixed: &[("spin", C(0)), ("rotation", F(90.0))] },
    Transition { id: "spin_left", name: "Spin left", effect: "spin_motion", fixed: &[("spin", C(1)), ("rotation", F(360.0))] },
    Transition { id: "spin_right", name: "Spin right", effect: "spin_motion", fixed: &[("spin", C(0)), ("rotation", F(360.0))] },
    Transition { id: "flash_white", name: "Flash white", effect: "dip_to_white", fixed: &[] },
    Transition { id: "flash_color", name: "Flash color", effect: "flash", fixed: &[("color", Rgb(1.0, 0.35, 0.6))] },
    Transition { id: "flash_blur", name: "Flash blur", effect: "glow", fixed: &[] },
    Transition { id: "light_leak", name: "Light leak", effect: "light_leak", fixed: &[] },
    Transition { id: "film_leak", name: "Film leak", effect: "chroma_leak", fixed: &[] },
    Transition { id: "pink_light", name: "Pink light", effect: "light_sweep", fixed: &[("color", Rgb(1.0, 0.45, 0.75))] },
    Transition { id: "green_light", name: "Green light", effect: "flare", fixed: &[("color", Rgb(0.45, 1.0, 0.55))] },
    Transition { id: "glitch", name: "Glitch", effect: "glitch", fixed: &[] },
    Transition { id: "rgb_error", name: "RGB error", effect: "chaos", fixed: &[] },
    Transition { id: "dial_up", name: "Dial up", effect: "vhs_damage", fixed: &[] },
    Transition { id: "tunnel_wipe", name: "Tunnel wipe", effect: "iris_round", fixed: &[] },
    Transition { id: "star_wipe", name: "Star wipe", effect: "star_wipe", fixed: &[] },
    Transition { id: "soft_wipe", name: "Soft wipe", effect: "soft_wipe", fixed: &[] },
    Transition { id: "gold_bokeh", name: "Gold bokeh", effect: "lens_blur", fixed: &[] },
    Transition { id: "fade", name: "Fade", effect: "dip_to_black", fixed: &[] },
    Transition { id: "color_fade", name: "Color fade", effect: "dip_to_color", fixed: &[] },
    Transition { id: "duo_color", name: "Duo color", effect: "solarize", fixed: &[] },
    Transition { id: "blur_dissolve", name: "Blur dissolve", effect: "blur_dissolve", fixed: &[] },
    Transition { id: "wipe_up", name: "Wipe up", effect: "linear_wipe", fixed: &[("angle", F(0.0))] },
    Transition { id: "wipe_down", name: "Wipe down", effect: "linear_wipe", fixed: &[("angle", F(180.0))] },
    Transition { id: "wipe_left", name: "Wipe left", effect: "linear_wipe", fixed: &[("angle", F(270.0))] },
    Transition { id: "wipe_right", name: "Wipe right", effect: "linear_wipe", fixed: &[("angle", F(90.0))] },
    Transition { id: "stroke_wipe", name: "Stroke wipe", effect: "neon_wipe", fixed: &[] },
    Transition { id: "wipe_out", name: "Wipe out", effect: "iris_box", fixed: &[] },
    Transition { id: "wipe_in", name: "Wipe in", effect: "barn_doors", fixed: &[] },
    Transition { id: "blinds", name: "Blinds", effect: "louver", fixed: &[] },
    Transition { id: "split_horizontal", name: "Split horizontal", effect: "split", fixed: &[("orientation", C(0))] },
    Transition { id: "split_vertical", name: "Split vertical", effect: "split", fixed: &[("orientation", C(1))] },
    Transition { id: "side_blur", name: "Side blur", effect: "directional_blur_transition", fixed: &[("angle", F(90.0))] },
    Transition { id: "straight_blur", name: "Straight blur", effect: "directional_blur_transition", fixed: &[("angle", F(0.0))] },
    Transition { id: "kaleido_left", name: "Kaleido left", effect: "kaleidoscope", fixed: &[("segments", F(6.0))] },
    Transition { id: "kaleido_right", name: "Kaleido right", effect: "kaleidoscope", fixed: &[("segments", F(8.0))] },
    Transition { id: "mandala_left", name: "Mandala left", effect: "kaleidoscope", fixed: &[("segments", F(12.0))] },
    Transition { id: "mandala_right", name: "Mandala right", effect: "kaleidoscope", fixed: &[("segments", F(16.0))] },
    Transition { id: "chroma_zoom", name: "Chroma zoom", effect: "radial_blur", fixed: &[] },
    Transition { id: "chroma_burn", name: "Chroma burn", effect: "burn_chroma", fixed: &[] },
    Transition { id: "warp_in", name: "Warp in", effect: "liquid_distortion", fixed: &[] },
    Transition { id: "warp_out", name: "Warp out", effect: "wave", fixed: &[] },
    Transition { id: "cross_zoom", name: "Cross zoom", effect: "motion_camera", fixed: &[] },
];

/// The transition picker's list.
pub(crate) fn transitions() -> Vec<PresetInfo> {
    TRANSITIONS.iter().map(|t| PresetInfo { id: t.id.into(), name: t.name.into() }).collect()
}

/// A transition preset's engine effect and its fixed parameters, as JSON for
/// `sequence.applyVideoTransition`.
pub(crate) fn transition_params(id: &str) -> Option<(&'static str, serde_json::Value)> {
    let t = TRANSITIONS.iter().find(|t| t.id == id)?;
    let mut m = serde_json::Map::new();
    for &(p, v) in t.fixed {
        let j = match v {
            Fixed::F(x) => serde_json::json!(x),
            Fixed::Choice(c) => serde_json::json!(c),
            Fixed::Bool(b) => serde_json::json!(b),
            Fixed::Rgb(r, g, b) => serde_json::json!([r, g, b, 1.0]),
        };
        m.insert(p.to_string(), j);
    }
    Some((t.effect, serde_json::Value::Object(m)))
}

/// The engine effect instance of transition preset `id`.
pub(crate) fn transition_instance(id: &str) -> Option<EffectInstance> {
    let t = TRANSITIONS.iter().find(|t| t.id == id)?;
    let mut e = instance(t.effect)?;
    for &(p, v) in t.fixed {
        set(&mut e, p, fixed_value(v));
    }
    Some(e)
}

/// Which preset a transition's effect is (the engine effect's id when it is none of them).
pub(crate) fn transition_of(e: &EffectInstance) -> String {
    // presets that pin more parameters first, so "Twist left" isn't read as a plainer sibling
    let mut order: Vec<&Transition> = TRANSITIONS.iter().collect();
    order.sort_by_key(|t| std::cmp::Reverse(t.fixed.len()));
    order
        .into_iter()
        .find(|t| t.effect == e.effect && t.fixed.iter().all(|&(id, v)| fixed_matches(e, id, v)))
        .map(|t| t.id.to_string())
        .unwrap_or_else(|| e.effect.clone())
}
