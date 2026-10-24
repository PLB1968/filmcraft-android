//! Hardware video decoding on Android: H.264 and HEVC through the phone's own decoders
//! (`AMediaCodec`, the NDK's MediaCodec), registered in front of the engine's software decoders.
//!
//! The software decoders stay the reference, as on the desktop: a stream the phone can't take
//! (4:2:2, 12-bit, interlaced, no decoder free) goes to them from the start, and a hardware decoder
//! that fails mid-stream hands over to one. Thumbnails and other background work decode in
//! software, so the phone's few hardware sessions go to the monitors.
//!
//! Pictures come out of the decoder as NV12 / I420 (8-bit) or P010 (10-bit) and are copied into
//! the engine's planar frames, cropped to the stream's own output rectangle, so they match the
//! software decoder's pictures.

// only Android builds make hardware decoders; elsewhere the code is just tested
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use filmcraft_codecs::hw::{self, NalCodec, NalStreamInfo};
use filmcraft_codecs::{CodecError, DecodedFrame, Result, VideoDecoder};
use filmcraft_frame::{Chroma, PixelData, VideoFrame};
use filmcraft_isobmff::SampleEntry;
use std::sync::Arc;

/// Settings › Hardware decoding: Auto (the phone's decoders where they can) or Off.
pub(crate) fn set_enabled(on: bool) {
    hw::set_hardware_decoding(on);
}

/// Put the phone's decoders in front of the software ones (once, at start-up).
pub(crate) fn register() {
    #[cfg(target_os = "android")]
    {
        filmcraft_codecs::register_video_decoder(factory);
        hw::set_hw_backend("MediaCodec");
    }
}

#[cfg(target_os = "android")]
fn factory(e: &SampleEntry) -> Option<Result<Box<dyn VideoDecoder>>> {
    let info = match NalStreamInfo::from_entry(e)? {
        Ok(i) => i,
        Err(_) => return None,
    };
    if !hw::hardware_decoding() || filmcraft_media::cancel::background() || !supported(&info) {
        hw::note_hw_declined();
        return None;
    }
    match android::Codec::open(&info) {
        Some(codec) => {
            hw::note_hw_session();
            Some(Ok(Box::new(Hybrid { entry: e.clone(), info, hw: Some(codec), sw: None, pending: false })))
        }
        None => {
            hw::note_hw_declined();
            None
        }
    }
}

/// Streams worth handing to the phone: progressive 4:2:0, 8 or 10 bits.
fn supported(i: &NalStreamInfo) -> bool {
    i.chroma_format_idc == 1 && !i.interlaced && matches!(i.bit_depth_luma, 8 | 10) && i.bit_depth_luma == i.bit_depth_chroma && i.crop.2 > 0 && i.crop.3 > 0
}

/// The phone's decoder, and the software one it hands over to when it fails.
struct Hybrid {
    entry: SampleEntry,
    info: NalStreamInfo,
    hw: Option<Box<dyn HwDecoder>>,
    sw: Option<Box<dyn VideoDecoder>>,
    /// Switched to software since the last restart point: pictures until the next one may fail.
    pending: bool,
}

/// What [`Hybrid`] needs from a hardware decoder (the Android one; a fake in the tests).
trait HwDecoder: Send {
    fn decode(&mut self, sample: &[u8], pts: i64) -> std::result::Result<Vec<DecodedFrame>, String>;
    fn flush(&mut self) -> std::result::Result<Vec<DecodedFrame>, String>;
    fn reset(&mut self) -> std::result::Result<(), String>;
}

impl Hybrid {
    /// The phone's decoder failed: continue in software from here on.
    fn fall_back(&mut self, why: &str) -> Result<()> {
        log::warn!("hardware decoding failed ({why}); continuing in software");
        hw::note_hw_fallback();
        self.hw = None;
        self.sw = Some(filmcraft_codecs::software_video_decoder(&self.entry)?);
        self.pending = true;
        Ok(())
    }
}

