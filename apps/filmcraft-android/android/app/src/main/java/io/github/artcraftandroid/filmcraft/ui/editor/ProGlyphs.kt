package io.github.artcraftandroid.filmcraft.ui.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.artcraftandroid.filmcraft.ui.Glyph
import io.github.artcraftandroid.filmcraft.ui.ICON_STROKE

/**
 * Pro mode's glyphs, drawn on a 24-unit grid like [io.github.artcraftandroid.filmcraft.ui.Glyphs]:
 * the track header's switches and the timeline's toggles.
 */
object ProGlyphs {
    private fun DrawScope.u(v: Float) = v * size.minDimension / 24f
    private fun DrawScope.stroke() = Stroke(u(ICON_STROKE + 0.4f), cap = StrokeCap.Round, join = StrokeJoin.Round)

    private fun DrawScope.padlock(c: androidx.compose.ui.graphics.Color, open: Boolean) {
        drawRoundRect(c, Offset(u(5f), u(11f)), Size(u(14f), u(10f)), CornerRadius(u(2f)), style = stroke())
        val shackle = Path().apply {
            moveTo(u(8f), u(11f))
            lineTo(u(8f), u(7.5f))
            cubicTo(u(8f), u(2.5f), u(16f), u(2.5f), u(16f), u(7.5f))
            if (!open) lineTo(u(16f), u(11f))
        }
        drawPath(shackle, c, style = stroke())
    }

    val Lock = Glyph.Drawn { c -> padlock(c, open = false) }
    val Unlock = Glyph.Drawn { c -> padlock(c, open = true) }

    private fun DrawScope.eye(c: androidx.compose.ui.graphics.Color) {
        val lid = Path().apply {
            moveTo(u(2f), u(12f))
            cubicTo(u(6f), u(5f), u(18f), u(5f), u(22f), u(12f))
            cubicTo(u(18f), u(19f), u(6f), u(19f), u(2f), u(12f))
            close()
        }
        drawPath(lid, c, style = stroke())
        drawCircle(c, u(3f), Offset(u(12f), u(12f)), style = stroke())
    }

    val Eye = Glyph.Drawn { c -> eye(c) }
    val EyeOff = Glyph.Drawn { c ->
        eye(c)
        drawLine(c, Offset(u(4f), u(4f)), Offset(u(20f), u(20f)), u(ICON_STROKE + 0.4f), StrokeCap.Round)
    }

    /** Sync lock: two linked bars. */
    private fun DrawScope.sync(c: androidx.compose.ui.graphics.Color) {
        drawRoundRect(c, Offset(u(3f), u(5f)), Size(u(18f), u(5f)), CornerRadius(u(1.5f)), style = stroke())
        drawRoundRect(c, Offset(u(3f), u(14f)), Size(u(18f), u(5f)), CornerRadius(u(1.5f)), style = stroke())
        drawLine(c, Offset(u(12f), u(10f)), Offset(u(12f), u(14f)), u(ICON_STROKE + 0.4f), StrokeCap.Round)
    }

    val SyncLock = Glyph.Drawn { c -> sync(c) }
    val SyncOff = Glyph.Drawn { c ->
        sync(c)
        drawLine(c, Offset(u(4f), u(21f)), Offset(u(20f), u(3f)), u(ICON_STROKE + 0.4f), StrokeCap.Round)
    }

    /** Snap in Timeline: a magnet. */
    val Magnet = Glyph.Drawn { c ->
        val m = Path().apply {
            moveTo(u(5f), u(4f))
            lineTo(u(5f), u(12f))
            cubicTo(u(5f), u(21.5f), u(19f), u(21.5f), u(19f), u(12f))
            lineTo(u(19f), u(4f))
            lineTo(u(15f), u(4f))
            lineTo(u(15f), u(12f))
            cubicTo(u(15f), u(16f), u(9f), u(16f), u(9f), u(12f))
            lineTo(u(9f), u(4f))
            close()
        }
        drawPath(m, c, style = stroke())
        drawLine(c, Offset(u(5f), u(8f)), Offset(u(9f), u(8f)), u(ICON_STROKE + 0.4f))
        drawLine(c, Offset(u(15f), u(8f)), Offset(u(19f), u(8f)), u(ICON_STROKE + 0.4f))
    }

    /** Linked Selection: two chain links. */
    val Link = Glyph.Drawn { c ->
        drawRoundRect(c, Offset(u(2f), u(8f)), Size(u(11f), u(8f)), CornerRadius(u(4f)), style = stroke())
        drawRoundRect(c, Offset(u(11f), u(8f)), Size(u(11f), u(8f)), CornerRadius(u(4f)), style = stroke())
    }

