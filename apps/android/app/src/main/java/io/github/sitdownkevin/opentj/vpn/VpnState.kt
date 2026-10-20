package io.github.sitdownkevin.opentj.vpn

import io.github.sitdownkevin.opentj.core.TrafficSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionStatus(val label: String) {
    IDLE("未连接"), CONNECTING("正在连接"), CONNECTED("已连接"),
    RECONNECTING("等待重连"), STOPPING("正在断开"), ERROR("连接失败");

    val active: Boolean get() = this !in listOf(IDLE, ERROR)
}

data class ConnectionLog(val id: Long, val time: Long, val message: String, val error: Boolean)

data class VpnState(
    val status: ConnectionStatus = ConnectionStatus.IDLE,
    val detail: String = "随时连接同济校园网络",
    val connectedAt: Long? = null,
    val traffic: TrafficSnapshot = TrafficSnapshot(),
    val downloadHistory: List<Double> = emptyList(),
    val uploadHistory: List<Double> = emptyList(),
    val logs: List<ConnectionLog> = emptyList(),
)

object VpnStateStore {
    private val sequence = AtomicLong()
    private val mutable = MutableStateFlow(VpnState())
    val state = mutable.asStateFlow()

    fun transition(status: ConnectionStatus, detail: String) {
        mutable.update {
            it.copy(
                status = status,
                detail = detail,
                connectedAt = if (status == ConnectionStatus.CONNECTED) System.currentTimeMillis() else null,
                traffic = it.traffic.copy(uploadBps = 0.0, downloadBps = 0.0),
            )
        }
    }

    fun resetTraffic() {
        mutable.update { it.copy(traffic = TrafficSnapshot(), uploadHistory = emptyList(), downloadHistory = emptyList()) }
    }

    fun traffic(sample: TrafficSnapshot) {
        mutable.update {
            it.copy(
                traffic = sample,
                uploadHistory = (it.uploadHistory + sample.uploadBps).takeLast(60),
                downloadHistory = (it.downloadHistory + sample.downloadBps).takeLast(60),
            )
        }
    }

    fun log(message: String, error: Boolean = false) {
        val entry = ConnectionLog(sequence.incrementAndGet(), System.currentTimeMillis(), message, error)
        mutable.update { it.copy(logs = (it.logs + entry).takeLast(200)) }
    }

    fun clearLogs() { mutable.update { it.copy(logs = emptyList()) } }
}