impl VideoDecoder for Hybrid {
    fn decode(&mut self, sample: &[u8], pts: i64) -> Result<Vec<DecodedFrame>> {
        if let Some(h) = self.hw.as_mut() {
            match h.decode(sample, pts) {
                Ok(out) => {
                    hw::note_hw_frames(out.len());
                    return Ok(out);
                }
                Err(e) => self.fall_back(&e)?,
            }
        }
        let sw = self.sw.as_mut().ok_or_else(|| CodecError::Decode("no video decoder".into()))?;
        if self.pending && self.info.is_irap(sample) {
            self.pending = false;
        }
        sw.decode(sample, pts)
    }

    fn flush(&mut self) -> Vec<DecodedFrame> {
        if let Some(h) = self.hw.as_mut() {
            match h.flush() {
                Ok(out) => {
                    hw::note_hw_frames(out.len());
                    return out;
                }
                Err(e) => {
                    if self.fall_back(&e).is_err() {
                        return Vec::new();
                    }
                }
            }
        }
        self.sw.as_mut().map(|d| d.flush()).unwrap_or_default()
    }

    fn reset(&mut self) {
        if let Some(h) = self.hw.as_mut()
            && let Err(e) = h.reset()
        {
            let _ = self.fall_back(&e);
        }
        if let Some(d) = self.sw.as_mut() {
            d.reset();
        }
    }

    fn name(&self) -> &str {
        if self.hw.is_some() { "MediaCodec" } else { "FilmCraft (after MediaCodec)" }
    }

    fn is_random_access(&self, sample: &[u8]) -> Option<bool> {
        self.info.is_random_access(sample)
    }

    fn is_disposable(&self, sample: &[u8]) -> bool {
        self.info.is_disposable(sample)
    }
}

/// A length-prefixed sample as Annex B (start codes), which MediaCodec takes.
fn annex_b(sample: &[u8], length_size: usize, out: &mut Vec<u8>) {
    out.clear();
    let mut i = 0;
    while i + length_size <= sample.len() {
        let n = sample[i..i + length_size].iter().fold(0usize, |a, &b| (a << 8) | b as usize);
        i += length_size;
        let end = (i + n).min(sample.len());
        out.extend_from_slice(&[0, 0, 0, 1]);
        out.extend_from_slice(&sample[i..end]);
        i = end;
    }
}

/// The codec-specific data MediaCodec is configured with: H.264's SPS (`csd-0`) and PPS
/// (`csd-1`); HEVC's VPS, SPS and PPS together (`csd-0`). Each with a start code.
fn codec_data(i: &NalStreamInfo) -> Vec<Vec<u8>> {
    let with_start = |sets: &mut dyn Iterator<Item = &Vec<u8>>| -> Vec<u8> {
        let mut v = Vec::new();
        for p in sets {
            v.extend_from_slice(&[0, 0, 0, 1]);
            v.extend_from_slice(p);
        }
        v
    };
    match i.codec {
        NalCodec::H264 => {
            let sps = with_start(&mut i.parameter_sets.iter().filter(|p| p.first().is_some_and(|h| h & 0x1f == 7)));
            let pps = with_start(&mut i.parameter_sets.iter().filter(|p| p.first().is_some_and(|h| h & 0x1f == 8)));
            vec![sps, pps]
        }
        NalCodec::Hevc => vec![with_start(&mut i.parameter_sets.iter())],
    }
}

/// How a decoded picture lies in the decoder's output buffer.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Layout {
    /// Bytes from one row to the next (luma; chroma rows are the same for NV12 / P010, half for I420).
    stride: usize,
    /// Rows of the luma plane before the chroma (at least the picture's height).
    slice_height: usize,
    format: PixelLayout,
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum PixelLayout {
    /// Y then interleaved U V (`COLOR_FormatYUV420SemiPlanar`, Qualcomm's 32m variant).
    Nv12,
    /// Y, U, V planes (`COLOR_FormatYUV420Planar`).
    I420,
    /// 16-bit little-endian Y then interleaved U V, the value in the top 10 bits.
    P010,
}

