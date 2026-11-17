package eu.hxreborn.pixelinjector.xposed.hook.systemui

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import eu.hxreborn.pixelinjector.Target
import eu.hxreborn.pixelinjector.prefs.Prefs
import eu.hxreborn.pixelinjector.snow.SnowConfig
import eu.hxreborn.pixelinjector.snow.SnowField
import eu.hxreborn.pixelinjector.util.member
import eu.hxreborn.pixelinjector.util.orNullIfMissing
import eu.hxreborn.pixelinjector.util.reason
import eu.hxreborn.pixelinjector.util.requiredField
import eu.hxreborn.pixelinjector.util.requiredMethod
import eu.hxreborn.pixelinjector.util.resolving
import eu.hxreborn.pixelinjector.util.signature
import eu.hxreborn.pixelinjector.xposed.Logger
import eu.hxreborn.pixelinjector.xposed.Switch
import eu.hxreborn.pixelinjector.xposed.Tweak
import eu.hxreborn.pixelinjector.xposed.Value
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

private const val TWEAK = "Snowfall"
private const val OVERLAY_TAG = "PixelInjector-Snowfall"
private const val SHADE_WINDOW_VIEW =
    "com.android.systemui.shade.NotificationShadeWindowView"
private const val LEGACY_SHADE_WINDOW_VIEW =
    "com.android.systemui.statusbar.phone.NotificationShadeWindowView"

private val mainHandler = Handler(Looper.getMainLooper())

@Volatile private var current: SnowfallBindings? = null

private val switch = Switch(Prefs.SNOWFALL) { current?.updateAll() }
private val intensity =
    Value(Prefs.SNOWFALL_INTENSITY, onLoad = { current?.updateAll() }) {
        it.coerceIn(Prefs.SNOWFALL_INTENSITY_MIN, Prefs.SNOWFALL_INTENSITY_MAX)
    }
private val speed =
    Value(Prefs.SNOWFALL_SPEED, onLoad = { current?.updateAll() }) {
        it.coerceIn(Prefs.SNOWFALL_SPEED_MIN, Prefs.SNOWFALL_SPEED_MAX)
    }
private val wind =
    Value(Prefs.SNOWFALL_WIND, onLoad = { current?.updateAll() }) {
        it.coerceIn(Prefs.SNOWFALL_WIND_MIN, Prefs.SNOWFALL_WIND_MAX)
    }
private val shadeOnly = Switch(Prefs.SNOWFALL_SHADE_ONLY) { current?.updateAll() }

internal val snowfall =
    Tweak(
        key = TWEAK,
        targets = setOf(Target.SYSTEMUI),
        prefs = listOf(switch, intensity, speed, wind, shadeOnly),
        install = XposedModule::installSnowfall,
        saveState = { current?.carry() },
        onReloadRejected = { current?.resume() },
        restoreState = { saved -> (saved as? List<*>)?.let { current?.restore(it) } },
    )

