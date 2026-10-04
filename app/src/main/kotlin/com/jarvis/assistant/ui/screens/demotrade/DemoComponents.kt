package com.jarvis.assistant.ui.screens.demotrade

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.demotrade.BucketStat
import com.jarvis.assistant.demotrade.Dir
import com.jarvis.assistant.demotrade.TradeResult
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisError
import com.jarvis.assistant.ui.theme.JarvisSuccess
import com.jarvis.assistant.ui.theme.JarvisTextPrimary
import com.jarvis.assistant.ui.theme.JarvisTextSecondary
import java.util.Locale

internal val Mono = FontFamily.Monospace
internal val PanelColor = Color(0xCC0B1520)
internal val Amber = Color(0xFFFBBF24)

internal fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
internal fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
internal fun money(v: Double): String = (if (v < 0.0) "-$" else "$") + f2(kotlin.math.abs(v))
internal fun signedMoney(v: Double): String = (if (v < 0.0) "-$" else "+$") + f2(kotlin.math.abs(v))
internal fun price(v: Double): String = String.format(Locale.US, "%.5f", v)
internal fun pct(v: Double?): String = if (v == null) "--" else String.format(Locale.US, "%.1f%%", v * 100.0)

internal fun dirColor(d: Dir): Color = when (d) { Dir.CALL -> JarvisSuccess; Dir.PUT -> JarvisError; Dir.WAIT -> Amber }
internal fun pnlColor(v: Double): Color = if (v > 0.0) JarvisSuccess else if (v < 0.0) JarvisError else JarvisTextSecondary
internal fun resultColor(r: TradeResult?): Color = when (r) {
    TradeResult.WIN -> JarvisSuccess
    TradeResult.LOSS -> JarvisError
    TradeResult.TIE, TradeResult.VOID -> Amber
    null -> JarvisCyan
}

@Composable
internal fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = PanelColor,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), color = JarvisCyan, fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun Stat(label: String, value: String, color: Color = JarvisTextPrimary, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), color = JarvisTextSecondary, fontSize = 9.sp, fontFamily = Mono)
        Text(value, color = color, fontSize = 16.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { content() }
}

@Composable
internal fun Body(text: String, color: Color = JarvisTextPrimary, size: Int = 12) {
    Text(text, color = color, fontSize = size.sp, fontFamily = Mono)
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) JarvisCyan.copy(alpha = 0.28f) else Color.Transparent,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (selected) JarvisCyan else JarvisTextSecondary.copy(alpha = 0.5f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label.uppercase(), color = if (selected) JarvisCyan else JarvisTextSecondary, fontSize = 10.sp,
            fontFamily = Mono, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Body(label)
            if (hint != null) Body(hint, JarvisTextSecondary, 10)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Body(label)
            if (hint != null) Body(hint, JarvisTextSecondary, 10)
        }
        Chip("-", false, onMinus)
        Spacer(Modifier.width(8.dp))
        Text(value, color = JarvisCyan, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Chip("+", false, onPlus)
    }
}

@Composable
internal fun LineChart(values: List<Double>, color: Color, modifier: Modifier = Modifier, zeroLine: Boolean = false) {
    Canvas(modifier.fillMaxWidth().height(110.dp).background(Color(0x22000000))) {
        if (values.size < 2) return@Canvas
        val lo = minOf(values.min(), if (zeroLine) 0.0 else values.min())
        val hi = maxOf(values.max(), if (zeroLine) 0.0 else values.max())
        val span = if (hi - lo < 1e-9) 1.0 else hi - lo
        fun x(i: Int) = size.width * i / (values.size - 1).toFloat()
        fun y(v: Double) = (size.height - ((v - lo) / span * size.height * 0.9 + size.height * 0.05)).toFloat()
        if (zeroLine) {
            drawLine(JarvisTextSecondary.copy(alpha = 0.5f), Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1f)
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

/** Horizontal bars of win rate per bucket; the thin marker is the break-even win rate for the configured payout. */
@Composable
internal fun BucketBars(buckets: List<BucketStat>, breakEven: Double) {
    if (buckets.isEmpty()) {
        Body("No closed demo trades yet.", JarvisTextSecondary, 11)
        return
    }
    buckets.forEach { b ->
        val wr = b.winRate
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Body(b.key, size = 11)
                Body("${b.trades} trades  ${pct(wr)}  ${signedMoney(b.pnl)}", pnlColor(b.pnl), 11)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF))) {
                if (wr != null) {
                    Box(
                        Modifier.fillMaxWidth(wr.toFloat().coerceIn(0.02f, 1f)).height(6.dp)
                            .background(if (wr >= breakEven) JarvisSuccess else JarvisError)
                    )
                }
                Box(Modifier.fillMaxWidth(breakEven.toFloat().coerceIn(0f, 1f)).height(6.dp)) {
                    Box(Modifier.align(Alignment.CenterEnd).width(2.dp).height(6.dp).background(Color.White))
                }
            }
        }
    }
}
