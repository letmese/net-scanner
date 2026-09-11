package com.netscanner.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netscanner.ui.theme.LocalGlassPalette

/** Shared glass-styled form controls used across all screens. */

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    val p = LocalGlassPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = p.dim, fontSize = 13.sp) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = p.text,
            unfocusedTextColor = p.text,
            focusedBorderColor = p.accent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
            cursorColor = p.accent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent
        ),
        shape = RoundedCornerShape(14.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = p.text)
    )
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false
) {
    val p = LocalGlassPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) p.accent else p.cardFill,
            contentColor = if (accent) Color(0xFF04222A) else p.text,
            disabledContainerColor = p.cardFill.copy(alpha = 0.4f),
            disabledContentColor = p.faint
        )
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** key → value row used for info cards. */
@Composable
fun KV(key: String, value: String, valueColor: Color? = null) {
    val p = LocalGlassPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, color = p.dim, fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
        Text(
            value, color = valueColor ?: p.text, fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    val p = LocalGlassPalette.current
    Text(
        text, color = p.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}