impl PixelLayout {
    /// MediaCodecInfo.CodecCapabilities color formats.
    fn of(color_format: i32) -> Option<Self> {
        match color_format {
            21 | 0x7FA3_0C04 | 0x7F42_0888 => Some(Self::Nv12),
            19 => Some(Self::I420),
            54 => Some(Self::P010),
            _ => None,
        }
    }
}

/// Copy picture `buf` (laid out as `l`) into an engine frame, cropped to `crop` (x, y, w, h).
fn to_frame(buf: &[u8], l: Layout, i: &NalStreamInfo) -> std::result::Result<VideoFrame, String> {
    let (cx, cy, w, h) = (i.crop.0 as usize & !1, i.crop.1 as usize & !1, i.crop.2 as usize, i.crop.3 as usize);
    let (cw, ch) = (w.div_ceil(2), h.div_ceil(2));
    let rows = l.slice_height.max(cy + h);
    let short = || "the decoder's picture is smaller than expected".to_string();
    let data = match l.format {
        PixelLayout::Nv12 | PixelLayout::I420 => {
            let mut y = Vec::with_capacity(w * h);
            for r in cy..cy + h {
                let s = r * l.stride + cx;
                y.extend_from_slice(buf.get(s..s + w).ok_or_else(short)?);
            }
            let mut u = Vec::with_capacity(cw * ch);
            let mut v = Vec::with_capacity(cw * ch);
            let base = l.stride * rows;
            if l.format == PixelLayout::Nv12 {
                for r in cy / 2..cy / 2 + ch {
                    let s = base + r * l.stride + cx;
                    let row = buf.get(s..s + cw * 2).ok_or_else(short)?;
                    for p in row.as_chunks::<2>().0 {
                        u.push(p[0]);
                        v.push(p[1]);
                    }
                }
            } else {
                let cs = l.stride / 2;
                let vbase = base + cs * rows.div_ceil(2);
                for r in cy / 2..cy / 2 + ch {
                    let s = base + r * cs + cx / 2;
                    u.extend_from_slice(buf.get(s..s + cw).ok_or_else(short)?);
                    let s = vbase + r * cs + cx / 2;
                    v.extend_from_slice(buf.get(s..s + cw).ok_or_else(short)?);
                }
            }
            PixelData::Yuv8 { planes: [Arc::new(y), Arc::new(u), Arc::new(v)], chroma: Chroma::C420, alpha: None }
        }
        PixelLayout::P010 => {
            let px = |b: &[u8; 2]| u16::from_le_bytes(*b) >> 6;
            let mut y = Vec::with_capacity(w * h);
            for r in cy..cy + h {
                let s = r * l.stride + cx * 2;
                y.extend(buf.get(s..s + w * 2).ok_or_else(short)?.as_chunks::<2>().0.iter().map(px));
            }
            let mut u = Vec::with_capacity(cw * ch);
            let mut v = Vec::with_capacity(cw * ch);
            let base = l.stride * rows;
            for r in cy / 2..cy / 2 + ch {
                let s = base + r * l.stride + cx * 2;
                for p in buf.get(s..s + cw * 4).ok_or_else(short)?.as_chunks::<4>().0 {
                    u.push(px(&[p[0], p[1]]));
                    v.push(px(&[p[2], p[3]]));
                }
            }
            PixelData::Yuv16 { planes: [Arc::new(y), Arc::new(u), Arc::new(v)], chroma: Chroma::C420, bits: 10, alpha: None }
        }
    };
    Ok(VideoFrame { width: w as u32, height: h as u32, data, color: i.color, par: i.par, pts: filmcraft_time::Tick::ZERO })
}

#[cfg(target_os = "android")]
mod android {
    //! The NDK's MediaCodec (through the `ndk` crate's safe wrapper), used synchronously: input
    //! buffers in, output buffers (bytes, no surface) out. A MediaCodec handle may not move between
    //! threads, so each decoder lives on a thread of its own and the engine talks to it over
    //! channels (the samples and pictures move, nothing is copied twice).

    use std::sync::mpsc::{Receiver, Sender, channel};
    use std::time::Duration;

