package io.github.artcraftandroid.rushcraft.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.rushcraft.core.SequenceEntry
import io.github.artcraftandroid.rushcraft.ui.Fc
import io.github.artcraftandroid.rushcraft.ui.FcDialog
import io.github.artcraftandroid.rushcraft.ui.FcType
import io.github.artcraftandroid.rushcraft.ui.GlyphIcon
import io.github.artcraftandroid.rushcraft.ui.Inter
import io.github.artcraftandroid.rushcraft.ui.LucideIcons
import io.github.artcraftandroid.rushcraft.ui.SelectChip
import io.github.artcraftandroid.rushcraft.ui.glyph

/** A sequence's shape, as the New Sequence dialog offers them. */
private val SHAPES = listOf("16:9" to (1920 to 1080), "9:16" to (1080 to 1920), "1:1" to (1080 to 1080), "4:5" to (1080 to 1350))

/** What a dialog for a sequence is doing: making one, or renaming [entry]. */
private sealed interface SequenceDialog {
    data object New : SequenceDialog
    data class Rename(val entry: SequenceEntry) : SequenceDialog
}

/**
 * The tabs over the timeline: one per sequence of the project (tap to open it, hold for Rename,
 * Duplicate and Delete), then + for a new one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SequenceTabs(ed: EditorController) {
    var dialog by remember { mutableStateOf<SequenceDialog?>(null) }
    var menuFor by remember { mutableStateOf<ULong?>(null) }
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(Fc.Panel).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp).testTag("pro_sequence_tabs"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (q in ed.sequences) {
            Box {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(if (q.active) Fc.Black else Color.Transparent)
                        .combinedClickable(role = Role.Tab, onLongClick = { menuFor = q.id }, onClick = { ed.openSequence(q.id) })
                        .padding(horizontal = 12.dp)
                        .testTag("pro_sequence_tab_${q.id}")
                        .semantics { selected = q.active },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(q.name, style = BarText, color = if (q.active) Fc.Title else Fc.Detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SequenceMenu(ed, q, menuFor == q.id, { menuFor = null }) { dialog = SequenceDialog.Rename(q) }
            }
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).combinedClickable(role = Role.Button, onClick = { dialog = SequenceDialog.New }).testTag("pro_sequence_new").semantics { contentDescription = "New sequence" },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(LucideIcons.Plus.glyph(), Fc.Icon, size = 18.dp)
        }
    }
    if (ed.newSequenceAsked) {
        SequenceNameDialog(ed, SequenceDialog.New) { ed.newSequenceAsked = false }
    }
    dialog?.let { d -> SequenceNameDialog(ed, d) { dialog = null } }
}

/** A sequence's menu: Open, Rename…, Duplicate, Delete. */
@Composable
internal fun SequenceMenu(ed: EditorController, q: SequenceEntry, open: Boolean, onDismiss: () -> Unit, onRename: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = Fc.Panel) {
        val one = ed.sequences.size <= 1
        for ((label, enabled, action) in listOf<Triple<String, Boolean, () -> Unit>>(
            Triple("Open", !q.active, { ed.openSequence(q.id) }),
            Triple("Rename…", true, onRename),
            Triple("Duplicate", true, { ed.duplicateSequence(q.id) }),
            Triple(if (q.nested) "Delete (used in another sequence)" else "Delete", !one && !q.nested, { ed.deleteSequence(q.id) }),
        )) {
            DropdownMenuItem(
                text = { Text(label, style = FcType.Body, color = if (enabled) Fc.Body else Fc.Disabled) },
                enabled = enabled,
                onClick = {
                    onDismiss()
                    action()
                },
            )
        }
    }
}

/** New Sequence (a name and a shape) or Rename. */
@Composable
private fun SequenceNameDialog(ed: EditorController, d: SequenceDialog, onClose: () -> Unit) {
    val rename = d as? SequenceDialog.Rename
    var name by remember { mutableStateOf(rename?.entry?.name ?: "Sequence ${ed.sequences.size + 1}") }
    var shape by remember { mutableStateOf(0) }
    FcDialog(
        if (rename != null) "Rename Sequence" else "New Sequence",
        if (rename != null) "Rename" else "Create",
        onConfirm = {
            onClose()
            if (rename != null) {
                ed.renameSequence(rename.entry.id, name)
            } else {
                val (w, h) = SHAPES[shape].second
                ed.newSequence(name, w, h)
            }
        },
        onDismiss = onClose,
        confirmEnabled = name.isNotBlank(),
    ) {
        Text("Name", style = FcType.BodySmall, color = Fc.Secondary)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            name,
            { name = it },
            singleLine = true,
            textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Fc.Title),
            cursorBrush = SolidColor(Fc.Title),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Fc.Raised).padding(12.dp).testTag("pro_sequence_name"),
        )
        if (rename == null) {
            Spacer(Modifier.height(12.dp))
            Text("Shape", style = FcType.BodySmall, color = Fc.Secondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SHAPES.forEachIndexed { i, (label, _) -> SelectChip(label, shape == i, { shape = i }, tag = "pro_sequence_shape_$i") }
            }
        }
    }
}

/**
 * The Project panel's sequences, above the media: tap one to open it, hold and drag it onto the
 * timeline to use it as one clip (a nest), hold for its menu.
 */
@Composable
internal fun ProjectSequences(ed: EditorController) {
    var menuFor by remember { mutableStateOf<ULong?>(null) }
    var renaming by remember { mutableStateOf<SequenceEntry?>(null) }
    Column(Modifier.fillMaxWidth()) {
    for (q in ed.sequences) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(if (q.active) Fc.Raised else Color.Transparent)
                    .mediaDragSource(
                        ed,
                        enabled = true,
                        // (not into itself)
                        drag = { if (q.active) null else ed.mediaDragOf(q.id, q.name, q.duration) },
                        onTap = {
                            ed.openSequence(q.id)
                            if (!ed.proDesktop) ed.proTab = 0
                        },
                        onHold = { menuFor = q.id },
                    )
                    .padding(horizontal = 8.dp)
                    .testTag("pro_bin_sequence_${q.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 72.dp, height = 36.dp).clip(RoundedCornerShape(4.dp)).background(Fc.ThumbPlaceholder), contentAlignment = Alignment.Center) {
                    GlyphIcon(LucideIcons.Film.glyph(), if (q.active) Fc.AccentLight else Fc.Secondary, size = 20.dp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    q.name + "  ·  ${q.width}×${q.height}" + (if (q.active) "  ·  open" else ""),
                    style = FcType.BodySmall, color = Fc.Title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SequenceMenu(ed, q, menuFor == q.id, { menuFor = null }) { renaming = q }
        }
    }
    }
    renaming?.let { q -> SequenceNameDialog(ed, SequenceDialog.Rename(q)) { renaming = null } }
}
