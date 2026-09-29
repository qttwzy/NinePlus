package com.example.ninebotplus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ninebotplus.domain.DiagnosticsSnapshot
import com.example.ninebotplus.ui.AppViewModel
import com.example.ninebotplus.ui.theme.TeslaGreen
import com.example.ninebotplus.ui.theme.TeslaOrange
import com.example.ninebotplus.ui.theme.TeslaRed
import com.example.ninebotplus.util.NineplusDates

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val login by viewModel.loginResult.collectAsState()
    val baseUrl by viewModel.baseUrlString.collectAsState()
    val bearer by viewModel.bearerToken.collectAsState()
    val account by viewModel.account.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val privacy by viewModel.capturePrivacy.collectAsState()
    val pushToken by viewModel.pushToken.collectAsState()

    if (login == null) {
        LoginContent(
            viewModel = viewModel,
            baseUrl = baseUrl,
            bearer = bearer,
            account = account,
            uiMessage = ui.errorMessage ?: ui.statusMessage,
        )
        return
    }

    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf<DiagnosticsSnapshot?>(null) }

    LaunchedEffect(ui.statusMessage, ui.errorMessage) {
        diagnostics = runCatching { viewModel.diagnostics() }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("我的", style = MaterialTheme.typography.headlineMedium)

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(login?.phone ?: "NinePlus", fontWeight = FontWeight.SemiBold)
                Text(
                    "${diagnostics?.vehicleCount ?: 0} 台车辆 · ${login?.businessUid ?: "账号已绑定"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.errorMessage != null) {
            Text(ui.errorMessage!!, color = TeslaRed, fontSize = 13.sp)
        }
        if (ui.statusMessage != null) {
            Text(ui.statusMessage!!, color = TeslaGreen, fontSize = 13.sp)
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("连接与通知", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://服务器IP:19009") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bearer,
                    onValueChange = viewModel::setBearerToken,
                    label = { Text("访问口令（可选）") },
                    placeholder = { Text("后台设置了 App Bearer Token 时填写") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.testConnection() }) { Text("测试") }
                    Button(onClick = { viewModel.saveConfiguration() }) { Text("保存") }
                }
                Text(
                    "多账号、推送和轮询策略在 NinePlus Platform 后台管理；后台未设置 App Bearer Token 时可留空。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (pushToken.isNullOrBlank()) {
                        "推送设备未上报"
                    } else {
                        "推送设备已就绪 · ${pushToken!!.take(8)}…${pushToken!!.takeLast(6)}"
                    },
                    color = if (pushToken.isNullOrBlank()) TeslaOrange else TeslaGreen,
                    fontSize = 12.sp,
                )
                Text(
                    "FCM 为可选能力：客户端管道已就绪。启用远程推送需 Platform 配置 Android FCM，并在客户端提供 Firebase 配置（android/app/google-services.json，或 local.properties 的 firebase.*）。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        viewModel.enablePush()
                    } else {
                        viewModel.setNotificationPermissionDenied()
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            )
                        } else {
                            viewModel.enablePush()
                        }
                    },
                ) {
                    Text("检查权限并上报推送")
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("截图录屏保护", fontWeight = FontWeight.SemiBold)
                        Text(
                            "隐藏首页地址、车辆位置地图和地址",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = privacy, onCheckedChange = viewModel::setCapturePrivacy)
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("诊断中心", fontWeight = FontWeight.SemiBold)
                diagnostics?.let { d ->
                    Text(d.serverText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("车辆 ${d.vehicleCount} · ${d.selectedVehicleName}", fontSize = 12.sp)
                    Text(
                        d.dashboardUpdatedAt?.let { "车况更新 ${NineplusDates.formatDateTime(it)}" }
                            ?: "车况尚未更新",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "接口行程 ${d.interfaceRideCount} · 历史快照 ${d.historyPointCount} · 本地轨迹 ${d.recordedRideCount}",
                        fontSize = 12.sp,
                    )
                    d.lastError?.let {
                        Text("最近错误：$it", color = TeslaOrange, fontSize = 12.sp)
                    }
                } ?: Text("加载中…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("快捷入口", fontWeight = FontWeight.SemiBold)
                Text(
                    "已支持：桌面组件快捷刷新、寻车铃，以及通知/桌面打开 App 的 deep link。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "App Shortcuts / 语音助手指令尚未实现（计划中）。危险车控不会从桌面静默执行，需在 App 内确认。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录", color = TeslaRed)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoginContent(
    viewModel: AppViewModel,
    baseUrl: String,
    bearer: String,
    account: String,
    uiMessage: String?,
) {
    var password by remember {
        mutableStateOf(com.example.ninebotplus.ui.DebugLoginDefaults.password)
    }
    var showPassword by remember { mutableStateOf(false) }
    var agreed by remember {
        mutableStateOf(com.example.ninebotplus.ui.DebugLoginDefaults.hasAny)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("欢迎登录", style = MaterialTheme.typography.headlineLarge)
        Text(
            "开启智能出行新体验",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = viewModel::setBaseUrl,
            label = { Text("服务器地址") },
            placeholder = { Text("http://服务器IP:19009") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = bearer,
            onValueChange = viewModel::setBearerToken,
            label = { Text("访问口令（可选）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = account,
            onValueChange = viewModel::setAccount,
            label = { Text("请输入手机号") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("请输入密码") },
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "隐藏" else "显示")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = agreed, onCheckedChange = { agreed = it })
            Spacer(Modifier.width(8.dp))
            Text("我已阅读并同意《用户协议》和《隐私政策》", fontSize = 12.sp)
        }

        uiMessage?.let {
            Text(it, color = TeslaOrange, fontSize = 13.sp)
        }

        Button(
            onClick = {
                viewModel.saveConfiguration {
                    viewModel.loginWithPassword(password)
                }
            },
            enabled = agreed,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text("登录", fontWeight = FontWeight.SemiBold)
        }

        TextButton(onClick = { viewModel.testConnection() }) {
            Text("测试服务器连接")
        }
        Spacer(Modifier.height(24.dp))
    }
}
