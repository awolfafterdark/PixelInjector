package eu.hxreborn.pixelinjector.snow

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import eu.hxreborn.pixelinjector.prefs.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TWO_PI = (PI * 2.0).toFloat()
private const val PHI = 1.618034f
private const val SQRT2 = 1.4142135f
private const val SQRT3 = 1.7320508f
private const val PERCENT_FULL = 100f
private const val MAX_DT_SECONDS = 0.05f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MIN_FLAKES = 16
private const val MAX_FLAKES = 150
private const val BASE_AREA_DP2 = 320_000f
private const val DEPTH_MIN = 0.16f
private const val DEPTH_SPAN = 0.84f
private const val DEPTH_EXPONENT = 1.55f
private const val MIN_RADIUS_DP = 1.0f
private const val MAX_RADIUS_SPAN_DP = 3.1f
private const val BASE_ALPHA_MIN = 0.06f
private const val BASE_ALPHA_SPAN = 0.24f
private const val SHIMMER_AMP = 0.18f
private const val TOP_FADE_DP = 40f
private const val BOTTOM_FADE_DP = 56f
private const val SPAWN_MARGIN_DP = 28f
private const val HALO_RADIUS_MULT = 1.85f
private const val HALO_ALPHA_MULT = 0.28f
private const val CORE_RADIUS_MULT = 0.52f
private const val DENDRITE_ARMS = 6
private const val BRANCH_OFFSET_INNER = 0.52f
private const val BRANCH_OFFSET_OUTER = 0.78f
private const val BRANCH_LEN_INNER = 0.34f
private const val BRANCH_LEN_OUTER = 0.24f
private const val BRANCH_ANGLE_RAD = 0.58f
private const val FORESHORTEN_MIN = 0.58f
private const val FORESHORTEN_SPAN = 0.42f
private const val TILT_GAIN = 0.0045f
private const val MAX_TILT_DEG = 24f
private const val OPAQUE = 255f
private const val KIND_BOKEH = 0
private const val KIND_DENDRITE = 1
private const val KIND_STAR = 2

data class SnowConfig(
    val intensity: Int = Prefs.SNOWFALL_INTENSITY_DEFAULT,
    val speedPercent: Int = Prefs.SNOWFALL_SPEED_DEFAULT,
    val windPercent: Int = Prefs.SNOWFALL_WIND_DEFAULT,
)

private class Snowflake(
    var x: Float,
    var y: Float,
    var depth: Float,
    var radiusDp: Float,
    var baseAlpha: Float,
    var kind: Int,
    var vx: Float,
    var vy: Float,
    var terminalVyDp: Float,
    var flutterFreq: Float,
    var flutterAmpDp: Float,
    var flutterPhase: Float,
    var spinSpeed: Float,
    var spinAngle: Float,
    var tumbleSpeed: Float,
    var tumblePhase: Float,
    var shimmerFreq: Float,
    var shimmerPhase: Float,
    var drag: Float,
)

