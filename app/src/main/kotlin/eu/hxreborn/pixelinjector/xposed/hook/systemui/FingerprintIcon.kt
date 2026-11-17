package eu.hxreborn.pixelinjector.xposed.hook.systemui

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.StateSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import eu.hxreborn.pixelinjector.Target
import eu.hxreborn.pixelinjector.icons.FingerprintStyles
import eu.hxreborn.pixelinjector.prefs.Prefs
import eu.hxreborn.pixelinjector.util.member
import eu.hxreborn.pixelinjector.util.reason
import eu.hxreborn.pixelinjector.util.requiredField
import eu.hxreborn.pixelinjector.util.requiredMethod
import eu.hxreborn.pixelinjector.util.resolving
import eu.hxreborn.pixelinjector.util.signature
import eu.hxreborn.pixelinjector.xposed.Logger
import eu.hxreborn.pixelinjector.xposed.Switch
import eu.hxreborn.pixelinjector.xposed.Tweak
import eu.hxreborn.pixelinjector.xposed.Value
import eu.hxreborn.pixelinjector.xposed.module
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private const val TWEAK = "FingerprintIcon"
private const val ICON_VIEW = "com.android.systemui.keyguard.ui.view.DeviceEntryIconView"
private const val UDFPS_VIEW = "com.android.systemui.biometrics.UdfpsKeyguardView"
private const val SAMSUNG_LOCK_ICON_VIEW = "com.android.keyguard.SecLockIconView"
private const val OPAQUE = 255
private const val PERCENT_FULL = 100f
private const val MAX_CUSTOM_ICON_SIZE_PX = 1024
private const val MAX_CUSTOM_SOURCE_SIZE_PX = 32768

private val invertColorFilter =
    ColorMatrixColorFilter(
        floatArrayOf(
            -1f,
            0f,
            0f,
            0f,
            255f,
            0f,
            -1f,
            0f,
            0f,
            255f,
            0f,
            0f,
            -1f,
            0f,
            255f,
            0f,
            0f,
            0f,
            1f,
            0f,
        ),
    )

@Volatile private var current: FingerprintBindings? = null

private val switch = Switch(Prefs.FINGERPRINT_ICON) { current?.updateAll() }
private val style = Value(Prefs.FINGERPRINT_ICON_STYLE, onLoad = { current?.updateAll() }) { it }
private val hideCircle = Switch(Prefs.FINGERPRINT_HIDE_CIRCLE) { current?.updateAll() }
private val iconSize =
    Value(Prefs.FINGERPRINT_ICON_SIZE, onLoad = { current?.updateAll() }) {
        it.coerceIn(Prefs.FINGERPRINT_ICON_SIZE_MIN, Prefs.FINGERPRINT_ICON_SIZE_MAX)
    }
private val iconOpacity =
    Value(Prefs.FINGERPRINT_ICON_OPACITY, onLoad = { current?.updateAll() }) {
        it.coerceIn(Prefs.FINGERPRINT_ICON_OPACITY_MIN, Prefs.FINGERPRINT_ICON_OPACITY_MAX)
    }
private val iconRotation =
    Value(Prefs.FINGERPRINT_ICON_ROTATION, onLoad = { current?.updateAll() }) { it }
private val iconTint =
    Value(Prefs.FINGERPRINT_ICON_TINT, onLoad = { current?.updateAll() }) { it }
private val customScale =
    Value(Prefs.FINGERPRINT_CUSTOM_SCALE, onLoad = { current?.updateAll() }) { it }
private val customClipCircle =
    Switch(Prefs.FINGERPRINT_CUSTOM_CLIP_CIRCLE) { current?.updateAll() }
private val customInvert =
    Switch(Prefs.FINGERPRINT_CUSTOM_INVERT) { current?.updateAll() }

internal val fingerprintIcon =
    Tweak(
        key = TWEAK,
        targets = setOf(Target.SYSTEMUI),
        prefs =
            listOf(
                switch,
                style,
                hideCircle,
                iconSize,
                iconOpacity,
                iconRotation,
                iconTint,
                customScale,
                customClipCircle,
                customInvert,
            ),
        install = XposedModule::installFingerprintIcon,
        saveState = { current?.carry() },
        onReloadRejected = { current?.resume() },
        restoreState = { saved -> (saved as? Map<*, *>)?.let { current?.restore(it) } },
    )

