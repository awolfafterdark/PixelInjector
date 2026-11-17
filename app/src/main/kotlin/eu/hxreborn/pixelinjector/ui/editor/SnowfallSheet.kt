package eu.hxreborn.pixelinjector.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.hxreborn.pixelinjector.R
import eu.hxreborn.pixelinjector.prefs.Prefs
import eu.hxreborn.pixelinjector.snow.SnowConfig
import eu.hxreborn.pixelinjector.ui.component.SheetHeader
import eu.hxreborn.pixelinjector.ui.component.SnowfallOverlay
import eu.hxreborn.pixelinjector.ui.component.Tile
import eu.hxreborn.pixelinjector.ui.component.TileGap
import eu.hxreborn.pixelinjector.ui.component.TileText
import eu.hxreborn.pixelinjector.ui.theme.AppText
import eu.hxreborn.pixelinjector.ui.util.verticalScrollbar
import kotlin.math.roundToInt

private val PreviewHeight = 156.dp
private val PreviewShape = RoundedCornerShape(28.dp)
private val TileShape = RoundedCornerShape(20.dp)
private val SectionGap = 16.dp
private val ActionIconSize = 22.dp
private val PreviewIconSize = 28.dp
private val SkyTop = Color(0xFF0B1324)
private val SkyBottom = Color(0xFF16243E)
private val FrostTint = Color(0xB3DCEBFF)
private const val SHEET_HEIGHT_FRACTION = 0.85f

@Composable
fun SnowfallSheet(
    intensity: Int,
    speed: Int,
    wind: Int,
    appOverlay: Boolean,
    shadeOnly: Boolean,
    onIntensityChange: (Int) -> Unit,
    onSpeedChange: (Int) -> Unit,
    onWindChange: (Int) -> Unit,
    onAppOverlayChange: (Boolean) -> Unit,
    onShadeOnlyChange: (Boolean) -> Unit,
    onResetOptions: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var currentIntensity by remember(intensity) { mutableFloatStateOf(intensity.toFloat()) }
    var currentSpeed by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
    var currentWind by remember(wind) { mutableFloatStateOf(wind.toFloat()) }
    val scroll = rememberScrollState()

    val liveConfig =
        SnowConfig(
            intensity = currentIntensity.roundToInt(),
            speedPercent = currentSpeed.roundToInt(),
            windPercent = currentWind.roundToInt(),
        )

    Column(Modifier.fillMaxHeight(SHEET_HEIGHT_FRACTION).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        SheetHeader(stringResource(R.string.tweak_snowfall_edit), stringResource(R.string.tweak_snowfall_desc))
        SnowPreview(config = liveConfig)
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
                ChipSwitchTile(stringResource(R.string.snowfall_app_overlay), appOverlay, onAppOverlayChange)
                ChipSwitchTile(stringResource(R.string.snowfall_shade_only), shadeOnly, onShadeOnlyChange)
            }
            SnowSliderTile(
                title = stringResource(R.string.snowfall_intensity),
                valueText = stringResource(R.string.fingerprint_percent_value, currentIntensity.roundToInt()),
                value = currentIntensity,
                range = Prefs.SNOWFALL_INTENSITY_MIN.toFloat()..Prefs.SNOWFALL_INTENSITY_MAX.toFloat(),
                onValueChange = { currentIntensity = it },
                onValueChangeFinished = { onIntensityChange(currentIntensity.roundToInt()) },
            )
            SnowSliderTile(
                title = stringResource(R.string.snowfall_speed),
                valueText = stringResource(R.string.fingerprint_percent_value, currentSpeed.roundToInt()),
                value = currentSpeed,
                range = Prefs.SNOWFALL_SPEED_MIN.toFloat()..Prefs.SNOWFALL_SPEED_MAX.toFloat(),
                onValueChange = { currentSpeed = it },
                onValueChangeFinished = { onSpeedChange(currentSpeed.roundToInt()) },
            )
            SnowSliderTile(
                title = stringResource(R.string.snowfall_wind),
                valueText = stringResource(R.string.fingerprint_percent_value, currentWind.roundToInt()),
                value = currentWind,
                range = Prefs.SNOWFALL_WIND_MIN.toFloat()..Prefs.SNOWFALL_WIND_MAX.toFloat(),
                onValueChange = { currentWind = it },
                onValueChangeFinished = { onWindChange(currentWind.roundToInt()) },
            )
            val modified =
                intensity != Prefs.SNOWFALL_INTENSITY_DEFAULT ||
                    speed != Prefs.SNOWFALL_SPEED_DEFAULT ||
                    wind != Prefs.SNOWFALL_WIND_DEFAULT ||
                    !appOverlay ||
                    shadeOnly
            if (modified) {
                Tile(
                    shape = TileShape,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onResetOptions()
                    },
                ) {
                    TileText(stringResource(R.string.snowfall_reset_options))
                    Icon(
                        Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(ActionIconSize).padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SnowPreview(config: SnowConfig) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(PreviewHeight)
            .clip(PreviewShape)
            .background(Brush.verticalGradient(listOf(SkyTop, SkyBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.AcUnit,
                contentDescription = null,
                modifier = Modifier.size(PreviewIconSize),
                tint = FrostTint,
            )
            Spacer(Modifier.height(6.dp))
            Text("12:30", style = AppText.sheetTitle, color = Color.White)
        }
        SnowfallOverlay(
            config = config,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SnowSliderTile(
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