class SnowField(
    seed: Long = System.nanoTime(),
) {
    private val rng = Random(seed)
    private val flakes = ArrayList<Snowflake>(MAX_FLAKES)
    private val timeSeed1 = rng.nextFloat() * TWO_PI
    private val timeSeed2 = rng.nextFloat() * TWO_PI
    private val timeSeed3 = rng.nextFloat() * TWO_PI
    private var elapsedSeconds = rng.nextFloat() * 120f
    private var lastFrameNanos = 0L
    private var lastWidth = 0
    private var lastHeight = 0

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(242, 248, 255)
        }

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(244, 250, 255)
        }

    private val armCos = FloatArray(DENDRITE_ARMS) { i -> cos((i * PI / 3.0).toFloat()) }
    private val armSin = FloatArray(DENDRITE_ARMS) { i -> sin((i * PI / 3.0).toFloat()) }
    private val branchLeftCos =
        FloatArray(DENDRITE_ARMS) { i -> cos((i * PI / 3.0 - BRANCH_ANGLE_RAD).toFloat()) }
    private val branchLeftSin =
        FloatArray(DENDRITE_ARMS) { i -> sin((i * PI / 3.0 - BRANCH_ANGLE_RAD).toFloat()) }
    private val branchRightCos =
        FloatArray(DENDRITE_ARMS) { i -> cos((i * PI / 3.0 + BRANCH_ANGLE_RAD).toFloat()) }
    private val branchRightSin =
        FloatArray(DENDRITE_ARMS) { i -> sin((i * PI / 3.0 + BRANCH_ANGLE_RAD).toFloat()) }

    fun resetClock() {
        lastFrameNanos = 0L
    }

    fun stepAndDraw(
        canvas: Canvas,
        width: Int,
        height: Int,
        density: Float,
        frameTimeNanos: Long,
        config: SnowConfig,
    ) {
        if (width <= 0 || height <= 0 || density <= 0f) return
        val dt =
            if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND)
                    .coerceIn(0.001f, MAX_DT_SECONDS)
            }
        lastFrameNanos = frameTimeNanos
        elapsedSeconds += dt

        val widthDp = width / density
        val heightDp = height / density
        val areaScale = ((widthDp * heightDp) / BASE_AREA_DP2).coerceIn(0.35f, 2.2f)
        val intensityFraction =
            config.intensity.coerceIn(Prefs.SNOWFALL_INTENSITY_MIN, Prefs.SNOWFALL_INTENSITY_MAX) /
                PERCENT_FULL
        val targetCount =
            (
                (MIN_FLAKES + (MAX_FLAKES - MIN_FLAKES) * intensityFraction.pow(1.15f)) *
                    sqrt(areaScale)
            ).roundToInt().coerceIn(8, MAX_FLAKES)

        val resized = lastWidth != width || lastHeight != height
        lastWidth = width
        lastHeight = height
        syncPopulation(targetCount, width.toFloat(), height.toFloat(), density, resized)

        val speedScale =
            config.speedPercent.coerceIn(Prefs.SNOWFALL_SPEED_MIN, Prefs.SNOWFALL_SPEED_MAX) /
                PERCENT_FULL
        val windScale =
            config.windPercent.coerceIn(Prefs.SNOWFALL_WIND_MIN, Prefs.SNOWFALL_WIND_MAX) /
                PERCENT_FULL
        val t = elapsedSeconds
        val macroWindDp =
            windScale *
                38f *
                (
                    0.48f * sin(0.19f * t + timeSeed1) +
                        0.32f * sin(0.19f * PHI * t + timeSeed2) +
                        0.20f * cos(0.19f * SQRT2 * t + timeSeed3)
                )
        val marginPx = SPAWN_MARGIN_DP * density
        val topFadePx = TOP_FADE_DP * density
        val bottomFadePx = BOTTOM_FADE_DP * density
        val w = width.toFloat()
        val h = height.toFloat()

        for (i in 0 until flakes.size) {
            val flake = flakes[i]
            val nx = (flake.x / w).coerceIn(0f, 1f)
            val ny = (flake.y / h).coerceIn(0f, 1f)
            val eddyX =
                sin(2.6f * ny + 0.41f * t) * cos(1.9f * nx - 0.29f * PHI * t) +
                    0.45f * sin(4.2f * ny - 0.57f * SQRT3 * t)
            val eddyY =
                0.20f * cos(2.2f * nx + 0.37f * t) * sin(3.1f * ny - 0.43f * PHI * t)

            flake.flutterPhase = (flake.flutterPhase + flake.flutterFreq * dt) % TWO_PI
            flake.tumblePhase = (flake.tumblePhase + flake.tumbleSpeed * dt) % TWO_PI
            flake.shimmerPhase = (flake.shimmerPhase + flake.shimmerFreq * dt) % TWO_PI
            flake.spinAngle = (flake.spinAngle + flake.spinSpeed * speedScale * dt * 38f) % 360f

            val depthWind = flake.depth.pow(0.85f)
            val flutterVxDp =
                flake.flutterAmpDp *
                    (0.35f + 0.65f * windScale) *
                    sin(flake.flutterPhase)
            val targetVx =
                (macroWindDp * depthWind + windScale * 18f * eddyX * flake.depth + flutterVxDp) *
                    density
            val targetVy =
                flake.terminalVyDp *
                    speedScale *
                    (1f + eddyY * (0.4f + 0.6f * windScale)) *
                    density

            val relax = min(1f, flake.drag * dt)
            flake.vx += (targetVx - flake.vx) * relax
            flake.vy += (targetVy - flake.vy) * relax

            flake.x += flake.vx * dt
            flake.y += flake.vy * dt

            if (flake.y > h + marginPx) {
                respawnFlake(flake, w, h, density, randomY = false, windPx = macroWindDp * density)
            } else if (flake.x < -marginPx) {
                flake.x = w + marginPx * 0.6f
                flake.y = rng.nextFloat() * h * 0.92f
            } else if (flake.x > w + marginPx) {
                flake.x = -marginPx * 0.6f
                flake.y = rng.nextFloat() * h * 0.92f
            }

            drawFlake(canvas, flake, density, h, topFadePx, bottomFadePx)
        }
    }

    private fun drawFlake(
        canvas: Canvas,
        flake: Snowflake,
        density: Float,
        height: Float,
        topFadePx: Float,
        bottomFadePx: Float,
    ) {
        val topFactor =
            if (flake.y <= 0f) {
                0f
            } else {
                (flake.y / topFadePx).coerceIn(0f, 1f)
            }
        val bottomDist = height - flake.y
        val bottomFactor =
            if (bottomDist <= 0f) {
                0f
            } else {
                (bottomDist / bottomFadePx).coerceIn(0f, 1f)
            }
        val edgeFade = min(topFactor, bottomFactor)
        if (edgeFade <= 0.01f) return

        val shimmer = 1f + SHIMMER_AMP * sin(flake.shimmerPhase)
        val alpha = (flake.baseAlpha * shimmer * edgeFade).coerceIn(0f, 0.42f)
        val alphaInt = (alpha * OPAQUE).roundToInt().coerceIn(0, 255)
        if (alphaInt <= 1) return

        val rPx = flake.radiusDp * density
        val haloAlpha = (alphaInt * HALO_ALPHA_MULT).roundToInt().coerceIn(1, 255)

        when (flake.kind) {
            KIND_BOKEH -> {
                fillPaint.alpha = haloAlpha
                canvas.drawCircle(flake.x, flake.y, rPx * HALO_RADIUS_MULT, fillPaint)
                fillPaint.alpha = (alphaInt * 0.62f).roundToInt().coerceIn(1, 255)
                canvas.drawCircle(flake.x, flake.y, rPx, fillPaint)
                fillPaint.alpha = alphaInt
                canvas.drawCircle(flake.x, flake.y, rPx * CORE_RADIUS_MULT, fillPaint)
            }

            KIND_DENDRITE -> {
                fillPaint.alpha = haloAlpha
                canvas.drawCircle(flake.x, flake.y, rPx * 1.35f, fillPaint)
                val save = canvas.save()
                try {
                    val tiltDeg = (flake.vx * TILT_GAIN).coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG)
                    val foreshorten =
                        FORESHORTEN_MIN + FORESHORTEN_SPAN * cos(flake.tumblePhase)
                    canvas.translate(flake.x, flake.y)
                    canvas.rotate(flake.spinAngle + tiltDeg)
                    canvas.scale(foreshorten, 1f)
                    strokePaint.alpha = alphaInt
                    strokePaint.strokeWidth = (0.68f * density * (0.75f + 0.4f * flake.depth))
                    val armLen = rPx * 1.45f
                    val innerOffset = armLen * BRANCH_OFFSET_INNER
                    val outerOffset = armLen * BRANCH_OFFSET_OUTER
                    val innerBranch = armLen * BRANCH_LEN_INNER
                    val outerBranch = armLen * BRANCH_LEN_OUTER
                    for (arm in 0 until DENDRITE_ARMS) {
                        val c = armCos[arm]
                        val s = armSin[arm]
                        canvas.drawLine(0f, 0f, c * armLen, s * armLen, strokePaint)
                        val ix = c * innerOffset
                        val iy = s * innerOffset
                        canvas.drawLine(
                            ix,
                            iy,
                            ix + branchLeftCos[arm] * innerBranch,
                            iy + branchLeftSin[arm] * innerBranch,
                            strokePaint,
                        )
                        canvas.drawLine(
                            ix,
                            iy,
                            ix + branchRightCos[arm] * innerBranch,
                            iy + branchRightSin[arm] * innerBranch,
                            strokePaint,
                        )
                        val ox = c * outerOffset
                        val oy = s * outerOffset
                        canvas.drawLine(
                            ox,
                            oy,
                            ox + branchLeftCos[arm] * outerBranch,
                            oy + branchLeftSin[arm] * outerBranch,
                            strokePaint,
                        )
                        canvas.drawLine(
                            ox,
                            oy,
                            ox + branchRightCos[arm] * outerBranch,
                            oy + branchRightSin[arm] * outerBranch,
                            strokePaint,
                        )
                    }
                    fillPaint.alpha = alphaInt
                    canvas.drawCircle(0f, 0f, rPx * 0.34f, fillPaint)
                } finally {
                    canvas.restoreToCount(save)
                }
            }

            else -> {
                fillPaint.alpha = haloAlpha
                canvas.drawCircle(flake.x, flake.y, rPx * 1.4f, fillPaint)
                val save = canvas.save()
                try {
                    val foreshorten =
                        FORESHORTEN_MIN + FORESHORTEN_SPAN * sin(flake.tumblePhase)
                    canvas.translate(flake.x, flake.y)
                    canvas.rotate(flake.spinAngle)
                    canvas.scale(foreshorten, 1f)
                    strokePaint.alpha = alphaInt
                    strokePaint.strokeWidth = 0.62f * density * (0.7f + 0.35f * flake.depth)
                    val primaryLen = rPx * 1.25f
                    val secondaryLen = rPx * 0.72f
                    for (arm in 0 until DENDRITE_ARMS) {
                        val len = if (arm % 2 == 0) primaryLen else secondaryLen
                        canvas.drawLine(0f, 0f, armCos[arm] * len, armSin[arm] * len, strokePaint)
                    }
                    fillPaint.alpha = alphaInt
                    canvas.drawCircle(0f, 0f, rPx * 0.38f, fillPaint)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }
    }

    private fun syncPopulation(
        targetCount: Int,
        width: Float,
        height: Float,
        density: Float,
        resized: Boolean,
    ) {
        if (resized && flakes.isNotEmpty()) {
            flakes.clear()
        }
        while (flakes.size < targetCount) {
            val flake =
                Snowflake(
                    x = 0f,
                    y = 0f,
                    depth = 0.5f,
                    radiusDp = 2f,
                    baseAlpha = 0.15f,
                    kind = KIND_BOKEH,
                    vx = 0f,
                    vy = 0f,
                    terminalVyDp = 30f,
                    flutterFreq = 2f,
                    flutterAmpDp = 10f,
                    flutterPhase = 0f,
                    spinSpeed = 0.5f,
                    spinAngle = 0f,
                    tumbleSpeed = 1f,
                    tumblePhase = 0f,
                    shimmerFreq = 1.5f,
                    shimmerPhase = 0f,
                    drag = 3f,
                )
            respawnFlake(flake, width, height, density, randomY = true, windPx = 0f)
            flakes.add(flake)
        }
        while (flakes.size > targetCount) {
            flakes.removeAt(flakes.lastIndex)
        }
    }

    private fun respawnFlake(
        flake: Snowflake,
        width: Float,
        height: Float,
        density: Float,
        randomY: Boolean,
        windPx: Float,
    ) {
        val u = rng.nextFloat()
        val depth = DEPTH_MIN + DEPTH_SPAN * u.pow(DEPTH_EXPONENT)
        val radiusVariance = 0.82f + rng.nextFloat() * 0.36f
        val radiusDp = (MIN_RADIUS_DP + MAX_RADIUS_SPAN_DP * depth) * radiusVariance
        val baseAlpha =
            (BASE_ALPHA_MIN + BASE_ALPHA_SPAN * depth) * (0.85f + rng.nextFloat() * 0.3f)
        val kindRoll = rng.nextFloat()
        val kind =
            when {
                depth < 0.38f -> KIND_BOKEH
                kindRoll < 0.46f -> KIND_BOKEH
                kindRoll < 0.78f -> KIND_DENDRITE
                else -> KIND_STAR
            }
        val marginPx = SPAWN_MARGIN_DP * density
        val spawnFromSide =
            !randomY && kotlin.math.abs(windPx) > 12f * density && rng.nextFloat() < 0.22f
        if (randomY) {
            flake.x = rng.nextFloat() * width
            flake.y = rng.nextFloat() * height
        } else if (spawnFromSide) {
            flake.x = if (windPx > 0f) -marginPx * 0.5f else width + marginPx * 0.5f
            flake.y = rng.nextFloat() * height * 0.65f
        } else {
            flake.x = -marginPx + rng.nextFloat() * (width + marginPx * 2f)
            flake.y = -rng.nextFloat() * marginPx - 4f * density
        }
        val terminalVyDp = (17f + 45f * depth) * (0.84f + rng.nextFloat() * 0.32f)
        flake.depth = depth
        flake.radiusDp = radiusDp
        flake.baseAlpha = baseAlpha
        flake.kind = kind
        flake.terminalVyDp = terminalVyDp
        flake.vx = windPx * depth * 0.5f
        flake.vy = terminalVyDp * density * 0.85f
        flake.flutterFreq = 1.15f + rng.nextFloat() * 1.85f
        flake.flutterAmpDp = (5.5f + 14.5f * depth) * (0.8f + rng.nextFloat() * 0.4f)
        flake.flutterPhase = rng.nextFloat() * TWO_PI
        flake.spinSpeed = (rng.nextFloat() - 0.5f) * 2.4f
        flake.spinAngle = rng.nextFloat() * 360f
        flake.tumbleSpeed = 0.75f + rng.nextFloat() * 1.45f
        flake.tumblePhase = rng.nextFloat() * TWO_PI
        flake.shimmerFreq = 0.9f + rng.nextFloat() * 1.6f
        flake.shimmerPhase = rng.nextFloat() * TWO_PI
        flake.drag = 2.2f + (1f - depth) * 2.4f
    }
}
