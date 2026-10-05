package it.registratoreai.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// ---------------------------------------------------------------------------- Colori

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF7C3AED), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE), onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFFD97706), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF451A03),
    error = Color(0xFFE11D48), onError = Color.White,
    errorContainer = Color(0xFFFFE4E6), onErrorContainer = Color(0xFF4C0519),
    background = Color(0xFFF5F6FA), onBackground = Color(0xFF14151A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14151A),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF5B6070),
    surfaceContainer = Color(0xFFF0F1F7), surfaceContainerHigh = Color(0xFFE9EBF3),
    surfaceContainerLow = Color(0xFFFAFAFD), surfaceContainerLowest = Color.White,
    outline = Color(0xFFC9CDD9), outlineVariant = Color(0xFFE3E5EE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5B4FC), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFFC4B5FD), onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF4C1D95), onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFFFBBF24), onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F), onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFFB7185), onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF881337), onErrorContainer = Color(0xFFFFE4E6),
    background = Color(0xFF0E1016), onBackground = Color(0xFFE7E8EE),
    surface = Color(0xFF171A22), onSurface = Color(0xFFE7E8EE),
    surfaceVariant = Color(0xFF232733), onSurfaceVariant = Color(0xFFA3A8B8),
    surfaceContainer = Color(0xFF1C1F29), surfaceContainerHigh = Color(0xFF252935),
    surfaceContainerLow = Color(0xFF14161D), surfaceContainerLowest = Color(0xFF0B0C11),
    outline = Color(0xFF3B4050), outlineVariant = Color(0xFF2A2E3A),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 26.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography, shapes = AppShapes, content = content,
    )
}

val ColorScheme.isDark: Boolean get() = background.red < 0.5f

/** Sfumatura del marchio (indaco → viola), usata per i pulsanti principali. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
val RecordGradient = Brush.linearGradient(listOf(Color(0xFFE11D48), Color(0xFFDB2777)))

/** Colore stabile per ogni corso (stesso corso → stesso colore). */
@Composable
fun courseColor(course: String): Color {
    val palette = listOf(
        0xFF4F46E5, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444,
        0xFFEC4899, 0xFF8B5CF6, 0xFF14B8A6, 0xFFF97316, 0xFF6366F1,
    )
    val c = Color(palette[abs(course.trim().lowercase().hashCode()) % palette.size])
    return if (MaterialTheme.colorScheme.isDark) c.copy(alpha = 0.95f) else c
}

// ---------------------------------------------------------------------------- Componenti

/** Superficie bianca con bordo sottile e angoli morbidi. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = shape, color = color, border = BorderStroke(1.dp, border)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Etichetta colorata del corso. */
@Composable
fun CourseTag(course: String, modifier: Modifier = Modifier) {
    if (course.isBlank()) return
    val c = courseColor(course)
    Row(
        modifier.clip(CircleShape).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Text(
            course, Modifier.padding(start = 6.dp), color = c, maxLines = 1,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** Pastiglia di stato (es. "Trascritta", "In coda"). */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 2.dp),
        color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Barra di avanzamento spessa e arrotondata. */
@Composable
fun RoundProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, size = Size(maxOf(w, size.height), size.height), cornerRadius = r)
    }
}

/** Barra indeterminata (scorrimento) per i lavori senza percentuale. */
@Composable
fun IndeterminateProgress(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = rememberInfiniteTransition()
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart))
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * 0.3f
        val left = (size.width + w) * x - w
        val l = left.coerceAtLeast(0f)
        val rgt = (left + w).coerceAtMost(size.width)
        if (rgt > l) drawRoundRect(color, Offset(l, 0f), Size(rgt - l, size.height), r)
    }
}

/** Punto che pulsa (registrazione in corso). */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Int = 10) {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Box(modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

/** Misuratore di livello a barre (storico degli ultimi livelli del microfono). */
@Composable
fun LevelBars(levels: List<Float>, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 3f
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        levels.forEachIndexed { i, lv ->
            val h = (size.height * (0.08f + 0.92f * Math.sqrt(lv.toDouble()).toFloat().coerceIn(0f, 1f)))
            drawRoundRect(
                color.copy(alpha = 0.35f + 0.65f * (i + 1f) / n),
                Offset(i * (bw + gap), (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2),
            )
        }
    }
}

/** Contenitore con la sfumatura del marchio. */
@Composable
fun GradientBox(brush: Brush, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.large, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(shape).background(brush), content = content)
}

/** Riquadro icona circolare tinta (per intestazioni e stati vuoti). */
@Composable
fun IconBadge(color: Color, modifier: Modifier = Modifier, size: Int = 40, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape((size / 3.2).dp)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape((size / 3.2).dp)),
        contentAlignment = Alignment.Center, content = content,
    )
}

val MonoLabel: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Medium,
    )

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