    use ndk::media::media_codec::{DequeuedInputBufferResult, DequeuedOutputBufferInfoResult, MediaCodec, MediaCodecDirection};
    use ndk::media::media_format::MediaFormat;

    use super::{DecodedFrame, HwDecoder, Layout, NalCodec, NalStreamInfo, PixelLayout, annex_b, codec_data, to_frame};

    const FLAG_CODEC_CONFIG: u32 = 2;
    const FLAG_END_OF_STREAM: u32 = 4;

    type Reply = Result<Vec<DecodedFrame>, String>;

    enum Cmd {
        Decode(Vec<u8>, i64),
        Flush,
        Reset,
    }

    /// The engine's end of a decoder thread.
    pub(super) struct Codec {
        tx: Sender<Cmd>,
        rx: Receiver<Reply>,
    }

    impl Codec {
        pub(super) fn open(i: &NalStreamInfo) -> Option<Box<dyn HwDecoder>> {
            let (tx, cmds) = channel::<Cmd>();
            let (replies, rx) = channel::<Reply>();
            let (ready_tx, ready) = channel::<bool>();
            let info = i.clone();
            std::thread::Builder::new()
                .name("rushcraft-mediacodec".into())
                .spawn(move || {
                    let Some(mut w) = Worker::open(info) else {
                        let _ = ready_tx.send(false);
                        return;
                    };
                    let _ = ready_tx.send(true);
                    // ends when the engine drops its end
                    while let Ok(cmd) = cmds.recv() {
                        let r = match cmd {
                            Cmd::Decode(sample, pts) => w.decode(&sample, pts),
                            Cmd::Flush => w.flush(),
                            Cmd::Reset => w.reset().map(|()| Vec::new()),
                        };
                        if replies.send(r).is_err() {
                            break;
                        }
                    }
                    let _ = w.codec.stop();
                })
                .ok()?;
            ready.recv().ok().filter(|ok| *ok)?;
            Some(Box::new(Codec { tx, rx }))
        }

        fn ask(&mut self, cmd: Cmd) -> Reply {
            self.tx.send(cmd).map_err(|_| "the decoder thread stopped".to_string())?;
            self.rx.recv().map_err(|_| "the decoder thread stopped".to_string())?
        }
    }

    impl HwDecoder for Codec {
        fn decode(&mut self, sample: &[u8], pts: i64) -> Reply {
            self.ask(Cmd::Decode(sample.to_vec(), pts))
        }
        fn flush(&mut self) -> Reply {
            self.ask(Cmd::Flush)
        }
        fn reset(&mut self) -> Result<(), String> {
            self.ask(Cmd::Reset).map(|_| ())
        }
    }

    /// The decoder itself, on its thread.
    struct Worker {
        codec: MediaCodec,
        info: NalStreamInfo,
        layout: Option<Layout>,
        /// Samples queued whose pictures haven't come out yet.
        in_flight: usize,
        /// End of stream was queued: the codec needs a flush before more input.
        ended: bool,
        scratch: Vec<u8>,
    }

    /// How pictures lie in `codec`'s output buffers, from its output format.
    fn layout_of(codec: &MediaCodec, info: &NalStreamInfo) -> Result<Layout, String> {
        let f = codec.output_format();
        let width = f.i32("width").unwrap_or(info.coded.0 as i32).max(1) as usize;
        let height = f.i32("height").unwrap_or(info.coded.1 as i32).max(1) as usize;
        let color = f.i32("color-format").unwrap_or(21);
        let format = PixelLayout::of(color).ok_or_else(|| format!("unknown colour format {color:#x}"))?;
        let bytes = if format == PixelLayout::P010 { 2 } else { 1 };
        let stride = f.i32("stride").map(|s| s as usize).filter(|&s| s >= width * bytes).unwrap_or(width * bytes);
        let slice_height = f.i32("slice-height").map(|s| s as usize).filter(|&s| s >= height).unwrap_or(height);
        Ok(Layout { stride, slice_height, format })
    }

    fn err(what: &str, e: impl std::fmt::Debug) -> String {
        format!("{what}: {e:?}")
    }