private data class SwapConfig(
    val isCustom: Boolean = false,
    val hidden: Boolean = false,
    val sizePercent: Int = Prefs.FINGERPRINT_ICON_SIZE_DEFAULT,
    val opacityPercent: Int = Prefs.FINGERPRINT_ICON_OPACITY_DEFAULT,
    val rotation: Int = 0,
    val tintMode: String = Prefs.FINGERPRINT_TINT_NONE,
    val accentColor: Int = Color.WHITE,
    val scaleMode: String = Prefs.FINGERPRINT_SCALE_FIT,
    val clipCircle: Boolean = false,
    val invertColors: Boolean = false,
) {
    val hasStockTransforms: Boolean
        get() =
            sizePercent != Prefs.FINGERPRINT_ICON_SIZE_DEFAULT ||
                opacityPercent != Prefs.FINGERPRINT_ICON_OPACITY_DEFAULT ||
                rotation != 0 ||
                tintMode != Prefs.FINGERPRINT_TINT_NONE
}

private class FingerprintSwap(
    stock: Drawable,
    private val fingerprintStates: List<IntArray>,
    private val paddingView: View? = null,
) : DrawableWrapper(stock) {
    private val clipPath = Path()

    var hostTint: ColorStateList? = null
    var hostColorFilter: ColorFilter? = null

    var config: SwapConfig = SwapConfig()
        set(value) {
            field = value
            invalidateSelf()
        }

    var replacement: Drawable? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private fun isFingerprint(state: IntArray): Boolean =
        fingerprintStates.any {
            StateSet.stateSetMatches(it, state)
        }

    override fun setTintList(tint: ColorStateList?) {
        hostTint = tint
        super.setTintList(tint)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        hostColorFilter = colorFilter
        super.setColorFilter(colorFilter)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (!isFingerprint(state)) {
            super.draw(canvas)
            return
        }
        if (config.hidden) return
        val icon = replacement
        if (icon == null) {
            drawStockTransformed(canvas)
            return
        }
        if (bounds.isEmpty) return
        val padLeft = paddingView?.paddingLeft ?: 0
        val padTop = paddingView?.paddingTop ?: 0
        val padRight = paddingView?.paddingRight ?: 0
        val padBottom = paddingView?.paddingBottom ?: 0
        val left = bounds.left + padLeft
        val top = bounds.top + padTop
        val right = bounds.right - padRight
        val bottom = bounds.bottom - padBottom
        val availWidth = (right - left).takeIf { it > 0 } ?: bounds.width()
        val availHeight = (bottom - top).takeIf { it > 0 } ?: bounds.height()
        val cx = if (right > left) (left + right) / 2f else bounds.exactCenterX()
        val cy = if (bottom > top) (top + bottom) / 2f else bounds.exactCenterY()
        val baseSide = minOf(availWidth, availHeight).toFloat()
        val targetSide = baseSide * (config.sizePercent / PERCENT_FULL)
        if (targetSide <= 0f) return
        val half = targetSide / 2f
        val dw = icon.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: targetSide
        val dh = icon.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: targetSide
        val mode = if (config.isCustom) config.scaleMode else Prefs.FINGERPRINT_SCALE_FIT
        val (drawWidth, drawHeight) =
            when (mode) {
                Prefs.FINGERPRINT_SCALE_CROP -> {
                    val scale = maxOf(targetSide / dw, targetSide / dh)
                    dw * scale to dh * scale
                }

                Prefs.FINGERPRINT_SCALE_STRETCH -> {
                    targetSide to targetSide
                }

                else -> {
                    val scale = minOf(targetSide / dw, targetSide / dh)
                    dw * scale to dh * scale
                }
            }
        val halfW = drawWidth / 2f
        val halfH = drawHeight / 2f
        icon.setBounds(
            (cx - halfW).roundToInt(),
            (cy - halfH).roundToInt(),
            (cx + halfW).roundToInt(),
            (cy + halfH).roundToInt(),
        )
        icon.alpha =
            (OPAQUE * (config.opacityPercent / PERCENT_FULL)).roundToInt().coerceIn(0, OPAQUE)
        icon.colorFilter = resolveColorFilter(forStock = false)
        val saveCount = canvas.save()
        try {
            if (config.isCustom && config.clipCircle) {
                clipPath.reset()
                clipPath.addCircle(cx, cy, half, Path.Direction.CW)
                canvas.clipPath(clipPath)
            } else if (mode == Prefs.FINGERPRINT_SCALE_CROP) {
                canvas.clipRect(
                    (cx - half).roundToInt(),
                    (cy - half).roundToInt(),
                    (cx + half).roundToInt(),
                    (cy + half).roundToInt(),
                )
            }
            if (config.rotation != 0) {
                canvas.rotate(config.rotation.toFloat(), cx, cy)
            }
            icon.draw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawStockTransformed(canvas: Canvas) {
        if (!config.hasStockTransforms || bounds.isEmpty) {
            super.draw(canvas)
            return
        }
        val stockDrawable = drawable ?: return
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val scale = config.sizePercent / PERCENT_FULL
        val previousAlpha = stockDrawable.alpha
        val overrideFilter = resolveColorFilter(forStock = true)
        val saveCount = canvas.save()
        try {
            if (scale != 1f) {
                canvas.scale(scale, scale, cx, cy)
            }
            if (config.rotation != 0) {
                canvas.rotate(config.rotation.toFloat(), cx, cy)
            }
            stockDrawable.alpha =
                (OPAQUE * (config.opacityPercent / PERCENT_FULL)).roundToInt().coerceIn(0, OPAQUE)
            if (overrideFilter != null) {
                stockDrawable.colorFilter = overrideFilter
            }
            super.draw(canvas)
        } finally {
            stockDrawable.alpha = previousAlpha
            if (overrideFilter != null) {
                stockDrawable.colorFilter = hostColorFilter
                hostTint?.let(stockDrawable::setTintList)
            }
            canvas.restoreToCount(saveCount)
        }
    }

    private fun resolveColorFilter(forStock: Boolean): ColorFilter? =
        when (config.tintMode) {
            Prefs.FINGERPRINT_TINT_SYSTEM -> {
                if (forStock) {
                    null
                } else {
                    val tintColor =
                        hostTint?.getColorForState(state, hostTint?.defaultColor ?: Color.WHITE)
                    tintColor?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
                        ?: hostColorFilter
                        ?: PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                }
            }

            Prefs.FINGERPRINT_TINT_ACCENT -> {
                PorterDuffColorFilter(config.accentColor, PorterDuff.Mode.SRC_IN)
            }

            Prefs.FINGERPRINT_TINT_WHITE -> {
                PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }

            else -> {
                if (!forStock && config.isCustom && config.invertColors) {
                    invertColorFilter
                } else {
                    null
                }
            }
        }

    override fun onStateChange(state: IntArray): Boolean {
        val wasFingerprint = isFingerprint(drawable?.state ?: StateSet.NOTHING)
        val changed = super.onStateChange(state)
        if (wasFingerprint != isFingerprint(state)) drawable?.jumpToCurrentState()
        return changed
    }
}

private sealed interface IconTarget {
    val viewClass: Class<*>
    val members: String
    val refresh: Method?

    fun stock(view: View): Drawable? = null

    fun restoreStock(
        view: View,
        stock: Drawable,
    ) = Unit

    fun apply(
        view: View,
        replacement: () -> Drawable?,
        config: SwapConfig,
        active: Boolean,
        circleScale: Float,
    ): Boolean
}

private class DeviceEntryTarget(
    val iconView: Field,
    val bgView: Field,
    val getIconState: Method,
    val fingerprint: Any,
) : IconTarget {
    override val viewClass: Class<*> = iconView.declaringClass
    override val members = "${iconView.signature()},${bgView.signature()}"
    override val refresh: Method? = null

    @Volatile private var fingerprintStates: List<IntArray>? = null

    override fun stock(view: View): Drawable? =
        (iconView.get(view) as? ImageView)?.drawable?.let(::unwrap)

    override fun restoreStock(
        view: View,
        stock: Drawable,
    ) {
        val icon = iconView.get(view) as? ImageView ?: return
        val wrapper = icon.drawable as? DrawableWrapper ?: return
        if (wrapper !is FingerprintSwap && wrapper.drawable === stock) icon.setImageDrawable(stock)
    }

    private fun fingerprintStates(view: View): List<IntArray> =
        fingerprintStates
            ?: listOf(false, true)
                .map { getIconState.invoke(view, fingerprint, it) as IntArray }
                .also { fingerprintStates = it }

    override fun apply(
        view: View,
        replacement: () -> Drawable?,
        config: SwapConfig,
        active: Boolean,
        circleScale: Float,
    ): Boolean {
        val icon = iconView.get(view) as ImageView
        val bg = bgView.get(view) as ImageView
        val swap = replacement()
        val stock = unwrap(icon.drawable)
        val needsWrapper = active && (swap != null || config.hidden || config.hasStockTransforms)
        if (needsWrapper) {
            icon.cropToPadding = false
            (view as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            val wrapper =
                icon.drawable as? FingerprintSwap
                    ?: FingerprintSwap(stock, fingerprintStates(view)).also(icon::setImageDrawable)
            wrapper.hostTint = icon.imageTintList
            wrapper.hostColorFilter = icon.colorFilter
            wrapper.config = config
            wrapper.replacement = swap
            icon.invalidate()
        } else if (icon.drawable !== stock) {
            icon.setImageDrawable(stock)
            icon.invalidate()
        }
        bg.scaleX = circleScale
        bg.scaleY = circleScale
        return true
    }

    private fun unwrap(drawable: Drawable): Drawable =
        (drawable as? FingerprintSwap)?.drawable ?: drawable
}

private class UdfpsTarget(
    val fingerprints: List<Field>,
    val bgProtection: Field,
    override val refresh: Method,
) : IconTarget {
    override val viewClass: Class<*> = bgProtection.declaringClass
    override val members = (fingerprints + bgProtection).joinToString(",") { it.signature() }

    override fun apply(
        view: View,
        replacement: () -> Drawable?,
        config: SwapConfig,
        active: Boolean,
        circleScale: Float,
    ): Boolean {
        val bg = bgProtection.get(view) as? ImageView ?: return false
        if (active) {
            (view as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
        }
        fingerprints.map { it.get(view) as ImageView }.forEach { fingerprint ->
            val swap = replacement()
            val needsSwap = active && (swap != null || config.hidden || config.hasStockTransforms)
            if (needsSwap) {
                fingerprint.cropToPadding = false
                val targetDrawable =
                    swap
                        ?: fingerprint.drawable
                            ?.constantState
                            ?.newDrawable(view.resources)
                            ?.mutate()
                fingerprint.imageAlpha = 0
                fingerprint.foreground =
                    FingerprintSwap(
                        ColorDrawable(Color.TRANSPARENT),
                        listOf(StateSet.WILD_CARD),
                        paddingView = fingerprint,
                    ).apply {
                        hostTint = fingerprint.imageTintList
                        hostColorFilter = fingerprint.colorFilter
                        this.config = config
                        this.replacement = targetDrawable
                    }
            } else {
                fingerprint.imageAlpha = OPAQUE
                fingerprint.foreground = null
            }
            fingerprint.invalidate()
        }
        bg.scaleX = circleScale
        bg.scaleY = circleScale
        return true
    }
}

private class CachedIcon(
    val style: String,
    val drawable: Drawable?,
)

private class FingerprintBindings(
    val target: IconTarget,
) {
    val views: MutableSet<View> =
        Collections.synchronizedSet(
            Collections.newSetFromMap(WeakHashMap()),
        )

    @Volatile private var cachedIcon: CachedIcon? = null

    private val iconLoads = mutableMapOf<String, Thread>()
    private var retired = false

    @Synchronized
    fun register(
        view: View,
        carriedStock: Drawable? = null,
    ) {
        if (retired) return
        views += view
        view.post {
            synchronized(this) {
                if (!retired) {
                    carriedStock?.let { target.restoreStock(view, it) }
                    updateView(view)
                }
            }
        }
    }

    @Synchronized
    fun carry(): HashMap<View, Drawable?> {
        retired = true
        iconLoads.values.forEach(Thread::interrupt)
        val savedViews = synchronized(views) { views.toList() }
        return savedViews.associateWithTo(HashMap()) { target.stock(it) }
    }

    @Synchronized
    fun resume() {
        retired = false
        updateAll()
    }

    @Synchronized
    fun updateAll() {
        if (retired) return
        synchronized(views) { views.toList() }.forEach { view -> view.post { updateView(view) } }
    }

    fun registerAttached() {
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val roots =
                global
                    .requiredField(
                        "mViews",
                    ).get(global.getMethod("getInstance").invoke(null)) as List<*>
            val found = roots.filterIsInstance<View>().flatMap { iconViewsIn(it) }
            found.forEach(::register)
            Logger.info("attached tweak=$TWEAK views=${found.size}")
        } catch (e: Exception) {
            Logger.error("attached failed tweak=$TWEAK reason=${e.message}", e)
        } catch (e: LinkageError) {
            Logger.error("attached failed tweak=$TWEAK reason=${e.message}", e)
        }
    }

    private fun iconViewsIn(view: View): List<View> =
        when {
            target.viewClass.isInstance(view) -> {
                listOf(view)
            }

            view is ViewGroup -> {
                (0 until view.childCount).flatMap {
                    iconViewsIn(
                        view.getChildAt(it),
                    )
                }
            }

            else -> {
                emptyList()
            }
        }

    fun restore(saved: Map<*, *>) {
        for ((view, stock) in saved) {
            if (view is View) register(view, stock as? Drawable)
        }
        Logger.info("carried tweak=$TWEAK views=${saved.size}")
    }

    private fun loadAsync(
        resources: Resources,
        requestedStyle: String,
    ) {
        synchronized(this) {
            if (retired || requestedStyle in iconLoads) return
            val worker =
                thread(start = false, isDaemon = true, name = "PixelInjector-FingerprintIcon") {
                    val loadedIcon = loadIcon(resources, requestedStyle)
                    synchronized(this) {
                        iconLoads.remove(requestedStyle)
                        if (!retired && requestedStyle == style.value) {
                            cachedIcon = loadedIcon
                            updateAll()
                        }
                    }
                }
            iconLoads[requestedStyle] = worker
            worker.start()
        }
    }

    private fun loadIcon(
        resources: Resources,
        style: String,
    ): CachedIcon {
        val drawable =
            try {
                decodeIcon(resources, style)
            } catch (e: Exception) {
                Logger.error("icon failed tweak=$TWEAK style=$style reason=${e.message}", e)
                null
            } catch (e: LinkageError) {
                Logger.error("icon failed tweak=$TWEAK style=$style reason=${e.message}", e)
                null
            }
        if (drawable == null) {
            Logger.error("icon failed tweak=$TWEAK style=$style reason=no-drawable")
        }
        return CachedIcon(style, drawable)
    }

    private fun decodeIcon(
        resources: Resources,
        style: String,
    ): Drawable? =
        if (FingerprintStyles.isCustom(style)) {
            loadCustomIcon(resources)
        } else {
            ZipFile(module.moduleApplicationInfo.sourceDir).use { zip ->
                zip.getEntry("assets/${FingerprintStyles.assetPath(style)}")?.let { entry ->
                    zip.getInputStream(entry).use(BitmapFactory::decodeStream)?.let {
                        BitmapDrawable(resources, it)
                    }
                }
            }
        }

    private fun loadCustomIcon(resources: Resources): Drawable? {
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inSampleSize = 1
            }
        val bitmap =
            module.openRemoteFile(FingerprintStyles.IMAGE_FILE).use { fd ->
                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options)
                if (options.outWidth !in 1..MAX_CUSTOM_SOURCE_SIZE_PX ||
                    options.outHeight !in 1..MAX_CUSTOM_SOURCE_SIZE_PX
                ) {
                    Logger.warn(
                        "icon rejected tweak=$TWEAK width=${options.outWidth} height=${options.outHeight}",
                    )
                    return null
                }
                while (maxOf(options.outWidth, options.outHeight) >
                    MAX_CUSTOM_ICON_SIZE_PX * options.inSampleSize
                ) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options)
            } ?: return null
        if (bitmap.width > MAX_CUSTOM_ICON_SIZE_PX || bitmap.height > MAX_CUSTOM_ICON_SIZE_PX) {
            bitmap.recycle()
            Logger.warn(
                "icon rejected tweak=$TWEAK reason=decoded-size size=$MAX_CUSTOM_ICON_SIZE_PX",
            )
            return null
        }
        return BitmapDrawable(resources, bitmap)
    }

    private fun buildConfig(view: View): SwapConfig {
        val currentStyle = style.value
        val accent =
            try {
                view.context.getColor(android.R.color.system_accent1_200)
            } catch (_: Exception) {
                Color.WHITE
            }
        return SwapConfig(
            isCustom = FingerprintStyles.isCustom(currentStyle),
            hidden = switch.enabled && currentStyle == FingerprintStyles.NONE,
            sizePercent = iconSize.value,
            opacityPercent = iconOpacity.value,
            rotation = iconRotation.value,
            tintMode = iconTint.value,
            accentColor = accent,
            scaleMode = customScale.value,
            clipCircle = customClipCircle.enabled,
            invertColors = customInvert.enabled,
        )
    }

    @Synchronized
    private fun updateView(view: View) {
        if (retired) return
        try {
            val currentStyle = style.value
            val swapping =
                switch.enabled && currentStyle != FingerprintStyles.STOCK &&
                    currentStyle != FingerprintStyles.NONE
            val cached = cachedIcon?.takeIf { it.style == currentStyle }
            if (swapping && cached == null) return loadAsync(view.resources, currentStyle)
            val config = buildConfig(view)
            val applied =
                target.apply(
                    view,
                    replacement = {
                        if (swapping) {
                            cached
                                ?.drawable
                                ?.constantState
                                ?.newDrawable(view.resources)
                                ?.mutate()
                        } else {
                            null
                        }
                    },
                    config = config,
                    active = switch.enabled,
                    circleScale = if (switch.enabled && hideCircle.enabled) 0f else 1f,
                )
            if (!applied) return
            val styleLabel = currentStyle.ifEmpty { "stock" }
            Logger.info(
                "applied tweak=$TWEAK style=$styleLabel size=${config.sizePercent} " +
                    "hideCircle=${hideCircle.enabled} on=${switch.enabled}",
            )
        } catch (e: Exception) {
            Logger.error("apply failed tweak=$TWEAK reason=${e.message}", e)
        } catch (e: LinkageError) {
            Logger.error("apply failed tweak=$TWEAK reason=${e.message}", e)
        }
    }

    companion object {
        fun resolve(cl: ClassLoader): FingerprintBindings =
            FingerprintBindings(
                try {
                    deviceEntry(cl)
                } catch (_: ClassNotFoundException) {
                    udfps(cl)
                },
            )

        private fun deviceEntry(cl: ClassLoader): DeviceEntryTarget {
            val view = cl.loadClass(ICON_VIEW)
            val iconType = cl.loadClass("$ICON_VIEW\$IconType")
            val getIconState =
                view.requiredMethod(
                    "getIconState",
                    iconType,
                    Boolean::class.javaPrimitiveType,
                )
            return DeviceEntryTarget(
                iconView = view.requiredField("iconView"),
                bgView = view.requiredField("bgView"),
                getIconState = getIconState,
                fingerprint =
                    iconType.enumConstants.first {
                        (it as Enum<*>).name == "FINGERPRINT"
                    },
            )
        }

        // Android 14 SystemUI AP2A.240905.003
        private fun udfps(cl: ClassLoader): UdfpsTarget {
            val view =
                try {
                    cl.loadClass("${UDFPS_VIEW}Legacy")
                } catch (_: ClassNotFoundException) {
                    cl.loadClass(UDFPS_VIEW)
                }
            return UdfpsTarget(
                fingerprints =
                    listOf(
                        view.requiredField("mLockScreenFp"),
                        view.requiredField("mAodFp"),
                    ),
                bgProtection = view.requiredField("mBgProtection"),
                refresh = view.requiredMethod("updateColor"),
            )
        }
    }
}

