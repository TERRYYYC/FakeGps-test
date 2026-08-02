package name.caiyao.fakegps.ui.screen.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import name.caiyao.fakegps.config.UnavailableFieldSet
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.hook.BaselineExtractionGuard
import name.caiyao.fakegps.ui.SingleFlightGate
import name.caiyao.fakegps.verify.DeviceObserver
import name.caiyao.fakegps.verify.ObservationScope

class ProfileEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProfileRepository(AppDatabase.getInstance(app), app)

    private val _fieldValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldValues: StateFlow<Map<String, String>> = _fieldValues

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    // Per-ViewModel ownership; a cleared scope and its claim cannot leak into another editor.
    private val saveGate = SingleFlightGate()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private val _fieldErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldErrors: StateFlow<Map<String, String>> = _fieldErrors

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /**
     * What this device currently reports, keyed by dbColumn.
     *
     * Shown beside each input because a spoofed value is only verifiable if it DIFFERS from the real
     * one — with an empty form and no reference, users could not tell whether the value they typed
     * was distinguishable from the network they were already on.
     */
    private val _reference = MutableStateFlow<Map<String, String>>(emptyMap())
    val reference: StateFlow<Map<String, String>> = _reference

    /** Whether [reference] holds real device values or values this process already spoofs. */
    val scope: ObservationScope = ObservationScope.current()

    private var editingId: Long = 0L

    fun load(profileId: Long, defaultLat: Double, defaultLon: Double) {
        viewModelScope.launch {
            runCatching {
                if (profileId > 0) {
                    val entity = repo.getById(profileId)
                    if (entity != null) {
                        editingId = entity.id
                        _fieldValues.value = runCatching { entityToMap(entity) }
                            .getOrElse { metadataError ->
                                _notice.value =
                                    "档案中的不上报元数据已损坏或来自不兼容版本；已保留普通字段，请检查后重新保存"
                                entityToMap(entity.copy(unavailableFields = null))
                            }
                        _fieldErrors.value = ProfileFieldDraft.validationErrors(_fieldValues.value)
                        return@runCatching
                    }
                }
                editingId = 0L
                _fieldValues.value = mapOf(
                    "latitude" to defaultLat.toString(),
                    "longitude" to defaultLon.toString(),
                )
                _fieldErrors.value = emptyMap()
            }.onSuccess {
                refreshReference(_fieldValues.value)
            }.onFailure { failure ->
                _notice.value = "档案读取失败：${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    fun updateField(column: String, value: String) {
        val previousRouting = DeviceObserver.wcdmaDbmColumn(referenceColumns(_fieldValues.value))
        _fieldValues.value = ProfileFieldDraft.update(_fieldValues.value, column, value)
        _fieldErrors.value = ProfileFieldDraft.validationErrors(_fieldValues.value)
        _notice.value = null
        val nextRouting = DeviceObserver.wcdmaDbmColumn(referenceColumns(_fieldValues.value))
        if (previousRouting != nextRouting) refreshReference(_fieldValues.value)
    }

    /**
     * Emitted only when a save both succeeded AND was requested with "保存并验证".
     * Kept separate from [saved] so a failed publish cannot navigate anywhere — see
     * [postSaveAction].
     */
    private val _verifyRequested = MutableStateFlow(false)
    val verifyRequested: StateFlow<Boolean> = _verifyRequested

    fun saveAndVerify() = save(thenVerify = true)

    fun save(thenVerify: Boolean = false) {
        if (!saveGate.tryStart()) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val values = _fieldValues.value
                val errors = ProfileFieldDraft.validationErrors(values)
                _fieldErrors.value = errors
                if (errors.isNotEmpty()) {
                    _notice.value = "有 ${errors.size} 个字段格式无效，尚未保存"
                    return@launch
                }
                runCatching {
                    val entity = mapToEntity(values, editingId)
                    val result = repo.save(entity)
                    editingId = result.id
                    when (postSaveAction(result.published, thenVerify)) {
                        PostSaveAction.VERIFY -> _verifyRequested.value = true
                        PostSaveAction.BACK -> _saved.value = true
                        PostSaveAction.STAY ->
                            _notice.value =
                                "档案已写入数据库，但未发布给 Hook；当前目标 App 仍使用上一份配置"
                    }
                }.onFailure { failure ->
                    _notice.value = "保存失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            } finally {
                // This gate belongs to this ViewModel. The default viewModelScope dispatcher is
                // main, so saving=false and release are one non-suspending UI transition.
                _saving.value = false
                saveGate.finish()
            }
        }
    }

    private fun refreshReference(values: Map<String, String>) {
        val configuredColumns = referenceColumns(values)
        viewModelScope.launch(Dispatchers.IO) {
            _reference.value = runCatching {
                val observe = {
                    DeviceObserver(
                        getApplication(),
                        configuredColumns = configuredColumns,
                    ).observe().values
                }
                if (scope == ObservationScope.SELF_HOOKED) {
                    BaselineExtractionGuard.call(observe)
                } else {
                    observe()
                }
            }.getOrDefault(emptyMap())
        }
    }
}

