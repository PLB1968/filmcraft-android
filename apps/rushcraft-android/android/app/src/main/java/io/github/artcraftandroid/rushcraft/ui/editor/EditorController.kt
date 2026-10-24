package io.github.artcraftandroid.rushcraft.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.rushcraft.core.BinItem
import io.github.artcraftandroid.rushcraft.core.CaptionInfo
import io.github.artcraftandroid.rushcraft.core.CaptionLook
import io.github.artcraftandroid.rushcraft.core.CaptionStatus
import io.github.artcraftandroid.rushcraft.core.ClipInfo
import io.github.artcraftandroid.rushcraft.core.ClipKind
import io.github.artcraftandroid.rushcraft.core.EffectInfo
import io.github.artcraftandroid.rushcraft.core.KeyframeInfo
import io.github.artcraftandroid.rushcraft.core.ParamInfo
import io.github.artcraftandroid.rushcraft.core.LaneKind
import io.github.artcraftandroid.rushcraft.core.MobileSession
import io.github.artcraftandroid.rushcraft.core.ProClip
import io.github.artcraftandroid.rushcraft.core.ProTimeline
import io.github.artcraftandroid.rushcraft.core.ProxyStatus
import io.github.artcraftandroid.rushcraft.core.TrackSwitches
import io.github.artcraftandroid.rushcraft.core.Renderer
import io.github.artcraftandroid.rushcraft.core.RenderSpan
import io.github.artcraftandroid.rushcraft.core.SessionStatus
import io.github.artcraftandroid.rushcraft.core.SourceInfo
import io.github.artcraftandroid.rushcraft.core.SpeechAudio
import io.github.artcraftandroid.rushcraft.core.SpeechSettings
import io.github.artcraftandroid.rushcraft.core.TextStyle
import io.github.artcraftandroid.rushcraft.core.TimelineInfo
import io.github.artcraftandroid.rushcraft.core.TransitionInfo
import io.github.artcraftandroid.rushcraft.core.TransitionControls
import io.github.artcraftandroid.rushcraft.engine.AppPrefs
import io.github.artcraftandroid.rushcraft.engine.Engine
import io.github.artcraftandroid.rushcraft.engine.ProjectEntry
import io.github.artcraftandroid.rushcraft.engine.ProxyMaker
import io.github.artcraftandroid.rushcraft.engine.Projects
import io.github.artcraftandroid.rushcraft.engine.intoBitmap
import io.github.artcraftandroid.rushcraft.engine.toBitmap
import io.github.artcraftandroid.rushcraft.engine.userMessage
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pro mode's track heights (dp): the smallest still has 44 dp touch targets. */
const val PRO_TRACK_MIN = 44
const val PRO_TRACK_MAX = 160
const val PRO_VIDEO_TRACK = 56
const val PRO_AUDIO_TRACK = 48

/** Pro mode's tools, with their desktop shortcut keys. */
enum class ProTool(val label: String, val key: Char) {
    Selection("Selection", 'V'),
    TrackSelectForward("Track Select Forward", 'A'),
    RippleEdit("Ripple Edit", 'B'),
    RollingEdit("Rolling Edit", 'N'),
    RateStretch("Rate Stretch", 'R'),
    Razor("Razor", 'C'),
    Slip("Slip", 'Y'),
    Slide("Slide", 'U'),
    Hand("Hand", 'H'),
    Zoom("Zoom", 'Z'),
    Type("Type", 'T'),
}

/** A frame the Program monitor shows during a trim: project item [item] (null: no picture) at media time [time]. */
data class TrimFrame(val item: ULong?, val time: Long, val label: String)

/** The Program monitor during a trim: one frame, or two side by side (roll, slip, slide). */
data class TrimView(val frames: List<TrimFrame>, val caption: String)

/**
 * An edit point picked for one-frame trims (a tapped clip end, or the end last dragged): the clip,
 * which end, and how it trims (a plain trim, a ripple, or a roll with the clip it touches).
 */
data class PickedEdge(val clip: ULong, val head: Boolean, val ripple: Boolean = false, val roll: Boolean = false)

/** A tool's panel. */
sealed interface Panel {
    data object Speed : Panel
    data object Volume : Panel
    data object Fade : Panel
    data object Opacity : Panel
    data object Filters : Panel
    data object Effects : Panel
    /** The transition between [from] and [to] (either empty at the ends). */
    data class Transitions(val from: ULong?, val to: ULong?) : Panel
    /** Typing text: a new clip ([clip] empty), editing one, or a caption's words ([caption]). */
    data class TextInput(val clip: ULong?, val caption: ULong? = null) : Panel
    data object TextStyle : Panel
    data object AspectRatio : Panel
    data object Voiceover : Panel
    /** Captions: make them, and their look. */
    data object Captions : Panel
    /** Text to speech: new speech ([clip] empty) or saying a speech clip again. */
    data class Speech(val clip: ULong?) : Panel
}

/** The tool bar's submenus. */
enum class Submenu { None, Retime, ClipAudio, AddAudio, Files }

/** What the editor needs from the rest of the app. */
interface EditorHost {
    fun tell(message: String)
    /** Ask the user for media: [kind] picks the picker; [onPicked] gets what they chose. */
    fun pick(kind: PickKind, onPicked: (List<Uri>) -> Unit)
    fun close()
    fun export()
    /** Ask for the microphone; [onGranted] runs once it may be used. */
    fun requestMicrophone(onGranted: () -> Unit)
}

enum class PickKind { PhotosAndVideos, FilesVisual, FilesAudio, ReplaceVisual, ExtractAudioVideo }

/**
 * The open project in the editor: the timeline as the engine last reported it, the playhead,
 * the picture at it, playback, the selection and the open tool. Every edit goes through [edit],
 * which runs it on the engine's command thread, re-reads the timeline and saves the project.
 */
