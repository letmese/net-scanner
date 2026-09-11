package com.netscanner.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.ui.theme.LocalGlassPalette
import com.netscanner.nav.Navigator

/**
 * Common chrome for every v5 screen: frosted top pill with back chevron +
 * title + optional actions, then body content inside the aurora backdrop.
 */
@Composable
fun GlassScreen(
    title: String,
    nav: Navigator? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalGlassPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        GlassPill(Modifier.fillMaxWidth()) {
            if (nav != null) {
                Text(
                    "‹",
                    color = p.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .clickable { nav.pop() }
                        .padding(end = 10.dp)
                )
            }
            Text(
                title,
                color = p.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            actions()
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f), content = content)
        Spacer(Modifier.height(10.dp))
    }
}
