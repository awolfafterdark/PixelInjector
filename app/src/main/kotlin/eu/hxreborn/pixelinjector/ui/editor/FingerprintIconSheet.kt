package eu.hxreborn.pixelinjector.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import eu.hxreborn.pixelinjector.ModuleConstants
import eu.hxreborn.pixelinjector.R
import eu.hxreborn.pixelinjector.icons.FingerprintStyles
import eu.hxreborn.pixelinjector.prefs.Prefs
import eu.hxreborn.pixelinjector.ui.component.SheetHeader
import eu.hxreborn.pixelinjector.ui.component.Tile
import eu.hxreborn.pixelinjector.ui.component.TileGap
import eu.hxreborn.pixelinjector.ui.component.TileText
import eu.hxreborn.pixelinjector.ui.theme.AppText
import eu.hxreborn.pixelinjector.ui.util.verticalScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val StyleGap = 8.dp
private val CellSize = 64.dp
private val CellIcon = 36.dp
private val SelectedRing = 3.dp
private val PreviewHeight = 150.dp
private val PreviewCircle = 76.dp
private val PreviewIcon = 40.dp
private val PreviewShape = RoundedCornerShape(28.dp)
private val TileShape = RoundedCornerShape(20.dp)
private val SectionGap = 16.dp
private val ActionIconSize = 22.dp
private val LockScreen = Color.Black
private val LockScreenIcon = Color.White
private const val COLUMNS = 4
private const val ICON_PX = 192
private const val SHEET_HEIGHT_FRACTION = 0.85f
private const val PERCENT_FULL = 100f
private val RotationAngles = listOf(0, 90, 180, 270)