@Stable
class EditorController(
    val engine: Engine,
    project: ProjectEntry,
    private val projects: Projects,
    private val scope: CoroutineScope,
    private val host: EditorHost,
    private val prefs: AppPrefs? = null,
    /** Makes proxies with the phone's video hardware (else the engine makes them, slowly). */
    private val proxyMaker: ProxyMaker? = null,
) {
    private val session: MobileSession get() = engine.session

    var project by mutableStateOf(project)
        private set
    var timeline by mutableStateOf<TimelineInfo?>(null)
        private set
    var status by mutableStateOf<SessionStatus?>(null)
        private set
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    var playhead by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var panel by mutableStateOf<Panel?>(null)
    var submenu by mutableStateOf(Submenu.None)
    /** Timeline zoom; 0 until the timeline knows its width. */
    var pxPerSecond by mutableFloatStateOf(0f)
    /** The player's height (px) when the user has resized it, else 0 (55 % of the room). */
    var playerHeight by mutableFloatStateOf(0f)
    /** The player's height (px) last laid out without a panel: kept while one is open. */
    var basePlayerPx = 0f
    /** The side-by-side layout's player width fraction. */
    var sideFraction by mutableFloatStateOf(0f)
    /** Busy with something the user waits for (importing media). */
    var busy by mutableStateOf<String?>(null)
        private set
    /** Text being typed for a new text clip (shown on the preview until it is added). */
    var typing by mutableStateOf("")
    /** A clip is being dragged or trimmed: tools wait. */
    var gesture by mutableStateOf(false)

    /** RushCraft always edits in Pro mode (the desktop-style editor). */
    var proMode by mutableStateOf(true)
        private set
    /** The timeline as Pro mode shows it (read only while Pro mode is on). */
    var pro by mutableStateOf<ProTimeline?>(null)
        private set
    /** Pro mode's track heights (dp) by track, as the user resized them. */
    val trackHeights = mutableStateMapOf<ULong, Int>().apply { putAll(project.trackHeights) }
    /** Pro mode's timeline view: zoom (0 until laid out) and the time at its left edge. */
    var proPxPerSecond by mutableFloatStateOf(0f)
    var proScroll by mutableLongStateOf(0L)
    /**
     * Pro mode's panel sizes as the user dragged them, each a share (0..1) of the space it is cut
     * from, by [ProPane] key; a pane not listed has the layout's default. Remembered per project.
     */
    val proPanes = mutableStateMapOf<String, Float>().apply { putAll(project.panes) }

    val media = MediaCache(engine, scope)
    private var renderer: Renderer? = null
    private val player = Player()
    private var playJob: Job? = null
    private var saveJob: Job? = null
    private var lastSaved = -1L

    /** The preview's longest side in pixels (set by the preview from its size). */
    var previewSide = 720
        set(v) {
            field = v.coerceIn(160, 1280)
        }

    private data class FrameRequest(val time: Long, val draft: Boolean, val seq: Long)
    private val frameRequests = MutableStateFlow<FrameRequest?>(null)
    private var requestSeq = 0L
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var nextBitmap = 0

    init {
        scope.launch { renderFrames() }
    }

    // ---- reading ----

    val rateNum: Long get() = timeline?.rateNum ?: 30L
    val rateDen: Long get() = timeline?.rateDen ?: 1L
    val duration: Long get() = timeline?.duration ?: 0L
    val frameTicks: Long get() = TimelineMath.tickOfFrame(1, rateNum, rateDen)

    val selectedClip: ClipInfo?
        get() {
            val id = timeline?.selected ?: return null
            return timeline?.lanes?.firstNotNullOfOrNull { l -> l.clips.firstOrNull { it.id == id } }
        }

    /** The selected caption (picked here, not in the engine: captions aren't clips). */
    var selectedCaption by mutableStateOf<ULong?>(null)

    val selectedCaptionInfo: CaptionInfo?
        get() {
            val id = selectedCaption ?: return null
            return timeline?.captions?.firstOrNull { it.id == id }
        }

    val selectedTransition: TransitionInfo?
        get() {
            val id = timeline?.selectedTransition ?: return null
            return timeline?.transitions?.firstOrNull { it.id == id }
        }

    fun laneOf(clip: ULong): io.github.artcraftandroid.rushcraft.core.LaneInfo? = timeline?.lanes?.firstOrNull { l -> l.clips.any { it.id == clip } }

    fun isMain(clip: ULong): Boolean = laneOf(clip)?.kind == LaneKind.MAIN

    val mainClips: List<ClipInfo> get() = timeline?.lanes?.firstOrNull { it.kind == LaneKind.MAIN }?.clips.orEmpty()

    // ---- loading and saving ----

    /** Open the project: load or create its engine project, then show it. */
    suspend fun load(fresh: Boolean) {
        val t = engine.command {
            if (fresh || !project.projectFile.isFile) newProject(project.name, project.width.toUInt(), project.height.toUInt(), 30u)
            else openProject(project.projectFile.absolutePath)
        }
        timeline = t
        playhead = t.playhead
        if (proMode) refreshPro()
        refreshStatus()
        engine.command {
            setUseProxies(useProxies)
            setHardwareDecoding(prefs?.hardwareDecoding ?: true)
        }
        newRenderer()
        if (fresh) save()
        lastSaved = t.revision.toLong()
        requestFrame(playhead, draft = false)
        watchProxies()
    }

    private suspend fun refreshStatus() {
        status = engine.command { poll() }
    }

    private suspend fun newRenderer() {
        val r = runCatching { engine.command { renderer() } }.getOrNull() ?: return
        renderer = r
        media.renderer = r
    }

    /** Re-read the timeline after an edit; a new revision gets a new renderer and a save. */
    suspend fun refresh() {
        val t = runCatching { engine.command { timeline() } }.getOrNull() ?: return
        val changed = t.revision != timeline?.revision
        timeline = t
        if (proMode) refreshPro()
        // an edit that shortens the video past the playhead brings it back to the end
        playhead = if (t.playhead > t.duration) engine.command { setPlayhead(t.duration) } else t.playhead
        refreshStatus()
        if (changed) {
            newRenderer()
            scheduleSave()
        }
        requestFrame(playhead, draft = false)
        watchProxies()
        if (changed) renderSoon()
    }

    // ---- render previews ----

    /** The render bar: stretches with effects to render (yellow, red) and rendered ones (green). */
    var renderBar by mutableStateOf<List<RenderSpan>>(emptyList())
        private set
    /** How far rendering has got (0..1) while it runs, else null. */
    var renderProgress by mutableStateOf<Double?>(null)
        private set
    private var renderLoop: Job? = null
    private var renderJob: ULong? = null

    /** After an edit: render what it changed once editing pauses (Settings › Render effects). */
    private fun renderSoon() {
        stopRendering()
        if (closed || prefs?.autoRender == false) return
        renderLoop = scope.launch {
            delay(3000)
            if (!playing) renderNow()
        }
    }

    /** Render the stretches that need it now (the Render button), showing how far it has got. */
    fun renderEffects() {
        stopRendering()
        renderLoop = scope.launch { renderNow(tell = true) }
    }

    private suspend fun renderNow(tell: Boolean = false) {
        val job = runCatching { engine.command { renderEffects() } }.onFailure { if (tell) host.tell(it.userMessage) }.getOrNull()
        if (job == null) {
            if (tell) host.tell("Everything is rendered.")
            return
        }
        renderJob = job
        renderProgress = 0.0
        try {
            while (true) {
                delay(700)
                val st = runCatching { engine.command { exportStatus(job) } }.getOrNull() ?: break
                renderProgress = st.progress
                if (st.finished) {
                    st.error?.takeIf { tell && it != "cancelled" }?.let { host.tell("Rendering stopped: $it") }
                    break
                }
            }
        } finally {
            renderJob = null
            renderProgress = null
        }
        renderBar = runCatching { engine.command { renderBar() } }.getOrDefault(renderBar)
        requestFrame(playhead, draft = false)
    }

    /** The Render button while it renders: stop (what was rendered so far is kept). */
    fun stopRender() = stopRendering()

    /** Stop rendering (an edit, playback, leaving): what was rendered so far is kept. */
    private fun stopRendering() {
        renderLoop?.cancel()
        renderLoop = null
        renderJob?.let { j -> scope.launch { runCatching { engine.command { cancelExport(j) } } } }
        renderJob = null
        renderProgress = null
    }

    // ---- proxies ----

    /** How the proxies are coming along (null until first asked). */
    var proxies by mutableStateOf<ProxyStatus?>(null)
        private set
    /** Toggle Proxies: the monitors play proxies where there are some; kept for every project. */
    var useProxies by mutableStateOf(prefs?.useProxies ?: true)
        private set
    private var proxyLoop: Job? = null
    private var closed = false

    /** Keep proxies coming: check on them every second while one is being made or waits. */
    private fun watchProxies() {
        if (closed || proxyLoop?.isActive == true) return
        proxyLoop = scope.launch {
            while (isActive && !closed) {
                val mk = proxyMaker
                val making = mk?.media
                val done = mk?.progress()
                if (making != null && done != null) runCatching { engine.command { proxyProgress(making, done) } }
                val st = runCatching { engine.command { proxyTick(prefs?.autoProxies ?: true, mk != null) } }.getOrNull() ?: break
                proxies = st
                st.messages.forEach { host.tell(it) }
                // the proxy the phone's hardware is to make: start it, or stop one no longer wanted
                val w = st.appWork
                if (mk != null) {
                    if (w == null) {
                        if (mk.media != null) mk.cancel()
                    } else if (mk.media != w.media) {
                        mk.start(w) { ok -> scope.launch { proxyMade(w.media, ok) } }
                    }
                }
                // a proxy was attached: a new renderer plays it
                if (st.attached) refresh()
                if (!st.busy) break
                delay(1000)
            }
        }
    }

    /** The phone's hardware finished a proxy (or couldn't: then the engine makes it). */
    private suspend fun proxyMade(media: String, ok: Boolean) {
        val attached = runCatching { engine.command { proxyDone(media, ok) } }.getOrDefault(false)
        proxyMaker?.forget()
        if (attached) refresh()
        watchProxies()
    }

    fun toggleProxies() {
        val on = !useProxies
        useProxies = on
        prefs?.useProxies = on
        scope.launch {
            engine.command { setUseProxies(on) }
            refresh()
        }
    }

    /** Make a proxy of this Project panel item (Create Proxy). */
    fun createProxy(item: ULong) {
        scope.launch {
            runCatching { engine.command { createProxies(listOf(item)) } }.onFailure { host.tell(it.userMessage) }
            watchProxies()
        }
    }

    /** Take this item's proxy away and delete it (it plays from the original again). */
    fun removeProxy(item: ULong) {
        scope.launch {
            engine.command { this.removeProxy(item) }
            refresh()
        }
    }

    private suspend fun refreshPro() {
        pro = runCatching { engine.command { proTimeline() } }.getOrNull()
        renderBar = runCatching { engine.command { renderBar() } }.getOrDefault(emptyList())
        bin = runCatching { engine.command { projectBin() } }.getOrDefault(emptyList())
        source = runCatching { engine.command { sourceInfo() } }.getOrNull()
        if (source == null) sourcePicture = null
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            save()
        }
    }

    /** Save the project file and its listing now. */
    suspend fun save() {
        val t = timeline ?: return
        if (t.revision.toLong() == lastSaved) return
        val path = project.projectFile.absolutePath
        runCatching { engine.command { saveProject(path) } }.onFailure { host.tell(it.userMessage) }.onSuccess {
            lastSaved = t.revision.toLong()
            val p = project.copy(duration = t.duration, width = t.width.toInt(), height = t.height.toInt(), opened = System.currentTimeMillis())
            project = p
            withContext(Dispatchers.IO) { projects.write(p) }
            val now = System.currentTimeMillis()
            if (now - lastPoster > 20_000 || !p.poster.isFile) {
                lastPoster = now
                savePoster()
            }
        }
    }

    private var lastPoster = 0L

    /** The first frame, small, for the project's card on Home. */
    private suspend fun savePoster() {
        val r = renderer ?: return
        val bmp = withContext(engine.thumbnails) { runCatching { r.frame(0, 360u, false, null).intoBitmap(null) }.getOrNull() } ?: return
        withContext(Dispatchers.IO) { projects.savePoster(project, bmp) }
    }

    /** Save and make the poster for Home (the first frame). */
    suspend fun close() {
        stop()
        closed = true
        stopRendering()
        proxyLoop?.cancel()
        proxyMaker?.cancel()
        runCatching { engine.command { stopProxies() } }
        captionJob?.let { job -> runCatching { engine.command { cancelCaptions(job) } } }
        saveJob?.cancel()
        save()
        savePoster()
        media.release()
        // the natural voices' model and export buffers aren't needed on Home
        withContext(Dispatchers.IO) { runCatching { io.github.artcraftandroid.rushcraft.core.trimMemory() } }
    }

    // ---- editing ----

    /**
     * Run an edit on the engine's command thread; then show the result. Playback stops first.
     * Errors become a message.
     */
    fun edit(block: MobileSession.() -> Unit) {
        stop()
        scope.launch {
            try {
                engine.command { block() }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
        }
    }

    /** An edit that runs in the background without stopping playback or saving (slider drags). */
    fun quickEdit(block: MobileSession.() -> Unit) {
        scope.launch {
            try {
                engine.command { block() }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
        }
    }

    fun undo() = edit { undo() }
    fun redo() = edit { redo() }

    fun select(clip: ULong?) {
        submenu = Submenu.None
        if (panel !is Panel.TextInput) panel = null
        selectedCaption = null
        quickEdit { select(clip) }
    }

    fun selectCaption(id: ULong) {
        submenu = Submenu.None
        if (panel !is Panel.TextInput && panel != Panel.Captions) panel = null
        selectedCaption = id
        if (timeline?.selected != null || timeline?.selectedTransition != null) quickEdit { select(null) }
    }

    fun selectTransition(t: TransitionInfo) {
        submenu = Submenu.None
        quickEdit { selectTransition(t.id) }
    }

    // ---- playhead and playback ----

    /** Move the playhead (the timeline scrolls under it); [draft] while the finger moves. */
    fun seek(t: Long, draft: Boolean = true) {
        if (playing) stop()
        val c = t.coerceIn(0L, duration.coerceAtLeast(0L))
        playhead = c
        requestFrame(c, draft)
        scope.launch { engine.command { setPlayhead(c) } }
    }

    /** The finger left the timeline: draw the frame at full quality. */
    fun settle() {
        requestFrame(playhead, draft = false)
    }

    fun step(frames: Int) {
        val f = TimelineMath.frames(playhead, rateNum, rateDen) + frames
        seek(TimelineMath.tickOfFrame(f.coerceAtLeast(0), rateNum, rateDen), draft = false)
    }

    fun togglePlay() = if (playing) stop() else play()

    fun play() {
        pickedEdge = null
        // rendering would take the processor playback needs; it starts again after the next edit
        if (renderJob != null || renderLoop != null) stopRendering()
        val end = duration
        if (end <= 0) return
        if (playhead >= end - frameTicks) playhead = 0
        playRange(playhead, end, back = null)
    }

    /** Play from [from] to [to], then stop there, or go back to [back] if given. */
    private fun playRange(from: Long, to: Long, back: Long?) {
        val r = renderer ?: return
        stop()
        val end = to.coerceIn(0L, duration)
        val start = from.coerceIn(0L, end)
        if (end - start < frameTicks) return
        playhead = start
        playing = true
        player.start(r, start, end)
        // the UI thread's dispatcher brings the display's frame clock (the view model's scope has none)
        playJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            while (isActive && playing) {
                withFrameNanos { }
                val t = player.position().coerceAtMost(end)
                playhead = t
                requestFrame(t, draft = true)
                if (t >= end) break
            }
            if (playing) {
                playing = false
                player.stop()
                if (back != null) playhead = back.coerceIn(0L, duration)
                engine.command { setPlayhead(playhead) }
                requestFrame(playhead, draft = false)
            }
        }
    }

    /** Play the transition between [from] and [to], start to end; the playhead stays at its end. */
    private fun previewTransition(from: ULong?, to: ULong?) {
        val tr = timeline?.transitions?.firstOrNull { it.from == from && it.to == to } ?: return
        playRange(tr.start, tr.start + tr.duration, back = null)
    }

    fun stop() {
        if (!playing) return
        playing = false
        playJob?.cancel()
        playJob = null
        player.stop()
        val t = playhead
        scope.launch {
            engine.command { setPlayhead(t) }
            requestFrame(t, draft = false)
        }
    }

    private fun requestFrame(t: Long, draft: Boolean) {
        frameRequests.value = FrameRequest(t, draft, ++requestSeq)
    }

    private suspend fun renderFrames() {
        frameRequests.filterNotNull().collect { req ->
            val r = renderer ?: return@collect
            // Pro mode's playback resolution draws the program smaller (faster) while it plays and scrubs
            val full = if (proMode && req.draft) (previewSide * playbackScale).toInt().coerceAtLeast(120) else previewSide
            val side = if (req.draft && playing) (full * 3 / 4).coerceAtLeast(120) else full
            val frame = withContext(engine.preview) { runCatching { r.frame(req.time, side.toUInt(), req.draft, null) }.getOrNull() } ?: return@collect
            val bmp = frame.intoBitmap(bitmaps[nextBitmap]) ?: return@collect
            bitmaps[nextBitmap] = bmp
            nextBitmap = 1 - nextBitmap
            picture = bmp.asImageBitmap()
            // a draft that is still the newest wish is redrawn properly once things settle
            if (req.draft && !playing && frameRequests.value?.seq == req.seq) {
                delay(120)
                if (frameRequests.value?.seq == req.seq && !playing) requestFrame(req.time, draft = false)
            }
        }
    }

    // ---- adding media ----

    /** Add pictures and videos from the phone's gallery, files, or sounds from files. */
    fun addMedia(kind: PickKind) {
        submenu = Submenu.None
        host.pick(kind) { uris -> if (uris.isNotEmpty()) importAndAdd(uris, audio = kind == PickKind.FilesAudio) }
    }

    /** Add picked media; [first] is a new project's starting media, which can't be undone away. */
    fun importAndAdd(uris: List<Uri>, audio: Boolean, first: Boolean = false) {
        stop()
        scope.launch {
            busy = "Adding media…"
            try {
                val paths = withContext(Dispatchers.IO) { uris.mapNotNull { u -> runCatching { projects.importUri(project, u).absolutePath }.getOrNull() } }
                if (paths.isEmpty()) {
                    host.tell("That file can't be added.")
                } else {
                    engine.command {
                        if (audio) addAudio(paths) else addMedia(paths)
                        if (first) forgetHistory()
                    }
                    if (paths.size < uris.size) host.tell("Some files couldn't be added.")
                }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            } finally {
                busy = null
            }
            refresh()
        }
    }

    fun replaceSelected() {
        val c = selectedClip ?: return
        host.pick(PickKind.ReplaceVisual) { uris ->
            val u = uris.firstOrNull() ?: return@pick
            scope.launch {
                busy = "Replacing…"
                try {
                    val path = withContext(Dispatchers.IO) { projects.importUri(project, u).absolutePath }
                    engine.command { replace(c.id, path) }
                } catch (e: Exception) {
                    host.tell(e.userMessage)
                } finally {
                    busy = null
                }
                refresh()
            }
        }
    }

    /** Extract the sound of a video the user picks, onto an audio lane at the playhead. */
    fun extractAudioFromFile() {
        submenu = Submenu.None
        host.pick(PickKind.ExtractAudioVideo) { uris -> if (uris.isNotEmpty()) importAndAdd(uris, audio = true) }
    }

    // ---- clip tools ----

    private inline fun withSelected(block: (ClipInfo) -> Unit) {
        selectedClip?.let(block)
    }

    fun split() = withSelected { c -> edit { split(c.id) } }
    fun delete() = withSelected { c -> panel = null; submenu = Submenu.None; edit { deleteClip(c.id) } }
    fun duplicate() = withSelected { c -> edit { duplicate(c.id) } }
    fun freeze() = withSelected { c -> submenu = Submenu.None; edit { freeze(c.id) } }
    fun extractAudio() = withSelected { c -> submenu = Submenu.None; edit { extractAudio(c.id) } }
    fun toggleMirror() = withSelected { c -> edit { setFlip(c.id, true, !c.mirrored) } }
    fun toggleFlip() = withSelected { c -> edit { setFlip(c.id, false, !c.flipped) } }
    fun addBlank() = edit { addBlank() }

    fun setSpeed(speed: Double, done: Boolean) = withSelected { c ->
        if (done) edit { setSpeed(c.id, speed) }
    }

    fun setVolume(v: Double) = withSelected { c -> quickEdit { setVolume(c.id, v, true) } }
    fun setMuted(m: Boolean) = withSelected { c -> edit { setMuted(c.id, m) } }
    fun setFades(fadeIn: Long, fadeOut: Long) = withSelected { c -> edit { setFades(c.id, fadeIn, fadeOut) } }
    fun setOpacity(v: Double, all: Boolean = false) = withSelected { c -> quickEdit { setOpacity(c.id, v, !all, all) } }
    fun setFilter(id: String, amount: Double, merge: Boolean, all: Boolean = false) = withSelected { c -> quickEdit { setFilter(c.id, id, amount, merge, all) } }
    fun setEffect(id: String, amount: Double, merge: Boolean, all: Boolean = false) = withSelected { c -> quickEdit { setEffect(c.id, id, amount, merge, all) } }

    /** Put a transition on a cut (none for a blank [preset]) and play it. */
    fun setTransition(from: ULong?, to: ULong?, preset: String, seconds: Double, all: Boolean = false) {
        stop()
        scope.launch {
            try {
                engine.command { setTransition(from, to, preset, seconds, all) }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
            if (preset.isNotEmpty()) previewTransition(from, to)
        }
    }

    fun setTransitionDuration(id: ULong, seconds: Double) {
        stop()
        scope.launch {
            try {
                engine.command { setTransitionDuration(id, seconds) }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
            val tr = timeline?.transitions?.firstOrNull { it.id == id } ?: return@launch
            previewTransition(tr.from, tr.to)
        }
    }

    suspend fun maxTransition(from: ULong?, to: ULong?): Double = runCatching { engine.command { maxTransition(from, to) } }.getOrDefault(5.0)

    fun trim(clip: ULong, head: Boolean, delta: Long) = edit { trim(clip, head, delta) }
    fun reorder(clip: ULong, index: Int) = edit { reorder(clip, index.toUInt()) }
    fun move(clip: ULong, lane: Int, start: Long) = edit { moveClip(clip, lane, start) }

    fun setAspect(num: Int, den: Int) = edit { setAspect(num.toUInt(), den.toUInt()) }

    // ---- text ----

    fun addText(text: String) = edit { addText(text) }
    fun setText(clip: ULong, text: String) = edit { setText(clip, text) }

    suspend fun textStyle(clip: ULong): TextStyle? = runCatching { engine.command { textStyle(clip) } }.getOrNull()

    fun setTextStyle(clip: ULong, style: TextStyle, merge: Boolean, all: Boolean = false) = quickEdit { setTextStyle(clip, style, merge, all) }

    // ---- speech ----

    /** Speech of [st] to hear before adding it (null when it can't be made; the user is told). */
    suspend fun previewSpeech(st: SpeechSettings): SpeechAudio? =
        runCatching { engine.command { previewSpeech(st) } }.onFailure { host.tell(it.userMessage) }.getOrNull()

    suspend fun speechSettings(clip: ULong): SpeechSettings? = runCatching { engine.command { speechSettings(clip) } }.getOrNull()

    /** Add speech at the playhead, or say speech clip [clip] again. */
    fun saySpeech(clip: ULong?, st: SpeechSettings) {
        stop()
        scope.launch {
            busy = "Making speech…"
            try {
                val dir = project.mediaDir.apply { mkdirs() }.absolutePath
                engine.command { if (clip == null) addSpeech(st, dir) else editSpeech(clip, st, dir) }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            } finally {
                busy = null
            }
            refresh()
        }
    }

    // ---- captions ----

    /** Captions being made: how far along (null when nothing is running). */
    var captionProgress by mutableStateOf<CaptionStatus?>(null)
        private set
    private var captionJob: ULong? = null

    /** Make captions from what is said, with speech model [model] in [language] ("auto" detects it). */
    fun makeCaptions(model: String, language: String) {
        if (captionProgress != null) return
        stop()
        captionProgress = CaptionStatus(0.0, "Getting ready…", false, null, 0u)
        scope.launch {
            try {
                val job = engine.command { startCaptions(model, language) }
                captionJob = job
                while (true) {
                    val st = engine.command { captionStatus(job) }
                    captionProgress = st
                    if (st.finished) {
                        when {
                            st.error == "cancelled" -> {}
                            st.error != null -> host.tell(st.error ?: "")
                            st.captions == 0u -> host.tell("No speech was heard, so there are no captions.")
                        }
                        break
                    }
                    delay(300)
                }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            } finally {
                captionJob = null
                captionProgress = null
            }
            refresh()
        }
    }

    fun cancelCaptions() {
        val job = captionJob ?: return
        scope.launch { runCatching { engine.command { cancelCaptions(job) } } }
    }

    suspend fun captionLook(): CaptionLook? = runCatching { engine.command { captionLook() } }.getOrNull()

    fun setCaptionLook(look: CaptionLook, merge: Boolean) = quickEdit { setCaptionLook(look, merge) }

    fun setCaptionText(id: ULong, text: String) = edit { setCaptionText(id, text) }

    fun deleteCaption(id: ULong) {
        selectedCaption = null
        edit { deleteCaption(id) }
    }

    fun deleteCaptions() {
        selectedCaption = null
        edit { deleteCaptions() }
    }

    // ---- placement on the frame ----

    suspend fun placementOf(clip: ULong): io.github.artcraftandroid.rushcraft.core.Placement? =
        runCatching { engine.command { placement(clip) } }.getOrNull()

    /** Move/scale/turn a clip while a finger does it: one undo step per gesture. */
    fun setPlacement(clip: ULong, p: io.github.artcraftandroid.rushcraft.core.Placement, merge: Boolean) {
        placementJob = scope.launch {
            runCatching { engine.command { setPlacement(clip, p.x, p.y, p.scale, p.rotation, merge) } }
            refreshSoon()
        }
    }

    private var placementJob: Job? = null
    private var refreshJob: Job? = null

    /** Re-read the timeline once things calm down (a gesture sends many edits). */
    private fun refreshSoon() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(60)
            refresh()
        }
    }

    // ---- voice-over ----

    private var voiceStart = 0L

    fun requestMicrophone(onGranted: () -> Unit) = host.requestMicrophone(onGranted)

    fun tellUser(message: String) = host.tell(message)

    /** A recorder for a new take at the playhead, writing "Voiceover N.wav" into the project. */
    fun newVoiceRecorder(): VoiceRecorder? {
        stop()
        voiceStart = playhead
        val dir = project.mediaDir.apply { mkdirs() }
        var n = 1
        while (java.io.File(dir, "Voiceover $n.wav").exists()) n++
        return VoiceRecorder(java.io.File(dir, "Voiceover $n.wav"))
    }

    /** Put a finished take on an audio lane where it started. */
    fun finishVoiceover(r: VoiceRecorder, onAdded: (ULong) -> Unit) {
        val path = r.file.absolutePath
        val at = voiceStart
        scope.launch {
            try {
                if (r.frames < r.rate / 10) {
                    java.io.File(path).delete()
                } else {
                    val ids = engine.command {
                        setPlayhead(at)
                        addAudio(listOf(path))
                    }
                    ids.firstOrNull()?.let(onAdded)
                }
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
        }
    }

    fun discardTakes(ids: List<ULong>) = edit { ids.forEach { runCatching { deleteClip(it) } } }

    fun closeEditor() = host.close()

    // ---- Pro mode ----

    /** Keep the project's Pro mode and track heights in its listing. */
    private fun rememberView() {
        val p = project.copy(proMode = proMode, trackHeights = trackHeights.toMap(), panes = proPanes.toMap())
        project = p
        scope.launch(Dispatchers.IO) { projects.write(p) }
    }

    private var heightsJob: Job? = null

    /** Resize a Pro mode pane ([ProPane]) to [share] of its space; remembered with the project. */
    fun setPane(key: String, share: Float) {
        proPanes[key] = share.coerceIn(0.05f, 0.95f)
        heightsJob?.cancel()
        heightsJob = scope.launch {
            delay(300)
            rememberView()
        }
    }

    /** Resize a Pro track (dp); remembered with the project. */
    fun setTrackHeight(track: ULong, dp: Int) {
        trackHeights[track] = dp.coerceIn(PRO_TRACK_MIN, PRO_TRACK_MAX)
        heightsJob?.cancel()
        heightsJob = scope.launch {
            delay(300)
            rememberView()
        }
    }

    val proSelection: List<ProClip> get() = pro?.clips?.filter { it.selected }.orEmpty()

    /** Select a clip (with its linked partners while Linked Selection is on), nothing for none; [add] toggles it. */
    fun proSelect(clip: ULong?, add: Boolean = false) {
        pickedEdge = null
        proTransition = null
        submenu = Submenu.None
        selectedCaption = null
        quickEdit { selectClips(listOfNotNull(clip), add && clip != null) }
    }

    fun proMove(clip: ULong, track: ULong, start: Long) = edit { proMoveClip(clip, track, start) }
    fun deleteSelection(ripple: Boolean) = edit { deleteSelection(ripple) }

    fun addTracks(video: Int, audio: Int) = edit { addTracks(video.toUInt(), audio.toUInt()) }
    fun deleteTrack(track: ULong) = edit { deleteTrack(track) }
    fun setTrack(track: ULong, switches: TrackSwitches) = edit { setTrack(track, switches) }
    fun setTargeted(track: ULong, on: Boolean) = quickEdit { setTrackTargeted(track, on) }
    fun setSourcePatch(track: ULong, on: Boolean) = quickEdit { setSourcePatch(track, on) }
    fun setSnapping(on: Boolean) = quickEdit { setSnapping(on) }
    fun setLinkedSelection(on: Boolean) = quickEdit { setLinkedSelection(on) }

    fun markIn(at: Long = playhead) = edit { setMarkIn(at) }
    fun markOut(at: Long = playhead) = edit { setMarkOut(at) }
    fun clearIn() = edit { setMarkIn(null) }
    fun clearOut() = edit { setMarkOut(null) }
    fun clearInOut() = edit { clearInOut() }
    fun goToIn() = pro?.markIn?.let { seek(it, draft = false) }
    fun goToOut() = pro?.markOut?.let { seek(it, draft = false) }

    fun addMarker(at: Long = playhead) = edit { addMarker(at, "") }
    fun editMarker(id: ULong, name: String, comment: String, color: String) = edit { editMarker(id, name, comment, color) }
    fun deleteMarker(id: ULong) = edit { deleteMarker(id) }

    fun goToMarker(next: Boolean) {
        stop()
        scope.launch {
            val t = runCatching { engine.command { goToMarker(next) } }.getOrNull() ?: return@launch
            seek(t, draft = false)
        }
    }

    // ---- Pro mode: the tools ----

    var proTool by mutableStateOf(ProTool.Selection)

    /** A button's explanation, shown while set (a long press on a Pro mode button). */
    var help by mutableStateOf<ProHelp?>(null)

    /** What the Program monitor shows while a trim, roll, slip or slide is dragged (null: the program). */
    var trimView by mutableStateOf<TrimView?>(null)
        private set
    /** [trimView]'s pictures, in its order (null while one is drawn, or for sound). */
    var trimPictures by mutableStateOf<List<ImageBitmap?>>(emptyList())
        private set
    private val trimRequests = MutableStateFlow<TrimView?>(null)

    // started here, after the flow it reads: the scope's dispatcher runs a new coroutine at once
    init {
        scope.launch { renderTrims() }
    }

    /** Show [v] in the Program monitor (null: back to the program). */
    fun showTrim(v: TrimView?) {
        if (v == trimView) return
        if (v == null || v.frames.size != trimView?.frames?.size) trimPictures = List(v?.frames?.size ?: 0) { null }
        trimView = v
        trimRequests.value = v
    }

    private suspend fun renderTrims() {
        trimRequests.collectLatest { v ->
            if (v == null) return@collectLatest
            val r = renderer ?: return@collectLatest
            val side = (previewSide * 2 / 3).coerceIn(160, 960).toUInt()
            val pics = v.frames.map { f ->
                if (f.item == null) null
                else withContext(engine.preview) { runCatching { r.thumbnail(f.item, f.time.coerceAtLeast(0L), side, null).toBitmap()?.asImageBitmap() }.getOrNull() }
            }
            if (trimView == v) trimPictures = pics
        }
    }

    fun proTrim(clip: ULong, head: Boolean, delta: Long, ripple: Boolean) = edit { trimEdge(clip, head, delta, ripple) }

    /** A sound's Volume, all through (its volume line dragged with no keyframes). */
    fun setLevel(clip: ULong, db: Double) = edit { setLevel(clip, db) }

    /** A Volume keyframe at [at] (sequence time); [from] moves the keyframe there instead. */
    fun volumeKey(clip: ULong, at: Long, db: Double, from: Long?) = edit { volumeKey(clip, at, db, from) }

    fun deleteVolumeKey(clip: ULong, at: Long) = edit { deleteVolumeKey(clip, at) }

    /** Duck this music (and the other selected sounds) under every other sound. */
    fun duckMusic(clip: ULong) {
        val sel = pro?.clips?.filter { it.selected && it.kind == ClipKind.AUDIO }?.map { it.id }.orEmpty()
        val clips = (sel + clip).distinct()
        stop()
        scope.launch {
            try {
                val n = engine.command { duckMusic(clips, -15.0) }
                host.tell(if (n == 0u) "There's no other sound under this music to duck it under." else "The music now dips under the other sound.")
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
        }
    }

    /** The edit point picked for one-frame trims ([nudgeTrim]); the Program monitor shows its frames. */
    var pickedEdge by mutableStateOf<PickedEdge?>(null)

    /** Move the picked edit point by [frames] (negative: earlier), the way it was picked to trim. */
    fun nudgeTrim(frames: Int) {
        val e = pickedEdge ?: return
        val p = pro ?: return
        val c = p.clips.firstOrNull { it.id == e.clip } ?: return run { pickedEdge = null }
        val delta = frames * frameTicks
        if (!e.roll) return proTrim(c.id, e.head, delta, e.ripple)
        // a roll moves the cut between this clip and the one it touches
        val near = frameTicks / 2
        val other = p.clips.firstOrNull { o ->
            o.track == c.track && o.id != c.id && if (e.head) abs(o.start + o.duration - c.start) <= near else abs(o.start - (c.start + c.duration)) <= near
        }
        when {
            other == null -> proTrim(c.id, e.head, delta, ripple = false)
            e.head -> proRoll(other.id, c.id, delta)
            else -> proRoll(c.id, other.id, delta)
        }
    }
    fun proRoll(left: ULong, right: ULong, delta: Long) = edit { rollEdit(left, right, delta) }
    fun proSlip(clip: ULong, delta: Long) = edit { slipClip(clip, delta) }
    fun proSlide(clip: ULong, delta: Long) = edit { slideClip(clip, delta) }
    fun proRateStretch(clip: ULong, head: Boolean, delta: Long) = edit { rateStretch(clip, head, delta) }
    /** Razor at [at]; the clip before the cut is selected after. */
    fun proRazor(at: Long, clip: ULong?, track: ULong?) = cut { razor(at, clip, track) }

    /** Cut at the playhead: the selected clip under it (and its sound), else every track. */
    fun cutAtPlayhead() = cut { cutAtPlayhead() }

    /** A cut; says so when there was nothing at the playhead to cut. */
    private fun cut(block: MobileSession.() -> UInt) {
        stop()
        scope.launch {
            try {
                if (engine.command { block() } == 0u) host.tell("Nothing to cut at the playhead: move it over a clip.")
            } catch (e: Exception) {
                host.tell(e.userMessage)
            }
            refresh()
        }
    }
    fun proShift(clips: List<ULong>, delta: Long) = edit { shiftClips(clips, delta) }
    fun proSelectForward(track: ULong?, at: Long) = quickEdit { selectForward(track, at) }

    /** The Type tool asked for a new title at this time (the dialog shows while it is set). */
    var titleAt by mutableStateOf<Long?>(null)

    fun addTitle(at: Long, text: String) {
        titleAt = null
        if (text.isBlank()) return
        playhead = at
        edit {
            setPlayhead(at)
            addText(text)
        }
    }

    // ---- J / K / L ----

    private var reverseJob: Job? = null
    private var reverseRate = 1

    /** J: play backwards (picture only); again for faster, up to 8×. */
    fun shuttleBack() {
        if (reverseJob != null) {
            reverseRate = (reverseRate * 2).coerceAtMost(8)
            return
        }
        stop()
        reverseRate = 1
        val from = playhead
        reverseJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            var last = withFrameNanos { it }
            var t = from
            while (isActive && t > 0L) {
                val now = withFrameNanos { it }
                t = (t - (now - last) / 1_000L * TPS / 1_000_000L * reverseRate).coerceAtLeast(0L)
                last = now
                playhead = t
                requestFrame(t, draft = true)
            }
            reverseJob = null
            engine.command { setPlayhead(playhead) }
            requestFrame(playhead, draft = false)
        }
    }

    /** K: stop, whichever way it plays. */
    fun shuttleStop() {
        reverseJob?.cancel()
        if (reverseJob != null) {
            reverseJob = null
            val t = playhead
            scope.launch {
                engine.command { setPlayhead(t) }
                requestFrame(t, draft = false)
            }
        }
        stop()
    }

    /** L: play forwards. */
    fun shuttleForward() {
        if (reverseJob != null) shuttleStop()
        if (!playing) play()
    }

    // ---- Pro mode: the Project panel and the Source monitor ----

    /** Pro mode's tabs: the phone-upright layout's (Timeline, Source, Effects, Project) and the side panel's (Effect Controls, Project). */
    var proTab by mutableIntStateOf(0)
    var proSideTab by mutableIntStateOf(0)
    /** Pro mode is showing the desktop layout (the Source monitor always in sight). */
    var proDesktop = false

    /** Open [item] in the Source monitor and Insert or Overwrite all of it at the playhead. */
    fun openSourceAndEdit(item: ULong, overwrite: Boolean) {
        stopSource()
        scope.launch {
            val s = runCatching { engine.command { openSource(item) } }.onFailure { host.tell(it.userMessage) }.getOrNull() ?: return@launch
            source = s
            requestSourceFrame(s.playhead)
            sourceEdit(overwrite)
        }
    }

    /** The Project panel's items. */
    var bin by mutableStateOf<List<BinItem>>(emptyList())
        private set
    /** The clip in the Source monitor, and its picture at the source playhead. */
    var source by mutableStateOf<SourceInfo?>(null)
        private set
    var sourcePicture by mutableStateOf<ImageBitmap?>(null)
        private set
    var sourcePlaying by mutableStateOf(false)
        private set
    private var sourceJob: Job? = null
    private val sourceRequests = MutableStateFlow<Long?>(null)
    /** The Program monitor's playback resolution (1, 1/2, 1/4) and its safe margins. */
    var playbackScale by mutableFloatStateOf(1f)
    var safeMargins by mutableStateOf(false)

    // started after the state it reads (see renderTrims)
    init {
        scope.launch { renderSource() }
    }

    /** Pick media and put it on [track] at [at], over what is there (a double tap on the timeline). */
    fun addMediaAt(kind: PickKind, track: io.github.artcraftandroid.rushcraft.core.ProTrack, at: Long) {
        if (track.locked) return host.tell("${track.code} is locked.")
        host.pick(kind) { uris ->
            if (uris.isEmpty()) return@pick
            stop()
            scope.launch {
                busy = "Adding media…"
                try {
                    val paths = withContext(Dispatchers.IO) { uris.mapNotNull { u -> runCatching { projects.importUri(project, u).absolutePath }.getOrNull() } }
                    if (paths.isEmpty()) {
                        host.tell("That file can't be added.")
                    } else {
                        engine.command { placeMedia(paths, track.id, at) }
                        if (paths.size < uris.size) host.tell("Some files couldn't be added.")
                    }
                } catch (e: Exception) {
                    host.tell(e.userMessage)
                } finally {
                    busy = null
                }
                refresh()
            }
        }
    }

    /** A crossfade on the cut after [left]: picture and/or sound, [seconds] long. */
    fun crossfade(left: ProClip, video: Boolean, audio: Boolean, seconds: Double) = edit { crossfade(left.id, video, audio, seconds) }
    fun removeTransition(id: ULong) = edit { removeTransition(id) }
    fun closeGap(track: ULong, at: Long) = edit { closeGap(track, at) }

    /** Import files into the Project panel without putting them on the timeline. */
    fun importToBin(kind: PickKind) {
        host.pick(kind) { uris ->
            if (uris.isEmpty()) return@pick
            scope.launch {
                busy = "Importing…"
                try {
                    val paths = withContext(Dispatchers.IO) { uris.mapNotNull { u -> runCatching { projects.importUri(project, u).absolutePath }.getOrNull() } }
                    if (paths.isEmpty()) host.tell("That file can't be imported.") else engine.command { `import`(paths) }
                } catch (e: Exception) {
                    host.tell(e.userMessage)
                } finally {
                    busy = null
                }
                refresh()
            }
        }
    }

    fun openSource(item: ULong) {
        stopSource()
        scope.launch {
            val s = runCatching { engine.command { openSource(item) } }.onFailure { host.tell(it.userMessage) }.getOrNull() ?: return@launch
            source = s
            requestSourceFrame(s.playhead)
        }
    }

    fun closeSource() {
        stopSource()
        scope.launch {
            runCatching { engine.command { closeSource() } }
            source = null
            sourcePicture = null
        }
    }

    /** Scrub the Source monitor. */
    fun seekSource(t: Long) {
        val s = source ?: return
        val c = t.coerceIn(s.start, max(s.start, s.end - TimelineMath.tickOfFrame(1, s.rateNum, s.rateDen)))
        source = s.copy(playhead = c)
        requestSourceFrame(c)
        scope.launch { runCatching { engine.command { setSourcePlayhead(c) } } }
    }

    fun stepSource(frames: Int) {
        val s = source ?: return
        val f = TimelineMath.frames(s.playhead, s.rateNum, s.rateDen) + frames
        seekSource(TimelineMath.tickOfFrame(f.coerceAtLeast(0), s.rateNum, s.rateDen))
    }

    /** Play the source clip (picture only) from its playhead to its Out (or end). */
    fun toggleSourcePlay() {
        if (sourcePlaying) return stopSource()
        val s = source ?: return
        stop()
        val end = (s.markOut?.let { it + TimelineMath.tickOfFrame(1, s.rateNum, s.rateDen) } ?: s.end).coerceAtMost(s.end)
        val from = if (s.playhead >= end - TimelineMath.tickOfFrame(1, s.rateNum, s.rateDen)) (s.markIn ?: s.start) else s.playhead
        sourcePlaying = true
        sourceJob = scope.launch(androidx.compose.ui.platform.AndroidUiDispatcher.Main) {
            var last = withFrameNanos { it }
            var t = from
            while (isActive && t < end) {
                val now = withFrameNanos { it }
                t = (t + (now - last) / 1_000L * TPS / 1_000_000L).coerceAtMost(end)
                last = now
                source = source?.copy(playhead = t)
                requestSourceFrame(t)
            }
            sourcePlaying = false
            sourceJob = null
            val at = source?.playhead ?: return@launch
            seekSource(at)
        }
    }

    fun stopSource() {
        sourceJob?.cancel()
        sourceJob = null
        if (sourcePlaying) {
            sourcePlaying = false
            source?.let { seekSource(it.playhead) }
        }
    }

    private fun requestSourceFrame(t: Long) {
        sourceRequests.value = t
    }

    private suspend fun renderSource() {
        sourceRequests.filterNotNull().collectLatest { t ->
            val s = source ?: return@collectLatest
            val r = renderer ?: return@collectLatest
            if (!s.hasVideo) {
                sourcePicture = null
                return@collectLatest
            }
            val side = (previewSide * 2 / 3).coerceIn(160, 960).toUInt()
            val pic = withContext(engine.preview) { runCatching { r.thumbnail(s.item, t, side, null).toBitmap()?.asImageBitmap() }.getOrNull() }
            if (pic != null && source?.item == s.item) sourcePicture = pic
        }
    }

    fun sourceMarkIn() = source?.let { s -> edit { setSourceMark(true, s.playhead) } }
    fun sourceMarkOut() = source?.let { s -> edit { setSourceMark(false, s.playhead) } }
    fun sourceClearInOut() = source?.let {
        edit {
            setSourceMark(true, null)
            setSourceMark(false, null)
        }
    }

    /** Insert (,) or Overwrite (.) the source In to Out into the sequence on the patched tracks. */
    fun sourceEdit(overwrite: Boolean) {
        if (source == null) return host.tell("Open a clip from the Project panel in the Source monitor first.")
        stopSource()
        edit { sourceEdit(overwrite) }
    }

    /** Lift (;) or Extract (') the sequence In to Out. */
    fun liftExtract(extract: Boolean) = edit { liftExtract(extract) }

    // ---- Pro mode: Effect Controls ----

    /** Effect Controls' effects for [effectsClip], values at the playhead. */
    var effects by mutableStateOf<List<EffectInfo>>(emptyList())
        private set
    var effectsClip by mutableStateOf<ULong?>(null)
        private set

    /** Read Effect Controls for [clip] (nothing for none). */
    suspend fun loadEffects(clip: ULong?) {
        if (clip == null) {
            effects = emptyList()
            effectsClip = null
            return
        }
        val e = runCatching { engine.command { effectControls(clip) } }.getOrNull() ?: emptyList()
        effects = e
        effectsClip = clip
    }

    /** Change a parameter at the playhead; [begin] for the first change of a drag, [merge] for the rest. */
    fun setParam(e: EffectInfo, p: ParamInfo, value: List<Double>, merge: Boolean, begin: Boolean) =
        quickEdit { setEffectParam(e.clip, e.index, p.id, value, merge, begin) }

    fun toggleAnimation(e: EffectInfo, p: ParamInfo) = edit { toggleAnimation(e.clip, e.index, p.id) }
    fun toggleKeyframe(e: EffectInfo, p: ParamInfo) = edit { toggleKeyframe(e.clip, e.index, p.id) }
    fun deleteKeyframe(e: EffectInfo, p: ParamInfo, k: KeyframeInfo) = edit { deleteKeyframe(e.clip, e.index, p.id, k.mediaTime) }
    fun moveKeyframe(e: EffectInfo, p: ParamInfo, k: KeyframeInfo, toMedia: Long) = edit { moveKeyframe(e.clip, e.index, p.id, k.mediaTime, toMedia, false) }
    fun setInterpolation(e: EffectInfo, p: ParamInfo, k: KeyframeInfo, interpolation: String) =
        edit { setKeyframeInterpolation(e.clip, e.index, p.id, k.mediaTime, interpolation) }
    fun resetParam(e: EffectInfo, p: ParamInfo) = edit { resetEffectParam(e.clip, e.index, p.id) }

    // ---- a transition in Effect Controls ----

    /** The crossfade or fade picked on the timeline (Effect Controls shows it), else null. */
    var proTransition by mutableStateOf<ULong?>(null)
        private set
    var transitionControls by mutableStateOf<TransitionControls?>(null)
        private set

    /** Pick a crossfade or fade (a tap on it): Effect Controls shows its settings. */
    fun selectTransition(id: ULong) {
        proSelect(null)
        proTransition = id
    }

    /** Read the picked transition's settings (it may be gone after an edit). */
    suspend fun loadTransition() {
        val id = proTransition ?: return run { transitionControls = null }
        val c = runCatching { engine.command { transitionControls(id) } }.getOrNull()
        transitionControls = c
        if (c == null) proTransition = null
    }

    /** Its length ([duration], ticks), alignment ("center", "start", "end") or Reverse. */
    fun editTransition(id: ULong, duration: Long? = null, align: String? = null, reverse: Boolean? = null, merge: Boolean = false, begin: Boolean = true) =
        quickEdit { editTransition(id, duration, align, reverse, merge, begin) }

    fun setTransitionParam(id: ULong, p: ParamInfo, value: List<Double>, merge: Boolean, begin: Boolean) =
        quickEdit { setTransitionParam(id, p.id, value, merge, begin) }

    fun resetTransition(id: ULong) = edit { resetTransition(id) }

    /** Make it another type, keeping its place and length; the new one stays picked. */
    fun changeTransition(id: ULong, effect: String) {
        stop()
        scope.launch {
            runCatching { engine.command { changeTransition(id, effect) } }
                .onSuccess { proTransition = it }
                .onFailure { host.tell(it.userMessage) }
            refresh()
        }
    }
    fun toggleEffect(e: EffectInfo) = edit { toggleEffect(e.clip, e.index) }
    fun removeEffect(e: EffectInfo) = edit { removeEffect(e.clip, e.index) }

    /** Go to the cut before (or after) the playhead: the nearest clip start or end on any track. */
    fun goToCut(next: Boolean) {
        val p = pro ?: return
        val frame = TimelineMath.tickOfFrame(1, rateNum, rateDen)
        val cuts = p.clips.flatMap { listOf(it.start, it.start + it.duration) }.distinct()
        val t = (if (next) cuts.filter { it > playhead + frame / 2 }.minOrNull() else cuts.filter { it < playhead - frame / 2 }.maxOrNull()) ?: return
        seek(t.coerceAtLeast(0L), draft = false)
    }

    /** Go to the sequence's start (or its last frame). */
    fun goToSequenceEdge(end: Boolean) {
        val frame = TimelineMath.tickOfFrame(1, rateNum, rateDen)
        seek(if (end) (duration - frame).coerceAtLeast(0L) else 0L, draft = false)
    }

    /** Go to the previous (or next) keyframe of [p] from the playhead. */
    fun goToKeyframe(p: ParamInfo, next: Boolean) {
        val now = playhead
        val t = if (next) p.keyframes.map { it.time }.filter { it > now }.minOrNull() else p.keyframes.map { it.time }.filter { it < now }.maxOrNull()
        if (t != null) seek(t.coerceAtLeast(0L), draft = false)
    }

    // ---- export ----

    fun export() {
        stop()
        val d = duration
        if (d < frameTicks * 5) {
            host.tell("Add at least a few frames to the timeline to export.")
            return
        }
        host.export()
    }

    fun clipKindHasPicture(c: ClipInfo) = c.kind != ClipKind.AUDIO && c.kind != ClipKind.TEXT
}
