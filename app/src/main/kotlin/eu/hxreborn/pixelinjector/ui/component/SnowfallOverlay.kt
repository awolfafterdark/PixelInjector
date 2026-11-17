package eu.hxreborn.pixelinjector.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import eu.hxreborn.pixelinjector.snow.SnowConfig
import eu.hxreborn.pixelinjector.snow.SnowField
import kotlin.math.roundToInt

@Composable
fun SnowfallOverlay(
    config: SnowConfig,
    modifier: Modifier = Modifier,
) {
    val snowField = remember { SnowField() }
    val density = LocalDensity.current.density
    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        snowField.resetClock()
        while (true) {
            withFrameNanos { nanos ->
                frameNanos = nanos
            }
        }
    }

    Canvas(
        modifier = modifier.fillMaxSize().clearAndSetSemantics {},
    ) {
        val currentNanos = frameNanos
        if (currentNanos != 0L) {
            snowField.stepAndDraw(
                canvas = drawContext.canvas.nativeCanvas,
                width = size.width.roundToInt(),
                height = size.height.roundToInt(),
                density = density,
                frameTimeNanos = currentNanos,
                config = config,
            )
        }
    }
}
