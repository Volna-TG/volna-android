package app.volna

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal class WaveArtwork(context: Context) : View(context) {
    private data class Swell(
        val level: Float,
        val height: Float,
        val phase: Float,
        val crest: Int,
        val body: Int,
        val edge: IntArray
    )

    private data class Speck(val x: Float, val y: Float, val size: Float, val phase: Float)

    private val swells = listOf(
        Swell(0.60f, 0.15f, -0.48f, 0x704344A0, 0x3010223C, intArrayOf(0x001F5D8F, 0xD97082ED.toInt(), 0xE58BC9FF.toInt(), 0x605775CD)),
        Swell(0.67f, 0.18f, -0.57f, 0xB64574BD.toInt(), 0x8D134771.toInt(), intArrayOf(0x66468EF9, 0xE37383EF.toInt(), 0xFFB8F6FF.toInt(), 0x654D82D2)),
        Swell(0.76f, 0.17f, -0.33f, 0xBE1686B4.toInt(), 0xBB183E70.toInt(), intArrayOf(0x925ACCFC.toInt(), 0xCC789BEF.toInt(), 0xDF7BEBFA.toInt(), 0x406BDFFC)),
        Swell(0.83f, 0.16f, -0.12f, 0xCA286D99.toInt(), 0xC6163760.toInt(), intArrayOf(0x346CCBF8, 0xD5A292F9.toInt(), 0xDF8FCBFF.toInt(), 0x6C7597DA)),
        Swell(0.92f, 0.13f, 0.06f, 0xBA284881.toInt(), 0xEF101E3B.toInt(), intArrayOf(0x306894D3, 0xAB5EADDA.toInt(), 0xB279BBFF.toInt(), 0x006C9EF4))
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val ridge = Path()
    private val silhouette = Path()
    private val contour = Path()
    private val ribbon = Path()
    private val ribbonOutline = Path()
    private val density = resources.displayMetrics.density
    private val dust = Random(741).let { random ->
        List(122) { Speck(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    }
    private val cloud = Random(167).let { random ->
        List(78) { Speck(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    }
    private val highlight = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFFF1FFFF.toInt(), 0x746FDFFF, 0x006FDFFF),
        floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP
    )
    private val highlightMatrix = Matrix()
    private var sky: Shader? = null
    private var atmosphere: Shader? = null
    private var horizon: Shader? = null
    private var auroraFill: Shader? = null
    private var auroraEdge: Shader? = null
    private var waterBodies = emptyList<Shader>()
    private var shiningEdges = emptyList<Shader>()
    private var progress = 1f
    private var widthPx = 0f
    private var heightPx = 0f
    private var amplitudeScale = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun seek(value: Float) {
        progress = value
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || height == 0) return
        widthPx = width.toFloat()
        heightPx = height.toFloat()
        amplitudeScale = minOf(heightPx, widthPx * 1.85f)
        sky = LinearGradient(
            0f, 0f, widthPx * 0.36f, heightPx,
            intArrayOf(0xFF050912.toInt(), 0xFF0E1831.toInt(), 0xFF101A38.toInt()),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        atmosphere = RadialGradient(
            widthPx * 0.51f, heightPx * 0.49f, maxOf(widthPx * 0.62f, heightPx * 0.42f),
            intArrayOf(0xB229467C.toInt(), 0x47234F82, 0x00234F82),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        horizon = RadialGradient(
            widthPx * 0.72f, heightPx * 0.67f, maxOf(widthPx * 0.52f, heightPx * 0.28f),
            intArrayOf(0x884CC4E1.toInt(), 0x402B6BE0, 0x002B6BE0),
            floatArrayOf(0f, 0.36f, 1f), Shader.TileMode.CLAMP
        )
        auroraFill = LinearGradient(
            0f, heightPx * 0.69f, widthPx, heightPx * 0.12f,
            intArrayOf(0x006A62DA, 0x436963D1, 0x6887F4FB, 0x0045A6E8),
            floatArrayOf(0f, 0.38f, 0.69f, 1f), Shader.TileMode.CLAMP
        )
        auroraEdge = LinearGradient(
            0f, 0f, widthPx, 0f,
            intArrayOf(0x005261B4, 0xB39B88FD.toInt(), 0xE0A1F7F9.toInt(), 0x0065E3FF),
            floatArrayOf(0f, 0.32f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        waterBodies = swells.map { swell ->
            LinearGradient(
                0f, heightPx * (swell.level - swell.height), widthPx * 0.14f, heightPx * (swell.level + 0.28f),
                intArrayOf(swell.crest, swell.body, 0xF009152C.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
        }
        shiningEdges = swells.map { swell ->
            LinearGradient(
                0f, 0f, widthPx, 0f, swell.edge,
                floatArrayOf(0f, 0.37f, 0.75f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (heightPx <= 0f || widthPx <= 0f) return
        val rise = ease(0.02f, 0.47f, progress)
        val light = ease(0.13f, 0.52f, progress)
        val drift = 0.23f * ease(0f, 1f, progress)

        wash(canvas, sky, 255)
        wash(canvas, atmosphere, (60 + light * 130).toInt())
        drawDust(canvas, light, drift)
        wash(canvas, horizon, (light * 142).toInt())
        drawAurora(canvas)


        swells.forEachIndexed { index, swell ->
            drawSwell(canvas, swell, index, rise, drift)
        }
        drawSpray(canvas, rise, light, drift)
    }

    private fun drawAurora(canvas: Canvas) {
        val bloom = ease(0.08f, 0.48f, progress)
        val settle = ease(0.60f, 1f, progress)
        if (bloom == 0f) return
        val travel = 0.17f * (1f - bloom) - 0.035f * settle
        for (band in 0..2) {
            ribbon.rewind()
            for (step in 0..72) {
                val x = step / 72f
                val y = auroraY(x, band, travel)
                if (step == 0) ribbon.moveTo(0f, y) else ribbon.lineTo(x * widthPx, y)
            }
            ribbonOutline.set(ribbon)
            for (step in 72 downTo 0) {
                val x = step / 72f
                val thickness = sine(x * 0.5f) * amplitudeScale * (0.094f - band * 0.016f)
                ribbonOutline.lineTo(x * widthPx, auroraY(x, band, travel) - thickness)
            }
            ribbonOutline.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.shader = auroraFill
            paint.alpha = ((134 - band * 25) * bloom).toInt()
            canvas.drawPath(ribbonOutline, paint)
            paint.style = Paint.Style.STROKE
            paint.shader = auroraEdge
            paint.strokeWidth = density * (6f - band)
            paint.alpha = (29 * bloom).toInt()
            canvas.drawPath(ribbon, paint)
            paint.strokeWidth = density * (1.2f - band * 0.19f)
            paint.alpha = ((155 - band * 39) * bloom * (1f - settle * 0.18f)).toInt()
            canvas.drawPath(ribbon, paint)
        }
    }

    private fun auroraY(x: Float, band: Int, travel: Float): Float {
        val current = x + travel
        val contour = 0.1f * sine(current * 1.15f - 0.11f + band * 0.09f) +
            0.024f * sine(current * 2.1f + band * 0.19f)
        return heightPx * (0.66f - 0.40f * x + travel - band * 0.041f) + contour * amplitudeScale
    }

    private fun wash(canvas: Canvas, shader: Shader?, opacity: Int) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = shader
        paint.alpha = opacity
        canvas.drawRect(0f, 0f, widthPx, heightPx, paint)
    }

    private fun drawDust(canvas: Canvas, light: Float, drift: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFFB5D5FF.toInt()
        for (star in dust) {
            val twinkle = 0.62f + 0.38f * sine(star.phase + drift * 2.2f)
            paint.alpha = ((12 + star.size * 106) * twinkle * (0.44f + light * 0.56f)).toInt()
            val x = (star.x + 0.018f * drift * star.size) * widthPx
            val y = star.y * heightPx * 0.77f
            val radius = (0.23f + star.size * star.size * 1.05f) * density
            canvas.drawCircle(x, y, radius, paint)
            if (star.size > 0.96f) {
                paint.alpha = (paint.alpha * 0.25f).toInt()
                canvas.drawLine(x - radius * 2.8f, y, x + radius * 2.8f, y, paint)
                canvas.drawLine(x, y - radius * 2.8f, x, y + radius * 2.8f, paint)
            }
        }
    }

    private fun drawSwell(canvas: Canvas, swell: Swell, index: Int, rise: Float, drift: Float) {
        ridge.rewind()
        for (step in 0..64) {
            val fraction = -0.04f + step / 64f * 1.08f
            val x = fraction * widthPx
            val y = surface(swell, fraction, rise, drift)
            if (step == 0) ridge.moveTo(x, y) else ridge.lineTo(x, y)
        }
        silhouette.set(ridge)
        silhouette.lineTo(widthPx * 1.04f, heightPx * 1.6f)
        silhouette.lineTo(-widthPx * 0.04f, heightPx * 1.6f)
        silhouette.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = waterBodies[index]
        canvas.drawPath(silhouette, paint)

        paint.style = Paint.Style.STROKE
        paint.shader = shiningEdges[index]
        paint.strokeWidth = density * (6f - index * 0.7f)
        paint.alpha = 34
        canvas.drawPath(ridge, paint)
        paint.strokeWidth = density * (2.5f - index * 0.26f)
        paint.alpha = 87
        canvas.drawPath(ridge, paint)
        paint.strokeWidth = density * if (index == 1) 1.35f else 0.85f
        paint.alpha = 218 - index * 23
        canvas.drawPath(ridge, paint)

        if (index == 1 && rise > 0f) {
            drawCrestLight(canvas, swell, rise, drift)
            paint.shader = shiningEdges[index]
        }
        for (strand in 1..3) {
            contour.rewind()
            for (step in 0..48) {
                val x = 0.08f + step / 48f * 0.94f
                val distance = (0.014f + strand * 0.011f) * (1f + 0.4f * sine(x * 0.9f + strand * 0.12f))
                val y = surface(swell, x, rise, drift) + distance * amplitudeScale
                if (step == 0) contour.moveTo(x * widthPx, y) else contour.lineTo(x * widthPx, y)
            }
            paint.strokeWidth = density * 0.7f
            paint.alpha = 61 - strand * 11
            canvas.drawPath(contour, paint)
        }
    }

    private fun drawCrestLight(canvas: Canvas, swell: Swell, rise: Float, drift: Float) {
        val sweep = ease(0.26f, 0.85f, progress)
        val position = 0.29f + sweep * 0.54f
        val strength = rise * (0.44f + 0.56f * sine(sweep * 0.5f))
        highlightMatrix.setScale(widthPx * 0.32f, heightPx * 0.13f)
        highlightMatrix.postTranslate(position * widthPx, surface(swell, position, rise, drift))
        highlight.setLocalMatrix(highlightMatrix)
        paint.shader = highlight
        paint.strokeWidth = density * 8f
        paint.alpha = (strength * 58f).toInt()
        canvas.drawPath(ridge, paint)
        paint.strokeWidth = density * 2f
        paint.alpha = (strength * 224f).toInt()
        canvas.drawPath(ridge, paint)
    }

    private fun drawSpray(canvas: Canvas, rise: Float, light: Float, drift: Float) {
        val crest = swells[1]
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = 0xFFC4F5FF.toInt()
        for (spark in cloud) {
            val fraction = spark.x + 0.022f * sine(spark.phase + drift)
            val lift = (0.012f + spark.y * spark.y * 0.19f) * amplitudeScale
            val y = surface(crest, fraction, rise, drift) - lift
            val radius = (0.30f + spark.size * spark.size * 1.23f) * density
            val sparkle = 0.68f + 0.32f * sine(spark.phase + drift * 2.1f)
            val concentration = 0.28f + 0.72f * ease(0.38f, 0.74f, fraction)
            paint.alpha = ((34f + 172f * spark.size) * (1f - spark.y * 0.72f) * light * sparkle * concentration).toInt()
            canvas.drawCircle(fraction * widthPx, y, radius, paint)
        }
    }

    private fun surface(swell: Swell, x: Float, rise: Float, drift: Float): Float {
        val broad = sine(x * 1.02f + swell.phase - drift)
        val fold = sine(x * 1.97f + swell.phase * 0.68f + 0.11f - drift * 0.66f)
        val crest = (0.73f * broad + 0.27f * fold) * (0.72f + x * 0.28f)
        return heightPx * (swell.level + 0.73f * (1f - rise)) - crest * swell.height * amplitudeScale
    }

    private fun sine(turns: Float) = sin(turns * (2f * PI.toFloat()))
}
