package io.github.artcraftandroid.rushcraft.ui.editor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.rushcraft.core.CaptionLook
import io.github.artcraftandroid.rushcraft.core.CaptionPosition
import io.github.artcraftandroid.rushcraft.core.ModelPackage
import io.github.artcraftandroid.rushcraft.core.SpeechAudio
import io.github.artcraftandroid.rushcraft.core.SpeechSettings
import io.github.artcraftandroid.rushcraft.core.VoiceInfo
import io.github.artcraftandroid.rushcraft.core.speechModels
import io.github.artcraftandroid.rushcraft.core.voicePackage
import io.github.artcraftandroid.rushcraft.core.voices
import io.github.artcraftandroid.rushcraft.engine.ModelDownloads
import io.github.artcraftandroid.rushcraft.engine.megabytes
import io.github.artcraftandroid.rushcraft.ui.DialogButton
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcDialog
import io.github.artcraftandroid.rushcraft.ui.FcSize
import io.github.artcraftandroid.rushcraft.ui.FcSlider
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.Inter
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.PanelAction
import io.github.artcraftandroid.rushcraft.ui.PrimaryButton
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.SliderRow
import io.github.artcraftandroid.rushcraft.ui.ToolPanel
import io.github.artcraftandroid.rushcraft.ui.glyph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Languages captions can be made in (Whisper knows many more; these are the common ones). */
private val LANGUAGES = listOf(
    "auto" to "Detect", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian",
    "ar" to "Arabic", "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
)

/** The speech models by what they are good at. */
private fun modelLabel(id: String) = if (id == "whisper-tiny") "Faster" else "More accurate"

private fun prefs(ctx: Context) = ctx.getSharedPreferences("speech", Context.MODE_PRIVATE)

