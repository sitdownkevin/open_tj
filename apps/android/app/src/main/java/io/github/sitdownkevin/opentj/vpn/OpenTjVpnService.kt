package io.github.sitdownkevin.opentj.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.sitdownkevin.opentj.MainActivity
import io.github.sitdownkevin.opentj.R
import io.github.sitdownkevin.opentj.core.Credentials
import io.github.sitdownkevin.opentj.core.GatewayClient
import io.github.sitdownkevin.opentj.core.GatewayNetworkException
import io.github.sitdownkevin.opentj.core.GatewaySession
import io.github.sitdownkevin.opentj.core.Protocol
import io.github.sitdownkevin.opentj.core.ResourceScope
import io.github.sitdownkevin.opentj.core.RetryPolicy
import io.github.sitdownkevin.opentj.core.TlsConnector
import io.github.sitdownkevin.opentj.core.TunnelPump
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OpenTjVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val resolver = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "OpenTJ-DNS").apply { isDaemon = true }
    }
    private val running = AtomicBoolean()
    private var workerActive = false
    private val destroyed = AtomicBoolean()
    private val lock = Object()
    private val networks = mutableMapOf<Network, NetworkCapabilities>()
    private var selectedNetwork: Network? = null
    private var networkGeneration = 0L
    private var currentScope: ResourceScope? = null
    private var callbackRegistered = false
    private lateinit var connectivity: ConnectivityManager
    private lateinit var notifications: NotificationManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivity.getNetworkCapabilities(network)?.let { updateNetwork(network, it) }
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetwork(network, capabilities)
        }
        override fun onLost(network: Network) { updateNetwork(network, null) }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.vpn_channel_description)
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            if (!workerActive) stopSelf(startId)
            return START_NOT_STICKY
        }
        val credentials = pendingCredentials.getAndSet(null)
        if (intent?.action != ACTION_CONNECT || credentials == null || prepare(this) != null) {
            if (!workerActive) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (workerActive || !running.compareAndSet(false, true)) return START_NOT_STICKY
        workerActive = true
        try {
            val notification = notification("正在连接校园网络")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            VpnStateStore.resetTraffic()
            VpnStateStore.transition(ConnectionStatus.CONNECTING, "正在验证 VPN 账号")
            VpnStateStore.log("开始连接 ${GatewayClient.HOST} · ${credentials.method}")
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                networkCallback,
            )
            callbackRegistered = true
            executor.execute { connectLoop(credentials) }
        } catch (_: Exception) {
            running.set(false)
            workerActive = false
            VpnStateStore.transition(ConnectionStatus.ERROR, "无法启动 VPN 服务，请重新授权后重试")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateNetwork(network: Network, capabilities: NetworkCapabilities?) {
        val scope = synchronized(lock) {
            if (capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                networks[network] = capabilities
            } else networks.remove(network)
            val next = networks.entries.maxByOrNull { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1)
            }?.key
            if (next == selectedNetwork) return
            selectedNetwork = next
            networkGeneration++
            networks[next]?.let { caps ->
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    else -> "其他网络"
                }
                Log.i(TAG, "selected $transport validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} generation=$networkGeneration")
            }
            lock.notifyAll()
            currentScope
        }
        scope?.close()
    }

    private fun connectLoop(credentials: Credentials) {
        var finalError: String? = null
        var attempt = 0
        var totalUpload = 0L
        var totalDownload = 0L
        try {
            while (running.get() && !destroyed.get()) {
                val selected = synchronized(lock) {
                    while (selectedNetwork == null && running.get()) {
                        transition(ConnectionStatus.RECONNECTING, "等待可用的 Wi-Fi 或移动网络")
                        lock.wait(1000)
                    }
                    if (!running.get()) null else selectedNetwork?.let { it to networkGeneration }
                } ?: break
                val (network, generation) = selected
                val scope = ResourceScope()
                synchronized(lock) {
                    currentScope = scope
                    if (!running.get() || networkGeneration != generation) scope.close()
                }
                val connector = TlsConnector(
                    resolve = resolve@{ host ->
                        val lookup = resolver.submit<List<java.net.InetAddress>> { network.getAllByName(host).toList() }
                        try {
                            val deadline = System.nanoTime() + 10_000_000_000L
                            while (true) {
                                scope.checkActive()
                                try {
                                    return@resolve lookup.get(250, TimeUnit.MILLISECONDS)
                                } catch (_: TimeoutException) {
                                    if (System.nanoTime() >= deadline) throw java.net.SocketTimeoutException("域名解析超时")
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            emptyList()
                        } catch (error: java.util.concurrent.ExecutionException) {
                            throw (error.cause as? IOException ?: IOException("域名解析失败", error.cause))
                        } finally { lookup.cancel(true) }
                    },
                    prepareSocket = { socket ->
                        if (!protect(socket)) {
                            // Some vendor VPN implementations reject protect() until the
                            // tunnel interface exists. An explicit underlying-network bind
                            // still prevents the gateway socket from looping back into us.
                            Log.w(TAG, "gateway protect rejected; using selected underlying network")
                        }
                        try {
                            network.bindSocket(socket)
                            Log.i(TAG, "gateway socket bound to selected underlying network")
                        } catch (error: SecurityException) {
                            throw error
                        } catch (error: Exception) {
                            throw GatewayNetworkException().apply { initCause(error) }
                        }
                    },
                )
                val client = GatewayClient(connector)
                var session: GatewaySession? = null
                var received = 0L
                var sent = 0L
                var failure: Throwable? = null
                var stage = "准备底层网络"
                try {
                    scope.checkActive()
                    stage = "建立 TLS 连接"
                    transition(ConnectionStatus.CONNECTING, "正在认证并读取隧道配置")
                    session = client.login(credentials, scope)
                    scope.checkActive()
                    stage = "创建系统 VPN 接口"
                    VpnStateStore.log("认证成功，已读取隧道配置")
                    val builder = Builder()
                        .setSession("Open TJ")
                        .setMtu(Protocol.MTU)
                        .addAddress(session.config.address, 32)
                        .addRoute("0.0.0.0", 0)
                        .setBlocking(false)
                        .setUnderlyingNetworks(arrayOf(network))
                        .setConfigureIntent(openAppIntent())
                    session.config.dnsServers.forEach { builder.addDnsServer(it) }
                    // This is an IPv4-only tunnel; Android installs an unreachable IPv6 default route.
                    if (Build.VERSION.SDK_INT >= 29) {
                        val caps = connectivity.getNetworkCapabilities(network)
                        builder.setMetered(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                    }
                    val descriptor = builder.establish()
                        ?: throw SecurityException("VPN 授权已撤销")
                    val device = scope.own(AndroidPacketDevice(descriptor))
                    scope.checkActive()
                    stage = "转发 IPv4 流量"
                    transition(ConnectionStatus.CONNECTED, "IPv4 流量已通过校园 VPN")
                    VpnStateStore.log("隧道已连接 · IPv4 · DNS ${session.config.dnsServers.size} 个")
                    attempt = 0
                    TunnelPump().run(session, device, scope) { sample ->
                        sent = sample.uploadBytes
                        received = sample.downloadBytes
                        VpnStateStore.traffic(sample.copy(
                            uploadBytes = totalUpload + sent, downloadBytes = totalDownload + received,
                        ))
                    }
                } catch (error: Exception) {
                    failure = error
                    Log.w(TAG, "connection attempt failed while $stage", error)
                } finally {
                    scope.close()
                    totalUpload += sent
                    totalDownload += received
                    session?.let {
                        val logoutScope = ResourceScope()
                        synchronized(lock) {
                            currentScope = logoutScope
                            if (destroyed.get()) logoutScope.close()
                        }
                        try { client.logout(it, logoutScope) } catch (_: Exception) {
                            VpnStateStore.log("会话注销未确认；本地隧道已关闭")
                        } finally { logoutScope.close() }
                    }
                    synchronized(lock) { currentScope = null }
                }
                if (!running.get() || destroyed.get()) break
                val changedNetwork = synchronized(lock) { networkGeneration != generation }
                if (changedNetwork) {
                    VpnStateStore.log("网络发生变化，正在重新连接")
                    continue
                }
                val error = failure ?: IOException("连接已结束")
                if (!RetryPolicy.shouldRetry(error)) {
                    finalError = if (error is SecurityException) "VPN 授权已撤销，请重新连接" else RetryPolicy.message(error)
                    VpnStateStore.log("${stage}失败：$finalError（${failureDetail(error)}）", error = true)
                    break
                }
                attempt++
                val delay = RetryPolicy.delaySeconds(attempt)
                VpnStateStore.log("${stage}失败：${RetryPolicy.message(error)}（${failureDetail(error)}），${delay} 秒后重试")
                transition(ConnectionStatus.RECONNECTING, "${RetryPolicy.message(error)}，${delay} 秒后重试")
                val until = System.nanoTime() + delay * 1_000_000_000L
                synchronized(lock) {
                    while (running.get() && networkGeneration == generation) {
                        val remaining = (until - System.nanoTime()) / 1_000_000
                        if (remaining <= 0) break
                        lock.wait(remaining.coerceAtMost(1000))
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(TAG, "VPN service loop failed", error)
            finalError = "VPN 服务遇到异常，请重新连接"
        } finally {
            synchronized(lock) { currentScope?.close(); currentScope = null }
            main.post {
                if (destroyed.get()) return@post
                running.set(false)
                workerActive = false
                if (finalError != null) {
                    VpnStateStore.log(finalError!!, error = true)
                    VpnStateStore.transition(ConnectionStatus.ERROR, finalError!!)
                } else {
                    VpnStateStore.log("隧道已关闭，系统网络已交还 Android")
                    VpnStateStore.transition(ConnectionStatus.IDLE, "连接已结束")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun failureDetail(error: Throwable): String {
        val message = error.message
            ?.lineSequence()
            ?.joinToString(" ")
            ?.trim()
            ?.take(160)
            .orEmpty()
        return if (message.isEmpty()) error.javaClass.simpleName else "${error.javaClass.simpleName}: $message"
    }

    private fun transition(status: ConnectionStatus, detail: String) {
        if (!running.get()) return
        VpnStateStore.transition(status, detail)
        notifications.notify(NOTIFICATION_ID, notification(detail))
    }

    private fun requestStop() {
        if (running.getAndSet(false)) {
            VpnStateStore.transition(ConnectionStatus.STOPPING, "正在关闭隧道")
            VpnStateStore.log("收到断开请求")
        }
        synchronized(lock) {
            currentScope?.close()
            lock.notifyAll()
        }
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed.set(true)
        requestStop()
        if (callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        }
        executor.shutdownNow()
        resolver.shutdownNow()
        if (VpnStateStore.state.value.status != ConnectionStatus.ERROR) {
            VpnStateStore.transition(ConnectionStatus.IDLE, "连接已结束")
        }
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notification(detail: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OpenTjVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Open TJ")
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "vpn_connection"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "OpenTJ"
        private const val ACTION_CONNECT = "io.github.sitdownkevin.opentj.CONNECT"
        private const val ACTION_STOP = "io.github.sitdownkevin.opentj.STOP"
        private val pendingCredentials = AtomicReference<Credentials?>()

        fun connect(context: Context, credentials: Credentials) {
            pendingCredentials.set(credentials)
            try {
                context.startForegroundService(Intent(context, OpenTjVpnService::class.java).setAction(ACTION_CONNECT))
            } catch (error: Exception) {
                pendingCredentials.set(null)
                throw error
            }
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, OpenTjVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
