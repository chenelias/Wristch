package dev.eliaschen.wristch.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eliaschen.wristch.ui.shape.WristchShapes

@Composable
fun AccessibilityBlockerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .systemBarsPadding()
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            PermissionBadge()
            Spacer(Modifier.height(20.dp))
            Text(
                "需要無障礙權限",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "請允許 Wristch 使用你裝置上的無障礙服務",
                textAlign = TextAlign.Center,
                color = Color.Gray, fontSize = 13.sp
            )
        }
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Text(
                "開啟權限"
            )
        }
    }
}

/**
 * The one piece of art on the screen: a padlock sitting on the app's alert shape.
 *
 * [WristchShapes.Alert] is what every other "this went wrong" badge in the app is drawn
 * on, so the blocker reads as the same kind of thing rather than as a stray illustration -
 * and the shape's flat sides leave the most room of any shape here for the glyph on top.
 */
@Composable
private fun PermissionBadge() {
    Surface(
        // Square, and no padding inside the size: the shape fits itself to the box it is
        // given without stretching, so an uneven box would only letterbox the badge.
        modifier = Modifier.size(96.dp),
        shape = WristchShapes.Alert,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Null description: the heading under it already says what this is, and a
            // screen reader announcing "lock" first only delays that.
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
