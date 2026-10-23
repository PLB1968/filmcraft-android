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
}