internal fun referenceColumns(values: Map<String, String>): Set<String> =
    values.filterValues { it.isNotBlank() }.keys

internal fun entityToMap(entity: ProfileEntity): Map<String, String> {
    val m = mutableMapOf<String, String>()
    fun put(k: String, v: Any?) { if (v != null) m[k] = v.toString() }
    // Skip addname — auto-generated on save
    put("latitude", entity.latitude)
    put("longitude", entity.longitude)
    put("altitude", entity.altitude)
    put("speed", entity.speed)
    put("bearing", entity.bearing)
    put("accuracy", entity.accuracy)
    put("lac", entity.lac)
    put("cid", entity.cid)
    put("mcc", entity.mcc)
    put("mnc", entity.mnc)
    put("arfcn", entity.arfcn)
    put("bsic", entity.bsic)
    put("psc", entity.psc)
    put("uarfcn", entity.uarfcn)
    put("tac", entity.tac)
    put("ci", entity.ci)
    put("pci", entity.pci)
    put("earfcn", entity.earfcn)
    put("lte_bandwidth", entity.lteBandwidth)
    put("nci", entity.nci)
    put("nrarfcn", entity.nrarfcn)
    put("nr_pci", entity.nrPci)
    put("nr_tac", entity.nrTac)
    put("gsm_rssi", entity.gsmRssi)
    put("gsm_ber", entity.gsmBer)
    put("gsm_ta", entity.gsmTa)
    put("wcdma_rssi", entity.wcdmaRssi)
    put("wcdma_rscp", entity.wcdmaRscp)
    put("wcdma_ecno", entity.wcdmaEcno)
    put("lte_rssi", entity.lteRssi)
    put("lte_rsrp", entity.lteRsrp)
    put("lte_rsrq", entity.lteRsrq)
    put("lte_sinr", entity.lteSinr)
    put("lte_cqi", entity.lteCqi)
    put("lte_ta", entity.lteTa)
    put("nr_ss_rsrp", entity.nrSsRsrp)
    put("nr_ss_rsrq", entity.nrSsRsrq)
    put("nr_ss_sinr", entity.nrSsSinr)
    put("nr_csi_rsrp", entity.nrCsiRsrp)
    put("nr_csi_rsrq", entity.nrCsiRsrq)
    put("nr_csi_sinr", entity.nrCsiSinr)
    put("signal_fluctuation_enabled", entity.signalFluctuationEnabled)
    put("signal_fluctuation_range_db", entity.signalFluctuationRangeDb)
    put("network_type", entity.networkType)
    put("data_network_type", entity.dataNetworkType)
    put("voice_network_type", entity.voiceNetworkType)
    put("operator_name", entity.operatorName)
    put("operator_numeric", entity.operatorNumeric)
    put("sim_operator", entity.simOperator)
    put("sim_operator_name", entity.simOperatorName)
    put("sim_country_iso", entity.simCountryIso)
    put("network_country_iso", entity.networkCountryIso)
    put("is_roaming", entity.isRoaming)
    put("phone_type", entity.phoneType)
    put("service_state", entity.serviceState)
    put("data_state", entity.dataState)
    put("data_activity", entity.dataActivity)
    put("override_network_type", entity.overrideNetworkType)
    put("band", entity.band)
    put("channel_bandwidth", entity.channelBandwidth)
    put("cell_bandwidth_downlink", entity.cellBandwidthDownlink)
    put("physical_cell_id", entity.physicalCellId)
    put("wifi_ssid", entity.wifiSsid)
    put("wifi_bssid", entity.wifiBssid)
    put("wifi_rssi", entity.wifiRssi)
    put("wifi_frequency", entity.wifiFrequency)
    put("wifi_link_speed", entity.wifiLinkSpeed)
    put("wifi_tx_link_speed", entity.wifiTxLinkSpeed)
    put("wifi_rx_link_speed", entity.wifiRxLinkSpeed)
    put("wifi_channel", entity.wifiChannel)
    put("wifi_standard", entity.wifiStandard)
    put("wifi_security_type", entity.wifiSecurityType)
    put("wifi_mac", entity.wifiMac)
    put("wifi_ip", entity.wifiIp)
    put("wifi_hidden", entity.wifiHidden)
    put("wifi_enabled", entity.wifiEnabled)
    put("local_ipv4", entity.localIpv4)
    put("local_ipv6", entity.localIpv6)
    put("dns_primary", entity.dnsPrimary)
    put("dns_secondary", entity.dnsSecondary)
    put("gateway", entity.gateway)
    put("subnet_mask", entity.subnetMask)
    put("connection_type", entity.connectionType)
    put("interface_name", entity.interfaceName)
    put("neighbor_cells_json", entity.neighborCellsJson)
    return ProfileFieldDraft.forDisplay(m, UnavailableFieldSet.decode(entity.unavailableFields))
}

