package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.rushcraft.core.EffectInfo
import io.github.artcraftandroid.rushcraft.core.ParamInfo
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcSize
import io.github.artcraftandroid.rushcraft.ui.FcSlider
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.ToolPanel

/** The Colour panel's sliders: Lumetri Color's Basic Correction, then its Creative and Vignette. */
private val BASIC = listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks", "temperature", "tint", "saturation")
private val CREATIVE = listOf("look_intensity", "faded_film", "vibrance", "sharpen", "vignette_amount")

/**
 * Colour (Pro mode): the selected video clip's Lumetri Color as phone sliders: exposure, contrast,
 * the tones, white balance and saturation; a creative look and its strength; an Input LUT for
 * log footage. Every change is the same parameter Effect Controls shows (keyframes too, when its
 * stopwatch is on).
 */
@Composable
fun ColorPanel(ed: EditorController, close: () -> Unit, modifier: Modifier = Modifier) {
    val pro = ed.pro
    val video = pro?.videoTracks.orEmpty().map { it.id }.toSet()
    val clip = ed.proSelection.firstOrNull { it.track in video }
    LaunchedEffect(clip?.id, pro?.revision) { ed.loadEffects(clip?.id) }
    val lumetri: EffectInfo? = if (clip != null && ed.effectsClip == clip.id) ed.effects.firstOrNull { it.id == "lumetri" } else null
    ToolPanel("Colour", close, modifier, tag = "color_panel") {
        val pad = Modifier.padding(horizontal = FcSize.PanelPadding)
        if (clip == null || lumetri == null) {
            Text("Select a video clip in the timeline.", style = FcType.BodySmall, color = Fc.Detail, modifier = pad.padding(vertical = 16.dp))
            return@ToolPanel
        }
        Column(Modifier.fillMaxWidth()) {
            Text("Basic correction", style = FcType.Label, color = Fc.Secondary, modifier = pad)
            for (id in BASIC) lumetri.params.firstOrNull { it.id == id }?.let { ColorSlider(ed, lumetri, it) }
            Spacer(Modifier.height(16.dp))
            // the creative look: a built-in LUT, then how strong
            lumetri.params.firstOrNull { it.id == "look" }?.let { look ->
                Text("Look", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                Spacer(Modifier.height(8.dp))
                val now = (look.value.firstOrNull() ?: 0.0).toInt()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    look.choices.forEachIndexed { k, name ->
                        SelectChip(name, k == now, { ed.setParam(lumetri, look, listOf(k.toDouble()), merge = false, begin = true) }, tag = "color_look_$k")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            for (id in CREATIVE) lumetri.params.firstOrNull { it.id == id }?.let { ColorSlider(ed, lumetri, it) }
            Spacer(Modifier.height(16.dp))
            // log footage: a camera's log to Rec. 709
            val luts = remember { ed.inputLuts() }
            if (luts.isNotEmpty()) {
                Text("Input LUT (log footage)", style = FcType.Label, color = Fc.Secondary, modifier = pad)
                Spacer(Modifier.height(8.dp))
                var picked by remember(clip.id) { mutableStateOf("") }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = FcSize.PanelPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectChip("None", picked.isEmpty(), {
                        picked = ""
                        ed.setInputLut(clip.id, "")
                    }, tag = "color_lut_none")
                    for (l in luts) {
                        SelectChip(l.name, picked == l.id, {
                            picked = l.id
                            ed.setInputLut(clip.id, l.id)
                        }, tag = "color_lut_${l.id}")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One Lumetri setting as a labelled slider over its usual range, the value beside it. */
@Composable
private fun ColorSlider(ed: EditorController, e: EffectInfo, p: ParamInfo) {
    val lo = p.softMin
    val hi = if (p.softMax > p.softMin) p.softMax else p.max
    var shown by remember(p.value) { mutableFloatStateOf(((p.value.firstOrNull() ?: 0.0) - lo).div(hi - lo).toFloat().coerceIn(0f, 1f)) }
    var first by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxWidth().padding(horizontal = FcSize.PanelPadding).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, style = FcType.BodySmall, color = Fc.Body, modifier = Modifier.width(96.dp), maxLines = 1)
        FcSlider(
            shown,
            { f ->
                shown = f
                ed.setParam(e, p, listOf(lo + f * (hi - lo)), merge = true, begin = first)
                first = false
            },
            onDone = { first = true },
            modifier = Modifier.weight(1f),
            description = p.name,
            tag = "color_${p.id}",
        )
        Text("%.${p.decimals.toInt().coerceIn(0, 2)}f".format(lo + shown * (hi - lo)), style = FcType.BodySmall, color = Fc.AccentLight, modifier = Modifier.width(52.dp).padding(start = 8.dp))
    }
}