    /** A marker: the desktop's pentagon flag. */
    val Marker = Glyph.Drawn { c ->
        val p = Path().apply {
            moveTo(u(6f), u(3f))
            lineTo(u(18f), u(3f))
            lineTo(u(18f), u(15f))
            lineTo(u(12f), u(21f))
            lineTo(u(6f), u(15f))
            close()
        }
        drawPath(p, c, style = stroke())
    }

    private fun DrawScope.bracket(c: androidx.compose.ui.graphics.Color, isIn: Boolean) {
        val x0 = if (isIn) u(9f) else u(15f)
        val x1 = if (isIn) u(15f) else u(9f)
        val p = Path().apply {
            moveTo(x1, u(4f))
            lineTo(x0, u(4f))
            lineTo(x0, u(20f))
            lineTo(x1, u(20f))
        }
        drawPath(p, c, style = stroke())
    }

    /** Mark In "{" and Mark Out "}". */
    val MarkIn = Glyph.Drawn { c -> bracket(c, isIn = true) }
    val MarkOut = Glyph.Drawn { c -> bracket(c, isIn = false) }

    /** Go to In / Go to Out: a bracket with an arrow into it. */
    val GoToIn = Glyph.Drawn { c ->
        bracket(c, isIn = true)
        drawLine(c, Offset(u(20f), u(12f)), Offset(u(11f), u(12f)), u(ICON_STROKE + 0.4f), StrokeCap.Round)
    }
    val GoToOut = Glyph.Drawn { c ->
        bracket(c, isIn = false)
        drawLine(c, Offset(u(4f), u(12f)), Offset(u(13f), u(12f)), u(ICON_STROKE + 0.4f), StrokeCap.Round)
    }

    /** Zoom to the whole sequence: arrows pointing out. */
    val Fit = Glyph.Drawn { c ->
        val s = u(ICON_STROKE + 0.4f)
        drawLine(c, Offset(u(3f), u(12f)), Offset(u(21f), u(12f)), s, StrokeCap.Round)
        drawLine(c, Offset(u(3f), u(12f)), Offset(u(7f), u(8f)), s, StrokeCap.Round)
        drawLine(c, Offset(u(3f), u(12f)), Offset(u(7f), u(16f)), s, StrokeCap.Round)
        drawLine(c, Offset(u(21f), u(12f)), Offset(u(17f), u(8f)), s, StrokeCap.Round)
        drawLine(c, Offset(u(21f), u(12f)), Offset(u(17f), u(16f)), s, StrokeCap.Round)
    }

    // ---- the tool bar ----

    private fun DrawScope.ln(c: androidx.compose.ui.graphics.Color, x0: Float, y0: Float, x1: Float, y1: Float) =
        drawLine(c, Offset(u(x0), u(y0)), Offset(u(x1), u(y1)), u(ICON_STROKE + 0.4f), StrokeCap.Round)

    private fun DrawScope.arrowHead(c: androidx.compose.ui.graphics.Color, x: Float, y: Float, right: Boolean) {
        val k = if (right) -1f else 1f
        ln(c, x, y, x + 3f * k, y - 3f)
        ln(c, x, y, x + 3f * k, y + 3f)
    }

    /** Selection (V): the pointer. */
    val Selection = Glyph.Drawn { c ->
        val p = Path().apply {
            moveTo(u(6f), u(3f))
            lineTo(u(18f), u(14f))
            lineTo(u(12.5f), u(14.5f))
            lineTo(u(15.5f), u(20.5f))
            lineTo(u(13f), u(21.5f))
            lineTo(u(10f), u(15.5f))
            lineTo(u(6f), u(19f))
            close()
        }
        drawPath(p, c, style = stroke())
    }

    /** Track Select Forward (A): two arrows into a bar. */
    val TrackSelectForward = Glyph.Drawn { c ->
        ln(c, 4f, 4f, 4f, 20f)
        ln(c, 7f, 9f, 19f, 9f)
        arrowHead(c, 19f, 9f, right = true)
        ln(c, 7f, 15f, 19f, 15f)
        arrowHead(c, 19f, 15f, right = true)
    }

    /** Ripple Edit (B): an edit bracket with an arrow pushing on. */
    val RippleEdit = Glyph.Drawn { c ->
        ln(c, 9f, 4f, 9f, 20f)
        ln(c, 9f, 4f, 12f, 4f)
        ln(c, 9f, 20f, 12f, 20f)
        ln(c, 13f, 12f, 21f, 12f)
        arrowHead(c, 21f, 12f, right = true)
        ln(c, 3f, 12f, 7f, 12f)
    }