private class SnowOverlayView(
    context: Context,
) : View(context),
    Choreographer.FrameCallback {
    private val snowField = SnowField()
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var running = false

    var config: SnowConfig = SnowConfig()
        set(value) {
            field = value
            invalidate()
        }

    init {
        tag = OVERLAY_TAG
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = Float.MAX_VALUE
    }

    fun updateRunning() {
        val shouldRun =
            visibility == VISIBLE &&
                isAttachedToWindow &&
                windowVisibility == VISIBLE &&
                (powerManager?.isInteractive != false)
        if (shouldRun == running) return
        running = shouldRun
        if (shouldRun) {
            snowField.resetClock()
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (powerManager?.isInteractive == false || windowVisibility != VISIBLE || !isShown) {
            running = false
            return
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        snowField.stepAndDraw(
            canvas = canvas,
            width = width,
            height = height,
            density = resources.displayMetrics.density,
            frameTimeNanos = System.nanoTime(),
            config = config,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(
        changedView: View,
        visibility: Int,
    ) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }
}

private class SnowfallBindings(
    val hostClass: Class<*>,
    val onAttached: Method,
) {
    private val hosts: MutableSet<ViewGroup> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    @Volatile private var retired = false

    fun register(host: ViewGroup) {
        if (retired) return
        hosts += host
        mainHandler.post {
            if (!retired) updateHost(host)
        }
    }

    fun updateAll() {
        if (retired) return
        mainHandler.post {
            if (retired) return@post
            val snapshot = synchronized(hosts) { hosts.toList() }
            snapshot.forEach(::updateHost)
            Logger.info(
                "applied tweak=$TWEAK on=${switch.enabled} intensity=${intensity.value} " +
                    "speed=${speed.value} wind=${wind.value} hosts=${snapshot.size}",
            )
        }
    }

    private fun updateHost(host: ViewGroup) {
        try {
            val existing = host.findViewWithTag<View>(OVERLAY_TAG)
            if (!switch.enabled) {
                if (existing != null) host.removeView(existing)
                return
            }
            if (existing != null && existing !is SnowOverlayView) {
                host.removeView(existing)
            }
            val overlay =
                (host.findViewWithTag<View>(OVERLAY_TAG) as? SnowOverlayView)
                    ?: SnowOverlayView(host.context).also { view ->
                        host.addView(
                            view,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
            overlay.config =
                SnowConfig(
                    intensity = intensity.value,
                    speedPercent = speed.value,
                    windPercent = wind.value,
                )
            overlay.visibility =
                if (!shadeOnly.enabled || hostClass.isInstance(host)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            overlay.bringToFront()
            overlay.updateRunning()
        } catch (e: Exception) {
            Logger.error("apply failed tweak=$TWEAK reason=${e.message}", e)
        } catch (e: LinkageError) {
            Logger.error("apply failed tweak=$TWEAK reason=${e.message}", e)
        }
    }

    fun carry(): ArrayList<ViewGroup> {
        retired = true
        val snapshot = synchronized(hosts) { ArrayList(hosts) }
        mainHandler.post {
            snapshot.forEach { host ->
                host.findViewWithTag<View>(OVERLAY_TAG)?.let(host::removeView)
            }
        }
        return snapshot
    }

    fun resume() {
        retired = false
        updateAll()
    }

    fun restore(saved: List<*>) {
        saved.filterIsInstance<ViewGroup>().forEach(::register)
        Logger.info("carried tweak=$TWEAK hosts=${saved.size}")
    }

    fun registerAttached() {
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val roots =
                global
                    .requiredField("mViews")
                    .get(global.getMethod("getInstance").invoke(null)) as List<*>
            val found = roots.filterIsInstance<View>().flatMap(::hostViewsIn)
            found.forEach(::register)
            Logger.info("attached tweak=$TWEAK hosts=${found.size}")
        } catch (e: Exception) {
            Logger.error("attached failed tweak=$TWEAK reason=${e.message}", e)
        } catch (e: LinkageError) {
            Logger.error("attached failed tweak=$TWEAK reason=${e.message}", e)
        }
    }

    private fun hostViewsIn(view: View): List<ViewGroup> =
        when {
            hostClass.isInstance(view) && view is ViewGroup -> {
                listOf(view)
            }

            view is ViewGroup -> {
                (0 until view.childCount).flatMap { hostViewsIn(view.getChildAt(it)) }
            }

            else -> {
                emptyList()
            }
        }

    companion object {
        fun resolve(cl: ClassLoader): SnowfallBindings {
            val shadeClass =
                orNullIfMissing { cl.loadClass(SHADE_WINDOW_VIEW) }
                    ?: cl.loadClass(LEGACY_SHADE_WINDOW_VIEW)
            val method =
                orNullIfMissing { shadeClass.getDeclaredMethod("onAttachedToWindow") }
                    ?: ViewGroup::class.java.requiredMethod("onAttachedToWindow")
            return SnowfallBindings(shadeClass, method)
        }
    }
}

private fun XposedModule.installSnowfall(cl: ClassLoader): Boolean {
    val bindings =
        resolving { SnowfallBindings.resolve(cl) }.getOrElse {
            Logger.error(
                "target not found tweak=$TWEAK member=${it.member()} build=${Build.ID} reason=${it.reason()}",
            )
            return false
        }
    Logger.info("resolved tweak=$TWEAK members=${bindings.onAttached.signature()}")
    current = bindings
    hook(bindings.onAttached).intercept { chain ->
        val result = chain.proceed()
        val view = chain.thisObject
        if (bindings.hostClass.isInstance(view) && view is ViewGroup) {
            bindings.register(view)
        }
        result
    }
    for (constructor in bindings.hostClass.declaredConstructors) {
        hook(constructor).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let(bindings::register)
            result
        }
    }
    mainHandler.post { bindings.registerAttached() }
    return true
}