    impl Worker {
        fn open(i: NalStreamInfo) -> Option<Self> {
            let mime = match i.codec {
                NalCodec::H264 => "video/avc",
                NalCodec::Hevc => "video/hevc",
            };
            let codec = MediaCodec::from_decoder_type(mime)?;
            let mut f = MediaFormat::new();
            f.set_str("mime", mime);
            f.set_i32("width", i.coded.0 as i32);
            f.set_i32("height", i.coded.1 as i32);
            for (n, d) in codec_data(&i).iter().enumerate() {
                f.set_buffer(["csd-0", "csd-1", "csd-2"][n.min(2)], d);
            }
            codec.configure(&f, None, MediaCodecDirection::Decoder).ok()?;
            codec.start().ok()?;
            Some(Worker { codec, info: i, layout: None, in_flight: 0, ended: false, scratch: Vec::new() })
        }

        /// The output buffer's layout, from the codec's output format.
        fn read_layout(&mut self) -> Result<(), String> {
            self.layout = Some(layout_of(&self.codec, &self.info)?);
            Ok(())
        }

        /// Take every picture that is ready, waiting up to `wait` for the first; `until_end` keeps
        /// going until the end of stream comes out.
        fn drain(&mut self, out: &mut Vec<DecodedFrame>, mut wait: Duration, until_end: bool) -> Result<(), String> {
            let mut spins = 0;
            loop {
                match self.codec.dequeue_output_buffer(wait).map_err(|e| err("dequeueOutputBuffer", e))? {
                    DequeuedOutputBufferInfoResult::Buffer(b) => {
                        let (flags, offset, size, pts) = (b.info().flags(), b.info().offset(), b.info().size(), b.info().presentation_time_us());
                        let mut picture = None;
                        if size > 0 && flags & FLAG_CODEC_CONFIG == 0 {
                            if self.layout.is_none() {
                                self.layout = Some(layout_of(&self.codec, &self.info)?);
                            }
                            let buf = b.buffer();
                            let start = (offset.max(0) as usize).min(buf.len());
                            let end = (start + size.max(0) as usize).min(buf.len());
                            picture = Some(self.layout.ok_or_else(|| "no layout".to_string()).and_then(|l| to_frame(&buf[start..end], l, &self.info)));
                        }
                        self.codec.release_output_buffer(b, false).map_err(|e| err("releaseOutputBuffer", e))?;
                        if let Some(p) = picture {
                            self.in_flight = self.in_flight.saturating_sub(1);
                            out.push(DecodedFrame { pts, frame: p?, draft: false });
                        }
                        if flags & FLAG_END_OF_STREAM != 0 {
                            return Ok(());
                        }
                        wait = if until_end { Duration::from_millis(20) } else { Duration::ZERO };
                    }
                    DequeuedOutputBufferInfoResult::OutputFormatChanged => self.read_layout()?,
                    DequeuedOutputBufferInfoResult::OutputBuffersChanged => {}
                    DequeuedOutputBufferInfoResult::TryAgainLater => {
                        if !until_end {
                            return Ok(());
                        }
                        spins += 1;
                        // the end of stream should come out within a couple of seconds
                        if spins > 100 {
                            return Err("the decoder didn't finish".into());
                        }
                        wait = Duration::from_millis(20);
                    }
                }
            }
        }

        fn queue(&mut self, data: &[u8], pts: i64, flags: u32, out: &mut Vec<DecodedFrame>) -> Result<(), String> {
            for _ in 0..200 {
                match self.codec.dequeue_input_buffer(Duration::from_millis(10)).map_err(|e| err("dequeueInputBuffer", e))? {
                    DequeuedInputBufferResult::Buffer(mut b) => {
                        let room = b.buffer_mut();
                        if room.len() < data.len() {
                            let room = room.len();
                            let _ = self.codec.queue_input_buffer(b, 0, 0, pts as u64, 0);
                            return Err(format!("input buffer too small ({room} < {})", data.len()));
                        }
                        for (d, s) in room.iter_mut().zip(data) {
                            d.write(*s);
                        }
                        return self.codec.queue_input_buffer(b, 0, data.len(), pts as u64, flags).map_err(|e| err("queueInputBuffer", e));
                    }
                    // every input buffer is in use: take the pictures that are ready to free some
                    DequeuedInputBufferResult::TryAgainLater => self.drain(out, Duration::ZERO, false)?,
                }
            }
            Err("no input buffer came free".into())
        }

