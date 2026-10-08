// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** The camera preview with ZXing decoding QR codes only (open source; no Google ML Kit). */
@Composable
fun QrScannerView(paused: Boolean, modifier: Modifier = Modifier, onCode: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onCode)
    val holder = androidx.compose.runtime.remember { arrayOfNulls<BarcodeView>(1) }
    val lastCode = androidx.compose.runtime.remember { arrayOfNulls<String>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            BarcodeView(context).apply {
                decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                decodeContinuous { result ->
                    val text = result.text ?: return@decodeContinuous
                    if (text != lastCode[0]) {
                        lastCode[0] = text
                        callback(text)
                    }
                }
                holder[0] = this
                resume()
            }
        },
        update = { view ->
            if (paused) {
                view.pause()
            } else {
                lastCode[0] = null
                view.resume()
            }
        },
    )

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!paused) holder[0]?.resume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            holder[0]?.pause()
        }
    }
}