private fun XposedModule.installFingerprintIcon(cl: ClassLoader): Boolean {
    val samsungLockIcon =
        resolving {
            try {
                cl.loadClass(SAMSUNG_LOCK_ICON_VIEW)
            } catch (_: ClassNotFoundException) {
                null
            }
        }.getOrElse {
            Logger.error(
                "target not found tweak=$TWEAK member=${it.member()} build=${Build.ID} reason=${it.reason()}",
            )
            return false
        }
    if (samsungLockIcon != null) {
        Logger.error(
            "target not found tweak=$TWEAK member=${samsungLockIcon.simpleName} " +
                "build=${Build.ID} " +
                "reason=Samsung-draws-fingerprint-icon-outside-SystemUI",
        )
        return false
    }
    val bindings =
        resolving { FingerprintBindings.resolve(cl) }.getOrElse {
            Logger.error(
                "target not found tweak=$TWEAK member=${it.member()} build=${Build.ID} reason=${it.reason()}",
            )
            return false
        }
    Logger.info("resolved tweak=$TWEAK members=${bindings.target.members}")
    current = bindings
    bindings.target.refresh?.let { refresh ->
        hook(refresh).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? View)?.let(bindings::register)
            result
        }
    }
    for (constructor in bindings.target.viewClass.declaredConstructors) {
        hook(constructor).intercept { chain ->
            val result = chain.proceed()
            (chain.thisObject as? View)?.let(bindings::register)
            result
        }
    }
    Handler(Looper.getMainLooper()).post { bindings.registerAttached() }
    return true
}
