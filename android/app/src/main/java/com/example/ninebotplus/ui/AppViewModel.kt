package com.example.ninebotplus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ninebotplus.NinePlusApp
import com.example.ninebotplus.data.VehicleRepository
import com.example.ninebotplus.domain.BatteryChemistry
import com.example.ninebotplus.domain.Dashboard
import com.example.ninebotplus.domain.DiagnosticsSnapshot
import com.example.ninebotplus.domain.LoginResult
import com.example.ninebotplus.domain.RecordedRide
import com.example.ninebotplus.domain.RefreshEvent
import com.example.ninebotplus.domain.ResolvedAddress
import com.example.ninebotplus.domain.RideDetail
import com.example.ninebotplus.domain.RideRecord
import com.example.ninebotplus.domain.ServerConfiguration
import com.example.ninebotplus.domain.VehicleAction
import com.example.ninebotplus.domain.VehicleHistoryPoint
import com.example.ninebotplus.domain.VehicleSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiMessage(
    val text: String,
    val isError: Boolean,
)

data class AppUiState(
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val activeAction: VehicleAction? = null,
    val activeActionSn: String? = null,
    val syncingMonth: String? = null,
)

class AppViewModel(
    private val app: NinePlusApp,
    private val repository: VehicleRepository,
) : ViewModel() {

    val dashboard: StateFlow<Dashboard> = repository.dashboard
    val loginResult: StateFlow<LoginResult?> = repository.loginResult
    val resolvedAddresses: StateFlow<Map<String, ResolvedAddress>> = repository.resolvedAddresses
    val recordedRides: StateFlow<List<RecordedRide>> = repository.recordedRides
    val capturePrivacy: StateFlow<Boolean> = repository.capturePrivacy
    val pushToken: StateFlow<String?> = repository.pushToken

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _baseBaseUrl = MutableStateFlow("")
    val baseUrlString: StateFlow<String> = _baseBaseUrl.asStateFlow()

    private val _bearerToken = MutableStateFlow("")
    val bearerToken: StateFlow<String> = _bearerToken.asStateFlow()

    private val _account = MutableStateFlow("")
    val account: StateFlow<String> = _account.asStateFlow()

    private val _rideDetails = MutableStateFlow<Map<String, RideDetail>>(emptyMap())
    val rideDetails: StateFlow<Map<String, RideDetail>> = _rideDetails.asStateFlow()

    private val _history = MutableStateFlow<Map<String, List<VehicleHistoryPoint>>>(emptyMap())
    val history: StateFlow<Map<String, List<VehicleHistoryPoint>>> = _history.asStateFlow()

    private val _interfaceRides = MutableStateFlow<Map<String, List<RideRecord>>>(emptyMap())
    val interfaceRides: StateFlow<Map<String, List<RideRecord>>> = _interfaceRides.asStateFlow()

    private var lastAutoRefreshAt = 0L

    fun initialize() {
        viewModelScope.launch {
            repository.initialize()
            val configuration = repository.configuration()
            _baseBaseUrl.value = configuration.baseUrlString
            _bearerToken.value = configuration.bearerToken
            _account.value = repository.loginResult.value?.phone.orEmpty()
            refreshLocalCaches()
            autoRefreshIfPossible()
        }
    }

    fun setBaseUrl(value: String) {
        _baseBaseUrl.value = value
    }

    fun setBearerToken(value: String) {
        _bearerToken.value = value
    }

    fun setAccount(value: String) {
        _account.value = value
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }

    fun saveConfiguration(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            runOperation("正在保存配置") {
                val configuration = currentConfiguration()
                if (!configuration.isUsable()) {
                    error("请先填写 NinePlus 服务器地址")
                }
                repository.saveConfiguration(configuration)
                status("服务器配置已保存")
                onDone?.invoke()
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            runOperation("正在测试连接") {
                repository.saveConfiguration(currentConfiguration())
                repository.testConnection()
                status("服务器连接正常")
            }
        }
    }

    fun loginWithPassword(password: String) {
        viewModelScope.launch {
            runOperation("正在密码登录") {
                val account = _account.value.trim()
                if (account.isEmpty()) error("请填写手机号")
                if (password.isEmpty()) error("请填写密码")
                repository.saveConfiguration(currentConfiguration())
                repository.login(account, password)
                _account.value = repository.loginResult.value?.phone ?: account
                refreshLocalCaches()
                repository.refreshDashboard()
                refreshLocalCaches()
                status("登录成功")
            }
        }
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            runOperation("正在刷新车况") {
                repository.refreshDashboard()
                refreshLocalCaches()
                val at = repository.dashboard.value.updatedAt
                status("已更新 ${com.example.ninebotplus.util.NineplusDates.formatDateTime(at)}")
                app.pushManager.syncChargingNotification(repository.dashboard.value)
            }
        }
    }

    fun selectVehicle(sn: String) {
        viewModelScope.launch {
            repository.selectVehicle(sn)
        }
    }

    fun perform(action: VehicleAction, sn: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                activeAction = action,
                activeActionSn = sn,
            )
            runOperation(action.loadingTitle) {
                repository.performAction(action, sn)
                refreshLocalCaches()
                status(action.resultTitle)
                app.pushManager.syncChargingNotification(repository.dashboard.value)
            }
            _uiState.value = _uiState.value.copy(activeAction = null, activeActionSn = null)
        }
    }

    fun syncTravelMonth(vehicleSn: String, month: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(syncingMonth = month)
            runOperation("正在获取 ${com.example.ninebotplus.util.NineplusDates.displayMonth(month)} 行程") {
                repository.syncTravelMonth(vehicleSn, month)
                refreshLocalCaches()
                val records = _interfaceRides.value[vehicleSn].orEmpty()
                if (records.isEmpty()) {
                    status("${com.example.ninebotplus.util.NineplusDates.displayMonth(month)} 暂无行程")
                } else {
                    status("已获取 ${com.example.ninebotplus.util.NineplusDates.displayMonth(month)} ${records.size} 条行程")
                }
            }
            _uiState.value = _uiState.value.copy(syncingMonth = null)
        }
    }

    fun refreshRideDetail(vehicleSn: String, rideId: String, force: Boolean = false) {
        viewModelScope.launch {
            val key = "$vehicleSn|$rideId"
            runCatching {
                val detail = repository.refreshRideDetail(vehicleSn, rideId, force)
                _rideDetails.value = _rideDetails.value + (key to detail)
            }.onFailure {
                _uiState.value = _uiState.value.copy(errorMessage = it.message)
            }
        }
    }

    fun updateBatteryChemistry(
        sn: String,
        chemistry: BatteryChemistry,
        nominalVoltage: Double?,
        capacityWh: Double?,
    ) {
        viewModelScope.launch {
            runOperation("正在更新电池类型") {
                repository.updateBatteryChemistry(sn, chemistry, nominalVoltage, capacityWh)
                refreshLocalCaches()
                status("已更新${chemistry.title}电池参数")
            }
        }
    }

    fun enablePush() {
        viewModelScope.launch {
            runOperation("正在开启充电通知") {
                app.pushManager.let { manager ->
                    // Token is obtained via FCM service; register whatever is stored.
                    repository.registerPushTokenToServer()
                }
                if (repository.pushToken.value == null) {
                    status("已请求通知权限，系统返回设备 Token 后会自动上报")
                } else {
                    status("充电通知已开启")
                }
            }
        }
    }

    fun resolveAddressesNow() {
        viewModelScope.launch {
            runOperation("正在解析车辆位置") {
                repository.resolveAddressesNow()
                status("车辆位置已解析")
            }
        }
    }

    fun setCapturePrivacy(enabled: Boolean) {
        viewModelScope.launch {
            repository.setCapturePrivacy(enabled)
            status(if (enabled) "截图录屏保护已开启" else "截图录屏保护已关闭")
        }
    }

    fun saveRecordedRide(ride: RecordedRide) {
        viewModelScope.launch {
            repository.saveRecordedRide(ride)
            status("骑行记录已保存")
        }
    }

    fun deleteRecordedRide(id: String) {
        viewModelScope.launch {
            repository.deleteRecordedRide(id)
            status("骑行记录已删除")
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _account.value = ""
            status("已退出登录")
        }
    }

    suspend fun diagnostics(): DiagnosticsSnapshot = repository.diagnostics()

    fun historyFor(sn: String): List<VehicleHistoryPoint> = _history.value[sn].orEmpty()

    fun interfaceRidesFor(sn: String): List<RideRecord> = _interfaceRides.value[sn].orEmpty()

    fun resolvedAddressText(snapshot: VehicleSnapshot): String? =
        repository.resolvedAddress(snapshot.vehicle.sn)

    private fun currentConfiguration() = ServerConfiguration(
        baseUrlString = _baseBaseUrl.value,
        bearerToken = _bearerToken.value,
        appSessionToken = repository.loginResult.value?.sessionToken,
    )

    private suspend fun refreshLocalCaches() {
        val dashboard = repository.dashboard.value
        val history = mutableMapOf<String, List<VehicleHistoryPoint>>()
        val rides = mutableMapOf<String, List<RideRecord>>()
        for (snapshot in dashboard.vehicles) {
            val sn = snapshot.vehicle.sn
            history[sn] = repository.historyPoints(sn)
            rides[sn] = repository.interfaceRideRecords(sn)
        }
        _history.value = history
        _interfaceRides.value = rides
    }

    private fun autoRefreshIfPossible() {
        val configuration = currentConfiguration()
        if (!configuration.isUsable()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoRefreshAt < 8_000) return
        lastAutoRefreshAt = now
        refreshDashboard()
    }

    private fun status(message: String) {
        _uiState.value = _uiState.value.copy(statusMessage = message, errorMessage = null)
    }

    private fun runOperation(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingMessage = message,
                statusMessage = null,
                errorMessage = null,
            )
            try {
                block()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = error.message ?: "操作失败",
                    statusMessage = null,
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false, loadingMessage = null)
            }
        }
    }

    companion object {
        fun factory(app: NinePlusApp): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel(app, app.repository) as T
            }
        }
    }
}

private fun ServerConfiguration.isUsable(): Boolean = this.isUsable
