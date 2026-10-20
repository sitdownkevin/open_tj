package io.github.sitdownkevin.opentj

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.sitdownkevin.opentj.ui.MainViewModel
import io.github.sitdownkevin.opentj.ui.OpenTjScreen
import io.github.sitdownkevin.opentj.vpn.OpenTjVpnService
import io.github.sitdownkevin.opentj.vpn.VpnStateStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) requestNotificationsThenConnect() else model.permissionDenied()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification denial does not revoke Android's separate VPN authorization.
        startPendingConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            OpenTjScreen(
                state = VpnStateStore.state.collectAsStateWithLifecycle().value,
                form = model.form.collectAsStateWithLifecycle().value,
                onUsername = model::username,
                onPassword = model::password,
                onMethod = model::method,
                onRemember = model::remember,
                onConnect = ::connect,
                onDisconnect = { OpenTjVpnService.disconnect(this) },
                onClearLogs = VpnStateStore::clearLogs,
            )
        }
    }

    private fun connect() {
        if (VpnStateStore.state.value.status.active) return
        lifecycleScope.launch {
            if (!model.prepareConnection()) return@launch
            val request = VpnService.prepare(this@MainActivity)
            if (request == null) requestNotificationsThenConnect() else vpnPermission.launch(request)
        }
    }

    private fun requestNotificationsThenConnect() {
        val preferences = getSharedPreferences("ui", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_asked", false)
        ) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startPendingConnection()
    }

    private fun startPendingConnection() {
        val credentials = model.takeCredentials() ?: return
        try { OpenTjVpnService.connect(this, credentials) }
        catch (_: Exception) { model.startFailed() }
    }
}
