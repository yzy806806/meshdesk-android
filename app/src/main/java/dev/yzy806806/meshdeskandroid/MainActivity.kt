package dev.yzy806806.meshdeskandroid

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeshDeskApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshDeskApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var tab by remember { mutableStateOf(0) }
    var installed by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val (i, v, r) = withContext(Dispatchers.IO) {
                Triple(
                    MeshDeskDaemon.isInstalled(),
                    MeshDeskDaemon.version(),
                    MeshDeskDaemon.isRunning()
                )
            }
            installed = i
            version = v
            running = r
        }
    }

    LaunchedEffect(Unit) {
        rootOk = RootShell.isAvailable()
        refresh()
    }

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("MeshDesk") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🏠") }, label = { Text("状态") })
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Text("⚙️") }, label = { Text("配置") })
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                rootOk == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                rootOk == false -> RootNotAvailable()
                else -> when (tab) {
                    0 -> StatusTab(
                        installed, version, running, loading, message,
                        onInstall = {
                            loading = true; message = ""
                            val r = withContext(Dispatchers.IO) { BinaryInstaller.install(context) }
                            loading = false; message = r; toast(r); refresh()
                        },
                        onStart = {
                            loading = true
                            val r = withContext(Dispatchers.IO) { MeshDeskDaemon.start() }
                            loading = false; message = r; toast(r); refresh()
                        },
                        onStop = {
                            loading = true
                            val r = withContext(Dispatchers.IO) { MeshDeskDaemon.stop() }
                            loading = false; toast(r); refresh()
                        }
                    )
                    1 -> ConfigTab(
                        onSaved = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { MeshDeskDaemon.start() }
                                message = r; toast("配置已保存并重启")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RootNotAvailable() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("未获取到 root 权限", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("请确保设备已安装 Magisk 并允许本应用获取 root。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusTab(
    installed: Boolean,
    version: String,
    running: Boolean,
    loading: Boolean,
    message: String,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MeshDesk 状态", style = MaterialTheme.typography.titleMedium)
                Text(if (installed) "已安装 ($version)" else "未安装", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (running) "● 运行中" else "○ 未运行",
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "目录: /data/adb/meshdesk\n配置: config.yaml\n日志: meshdesk.log",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (!installed) {
            Button(onClick = onInstall, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "下载中…" else "下载并安装 meshdesk")
            }
            Text(
                "从 GitHub Releases 下载最新 arm64 二进制（当前 main 含 Android ip rule 修复）。",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !loading && !running, modifier = Modifier.weight(1f)) {
                    Text("启动")
                }
                OutlinedButton(onClick = onStop, enabled = !loading && running, modifier = Modifier.weight(1f)) {
                    Text("停止")
                }
            }
            Text(
                "首次启动前请先在「配置」页写好 config.yaml 并生成 identity。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (message.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )) {
                Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigTab(onSaved: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (!loaded) {
            config = withContext(Dispatchers.IO) {
                val existing = MeshDeskDaemon.readConfig()
                if (existing.isNotEmpty()) existing
                else defaultConfig()
            }
            loaded = true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("config.yaml", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = config,
            onValueChange = { config = it },
            modifier = Modifier.fillMaxSize().weight(1f),
            fontFamily = FontFamily.Monospace,
            textStyle = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { MeshDeskDaemon.writeConfig(config) }
                    if (ok) {
                        Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                        onSaved()
                    } else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.weight(1f)) { Text("保存并重启") }
            OutlinedButton(onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { MeshDeskDaemon.ensureIdentity() }
                    Toast.makeText(context, r, Toast.LENGTH_LONG).show()
                }
            }) { Text("生成 identity") }
        }
    }
}

private fun defaultConfig(): String = """
# MeshDesk config - Android native (Redmi)
mesh:
  mesh_cidr: 10.100.0.0/24
  port: 52888
  gossip_port: 52888
  static_virtual_ip: 10.100.0.6
  tun_enabled: true
  tun_mtu: 1400
  tun_name: mesh0
  dns_enabled: false
node:
  hostname: redmi
  identity_file: /data/adb/meshdesk/identity.pem
  web: :52888
p2p:
  advertise_endpoints: []
  enabled: true
  gossip_interval: 30
  max_peers: 256
  max_relay_hops: 2
reality:
  enabled: false
peers: []
""".trimIndent()
