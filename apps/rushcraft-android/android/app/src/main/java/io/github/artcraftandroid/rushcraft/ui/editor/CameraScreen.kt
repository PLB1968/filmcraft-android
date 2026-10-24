package io.github.artcraftandroid.rushcraft.ui.editor

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcIconButton
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.glyph
import kotlinx.coroutines.delay

/**
 * Record straight into the timeline: the phone's camera full screen, front or back, 1080p or 4K.
 * A finished recording goes into the project's media and onto the timeline at the playhead.
 */
@SuppressLint("MissingPermission") // asked for before this shows (EditorController.openCamera)
@Composable
fun CameraScreen(ed: EditorController) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var back by remember { mutableStateOf(true) }
    var uhd by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var seconds by remember { mutableLongStateOf(0L) }
    val controller = remember {
        LifecycleCameraController(ctx).apply { setEnabledUseCases(CameraController.VIDEO_CAPTURE) }
    }
    DisposableEffect(owner) {
        controller.bindToLifecycle(owner)
        onDispose {
            recording?.stop()
            controller.unbind()
        }
    }
    LaunchedEffect(back) { controller.cameraSelector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA }
    LaunchedEffect(uhd) { controller.videoCaptureQualitySelector = QualitySelector.from(if (uhd) Quality.UHD else Quality.FHD) }
    LaunchedEffect(recording) {
        while (recording != null) {
            seconds = (System.currentTimeMillis() - started) / 1000
            delay(250)
        }
    }
    BackHandler { if (recording == null) ed.camera = false else recording?.stop() }
    fun toggle() {
        val r = recording
        if (r != null) {
            r.stop()
            return
        }
        val file = ed.newRecordingFile()
        started = System.currentTimeMillis()
        seconds = 0
        recording = controller.startRecording(FileOutputOptions.Builder(file).build(), AudioConfig.create(true), ContextCompat.getMainExecutor(ctx)) { e ->
            if (e is VideoRecordEvent.Finalize) {
                recording = null
                if (e.hasError() && e.error != VideoRecordEvent.Finalize.ERROR_NONE && !file.isFile) {
                    ed.camera = false
                } else {
                    ed.addRecording(file)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("camera_screen")) {
        AndroidView(factory = { PreviewView(it).apply { this.controller = controller } }, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FcIconButton(LucideIcons.X.glyph(), "Close the camera", { if (recording == null) ed.camera = false })
            Spacer(Modifier.weight(1f))
            if (recording != null) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFDC2626)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("%d:%02d".format(seconds / 60, seconds % 60), style = FcType.Label, color = Color.White)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("1080p", !uhd, { uhd = false })
                    SelectChip("4K", uhd, { uhd = true })
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(48.dp))
            // the record button: a red dot, a red square while recording
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(role = Role.Button) { toggle() }
                    .semantics { contentDescription = if (recording == null) "Record" else "Stop recording" }
                    .testTag("camera_record"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(if (recording == null) 58.dp else 30.dp).clip(if (recording == null) CircleShape else RoundedCornerShape(6.dp)).background(Color(0xFFDC2626)))
            }
            FcIconButton(LucideIcons.RotateCcw.glyph(), "Switch camera", { if (recording == null) back = !back })
        }
        if (recording == null) {
            Text(
                "Recordings go onto the timeline at the playhead.",
                style = FcType.BodySmall,
                color = Fc.Detail,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
            )
        }
    }
}