        fn decode(&mut self, sample: &[u8], pts: i64) -> Result<Vec<DecodedFrame>, String> {
            if self.ended {
                self.reset()?;
            }
            let mut out = Vec::new();
            let mut data = std::mem::take(&mut self.scratch);
            annex_b(sample, self.info.length_size, &mut data);
            let r = self.queue(&data, pts, 0, &mut out);
            self.scratch = data;
            r?;
            self.in_flight += 1;
            // keep the pipeline moving: wait a little when it holds more than reordering needs
            let wait = if self.in_flight > self.info.reorder + 3 { Duration::from_millis(15) } else { Duration::ZERO };
            self.drain(&mut out, wait, false)?;
            Ok(out)
        }

        fn flush(&mut self) -> Result<Vec<DecodedFrame>, String> {
            let mut out = Vec::new();
            if self.ended {
                return Ok(out);
            }
            self.queue(&[], 0, FLAG_END_OF_STREAM, &mut out)?;
            self.ended = true;
            self.drain(&mut out, Duration::from_millis(20), true)?;
            self.in_flight = 0;
            Ok(out)
        }

        fn reset(&mut self) -> Result<(), String> {
            self.in_flight = 0;
            self.ended = false;
            self.codec.flush().map_err(|e| err("flush", e))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(crop: (u32, u32, u32, u32)) -> NalStreamInfo {
        NalStreamInfo {
            codec: NalCodec::Hevc,
            length_size: 4,
            highest_tid: None,
            parameter_sets: vec![vec![0x40, 1], vec![0x42, 1], vec![0x44, 1]],
            coded: (8, 4),
            crop,
            chroma_format_idc: 1,
            bit_depth_luma: 8,
            bit_depth_chroma: 8,
            interlaced: false,
            profile_idc: 1,
            color: filmcraft_color::ColorInfo::REC709,
            par: (1, 1),
            reorder: 0,
        }
    }

    #[test]
    fn samples_become_annex_b_and_parameter_sets_codec_data() {
        let mut out = Vec::new();
        annex_b(&[0, 0, 0, 2, 0x26, 1, 0, 0, 0, 1, 0x02], 4, &mut out);
        assert_eq!(out, vec![0, 0, 0, 1, 0x26, 1, 0, 0, 0, 1, 0x02]);
        // a length past the end is cut, not read beyond
        annex_b(&[0, 0, 0, 9, 0x26], 4, &mut out);
        assert_eq!(out, vec![0, 0, 0, 1, 0x26]);
        assert_eq!(codec_data(&info((0, 0, 8, 4))), vec![vec![0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1]]);
        let mut h = info((0, 0, 8, 4));
        h.codec = NalCodec::H264;
        h.parameter_sets = vec![vec![0x67, 9], vec![0x68, 8]];
        assert_eq!(codec_data(&h), vec![vec![0, 0, 0, 1, 0x67, 9], vec![0, 0, 0, 1, 0x68, 8]]);
    }

    #[test]
    fn nv12_i420_and_p010_pictures_are_cropped_into_planar_frames() {
        // an 8 x 4 picture in a buffer 10 bytes wide and 6 rows tall, cropped to 6 x 2 at (2, 2)
        let (stride, rows) = (10usize, 6usize);
        let mut nv12 = vec![0u8; stride * rows * 3 / 2];
        for r in 0..rows {
            for c in 0..stride {
                nv12[r * stride + c] = (r * 16 + c) as u8;
            }
        }
        for r in 0..rows / 2 {
            for c in 0..stride / 2 {
                nv12[stride * rows + r * stride + c * 2] = 100 + (r * 10 + c) as u8;
                nv12[stride * rows + r * stride + c * 2 + 1] = 200 + (r * 10 + c) as u8;
            }
        }
        let i = info((2, 2, 6, 2));
        let f = to_frame(&nv12, Layout { stride, slice_height: rows, format: PixelLayout::Nv12 }, &i).unwrap();
        assert_eq!((f.width, f.height), (6, 2));
        let PixelData::Yuv8 { planes, chroma, .. } = &f.data else { panic!("8-bit") };
        assert_eq!(*chroma, Chroma::C420);
        assert_eq!(planes[0][..6], [34, 35, 36, 37, 38, 39]);
        assert_eq!(planes[0][6..], [50, 51, 52, 53, 54, 55]);
        assert_eq!(planes[1][..], [111, 112, 113]);
        assert_eq!(planes[2][..], [211, 212, 213]);
        // I420: U then V planes, each half the stride
        let mut i420 = nv12[..stride * rows].to_vec();
        i420.extend((0..(stride / 2) * (rows / 2)).map(|k| 100 + ((k / 5) * 10 + k % 5) as u8));
        i420.extend((0..(stride / 2) * (rows / 2)).map(|k| 200 + ((k / 5) * 10 + k % 5) as u8));
        let f = to_frame(&i420, Layout { stride, slice_height: rows, format: PixelLayout::I420 }, &i).unwrap();
        let PixelData::Yuv8 { planes, .. } = &f.data else { panic!("8-bit") };
        assert_eq!((planes[1][..].to_vec(), planes[2][..].to_vec()), (vec![111, 112, 113], vec![211, 212, 213]));
        // P010: 10 bits in the top of each little-endian 16-bit sample
        let s16 = stride * 2;
        let mut p010 = vec![0u8; s16 * rows * 3 / 2];
        for r in 0..rows {
            for c in 0..stride {
                let v = ((r * 100 + c) as u16) << 6;
                p010[r * s16 + c * 2..r * s16 + c * 2 + 2].copy_from_slice(&v.to_le_bytes());
            }
        }
        let f = to_frame(&p010, Layout { stride: s16, slice_height: rows, format: PixelLayout::P010 }, &i).unwrap();
        let PixelData::Yuv16 { planes, bits, .. } = &f.data else { panic!("10-bit") };
        assert_eq!(*bits, 10);
        assert_eq!(planes[0][..3], [202, 203, 204]);
        // a buffer too small for the picture is an error, not a crash
        assert!(to_frame(&nv12[..20], Layout { stride, slice_height: rows, format: PixelLayout::Nv12 }, &i).is_err());
    }

    /// A hardware decoder that fails on its second sample.
    struct Flaky(usize);
    impl HwDecoder for Flaky {
        fn decode(&mut self, _sample: &[u8], _pts: i64) -> std::result::Result<Vec<DecodedFrame>, String> {
            self.0 += 1;
            if self.0 > 1 { Err("gone".into()) } else { Ok(Vec::new()) }
        }
        fn flush(&mut self) -> std::result::Result<Vec<DecodedFrame>, String> {
            Ok(Vec::new())
        }
        fn reset(&mut self) -> std::result::Result<(), String> {
            Ok(())
        }
    }

    #[test]
    fn a_failing_hardware_decoder_hands_over_to_software() {
        // an entry no software decoder takes: the hand-over itself is what's tested
        let entry = SampleEntry {
            format: filmcraft_isobmff::FourCc(*b"hvc1"),
            data_reference_index: 1,
            codec: filmcraft_isobmff::CodecConfig::Mp3,
            video: None,
            audio: None,
            bitrate: None,
        };
        let mut d = Hybrid { entry, info: info((0, 0, 8, 4)), hw: Some(Box::new(Flaky(0))), sw: None, pending: false };
        assert!(d.decode(&[0, 0, 0, 2, 0x26, 1], 0).is_ok());
        assert_eq!(d.name(), "MediaCodec");
        // the second sample fails in hardware: software takes over (or says it can't)
        let _ = d.decode(&[0, 0, 0, 2, 0x02, 1], 1);
        assert!(d.hw.is_none());
    }
}
