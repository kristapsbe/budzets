package lv.kristapsbe.budzets.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Fills the space of content that is still loading. The spinner only appears after
 * [SPINNER_DELAY_MILLIS]: the data usually arrives within a frame or two, and a spinner flashed
 * for that long only reads as lag.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_DELAY_MILLIS)
        showSpinner = true
    }
    Box(modifier.fillMaxSize(), Alignment.Center) {
        if (showSpinner) CircularProgressIndicator()
    }
}

private const val SPINNER_DELAY_MILLIS = 400L