internal fun mapToEntity(draft: Map<String, String>, id: Long): ProfileEntity {
    val split = ProfileFieldDraft.split(draft)
    val values = split.values
    val lat = values["latitude"]?.toDoubleOrNull()
    val lon = values["longitude"]?.toDoubleOrNull()
    val addname = if (lat != null && lon != null) "%.6f, %.6f".format(lat, lon) else null

    return ProfileEntity(
        id = id,
        latitude = lat,
        longitude = lon,
        altitude = values["altitude"]?.toDoubleOrNull(),
        speed = values["speed"]?.toFloatOrNull(),
        bearing = values["bearing"]?.toFloatOrNull(),
        accuracy = values["accuracy"]?.toFloatOrNull(),
        lac = values["lac"]?.toIntOrNull(),
        cid = values["cid"]?.toIntOrNull(),
        addname = addname,
        mcc = values["mcc"]?.toIntOrNull(),
        mnc = values["mnc"]?.toIntOrNull(),
        arfcn = values["arfcn"]?.toIntOrNull(),
        bsic = values["bsic"]?.toIntOrNull(),
        psc = values["psc"]?.toIntOrNull(),
        uarfcn = values["uarfcn"]?.toIntOrNull(),
        tac = values["tac"]?.toIntOrNull(),
        ci = values["ci"]?.toIntOrNull(),
        pci = values["pci"]?.toIntOrNull(),
        earfcn = values["earfcn"]?.toIntOrNull(),
        lteBandwidth = values["lte_bandwidth"]?.toIntOrNull(),
        nci = values["nci"]?.toLongOrNull(),
        nrarfcn = values["nrarfcn"]?.toIntOrNull(),
        nrPci = values["nr_pci"]?.toIntOrNull(),
        nrTac = values["nr_tac"]?.toIntOrNull(),
        gsmRssi = values["gsm_rssi"]?.toIntOrNull(),
        gsmBer = values["gsm_ber"]?.toIntOrNull(),
        gsmTa = values["gsm_ta"]?.toIntOrNull(),
        wcdmaRssi = values["wcdma_rssi"]?.toIntOrNull(),
        wcdmaRscp = values["wcdma_rscp"]?.toIntOrNull(),
        wcdmaEcno = values["wcdma_ecno"]?.toIntOrNull(),
        lteRssi = values["lte_rssi"]?.toIntOrNull(),
        lteRsrp = values["lte_rsrp"]?.toIntOrNull(),
        lteRsrq = values["lte_rsrq"]?.toIntOrNull(),
        lteSinr = values["lte_sinr"]?.toIntOrNull(),
        lteCqi = values["lte_cqi"]?.toIntOrNull(),
        lteTa = values["lte_ta"]?.toIntOrNull(),
        nrSsRsrp = values["nr_ss_rsrp"]?.toIntOrNull(),
        nrSsRsrq = values["nr_ss_rsrq"]?.toIntOrNull(),
        nrSsSinr = values["nr_ss_sinr"]?.toIntOrNull(),
        nrCsiRsrp = values["nr_csi_rsrp"]?.toIntOrNull(),
        nrCsiRsrq = values["nr_csi_rsrq"]?.toIntOrNull(),
        nrCsiSinr = values["nr_csi_sinr"]?.toIntOrNull(),
        signalFluctuationEnabled = values["signal_fluctuation_enabled"]?.toIntOrNull(),
        signalFluctuationRangeDb = values["signal_fluctuation_range_db"]?.toIntOrNull(),
        networkType = values["network_type"]?.toIntOrNull(),
        dataNetworkType = values["data_network_type"]?.toIntOrNull(),
        voiceNetworkType = values["voice_network_type"]?.toIntOrNull(),
        operatorName = values["operator_name"],
        operatorNumeric = values["operator_numeric"],
        simOperator = values["sim_operator"],
        simOperatorName = values["sim_operator_name"],
        simCountryIso = values["sim_country_iso"],
        networkCountryIso = values["network_country_iso"],
        isRoaming = values["is_roaming"]?.toIntOrNull(),
        phoneType = values["phone_type"]?.toIntOrNull(),
        serviceState = values["service_state"]?.toIntOrNull(),
        dataState = values["data_state"]?.toIntOrNull(),
        dataActivity = values["data_activity"]?.toIntOrNull(),
        overrideNetworkType = values["override_network_type"]?.toIntOrNull(),
        band = values["band"]?.toIntOrNull(),
        channelBandwidth = values["channel_bandwidth"]?.toIntOrNull(),
        cellBandwidthDownlink = values["cell_bandwidth_downlink"]?.toIntOrNull(),
        physicalCellId = values["physical_cell_id"]?.toIntOrNull(),
        wifiSsid = values["wifi_ssid"],
        wifiBssid = values["wifi_bssid"],
        wifiRssi = values["wifi_rssi"]?.toIntOrNull(),
        wifiFrequency = values["wifi_frequency"]?.toIntOrNull(),
        wifiLinkSpeed = values["wifi_link_speed"]?.toIntOrNull(),
        wifiTxLinkSpeed = values["wifi_tx_link_speed"]?.toIntOrNull(),
        wifiRxLinkSpeed = values["wifi_rx_link_speed"]?.toIntOrNull(),
        wifiChannel = values["wifi_channel"]?.toIntOrNull(),
        wifiStandard = values["wifi_standard"]?.toIntOrNull(),
        wifiSecurityType = values["wifi_security_type"]?.toIntOrNull(),
        wifiMac = values["wifi_mac"],
        wifiIp = values["wifi_ip"],
        wifiHidden = values["wifi_hidden"]?.toIntOrNull(),
        wifiEnabled = values["wifi_enabled"]?.toIntOrNull(),
        localIpv4 = values["local_ipv4"],
        localIpv6 = values["local_ipv6"],
        dnsPrimary = values["dns_primary"],
        dnsSecondary = values["dns_secondary"],
        gateway = values["gateway"],
        subnetMask = values["subnet_mask"],
        connectionType = values["connection_type"],
        interfaceName = values["interface_name"],
        neighborCellsJson = values["neighbor_cells_json"],
        unavailableFields = UnavailableFieldSet.encode(split.unavailable),
    )
}