    /** Rolling Edit (N): two brackets back to back with arrows both ways. */
    val RollingEdit = Glyph.Drawn { c ->
        ln(c, 10f, 4f, 10f, 20f)
        ln(c, 14f, 4f, 14f, 20f)
        ln(c, 7f, 4f, 10f, 4f)
        ln(c, 7f, 20f, 10f, 20f)
        ln(c, 14f, 4f, 17f, 4f)
        ln(c, 14f, 20f, 17f, 20f)
        ln(c, 2f, 12f, 7f, 12f)
        arrowHead(c, 2f, 12f, right = false)
        ln(c, 17f, 12f, 22f, 12f)
        arrowHead(c, 22f, 12f, right = true)
    }

    /** Rate Stretch (R): a clip with arrows out of both ends. */
    val RateStretch = Glyph.Drawn { c ->
        drawRoundRect(c, Offset(u(7f), u(7f)), Size(u(10f), u(10f)), CornerRadius(u(1.5f)), style = stroke())
        ln(c, 2f, 12f, 6f, 12f)
        arrowHead(c, 2f, 12f, right = false)
        ln(c, 18f, 12f, 22f, 12f)
        arrowHead(c, 22f, 12f, right = true)
    }

    /** Slip (Y): footage moving inside fixed edges. */
    val Slip = Glyph.Drawn { c ->
        ln(c, 3f, 5f, 3f, 19f)
        ln(c, 21f, 5f, 21f, 19f)
        ln(c, 7f, 12f, 17f, 12f)
        arrowHead(c, 7f, 12f, right = false)
        arrowHead(c, 17f, 12f, right = true)
        ln(c, 7f, 7f, 17f, 7f)
        ln(c, 7f, 17f, 17f, 17f)
    }

    /** Slide (U): a clip moving between its neighbours. */
    val Slide = Glyph.Drawn { c ->
        drawRoundRect(c, Offset(u(8f), u(8f)), Size(u(8f), u(8f)), CornerRadius(u(1.5f)), style = stroke())
        ln(c, 2f, 6f, 2f, 18f)
        ln(c, 22f, 6f, 22f, 18f)
        ln(c, 4f, 12f, 7f, 12f)
        ln(c, 17f, 12f, 20f, 12f)
        arrowHead(c, 20f, 12f, right = true)
        arrowHead(c, 4f, 12f, right = false)
    }

    /** Hand (H): an open hand. */
    val Hand = Glyph.Drawn { c ->
        for ((x, top) in listOf(8f to 6f, 11f to 4f, 14f to 4.5f, 17f to 7f)) {
            drawRoundRect(c, Offset(u(x - 1.3f), u(top)), Size(u(2.6f), u(12f - top)), CornerRadius(u(1.3f)), style = stroke())
        }
        val palm = Path().apply {
            moveTo(u(6.7f), u(12f))
            lineTo(u(4.5f), u(10.5f))
            cubicTo(u(3f), u(10f), u(2.5f), u(12f), u(4f), u(13f))
            lineTo(u(8f), u(19f))
            cubicTo(u(9f), u(20.5f), u(10f), u(21f), u(12f), u(21f))
            lineTo(u(14f), u(21f))
            cubicTo(u(17f), u(21f), u(18.3f), u(19f), u(18.3f), u(16f))
            lineTo(u(18.3f), u(12f))
        }
        drawPath(palm, c, style = stroke())
    }

    /** Zoom (Z): a magnifier with a plus. */
    val Zoom = Glyph.Drawn { c ->
        drawCircle(c, u(6.5f), Offset(u(10f), u(10f)), style = stroke())
        ln(c, 15f, 15f, 21f, 21f)
        ln(c, 7f, 10f, 13f, 10f)
        ln(c, 10f, 7f, 10f, 13f)
    }

    /** The stopwatch: a parameter's keyframing switch. */
    val Stopwatch = Glyph.Drawn { c ->
        drawCircle(c, u(7.5f), Offset(u(12f), u(13.5f)), style = stroke())
        ln(c, 12f, 13.5f, 12f, 9.5f)
        ln(c, 12f, 13.5f, 14.8f, 15f)
        ln(c, 10f, 3f, 14f, 3f)
        ln(c, 12f, 3f, 12f, 6f)
        ln(c, 18.5f, 6.5f, 19.8f, 5.2f)
    }

    /** A keyframe: a diamond. */
    val Keyframe = Glyph.Drawn { c ->
        val p = Path().apply {
            moveTo(u(12f), u(4f))
            lineTo(u(20f), u(12f))
            lineTo(u(12f), u(20f))
            lineTo(u(4f), u(12f))
            close()
        }
        drawPath(p, c, style = stroke())
    }
}

