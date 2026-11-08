// The few building blocks of the raw greyscale UI (docs/UX_SYNTHESIS.md §5.4): flat, ruled,
// text-first. Buttons are outlined rectangles; labels are monospace, outlined or inverted; the
// live marker is the only colour. Material components that default to pills get explicit shapes.

package app.ballistic.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ballistic.assistant.session.LabelStyle

private val ButtonShape = RoundedCornerShape(2.dp)

/** The only coloured element: an 8dp square meaning "Ballistic is acting on this phone now". */
@Composable
fun LiveMarker(modifier: Modifier = Modifier) {
    Box(modifier.size(Space.liveMarker).background(LocalBallisticExtras.current.live)
        .semantics { contentDescription = "Acting now" })
}

/** A monospace status label: outlined for an ordinary state, inverted when it needs the owner. */
@Composable
fun StatusLabel(text: String, style: LabelStyle = LabelStyle.Outlined, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val base = if (style == LabelStyle.Inverted) modifier.background(c.inverseSurface)
        else modifier.border(1.dp, c.outline)
    Text(text.uppercase(), base.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
        color = if (style == LabelStyle.Inverted) c.inverseOnSurface else c.onSurface, maxLines = 1)
}

/** An outlined rectangle. [emphasis] draws the 2dp outline and bold text (Approve, Save, Set up). */
@Composable
fun BButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, emphasis: Boolean = false) {
    val c = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 40.dp), enabled = enabled, shape = ButtonShape,
        border = BorderStroke(if (emphasis) Space.emphasis else Space.hairline, if (enabled) (if (emphasis) c.onSurface else c.outline) else c.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onSurface, disabledContentColor = c.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Medium)
    }
}

/** An underlined text action. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Text(text, modifier.heightIn(min = 32.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.Underline,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Monospace uppercase section heading. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(top = Space.s3, bottom = Space.s1), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = Space.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

/** An aligned label/value row; values in monospace unless [mono] is false. */
@Composable
fun KeyValue(label: String, value: String?, mono: Boolean = true, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "Not measured", Modifier.weight(1f),
            style = if (mono && value != null) LocalBallisticExtras.current.mono else MaterialTheme.typography.bodyMedium)
    }
}

/** ▸/▾ disclosure. Expansion is saved under [key] and never changes on its own. */
@Composable
fun Disclosure(title: String, key: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .clickable(onClickLabel = if (open) "Hide" else "Show", role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Shown" else "Hidden" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Text(if (open) "▾" else "▸", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        if (open) Column(Modifier.padding(start = Space.s3, bottom = Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) { content() }
    }
}

/** `(•)` / `( )` choice row; the selected row is boxed. */
@Composable
fun RadioRow(selected: Boolean, title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .border(if (selected) Space.emphasis else Space.hairline, if (selected) c.onSurface else c.outlineVariant)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = Space.touch).padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text(if (selected) "(•)" else "( )", style = LocalBallisticExtras.current.mono,
            color = if (enabled) c.onSurface else c.onSurfaceVariant)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) c.onSurface else c.onSurfaceVariant)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
        }
    }
}

/**
 * The warning form: a 3dp rule in text colour, an inverted label and a bold sentence. Used where
 * the owner must notice something (untrusted value, requests leaving the phone, errors).
 */
@Composable
fun WarnBlock(label: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(Space.warnRule).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
        Column(Modifier.padding(start = Space.s2, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            StatusLabel(label, LabelStyle.Inverted)
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** A 1dp ruled box for a task or a state that must read as one unit. */
fun Modifier.ruledBox(color: Color): Modifier = this.border(Space.hairline, color).padding(Space.s3)

/**
 * Ballistic's B, redrawn from assets/brand/ballistic-logo-v1.png as a greyscale mark: the letter
 * in text colour, the chevron in secondary grey (the mint is reserved for the live marker).
 */
@Composable
fun BallisticMark(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val fg = MaterialTheme.colorScheme.onSurface
    val chevron = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(size).semantics { contentDescription = "Ballistic" }) {
        // Source coordinates span about 230..1027 in a 1254px image.
        val s = this.size.minDimension / 800f
        fun p(x: Int, y: Int) = Offset((x - 228) * s, (y - 226) * s)
        fun poly(vararg pts: Pair<Int, Int>) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> val o = p(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            close()
        }
        val letter = Path().apply {
            fillType = PathFillType.EvenOdd
            addPath(poly(260 to 385, 460 to 250, 798 to 250, 953 to 455, 848 to 608, 997 to 782, 812 to 1002, 440 to 1000, 260 to 840))
            addPath(poly(377 to 455, 463 to 376, 735 to 376, 803 to 456, 733 to 553, 377 to 553))
            addPath(poly(377 to 679, 733 to 679, 828 to 780, 750 to 877, 466 to 877, 377 to 800))
        }
        drawPath(letter, fg)
        drawPath(poly(623 to 553, 735 to 553, 790 to 615, 735 to 679, 623 to 679, 680 to 615), chevron)
    }
}
