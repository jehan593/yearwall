package com.yearwall.app.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.yearwall.app.R
import com.yearwall.app.ui.theme.nord0
import com.yearwall.app.ui.theme.nord11
import com.yearwall.app.ui.theme.nord13
import com.yearwall.app.ui.theme.nord5
import com.yearwall.app.ui.theme.nord8
import java.time.LocalDate
import kotlin.math.ceil

/*
 * The magick pipeline of the Termux yearwall script, rebuilt as a single Canvas frame, with
 * every dimension derived from the render size (nothing hard-coded in pixels):
 *
 *   columns   — the width knob: cell = 0.8167 * width / columns; dot radius, ring stroke and
 *               row pitch all derive from the cell, so the grid stays proportionate at any
 *               column count.
 *   contentY  — the whole composite's vertical anchor: separator sits there, header hangs one
 *               text-size above, the grid hangs ~0.8 of a row pitch below. Moving it moves
 *               everything together.
 *
 * The trailing row is always centered (the termux render centered each text line via
 * ImageMagick gravity — that IS the original look). Leap years come from
 * LocalDate.lengthOfYear(); the shell's `10#` octal guard has no java.time analogue.
 */
object WallpaperGenerator {

    /**
     * @param columns       days per row — the width & density knob (script: 21)
     * @param contentYRatio separator Y as a fraction of the render height (script: 700/2400 ≈ 0.29)
     */
    data class Design(
        val width: Int = 1080,
        val height: Int = 2400,
        val columns: Int = 21,
        val contentYRatio: Float = 292f / 1000f,
    )

    // Shell-script-derived ratios, computed at the pipeline's 1080x2400 reference size —
    // then calibrated against the termux render side-by-side on-device: the magick dots
    // carried a fake-bold bleed (the script drew the whole grid 4x at 0/1/2px x-offsets),
    // so DOT_RATIO is the ●-glyph size INCLUDING that bleed, and the grid hangs only ~0.8
    // of a row pitch under the line rather than a full pitch.
    private const val GRID_WIDTH_RATIO = 0.8167f   // 21 days * 42px cell = 882 of 1080
    private const val DOT_RATIO = 0.25f            // bold ● glyph: radius ≈ half the advance
    private const val RING_RATIO = 0.062f          // hollow-day stroke, same weight class
    private const val PITCH_RATIO = 1.12f          // row pitch ≈ 47px at the 42px cell
    private const val FIRST_ROW_GAP = 0.82f        // separator → first dot center, in pitches
    private const val TEXT_RATIO = 0.0324f         // header text 35px of 1080
    private const val LINE_RATIO = 0.00926f        // separator 10px thick of 1080
    private const val MARGIN_RATIO = 0.088f        // separator left x = 95 of 1080
    private const val LINE_END_RATIO = 0.8963f     // separator right x = 968 of 1080

    fun generate(context: Context, date: LocalDate, d: Design): Bitmap {
        val total = date.lengthOfYear()
        val day = date.dayOfYear
        val percent = (100 * day) / total
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val columns = d.columns.coerceAtLeast(1)

        // Everything scales; nothing is stamped in raw pixels.
        val cellSize = GRID_WIDTH_RATIO * w / columns
        val rowPitch = PITCH_RATIO * cellSize
        val dotRadius = DOT_RATIO * cellSize
        val ringStroke = RING_RATIO * cellSize
        val headerTextSize = TEXT_RATIO * w
        val lineWidth = LINE_RATIO * w
        val lineLeft = MARGIN_RATIO * w
        val lineRight = LINE_END_RATIO * w
        val separatorY = d.contentYRatio * h
        val gridCenterX = (lineLeft + lineRight) / 2f

        val bitmap = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(nord0.toArgb())

        // -- Separator line (script: -draw "line 95,$LINE_Y 968,$LINE_Y") -------------------------
        canvas.drawLine(
            lineLeft, separatorY,
            lineRight, separatorY,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = nord8.toArgb()
                strokeWidth = lineWidth
            }
        )

        // -- Headers ------------------------------------------------------------------------------
        // The script drew each header three times (x offsets 0/1/2) as a fake-bold trick for
        // arbitrary TTFs; Paint has the same capability built in as isFakeBoldText. The header
        // hangs one text-height above the separator, scaling with the render, never with knobs.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.martian_mono_regular)
                ?: Typeface.MONOSPACE
            textSize = headerTextSize
            isFakeBoldText = true
        }
        val headerBaseline = separatorY - headerTextSize
        text.color = nord8.toArgb()
        canvas.drawText("$percent% of ${date.year}", lineLeft, headerBaseline, text)
        text.color = nord13.toArgb()
        val right = "$day/$total"
        canvas.drawText(right, lineRight - text.measureText(right), headerBaseline, text)

        // -- Dot grid: hangs below the separator, ~0.8 of a row pitch of air ----------------------
        // (the termux render sat the first line snugly under the line — a full pitch looked
        // detached by comparison).
        val rows = ceil(total / columns.toFloat()).toInt()
        val lastRowCount = total - (rows - 1) * columns
        val firstRowCenterY = separatorY + rowPitch * FIRST_ROW_GAP

        val elapsed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = nord5.toArgb()
            style = Paint.Style.FILL
        }
        val remaining = Paint(elapsed).apply {
            style = Paint.Style.STROKE
            strokeWidth = ringStroke
        }
        val today = Paint(elapsed).apply { color = nord11.toArgb() }

        for (i in 1..total) {
            val col = (i - 1) % columns
            val row = (i - 1) / columns
            // Short trailing row centers under the grid, always — the termux line-gravity look.
            val drift = if (row == rows - 1 && lastRowCount < columns) {
                (columns - lastRowCount) / 2f * cellSize
            } else {
                0f
            }
            val cx = gridCenterX + (col - (columns - 1) / 2f) * cellSize + drift
            val cy = firstRowCenterY + row * rowPitch
            if (i <= day) {
                // Same radius as the glyph's bolded footprint — this is the "weight" the
                // original carried.
                canvas.drawCircle(cx, cy, dotRadius, elapsed)
            } else {
                // Stroke is centered on the path: radius resolves the outer edge back to the
                // filled dots' diameter, so hollow and solid read as one consistent grid.
                canvas.drawCircle(cx, cy, dotRadius - ringStroke / 2f, remaining)
            }
            // A hair larger so it reads as highlighted-over, exactly the script's red overlay.
            if (i == day) canvas.drawCircle(cx, cy, dotRadius + ringStroke * 0.35f, today)
        }

        /*
         * Invisible ownership marker: two corner pixels carrying colors far outside the Nord
         * palette. Lets the applier recognize any wall yearwall ever painted (any day, any
         * install) in O(1) and refuse to "back it up" as the user's previous wallpaper.
         */
        bitmap.setPixel(d.width - 1, d.height - 1, MARKER_A)
        bitmap.setPixel(d.width - 2, d.height - 1, MARKER_B)

        return bitmap
    }

    // Marker colors deliberately outside the Nord palette.
    const val MARKER_A = 0xFFB81B62.toInt()
    const val MARKER_B = 0xFF1E4FC9.toInt()

    fun isAppRender(bitmap: Bitmap): Boolean =
        bitmap.width >= 2 &&
            bitmap.getPixel(bitmap.width - 1, bitmap.height - 1) == MARKER_A &&
            bitmap.getPixel(bitmap.width - 2, bitmap.height - 1) == MARKER_B
}