private val InvertMatrix =
    ColorMatrix(
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

fun fingerprintImageFile(context: Context): File = File(context.filesDir, FingerprintStyles.IMAGE_FILE)

@Composable
fun FingerprintIconSheet(
    style: String,
    hideCircle: Boolean,
    size: Int,
    opacity: Int,
    rotation: Int,
    tint: String,
    customScale: String,
    clipCircle: Boolean,
    invert: Boolean,
    onStyleChange: (String) -> Unit,
    onHideCircleChange: (Boolean) -> Unit,
    onSizeChange: (Int) -> Unit,
    onOpacityChange: (Int) -> Unit,
    onRotationChange: (Int) -> Unit,
    onTintChange: (String) -> Unit,
    onCustomScaleChange: (String) -> Unit,
    onClipCircleChange: (Boolean) -> Unit,
    onInvertChange: (Boolean) -> Unit,
    onResetOptions: () -> Unit,
    onImagePicked: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val icons by produceState(emptyMap<String, ImageBitmap?>()) {
        value = withContext(Dispatchers.Default) { loadIcons(context) }
    }
    val pickedImage by produceState<ImageBitmap?>(null, style) {
        value =
            withContext(Dispatchers.IO) {
                fingerprintImageFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(onImagePicked)
        }
    val imageSelected = FingerprintStyles.isCustom(style)
    var currentSize by remember(size) { mutableFloatStateOf(size.toFloat()) }
    var currentOpacity by remember(opacity) { mutableFloatStateOf(opacity.toFloat()) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxHeight(SHEET_HEIGHT_FRACTION).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        SheetHeader(stringResource(R.string.tweak_fingerprint_icon_edit), stringResource(R.string.tweak_fingerprint_icon_desc))
        Preview(
            icon = if (imageSelected) pickedImage else icons[style],
            isStock = style == FingerprintStyles.STOCK,
            isCustom = imageSelected,
            hideCircle = hideCircle,
            sizePercent = currentSize.roundToInt(),
            opacityPercent = currentOpacity.roundToInt(),
            rotation = rotation,
            tintMode = tint,
            scaleMode = customScale,
            clipCircle = clipCircle,
            invert = invert,
        )
        val cells = listOf(FingerprintStyles.STOCK, FingerprintStyles.NONE, FingerprintStyles.CUSTOM) + FingerprintStyles.presets
        Column(
            Modifier
                .padding(top = SectionGap)
                .weight(1f)
                .fillMaxWidth()
                .verticalScrollbar(scroll)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(SectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
                ChipSwitchTile(stringResource(R.string.fingerprint_hide_circle), hideCircle, onHideCircleChange)
                if (imageSelected) {
                    Tile(
                        shape = TileShape,
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) {
                        TileText(stringResource(R.string.fingerprint_change_image))
                        Icon(
                            Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(ActionIconSize).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ChipSwitchTile(stringResource(R.string.fingerprint_custom_clip_circle), clipCircle, onClipCircleChange)
                    ChipSwitchTile(stringResource(R.string.fingerprint_custom_invert), invert, onInvertChange)
                }
            }
            if (imageSelected) {
                ChipChoiceTile(
                    title = stringResource(R.string.fingerprint_custom_scale),
                    options =
                        listOf(
                            Prefs.FINGERPRINT_SCALE_FIT to stringResource(R.string.fingerprint_scale_fit),
                            Prefs.FINGERPRINT_SCALE_CROP to stringResource(R.string.fingerprint_scale_crop),
                            Prefs.FINGERPRINT_SCALE_STRETCH to stringResource(R.string.fingerprint_scale_stretch),
                        ),
                    selected = customScale,
                    onSelect = onCustomScaleChange,
                )
            }
            if (style != FingerprintStyles.NONE) {
                SliderTile(
                    title = stringResource(R.string.fingerprint_icon_size),
                    valueText = stringResource(R.string.fingerprint_percent_value, currentSize.roundToInt()),
                    value = currentSize,
                    range = Prefs.FINGERPRINT_ICON_SIZE_MIN.toFloat()..Prefs.FINGERPRINT_ICON_SIZE_MAX.toFloat(),
                    onValueChange = { currentSize = it },
                    onValueChangeFinished = { onSizeChange(currentSize.roundToInt()) },
                )
                SliderTile(
                    title = stringResource(R.string.fingerprint_icon_opacity),
                    valueText = stringResource(R.string.fingerprint_percent_value, currentOpacity.roundToInt()),
                    value = currentOpacity,
                    range = Prefs.FINGERPRINT_ICON_OPACITY_MIN.toFloat()..Prefs.FINGERPRINT_ICON_OPACITY_MAX.toFloat(),
                    onValueChange = { currentOpacity = it },
                    onValueChangeFinished = { onOpacityChange(currentOpacity.roundToInt()) },
                )
                ChipChoiceTile(
                    title = stringResource(R.string.fingerprint_icon_tint),
                    options =
                        listOf(
                            Prefs.FINGERPRINT_TINT_NONE to stringResource(R.string.fingerprint_tint_none),
                            Prefs.FINGERPRINT_TINT_SYSTEM to stringResource(R.string.fingerprint_tint_system),
                            Prefs.FINGERPRINT_TINT_ACCENT to stringResource(R.string.fingerprint_tint_accent),
                            Prefs.FINGERPRINT_TINT_WHITE to stringResource(R.string.fingerprint_tint_white),
                        ),
                    selected = tint,
                    onSelect = onTintChange,
                )
                ChipChoiceTile(
                    title = stringResource(R.string.fingerprint_icon_rotation),
                    options =
                        RotationAngles.map { angle ->
                            angle.toString() to stringResource(R.string.fingerprint_rotation_value, angle)
                        },
                    selected = rotation.toString(),
                    onSelect = { onRotationChange(it.toIntOrNull() ?: 0) },
                )
                val modified =
                    size != Prefs.FINGERPRINT_ICON_SIZE_DEFAULT ||
                        opacity != Prefs.FINGERPRINT_ICON_OPACITY_DEFAULT ||
                        rotation != 0 ||
                        tint != Prefs.FINGERPRINT_TINT_NONE ||
                        (imageSelected && (customScale != Prefs.FINGERPRINT_SCALE_FIT || clipCircle || invert))
                if (modified) {
                    Tile(
                        shape = TileShape,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onResetOptions()
                        },
                    ) {
                        TileText(stringResource(R.string.fingerprint_reset_options))
                        Icon(
                            Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(ActionIconSize).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(StyleGap),
            ) {
                cells.chunked(COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (key == FingerprintStyles.CUSTOM) {
                                    ImageCell(
                                        checked = imageSelected,
                                        image = pickedImage,
                                    ) {
                                        val savedFile = fingerprintImageFile(context)
                                        if (!imageSelected && pickedImage != null && savedFile.exists()) {
                                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                            onStyleChange(FingerprintStyles.customStyle(savedFile.lastModified()))
                                        } else {
                                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        }
                                    }
                                } else {
                                    PresetCell(key, icons[key], checked = style == key) {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        onStyleChange(key)
                                    }
                                }
                            }
                        }
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SliderTile(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Tile(shape = TileShape) {
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = AppText.tileTitle)
                Text(valueText, style = AppText.tileTitle, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
            )
        }
    }
}

@Composable
private fun PresetCell(
    key: String,
    icon: ImageBitmap?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    val label =
        when (key) {
            FingerprintStyles.STOCK -> stringResource(R.string.fingerprint_style_stock)
            FingerprintStyles.NONE -> stringResource(R.string.fingerprint_style_none)
            else -> key.replaceFirstChar(Char::uppercase)
        }
    StyleCell(label, checked, onClick) {
        when {
            key == FingerprintStyles.NONE -> Icon(Icons.Outlined.HideSource, contentDescription = null, modifier = Modifier.size(CellIcon))
            icon == null -> Unit
            key == FingerprintStyles.STOCK -> Icon(icon, contentDescription = null, modifier = Modifier.size(CellIcon))
            else -> Image(icon, contentDescription = null, modifier = Modifier.size(CellIcon))
        }
    }
}

@Composable
private fun ImageCell(
    checked: Boolean,
    image: ImageBitmap?,
    onClick: () -> Unit,
) {
    StyleCell(stringResource(R.string.fingerprint_style_custom), checked, onClick) {
        if (image != null) {
            Image(
                image,
                contentDescription = stringResource(R.string.fingerprint_pick_image),
                modifier = Modifier.size(CellIcon).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Outlined.AddPhotoAlternate,
                contentDescription = stringResource(R.string.fingerprint_pick_image),
                modifier = Modifier.size(CellIcon),
            )
        }
    }
}

@Composable
private fun Preview(
    icon: ImageBitmap?,
    isStock: Boolean,
    isCustom: Boolean,
    hideCircle: Boolean,
    sizePercent: Int,
    opacityPercent: Int,
    rotation: Int,
    tintMode: String,
    scaleMode: String,
    clipCircle: Boolean,
    invert: Boolean,
) {
    val circleAlpha by animateFloatAsState(
        if (hideCircle) 0f else 1f,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "circle",
    )
    val colors = MaterialTheme.colorScheme
    val filter =
        when (tintMode) {
            Prefs.FINGERPRINT_TINT_SYSTEM, Prefs.FINGERPRINT_TINT_WHITE -> {
                ColorFilter.tint(LockScreenIcon)
            }

            Prefs.FINGERPRINT_TINT_ACCENT -> {
                ColorFilter.tint(colors.primary)
            }

            else -> {
                when {
                    isStock -> ColorFilter.tint(LockScreenIcon)
                    isCustom && invert -> ColorFilter.colorMatrix(InvertMatrix)
                    else -> null
                }
            }
        }
    val contentScale =
        if (isCustom) {
            when (scaleMode) {
                Prefs.FINGERPRINT_SCALE_CROP -> ContentScale.Crop
                Prefs.FINGERPRINT_SCALE_STRETCH -> ContentScale.FillBounds
                else -> ContentScale.Fit
            }
        } else {
            ContentScale.Fit
        }
    val clipShape = if (isCustom && clipCircle) CircleShape else RectangleShape
    Box(
        Modifier
            .fillMaxWidth()
            .height(PreviewHeight)
            .clip(PreviewShape)
            .background(LockScreen),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(PreviewCircle)
                .alpha(circleAlpha)
                .background(colors.outline, CircleShape),
        )
        if (icon != null) {
            Image(
                icon,
                contentDescription = null,
                contentScale = contentScale,
                modifier =
                    Modifier
                        .size(PreviewIcon * (sizePercent / PERCENT_FULL))
                        .clip(clipShape)
                        .graphicsLayer {
                            rotationZ = rotation.toFloat()
                            alpha = opacityPercent / PERCENT_FULL
                        },
                colorFilter = filter,
            )
        }
    }
}

@Composable
private fun StyleCell(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(CellSize)
            .selectable(checked, interaction, indication = null, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(CellSize)
                .then(if (checked) Modifier.border(SelectedRing, colors.primary, CircleShape) else Modifier)
                .padding(SelectedRing)
                .clip(CircleShape)
                .background(LockScreen)
                .indication(interaction, ripple(color = LockScreenIcon)),
            contentAlignment = Alignment.Center,
        ) { CompositionLocalProvider(LocalContentColor provides LockScreenIcon, content = icon) }
        Text(
            label,
            style = AppText.tileSupporting,
            color = if (checked) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun centerCrop(drawable: Drawable): Bitmap {
    val bitmap = createBitmap(ICON_PX, ICON_PX)
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ICON_PX
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ICON_PX
    val scale = maxOf(ICON_PX.toFloat() / width, ICON_PX.toFloat() / height)
    val left = ((ICON_PX - width * scale) / 2).roundToInt()
    val top = ((ICON_PX - height * scale) / 2).roundToInt()
    drawable.setBounds(left, top, left + (width * scale).roundToInt(), top + (height * scale).roundToInt())
    drawable.draw(Canvas(bitmap))
    return bitmap
}

private fun loadIcons(context: Context): Map<String, ImageBitmap?> {
    val stock =
        runCatching {
            val systemUi = context.createPackageContext(ModuleConstants.SYSTEMUI_PACKAGE, 0)
            val id = systemUi.resources.getIdentifier("ic_fingerprint", "drawable", ModuleConstants.SYSTEMUI_PACKAGE)
            centerCrop(systemUi.getDrawable(id)!!).asImageBitmap()
        }.getOrNull()
    return FingerprintStyles.presets.associateWith { key ->
        context.assets
            .open(FingerprintStyles.assetPath(key))
            .use(BitmapFactory::decodeStream)
            ?.asImageBitmap()
    } + (FingerprintStyles.STOCK to stock)
}
