package io.github.sitdownkevin.opentj.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sitdownkevin.opentj.BuildConfig
import io.github.sitdownkevin.opentj.R
import io.github.sitdownkevin.opentj.core.Credentials
import io.github.sitdownkevin.opentj.vpn.ConnectionStatus
import io.github.sitdownkevin.opentj.vpn.VpnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val Ink = Color(0xFF183B3A)
private val Teal = Color(0xFF087F73)
private val Paper = Color(0xFFF5F3EC)
private val Muted = Color(0xFF637471)
private val Line = Color(0xFFDBE2DB)
private val Danger = Color(0xFFAF463B)

@Composable
fun OpenTjScreen(
    state: VpnState,
    form: AccountForm,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMethod: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearLogs: () -> Unit,
) {
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val connected = state.status == ConnectionStatus.CONNECTED
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal, onPrimary = Color.White, background = Paper, surface = Color.White,
            onSurface = Ink, onBackground = Ink, error = Danger,
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, fontWeight = FontWeight.Medium),
        ),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFE9F0E8), Paper, Paper)))
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.brand), contentDescription = null, modifier = Modifier.size(42.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("Open TJ", fontFamily = FontFamily.Serif, fontSize = 27.sp, color = Ink)
                        Text("同济校园网络", fontSize = 12.sp, color = Muted)
                    }
                    TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "返回" else "日志") }
                }
                if (showLogs) {
                    LogPanel(state, onClearLogs)
                } else {
                    Surface(shape = RoundedCornerShape(28.dp), color = Ink, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(if (connected) Color(0xFF9FE3B8) else Color(0xFFC8D2C8), CircleShape))
                                Text("校园连接", color = Color(0xFFD3E3DA), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                                Spacer(Modifier.weight(1f))
                                Text("IPv4", color = Color(0xFFD3E3DA), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }
                            Text(state.status.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Medium)
                            Text(state.detail, color = Color(0xFFCDDCD6), fontSize = 13.sp, lineHeight = 20.sp)
                            ConnectionDuration(state.connectedAt)
                            Button(
                                onClick = if (state.status.active) onDisconnect else onConnect,
                                enabled = !form.loading && !form.authorizing && state.status != ConnectionStatus.STOPPING,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD8F3BE), contentColor = Ink),
                            ) {
                                if (form.authorizing || state.status in listOf(ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING, ConnectionStatus.STOPPING)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), color = Ink, strokeWidth = 2.dp)
                                    Spacer(Modifier.size(10.dp))
                                }
                                Text(
                                    when {
                                        form.authorizing -> "等待系统授权"
                                        state.status == ConnectionStatus.STOPPING -> "正在断开"
                                        state.status.active -> "断开连接"
                                        else -> "连接校园网络"
                                    },
                                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TrafficCard("↓ 下载", state.traffic.downloadBps, state.traffic.downloadBytes, state.downloadHistory, Teal, Modifier.weight(1f))
                        TrafficCard("↑ 上传", state.traffic.uploadBps, state.traffic.uploadBytes, state.uploadHistory, Color(0xFFAE7750), Modifier.weight(1f))
                    }
                    Surface(
                        shape = RoundedCornerShape(22.dp), color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.fillMaxWidth().animateContentSize(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("连接设置", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("vpn.tongji.cn", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                            }
                            if (!state.status.active) {
                                AccountFields(form, onUsername, onPassword, onMethod, onRemember, onConnect)
                            } else {
                                Text(form.username, fontSize = 16.sp)
                                Text("${form.method} · 断开后可修改账号", color = Muted, fontSize = 12.sp)
                            }
                            AnimatedVisibility(form.error != null) {
                                Text(form.error.orEmpty(), color = Danger, fontSize = 13.sp)
                            }
                        }
                    }
                    Text(
                        "连接由 Android 系统管理。连接期间拦截 IPv6；关闭窗口后，VPN 会继续在后台运行。",
                        color = Muted, fontSize = 12.sp, lineHeight = 19.sp,
                    )
                    Text(
                        "OPEN TJ / ${BuildConfig.VERSION_NAME}",
                        color = Muted.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountFields(
    form: AccountForm,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onMethod: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onConnect: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }
    val enabled = !form.loading && !form.authorizing
    OutlinedTextField(
        value = form.username, onValueChange = onUsername, label = { Text("VPN 账号") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = form.password, onValueChange = onPassword, label = { Text("密码") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (enabled) onConnect() }),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示", fontSize = 12.sp) } },
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("认证方式", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { methodMenu = true }, enabled = enabled) { Text("${form.method} ▾", fontSize = 13.sp) }
            DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                Credentials.METHODS.forEach { method ->
                    DropdownMenuItem(text = { Text(method) }, onClick = { onMethod(method); methodMenu = false })
                }
            }
        }
    }
    HorizontalDivider(color = Line)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("记住密码", fontSize = 14.sp)
            Text("使用设备安全密钥加密保存", color = Muted, fontSize = 11.sp)
        }
        Switch(checked = form.remember, onCheckedChange = onRemember, enabled = enabled)
    }
}

@Composable
private fun TrafficCard(title: String, rate: Double, total: Long, history: List<Double>, color: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.7f))
            .border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = color, fontSize = 12.sp)
        Text(formatBytes(rate), fontFamily = FontFamily.Monospace, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text("每秒", color = Muted, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(38.dp).semantics { contentDescription = "最近一分钟流量趋势" }) {
            drawLine(Line, Offset(0f, size.height), Offset(size.width, size.height))
            if (history.size > 1) {
                val peak = maxOf(16384.0, history.max())
                val path = Path()
                history.forEachIndexed { index, value ->
                    val x = size.width * index / 59f
                    val y = (size.height * (1 - value / peak)).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text("累计 ${formatBytes(total.toDouble())}", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ConnectionDuration(start: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (start != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (start != null) {
        val seconds = ((now - start) / 1000).coerceAtLeast(0)
        Text(
            "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60),
            color = Color(0xFFD8F3BE), fontFamily = FontFamily.Monospace, fontSize = 18.sp,
        )
    }
}

@Composable
private fun LogPanel(state: VpnState, onClearLogs: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("连接日志", fontSize = 24.sp, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
        TextButton(onClick = onClearLogs) { Text("清空") }
    }
    Text("最近 200 条，仅保留在本次应用进程中。", color = Muted, fontSize = 12.sp)
    if (state.logs.isEmpty()) Text("连接后可在这里查看状态与错误信息。", color = Muted)
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    state.logs.asReversed().forEach { entry ->
        Surface(
            onClick = { selected = entry.message },
            shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(format.format(Date(entry.time)), fontFamily = FontFamily.Monospace, color = Muted, fontSize = 10.sp)
                Text(entry.message, color = if (entry.error) Danger else Ink, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
    if (selected != null) AlertDialog(
        onDismissRequest = { selected = null },
        title = { Text("日志详情") },
        text = { Text(selected.orEmpty()) },
        confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
    )
}

internal fun formatBytes(value: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var scaled = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
    var index = 0
    while (scaled >= 1024 && index < units.lastIndex) { scaled /= 1024; index++ }
    return String.format(Locale.US, if (index == 0) "%.0f %s" else "%.1f %s", scaled, units[index])
}
