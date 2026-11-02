package com.rysal.happytv.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import com.rysal.happytv.R

/**
 * The app icon ("Sunset screen") as it appears inside the app — the login
 * card header and the dashboard header wordmark.
 *
 * `R.mipmap.ic_launcher` resolves to the adaptive-icon XML in
 * `mipmap-anydpi-v26/` on API 26+, which Compose 1.6's `painterResource`
 * cannot decode (it throws "Only VectorDrawables and rasterized asset types
 * are supported" — this killed the first frame of both screens behind the
 * F-01 launch crash). Render the launcher's own layers instead, straight
 * from `mipmap-anydpi-v26/ic_launcher.xml`: the vector foreground over the
 * icon background color, clipped to the caller's shape.
 *
 * @param size icon edge length.
 * @param cornerRadius corner radius, as a fraction of [size] by convention
 * at the call sites (the launcher applies its own mask).
 */
@Composable
fun HappyTvAppIcon(
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