/** Captions: made on the phone from what is said, then their size, place and box. */
@Composable
fun CaptionsPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var models by remember { mutableStateOf<List<ModelPackage>>(emptyList()) }
    var model by remember { mutableStateOf(prefs(ctx).getString("model", "whisper-base") ?: "whisper-base") }
    var language by remember { mutableStateOf(prefs(ctx).getString("language", "auto") ?: "auto") }
    var asking by remember { mutableStateOf<ModelPackage?>(null) }
    val download = ModelDownloads.state
    LaunchedEffect(download == null) { models = withContext(Dispatchers.IO) { speechModels() } }
    val running = ed.captionProgress
    val has = ed.timeline?.captions?.isNotEmpty() == true
    var look by remember { mutableStateOf<CaptionLook?>(null) }
    LaunchedEffect(ed.timeline?.revision, has) { look = if (has) ed.captionLook() else null }
    fun go() {
        prefs(ctx).edit().putString("model", model).putString("language", language).apply()
        val m = models.firstOrNull { it.id == model } ?: return
        if (m.installed) ed.makeCaptions(model, language) else asking = m
    }
    ToolPanel(
        "Captions", close, modifier, tag = "captions_panel",
        actions = { PanelAction(LucideIcons.Trash2.glyph(), "Delete all captions", { ed.deleteCaptions() }, enabled = has && running == null, tag = "captions_delete_all_button") },
    ) {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        Column(Modifier.fillMaxWidth()) {
            when {
                running != null -> Column(pad) { ProgressBlock(running.stage, running.progress.toFloat(), "Stop", tag = "captions_stop_button") { ed.cancelCaptions() } }
                download != null && models.any { it.id == download.id } -> Column(pad) { DownloadBlock(download) }
                else -> {
                    Text("Spoken language", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        for ((id, name) in LANGUAGES) SelectChip(name, language == id, { language = id }, tag = "captions_language_$id")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Speech model", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                    Spacer(Modifier.height(8.dp))
                    ChipRow {
                        for (m in models) {
                            val label = modelLabel(m.id) + if (m.installed) "" else " · " + megabytes(m.missing.toLong())
                            SelectChip(label, model == m.id, { model = m.id }, tag = "captions_model_${m.id}")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(if (has) "Make captions again" else "Make captions", { go() }, modifier = pad, enabled = models.isNotEmpty(), tag = "captions_make_button")
                    Spacer(Modifier.height(8.dp))
                    Text("Captions are made on this phone. The sound never leaves it.", style = FcType.BodySmall, color = Fc.Detail, modifier = pad)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val l = look
        if (has && l != null && running == null) {
            HorizontalDivider(color = Fc.Divider, thickness = 1.dp)
            Spacer(Modifier.height(12.dp))
            SliderRow("Size", "${(l.scale * 100).roundToInt()}%") {
                FcSlider(
                    fraction = ((l.scale - 0.5) / 1.5).toFloat(),
                    onChange = { f ->
                        val v = l.copy(scale = Math.round((0.5 + f * 1.5) * 20) / 20.0)
                        look = v
                        ed.setCaptionLook(v, merge = true)
                    },
                    snapTo = (0.5f / 1.5f),
                    description = "Caption size",
                    tag = "captions_size_slider",
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((p, name) in listOf(CaptionPosition.TOP to "Top", CaptionPosition.MIDDLE to "Middle", CaptionPosition.BOTTOM to "Bottom")) {
                    SelectChip(name, l.position == p, {
                        val v = l.copy(position = p)
                        look = v
                        ed.setCaptionLook(v, merge = false)
                    }, tag = "captions_position_${name.lowercase()}")
                }
                SelectChip("Box", l.background, {
                    val v = l.copy(background = !l.background)
                    look = v
                    ed.setCaptionLook(v, merge = false)
                }, tag = "captions_box_chip")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    asking?.let { m ->
        DownloadDialog(
            title = "Download the speech model?",
            text = "Captions are made by a speech model on the phone (OpenAI's Whisper, MIT licence). It's ${megabytes(m.missing.toLong())}, downloaded once from Hugging Face, then works offline.",
            onDismiss = { asking = null },
            onConfirm = {
                asking = null
                val (mid, lang) = model to language
                ModelDownloads.start(m) { android.os.Handler(android.os.Looper.getMainLooper()).post { ed.makeCaptions(mid, lang) } }
            },
        )
    }
}

/** A row of chips that scrolls sideways under the panel's edges rather than stopping at its padding. */
@Composable
private fun ChipRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ProgressBlock(stage: String, fraction: Float, button: String, tag: String, onClick: () -> Unit) {
    Text(stage, style = FcType.Label, color = Fc.Body)
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Fc.SliderOff)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(Fc.Accent))
        }
        Spacer(Modifier.width(12.dp))
        Text("${(fraction * 100).roundToInt()}%", style = FcType.Value, color = Fc.Secondary)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.testTag(tag)) { DialogButton(button, onClick, filled = false) }
    }
}

@Composable
private fun DownloadBlock(d: ModelDownloads.State) {
    if (d.error != null) {
        Text(d.error, style = FcType.Body, color = Fc.Body)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { DialogButton("OK", { ModelDownloads.dismiss() }, filled = true) }
    } else {
        ProgressBlock("Downloading ${d.name}… ${megabytes(d.done)} of ${megabytes(d.total)}", d.fraction, "Cancel", tag = "model_download_cancel_button") { ModelDownloads.cancel() }
    }
}

@Composable
private fun DownloadDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FcDialog(title, "Download", onConfirm, onDismiss) {
        Text(text, style = FcType.Body, color = Fc.Body)
    }
}

/** Plays synthesized speech once (for hearing a voice before adding it). */
private class SpeechPlayer {
    private var track: AudioTrack? = null

    fun play(a: SpeechAudio) {
        stop()
        val samples = a.pcm.size / 4
        if (samples == 0) return
        val rate = a.sampleRate.toInt().coerceIn(8000, 192_000)
        val floats = FloatArray(samples)
        ByteBuffer.wrap(a.pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(samples * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return
        t.write(floats, 0, samples, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}

/** Text to speech: what to say, the voice, its pace and pitch; hear it, then add it. */
@Composable
fun SpeechPanel(ed: EditorController, panel: Panel.Speech, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(panel) { mutableStateOf("") }
    var voice by remember(panel) { mutableStateOf(prefs(ctx).getString("voice", "basic-female") ?: "basic-female") }
    var pace by remember(panel) { mutableStateOf(1.0) }
    var pitch by remember(panel) { mutableStateOf(0) }
    var all by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var pkg by remember { mutableStateOf<ModelPackage?>(null) }
    var asking by remember { mutableStateOf(false) }
    var hearing by remember { mutableStateOf<Job?>(null) }
    val player = remember { SpeechPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val download = ModelDownloads.state
    LaunchedEffect(download == null) {
        all = withContext(Dispatchers.IO) { voices() }
        pkg = withContext(Dispatchers.IO) { voicePackage() }
    }
    LaunchedEffect(panel) {
        val c = panel.clip ?: return@LaunchedEffect
        ed.speechSettings(c)?.let {
            text = it.text
            voice = it.voice
            pace = it.pace
            pitch = it.pitch
        }
    }
    fun settings() = SpeechSettings(text.trim(), voice, pace, pitch)
    val usable = all.filter { it.installed }
    ToolPanel(if (panel.clip == null) "Text to speech" else "Edit speech", close, modifier.imePadding(), tag = "speech_panel") {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        Column(Modifier.fillMaxWidth()) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= 2000) text = it },
                textStyle = TextStyle(fontFamily = Inter, fontSize = 17.sp, color = Fc.Title),
                cursorBrush = SolidColor(Fc.Title),
                minLines = 3,
                maxLines = 5,
                modifier = pad
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .border(1.dp, Fc.Divider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("speech_text_field"),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Type what to say", style = FcType.Label.copy(fontSize = 17.sp), color = Fc.Detail)
                    inner()
                },
            )
            Spacer(Modifier.height(16.dp))
            Text("Voice", style = FcType.Label, color = Fc.Secondary, modifier = pad)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                for (v in usable) SelectChip(v.name, voice == v.id, { voice = v.id }, tag = "speech_voice_${v.id}")
                val p = pkg
                if (p != null && !p.installed && download == null) {
                    SelectChip("Natural voices · ${megabytes(p.missing.toLong())}", false, { asking = true }, tag = "speech_get_natural_voices")
                }
            }
            if (download != null && download.id == pkg?.id) {
                Spacer(Modifier.height(12.dp))
                Column(pad) { DownloadBlock(download) }
            }
        }
        Spacer(Modifier.height(8.dp))
        SliderRow("Pace", "%.2f×".format(pace)) {
            FcSlider(
                fraction = ((pace - 0.5) / 1.5).toFloat(),
                onChange = { f -> pace = Math.round((0.5 + f * 1.5) * 20) / 20.0 },
                snapTo = 0.5f / 1.5f,
                description = "Pace",
                tag = "speech_pace_slider",
            )
        }
        SliderRow("Pitch", listOf("Lowest", "Low", "Normal", "High", "Highest")[pitch + 2]) {
            FcSlider(
                fraction = (pitch + 2) / 4f,
                onChange = { f -> pitch = (f * 4).roundToInt() - 2 },
                ticks = listOf(0.25f, 0.5f, 0.75f),
                description = "Pitch",
                tag = "speech_pitch_slider",
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.testTag("speech_listen_button")) {
                DialogButton(if (hearing != null) "Making…" else "Listen", {
                    if (text.isBlank() || hearing != null) return@DialogButton
                    hearing = scope.launch {
                        try {
                            ed.previewSpeech(settings())?.let { player.play(it) }
                        } finally {
                            hearing = null
                        }
                    }
                }, filled = false, enabled = text.isNotBlank())
            }
            PrimaryButton(if (panel.clip == null) "Add" else "Save", {
                prefs(ctx).edit().putString("voice", voice).apply()
                player.stop()
                ed.saySpeech(panel.clip, settings())
                close()
            }, modifier = Modifier.weight(1f), enabled = text.isNotBlank(), tag = "speech_add_button")
        }
    }
    val p = pkg
    if (asking && p != null) {
        DownloadDialog(
            title = "Download natural voices?",
            text = "Natural-sounding English voices (Kokoro, Apache licence). They're ${megabytes(p.missing.toLong())}, downloaded once from Hugging Face, then work offline.",
            onDismiss = { asking = false },
            onConfirm = {
                asking = false
                ModelDownloads.start(p)
            },
        )
    }
}

/**
 * Edit by Text (Pro mode): the sequence's transcript, made on the phone. Tap a word to go there
 * (the word playing is underlined); long-press a word, then tap another, to select the words
 * between, and delete them with the bin: the sequence is cut there and closes up, picture and
 * sound together. Filler words and long pauses go in one tap each.
 */
@Composable
fun TextEditPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var models by remember { mutableStateOf<List<ModelPackage>>(emptyList()) }
    var model by remember { mutableStateOf(prefs(ctx).getString("model", "whisper-base") ?: "whisper-base") }
    var language by remember { mutableStateOf(prefs(ctx).getString("language", "auto") ?: "auto") }
    var asking by remember { mutableStateOf<ModelPackage?>(null) }
    val download = ModelDownloads.state
    LaunchedEffect(download == null) { models = withContext(Dispatchers.IO) { speechModels() } }
    val running = ed.transcriptProgress
    var view by remember { mutableStateOf<io.github.artcraftandroid.rushcraft.core.TextView?>(null) }
    LaunchedEffect(ed.timeline?.revision, running == null) { if (running == null) view = ed.textView() }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var sel by remember { mutableStateOf<IntRange?>(null) }
    LaunchedEffect(ed.timeline?.revision) {
        anchor = null
        sel = null
    }
    var more by remember { mutableStateOf(false) }
    var pause by remember { mutableStateOf(1.0f) }
    fun go() {
        prefs(ctx).edit().putString("model", model).putString("language", language).apply()
        val m = models.firstOrNull { it.id == model } ?: return
        if (m.installed) ed.transcribe(model, language) else asking = m
    }
    val v = view
    ToolPanel(
        "Edit by Text", close, modifier, tag = "text_edit_panel",
        actions = {
            val s = sel
            if (s != null) {
                PanelAction(LucideIcons.X.glyph(), "Clear the selection", { anchor = null; sel = null }, tag = "text_edit_clear")
                PanelAction(LucideIcons.Trash2.glyph(), "Delete the selected words", {
                    ed.deleteWords(listOf(io.github.artcraftandroid.rushcraft.core.WordRange(s.first.toUInt(), s.last.toUInt())))
                    anchor = null
                    sel = null
                }, tag = "text_edit_delete")
            }
        },
    ) {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        when {
            running != null -> Column(pad) { ProgressBlock(running.stage, running.progress.toFloat(), "Stop", tag = "text_edit_stop") { ed.cancelCaptions() } }
            download != null && models.any { it.id == download.id } -> Column(pad) { DownloadBlock(download) }
            v == null || v.words.isEmpty() -> {
                Text("Transcribe what's said, then edit the video by editing its text.", style = FcType.Body, color = Fc.Body, modifier = pad)
                Spacer(Modifier.height(16.dp))
                Text("Spoken language", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                Spacer(Modifier.height(8.dp))
                ChipRow { for ((id, name) in LANGUAGES) SelectChip(name, language == id, { language = id }, tag = "text_edit_language_$id") }
                Spacer(Modifier.height(16.dp))
                Text("Speech model", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    for (m in models) {
                        val label = modelLabel(m.id) + if (m.installed) "" else " · " + megabytes(m.missing.toLong())
                        SelectChip(label, model == m.id, { model = m.id }, tag = "text_edit_model_${m.id}")
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryButton("Transcribe", { go() }, modifier = pad, enabled = models.isNotEmpty(), tag = "text_edit_transcribe")
                Spacer(Modifier.height(8.dp))
                Text("The transcript is made on this phone. The sound never leaves it.", style = FcType.BodySmall, color = Fc.Detail, modifier = pad)
            }
            else -> {
                if (v.untranscribed) {
                    Row(pad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("There's new sound since the transcript.", style = FcType.BodySmall, color = Fc.Detail, modifier = Modifier.weight(1f))
                        SelectChip("Transcribe it", false, { go() }, tag = "text_edit_more")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ChipRow {
                    SelectChip("Remove filler words", false, { ed.removeFillers(more) }, tag = "text_edit_fillers")
                    SelectChip("Also \"like\", \"so\", \"you know\"", more, { more = !more }, tag = "text_edit_more_fillers")
                }
                Spacer(Modifier.height(8.dp))
                Row(pad.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectChip("Remove pauses over %.1f s".format(pause), false, { ed.removePauses(pause.toDouble()) }, tag = "text_edit_pauses")
                    Spacer(Modifier.width(12.dp))
                    FcSlider(((pause - 0.3f) / 2.7f), { pause = (0.3f + it * 2.7f) }, modifier = Modifier.weight(1f), description = "Shortest pause to remove")
                }
                Text(
                    if (sel == null) "Tap a word to go there. Long-press a word, then tap another, to select the words between." else "Tap another word to change the selection; the bin deletes it.",
                    style = FcType.BodySmall, color = Fc.Detail, modifier = pad.padding(vertical = 8.dp),
                )
                // the playing word
                val ph = ed.playhead
                val cur = v.words.indexOfLast { it.start <= ph }.takeIf { it >= 0 && v.words[it].end >= ph }
                Column(
                    pad.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(8.dp)).background(Fc.Raised)
                        .verticalScroll(rememberScrollState()).padding(12.dp).testTag("text_edit_transcript"),
                ) {
                    val starts = v.paragraphs.map { it.toInt() } + v.words.size
                    for (p in 0 until starts.size - 1) {
                        val a = starts[p]
                        val b = starts[p + 1]
                        if (b <= a) continue
                        val who = v.speakers.getOrNull(p).orEmpty()
                        if (who.isNotBlank()) Text(who, style = FcType.Caption, color = Fc.AccentLight)
                        TranscriptParagraph(v.words, a, b, cur, sel, onTap = { i ->
                            val an = anchor
                            if (an != null) sel = minOf(an, i)..maxOf(an, i) else ed.seek(v.words[i].start, draft = false)
                        }, onLongPress = { i ->
                            anchor = i
                            sel = i..i
                        })
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    asking?.let { m ->
        DownloadDialog(
            title = "Download the speech model?",
            text = "The transcript is made by a speech model on the phone (OpenAI's Whisper, MIT licence). It's ${megabytes(m.missing.toLong())}, downloaded once from Hugging Face, then works offline.",
            onDismiss = { asking = null },
            onConfirm = {
                asking = null
                val (mid, lang) = model to language
                ModelDownloads.start(m) { android.os.Handler(android.os.Looper.getMainLooper()).post { ed.transcribe(mid, lang) } }
            },
        )
    }
}

/** Words [from] until [to] as one paragraph: fillers dim, the playing word underlined, a selection marked. */
@Composable
private fun TranscriptParagraph(
    words: List<io.github.artcraftandroid.rushcraft.core.TextWord>,
    from: Int,
    to: Int,
    current: Int?,
    selection: IntRange?,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    // where each word starts in the text
    val offsets = IntArray(to - from)
    val text = androidx.compose.ui.text.buildAnnotatedString {
        for (i in from until to) {
            offsets[i - from] = length
            val w = words[i]
            val picked = selection != null && i in selection
            val style = androidx.compose.ui.text.SpanStyle(
                color = when {
                    picked -> androidx.compose.ui.graphics.Color.White
                    w.filler -> Fc.Detail
                    else -> Fc.Title
                },
                background = if (picked) androidx.compose.ui.graphics.Color(0xFFB91C1C) else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (i == current) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            )
            pushStyle(style)
            append(w.text)
            pop()
            if (i < to - 1) append(" ")
        }
    }
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    val press by androidx.compose.runtime.rememberUpdatedState(onLongPress)
    fun wordAt(pos: androidx.compose.ui.geometry.Offset): Int? {
        val l = layout ?: return null
        val o = l.getOffsetForPosition(pos)
        val k = offsets.indexOfLast { it <= o }
        return if (k >= 0) from + k else null
    }
    Text(
        text,
        style = TextStyle(fontFamily = Inter, fontSize = 17.sp, lineHeight = 26.sp),
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(from, to, text) {
            androidx.compose.foundation.gestures.detectTapGestures(
                onTap = { p -> wordAt(p)?.let(tap) },
                onLongPress = { p -> wordAt(p)?.let(press) },
            )
        },
    )
}
