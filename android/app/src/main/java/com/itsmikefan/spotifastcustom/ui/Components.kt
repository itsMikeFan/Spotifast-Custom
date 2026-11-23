package com.itsmikefan.spotifastcustom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** The desktop's corner radii: 8 for cards and sheets, 4 for covers and chips. */
val Radius = RoundedCornerShape(8.dp)
val RadiusSmall = RoundedCornerShape(4.dp)

@Composable
fun Cover(url: String?, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RadiusSmall).background(p.surface),
    )
}

/** An icon button tinted secondary, or accent when [on]. */
@Composable
fun IconBtn(
    icon: ImageVector, label: String, size: Dp = 22.dp, on: Boolean = false, tint: Color? = null,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val p = LocalPalette.current
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, Modifier.size(size), tint = tint ?: if (!enabled) p.dim else if (on) p.accent else p.secondary)
    }
}

/** The big round accent play button. */
@Composable
fun PlayCircle(playing: Boolean, size: Dp = 56.dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(size).clip(CircleShape).background(p.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play",
            Modifier.size(size * 0.42f).padding(start = if (playing) 0.dp else 2.dp), tint = p.onAccent,
        )
    }
}

/** The desktop's pill button: accent when [primary], else a soft surface. */
@Composable
fun PillButton(label: String, primary: Boolean = true, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(
        onClick = onClick, enabled = enabled, shape = CircleShape, modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) p.accent else p.surface, contentColor = if (primary) p.onAccent else p.text,
            disabledContainerColor = p.surface, disabledContentColor = p.dim,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

/** A small rounded chip, accent-filled when [selected]. */
@Composable
fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        color = if (selected) p.onAccent else p.text,
        modifier = Modifier.clip(CircleShape).background(if (selected) p.accent else p.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun SearchField(value: String, placeholder: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    OutlinedTextField(
        value, onChange, placeholder = { Text(placeholder, color = p.dim) }, singleLine = true,
        leadingIcon = { Icon(Icons.Search, null, Modifier.size(18.dp), tint = p.secondary) },
        trailingIcon = { if (value.isNotEmpty()) IconBtn(Icons.X, "Clear", 18.dp) { onChange("") } },
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
            focusedBorderColor = p.accent, unfocusedBorderColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** A rounded card holding a settings group. */
@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.accent, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(Radius).background(p.panel)) { content() }
    }
}

/** One settings row: title, optional detail, and a control on the right. */
@Composable
fun SettingRow(
    title: String, detail: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) p.text else p.dim)
            detail?.let { Text(it, fontSize = 13.sp, color = if (enabled) p.secondary else p.dim, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    SettingRow(title, detail, enabled, onClick = { onChange(!checked) }) {
        Switch(
            checked, onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.accent, checkedThumbColor = p.onAccent,
                uncheckedTrackColor = p.surface, uncheckedThumbColor = p.secondary, uncheckedBorderColor = p.outline,
            ),
        )
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = LocalPalette.current.outline, thickness = 0.5.dp)
