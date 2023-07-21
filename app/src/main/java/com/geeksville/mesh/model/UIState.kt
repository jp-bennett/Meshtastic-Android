package com.geeksville.mesh.model

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.RemoteException
import android.view.Menu
import androidx.core.content.edit
import androidx.lifecycle.asFlow
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.*
import com.geeksville.mesh.ChannelProtos.ChannelSettings
import com.geeksville.mesh.ClientOnlyProtos.DeviceProfile
import com.geeksville.mesh.ConfigProtos.Config
import com.geeksville.mesh.ModuleConfigProtos.ModuleConfig
import com.geeksville.mesh.database.MeshLogRepository
import com.geeksville.mesh.database.QuickChatActionRepository
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.database.entity.MeshLog
import com.geeksville.mesh.database.entity.QuickChatAction
import com.geeksville.mesh.LocalOnlyProtos.LocalConfig
import com.geeksville.mesh.LocalOnlyProtos.LocalModuleConfig
import com.geeksville.mesh.MeshProtos.User
import com.geeksville.mesh.database.PacketRepository
import com.geeksville.mesh.repository.datastore.RadioConfigRepository
import com.geeksville.mesh.repository.radio.RadioInterfaceService
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.util.GPSFormat
import com.geeksville.mesh.util.positionToMeter
import com.google.protobuf.MessageLite
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/// Given a human name, strip out the first letter of the first three words and return that as the initials for
/// that user. If the original name is only one word, strip vowels from the original name and if the result is
/// 3 or more characters, use the first three characters. If not, just take the first 3 characters of the
/// original name.
fun getInitials(nameIn: String): String {
    val nchars = 4
    val minchars = 2
    val name = nameIn.trim()
    val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }

    val initials = when (words.size) {
        in 0 until minchars -> {
            val nm = if (name.isNotEmpty())
                name.first() + name.drop(1).filterNot { c -> c.lowercase() in "aeiou" }
            else
                ""
            if (nm.length >= nchars) nm else name
        }
        else -> words.map { it.first() }.joinToString("")
    }
    return initials.take(nchars)
}

@HiltViewModel
class UIViewModel @Inject constructor(
    private val app: Application,
    private val radioConfigRepository: RadioConfigRepository,
    private val radioInterfaceService: RadioInterfaceService,
    private val meshLogRepository: MeshLogRepository,
    private val packetRepository: PacketRepository,
    private val quickChatActionRepository: QuickChatActionRepository,
    private val preferences: SharedPreferences
) : ViewModel(), Logging {

    var actionBarMenu: Menu? = null
    var meshService: IMeshService? = null
    val nodeDB = NodeDB(this)

    val bondedAddress get() = radioInterfaceService.getBondedDeviceAddress()
    val selectedBluetooth get() = radioInterfaceService.getDeviceAddress()?.getOrNull(0) == 'x'

    private val _meshLog = MutableStateFlow<List<MeshLog>>(emptyList())
    val meshLog: StateFlow<List<MeshLog>> = _meshLog

    private val _packets = MutableStateFlow<List<Packet>>(emptyList())
    val packets: StateFlow<List<Packet>> = _packets

    private val _localConfig = MutableStateFlow<LocalConfig>(LocalConfig.getDefaultInstance())
    val localConfig: StateFlow<LocalConfig> = _localConfig
    val config get() = _localConfig.value

    private val _moduleConfig = MutableStateFlow<LocalModuleConfig>(LocalModuleConfig.getDefaultInstance())
    val moduleConfig: StateFlow<LocalModuleConfig> = _moduleConfig
    val module get() = _moduleConfig.value

    private val _channels = MutableStateFlow(ChannelSet())
    val channels: StateFlow<ChannelSet> = _channels

    private val _quickChatActions = MutableStateFlow<List<QuickChatAction>>(emptyList())
    val quickChatActions: StateFlow<List<QuickChatAction>> = _quickChatActions

    private val _ourNodeInfo = MutableStateFlow<NodeInfo?>(null)
    val ourNodeInfo: StateFlow<NodeInfo?> = _ourNodeInfo

    private val requestId = MutableStateFlow<Int?>(null)
    private val _packetResponse = MutableStateFlow<MeshLog?>(null)
    val packetResponse: StateFlow<MeshLog?> = _packetResponse

    init {
        viewModelScope.launch {
            meshLogRepository.getAllLogs().collect { logs ->
                _meshLog.value = logs
            }
        }
        viewModelScope.launch {
            packetRepository.getAllPackets().collect { packets ->
                _packets.value = packets
            }
        }
        radioConfigRepository.localConfigFlow.onEach { config ->
            _localConfig.value = config
        }.launchIn(viewModelScope)
        radioConfigRepository.moduleConfigFlow.onEach { config ->
            _moduleConfig.value = config
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            quickChatActionRepository.getAllActions().collect { actions ->
                _quickChatActions.value = actions
            }
        }
        radioConfigRepository.channelSetFlow.onEach { channelSet ->
            _channels.value = ChannelSet(channelSet)
        }.launchIn(viewModelScope)
        combine(nodeDB.nodes.asFlow(), nodeDB.myId.asFlow()) { nodes, id -> nodes[id] }.onEach {
            _ourNodeInfo.value = it
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            combine(meshLogRepository.getAllLogs(9), requestId) { list, id ->
                list.takeIf { id != null }?.firstOrNull { it.meshPacket?.decoded?.requestId == id }
            }.collect { response -> _packetResponse.value = response }
        }

        debug("ViewModel created")
    }

    private val contactKey: MutableStateFlow<String> = MutableStateFlow(DataPacket.ID_BROADCAST)
    fun setContactKey(contact: String) {
        contactKey.value = contact
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: LiveData<List<Packet>> = contactKey.flatMapLatest { contactKey ->
        packetRepository.getMessagesFrom(contactKey)
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val contacts: LiveData<Map<String, Packet>> = _packets.mapLatest { list ->
        list.filter { it.port_num == Portnums.PortNum.TEXT_MESSAGE_APP_VALUE }
            .associateBy { packet -> packet.contact_key }
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val waypoints: LiveData<Map<Int, Packet>> = _packets.mapLatest { list ->
        list.filter { it.port_num == Portnums.PortNum.WAYPOINT_APP_VALUE }
            .associateBy { packet -> packet.data.waypoint!!.id }
            .filterValues { it.data.waypoint!!.expire > System.currentTimeMillis() / 1000 }
    }.asLiveData()

    /**
     * Called immediately after activity observes packetResponse
     */
    fun clearPacketResponse() {
        requestId.value = null
        _packetResponse.value = null
    }

    fun generatePacketId(): Int? {
        return try {
            meshService?.packetId
        } catch (ex: RemoteException) {
            errormsg("RemoteException: ${ex.message}")
            return null
        }
    }

    fun sendMessage(str: String, contactKey: String = "0${DataPacket.ID_BROADCAST}") {
        // contactKey: unique contact key filter (channel)+(nodeId)
        val channel = contactKey[0].digitToIntOrNull()
        val dest = if (channel != null) contactKey.substring(1) else contactKey

        val p = DataPacket(dest, channel ?: 0, str)
        sendDataPacket(p)
    }

    fun sendWaypoint(wpt: MeshProtos.Waypoint, contactKey: String = "0${DataPacket.ID_BROADCAST}") {
        // contactKey: unique contact key filter (channel)+(nodeId)
        val channel = contactKey[0].digitToIntOrNull()
        val dest = if (channel != null) contactKey.substring(1) else contactKey

        val p = DataPacket(dest, channel ?: 0, wpt)
        if (wpt.id != 0) sendDataPacket(p)
    }

    private fun sendDataPacket(p: DataPacket) {
        try {
            meshService?.send(p)
        } catch (ex: RemoteException) {
            errormsg("Send DataPacket error: ${ex.message}")
        }
    }

    private fun request(
        destNum: Int,
        requestAction: suspend (IMeshService, Int, Int) -> Unit,
        errorMessage: String,
    ) = viewModelScope.launch {
        meshService?.let { service ->
            val packetId = service.packetId
            try {
                requestAction(service, packetId, destNum)
                requestId.value = packetId
            } catch (ex: RemoteException) {
                errormsg("$errorMessage: ${ex.message}")
            }
        }
    }

    fun getOwner(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteOwner(packetId, dest) },
        "Request getOwner error"
    )

    fun getChannel(destNum: Int, index: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteChannel(packetId, dest, index) },
        "Request getChannel error"
    )

    fun getConfig(destNum: Int, configType: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRemoteConfig(packetId, dest, configType) },
        "Request getConfig error",
    )

    fun getModuleConfig(destNum: Int, configType: Int) = request(
        destNum,
        { service, packetId, dest -> service.getModuleConfig(packetId, dest, configType) },
        "Request getModuleConfig error",
    )

    fun setRingtone(destNum: Int, ringtone: String) {
        meshService?.setRingtone(destNum, ringtone)
    }

    fun getRingtone(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getRingtone(packetId, dest) },
        "Request getRingtone error"
    )

    fun setCannedMessages(destNum: Int, messages: String) {
        meshService?.setCannedMessages(destNum, messages)
    }

    fun getCannedMessages(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.getCannedMessages(packetId, dest) },
        "Request getCannedMessages error"
    )

    fun requestTraceroute(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestTraceroute(packetId, dest) },
        "Request traceroute error"
    )

    fun requestShutdown(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestShutdown(packetId, dest) },
        "Request shutdown error"
    )

    fun requestReboot(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestReboot(packetId, dest) },
        "Request reboot error"
    )

    fun requestFactoryReset(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestFactoryReset(packetId, dest) },
        "Request factory reset error"
    )

    fun requestNodedbReset(destNum: Int) = request(
        destNum,
        { service, packetId, dest -> service.requestNodedbReset(packetId, dest) },
        "Request NodeDB reset error"
    )

    fun requestPosition(destNum: Int, position: Position = Position(0.0, 0.0, 0)) {
        try {
            meshService?.requestPosition(destNum, position)
        } catch (ex: RemoteException) {
            errormsg("Request position error: ${ex.message}")
        }
    }

    fun deleteAllLogs() = viewModelScope.launch(Dispatchers.IO) {
        meshLogRepository.deleteAll()
    }

    fun deleteAllMessages() = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteAllMessages()
    }

    fun deleteMessages(uuidList: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteMessages(uuidList)
    }

    fun deleteWaypoint(id: Int) = viewModelScope.launch(Dispatchers.IO) {
        packetRepository.deleteWaypoint(id)
    }

    companion object {
        fun getPreferences(context: Context): SharedPreferences =
            context.getSharedPreferences("ui-prefs", Context.MODE_PRIVATE)
    }

    /// Connection state to our radio device
    private val _connectionState = MutableLiveData(MeshService.ConnectionState.DISCONNECTED)
    val connectionState: LiveData<MeshService.ConnectionState> get() = _connectionState

    fun isConnected() = _connectionState.value != MeshService.ConnectionState.DISCONNECTED

    fun setConnectionState(connectionState: MeshService.ConnectionState) {
        _connectionState.value = connectionState
    }

    private val _requestChannelUrl = MutableLiveData<Uri?>(null)
    val requestChannelUrl: LiveData<Uri?> get() = _requestChannelUrl

    fun setRequestChannelUrl(channelUrl: Uri) {
        _requestChannelUrl.value = channelUrl
    }

    /**
     * Called immediately after activity observes requestChannelUrl
     */
    fun clearRequestChannelUrl() {
        _requestChannelUrl.value = null
    }

    var txEnabled: Boolean
        get() = config.lora.txEnabled
        set(value) {
            updateLoraConfig { it.copy { txEnabled = value } }
        }

    var region: Config.LoRaConfig.RegionCode
        get() = config.lora.region
        set(value) {
            updateLoraConfig { it.copy { region = value } }
        }

    fun gpsString(p: Position): String {
        return when (config.display.gpsFormat) {
            Config.DisplayConfig.GpsCoordinateFormat.DEC -> GPSFormat.DEC(p)
            Config.DisplayConfig.GpsCoordinateFormat.DMS -> GPSFormat.DMS(p)
            Config.DisplayConfig.GpsCoordinateFormat.UTM -> GPSFormat.UTM(p)
            Config.DisplayConfig.GpsCoordinateFormat.MGRS -> GPSFormat.MGRS(p)
            else -> GPSFormat.DEC(p)
        }
    }

    // managed mode disables all access to configuration
    val isManaged: Boolean get() = config.device.isManaged

    /// hardware info about our local device (can be null)
    private val _myNodeInfo = MutableLiveData<MyNodeInfo?>()
    val myNodeInfo: LiveData<MyNodeInfo?> get() = _myNodeInfo
    val myNodeNum get() = _myNodeInfo.value?.myNodeNum

    fun setMyNodeInfo(info: MyNodeInfo?) {
        _myNodeInfo.value = info
    }

    override fun onCleared() {
        super.onCleared()
        debug("ViewModel cleared")
    }

    /// Pull our latest node db from the device
    fun updateNodesFromDevice() {
        meshService?.let { service ->
            // Update our nodeinfos based on data from the device
            val nodes = service.nodes.associateBy { it.user?.id!! }
            nodeDB.setNodes(nodes)

            try {
                // Pull down our real node ID - This must be done AFTER reading the nodedb because we need the DB to find our nodeinof object
                nodeDB.setMyId(service.myId)
            } catch (ex: Exception) {
                warn("Ignoring failure to get myId, service is probably just uninited... ${ex.message}")
            }
        }
    }

    private inline fun updateLoraConfig(crossinline body: (Config.LoRaConfig) -> Config.LoRaConfig) {
        val data = body(config.lora)
        setConfig(config { lora = data })
    }

    // Set the radio config (also updates our saved copy in preferences)
    fun setConfig(config: Config) {
        meshService?.setConfig(config.toByteArray())
    }

    fun setRemoteConfig(destNum: Int, config: Config) {
        meshService?.setRemoteConfig(destNum, config.toByteArray())
    }

    fun setModuleConfig(destNum: Int, config: ModuleConfig) {
        meshService?.setModuleConfig(destNum, config.toByteArray())
    }

    fun setModuleConfig(config: ModuleConfig) {
        setModuleConfig(myNodeNum ?: return, config)
    }

    /**
     * Updates channels to match the [new] list. Only channels with changes are updated.
     *
     * @param destNum Destination node number.
     * @param old The current [ChannelSettings] list.
     * @param new The updated [ChannelSettings] list.
     */
    fun updateChannels(
        destNum: Int,
        old: List<ChannelSettings>,
        new: List<ChannelSettings>,
    ) {
        buildList {
            for (i in 0..maxOf(old.lastIndex, new.lastIndex)) {
                if (old.getOrNull(i) != new.getOrNull(i)) add(channel {
                    role = when (i) {
                        0 -> ChannelProtos.Channel.Role.PRIMARY
                        in 1..new.lastIndex -> ChannelProtos.Channel.Role.SECONDARY
                        else -> ChannelProtos.Channel.Role.DISABLED
                    }
                    index = i
                    settings = new.getOrNull(i) ?: channelSettings { }
                })
            }
        }.forEach { setRemoteChannel(destNum, it) }

        if (destNum == myNodeNum) viewModelScope.launch {
            radioConfigRepository.replaceAllSettings(new)
        }
    }

    private fun updateChannels(
        old: List<ChannelSettings>,
        new: List<ChannelSettings>
    ) {
        updateChannels(myNodeNum ?: return, old, new)
    }

    /**
     * Convert the [channels] array to and from [ChannelSet]
     */
    private var _channelSet: AppOnlyProtos.ChannelSet
        get() = channels.value.protobuf
        set(value) {
            updateChannels(channelSet.settingsList, value.settingsList)

            val newConfig = config { lora = value.loraConfig }
            if (config.lora != newConfig.lora) setConfig(newConfig)
        }
    val channelSet get() = _channelSet

    /// Set the radio config (also updates our saved copy in preferences)
    fun setChannels(channelSet: ChannelSet) {
        this._channelSet = channelSet.protobuf
    }

    private fun setRemoteChannel(destNum: Int, channel: ChannelProtos.Channel) {
        try {
            meshService?.setRemoteChannel(destNum, channel.toByteArray())
        } catch (ex: RemoteException) {
            errormsg("Can't set channel on radio ${ex.message}")
        }
    }

    val provideLocation = object : MutableLiveData<Boolean>(preferences.getBoolean("provide-location", false)) {
        override fun setValue(value: Boolean) {
            super.setValue(value)

            preferences.edit {
                this.putBoolean("provide-location", value)
            }
        }
    }

    fun setOwner(user: User) {
        setRemoteOwner(myNodeNum ?: return, user)
    }

    fun setRemoteOwner(destNum: Int, user: User) {
        try {
            // Note: we use ?. here because we might be running in the emulator
            meshService?.setRemoteOwner(destNum, user.toByteArray())
        } catch (ex: RemoteException) {
            errormsg("Can't set username on device, is device offline? ${ex.message}")
        }
    }

    val adminChannelIndex: Int /** matches [MeshService.adminChannelIndex] **/
        get() = channelSet.settingsList.indexOfFirst { it.name.equals("admin", ignoreCase = true) }
            .coerceAtLeast(0)

    /**
     * Write the persisted packet data out to a CSV file in the specified location.
     */
    fun saveMessagesCSV(file_uri: Uri) {
        viewModelScope.launch(Dispatchers.Main) {
            // Extract distances to this device from position messages and put (node,SNR,distance) in
            // the file_uri
            val myNodeNum = myNodeNum ?: return@launch

            // Capture the current node value while we're still on main thread
            val nodes = nodeDB.nodes.value ?: emptyMap()

            val positionToPos: (MeshProtos.Position?) -> Position? = { meshPosition ->
                meshPosition?.let { Position(it) }.takeIf {
                    it?.isValid() == true
                }
            }

            writeToUri(file_uri) { writer ->
                // Create a map of nodes keyed by their ID
                val nodesById = nodes.values.associateBy { it.num }.toMutableMap()
                val nodePositions = mutableMapOf<Int, MeshProtos.Position?>()

                writer.appendLine("date,time,from,sender name,sender lat,sender long,rx lat,rx long,rx elevation,rx snr,distance,hop limit,payload")

                // Packets are ordered by time, we keep most recent position of
                // our device in localNodePosition.
                val dateFormat = SimpleDateFormat("yyyy-MM-dd,HH:mm:ss", Locale.getDefault())
                meshLogRepository.getAllLogsInReceiveOrder(Int.MAX_VALUE).first().forEach { packet ->
                    // If we get a NodeInfo packet, use it to update our position data (if valid)
                    packet.nodeInfo?.let { nodeInfo ->
                        positionToPos.invoke(nodeInfo.position)?.let {
                            nodePositions[nodeInfo.num] = nodeInfo.position
                        }
                    }

                    packet.meshPacket?.let { proto ->
                        // If the packet contains position data then use it to update, if valid
                        packet.position?.let { position ->
                            positionToPos.invoke(position)?.let {
                                nodePositions[proto.from] = position
                            }
                        }

                        // Filter out of our results any packet that doesn't report SNR.  This
                        // is primarily ADMIN_APP.
                        if (proto.rxSnr != 0.0f) {
                            val rxDateTime = dateFormat.format(packet.received_date)
                            val rxFrom = proto.from.toUInt()
                            val senderName = nodesById[proto.from]?.user?.longName ?: ""

                            // sender lat & long
                            val senderPosition = nodePositions[proto.from]
                            val senderPos = positionToPos.invoke(senderPosition)
                            val senderLat = senderPos?.latitude ?: ""
                            val senderLong = senderPos?.longitude ?: ""

                            // rx lat, long, and elevation
                            val rxPosition = nodePositions[myNodeNum]
                            val rxPos = positionToPos.invoke(rxPosition)
                            val rxLat = rxPos?.latitude ?: ""
                            val rxLong = rxPos?.longitude ?: ""
                            val rxAlt = rxPos?.altitude ?: ""
                            val rxSnr = "%f".format(proto.rxSnr)

                            // Calculate the distance if both positions are valid

                            val dist = if (senderPos == null || rxPos == null) {
                                ""
                            } else {
                                positionToMeter(
                                    rxPosition!!, // Use rxPosition but only if rxPos was valid
                                    senderPosition!! // Use senderPosition but only if senderPos was valid
                                ).roundToInt().toString()
                            }

                            val hopLimit = proto.hopLimit

                            val payload = when {
                                proto.decoded.portnumValue != Portnums.PortNum.TEXT_MESSAGE_APP_VALUE -> "<${proto.decoded.portnum}>"
                                proto.hasDecoded() -> "\"" + proto.decoded.payload.toStringUtf8()
                                    .replace("\"", "\\\"") + "\""
                                proto.hasEncrypted() -> "${proto.encrypted.size()} encrypted bytes"
                                else -> ""
                            }

                            //  date,time,from,sender name,sender lat,sender long,rx lat,rx long,rx elevation,rx snr,distance,hop limit,payload
                            writer.appendLine("$rxDateTime,$rxFrom,$senderName,$senderLat,$senderLong,$rxLat,$rxLong,$rxAlt,$rxSnr,$dist,$hopLimit,$payload")
                        }
                    }
                }
            }
        }
    }

    private suspend inline fun writeToUri(uri: Uri, crossinline block: suspend (BufferedWriter) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                app.contentResolver.openFileDescriptor(uri, "wt")?.use { parcelFileDescriptor ->
                    FileWriter(parcelFileDescriptor.fileDescriptor).use { fileWriter ->
                        BufferedWriter(fileWriter).use { writer ->
                            block.invoke(writer)
                        }
                    }
                }
            } catch (ex: FileNotFoundException) {
                errormsg("Can't write file error: ${ex.message}")
            }
        }
    }

    private val _deviceProfile = MutableStateFlow<DeviceProfile?>(null)
    val deviceProfile: StateFlow<DeviceProfile?> = _deviceProfile

    fun setDeviceProfile(deviceProfile: DeviceProfile?) {
        _deviceProfile.value = deviceProfile
    }

    fun importProfile(file_uri: Uri) = viewModelScope.launch(Dispatchers.Main) {
        withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(file_uri).use { inputStream ->
                val bytes = inputStream?.readBytes()
                val protobuf = DeviceProfile.parseFrom(bytes)
                _deviceProfile.value = protobuf
            }
        }
    }

    fun exportProfile(file_uri: Uri) = viewModelScope.launch {
        val profile = deviceProfile.value ?: return@launch
        writeToUri(file_uri, profile)
        _deviceProfile.value = null
    }

    private suspend fun writeToUri(uri: Uri, message: MessageLite) = withContext(Dispatchers.IO) {
        try {
            app.contentResolver.openFileDescriptor(uri, "wt")?.use { parcelFileDescriptor ->
                FileOutputStream(parcelFileDescriptor.fileDescriptor).use { outputStream ->
                    message.writeTo(outputStream)
                }
            }
        } catch (ex: FileNotFoundException) {
            errormsg("Can't write file error: ${ex.message}")
        }
    }

    fun installProfile(protobuf: DeviceProfile) = with(protobuf) {
        _deviceProfile.value = null
        // meshService?.beginEditSettings()
        if (hasLongName() || hasShortName()) ourNodeInfo.value?.user?.let {
            val user = it.copy(
                longName = if (hasLongName()) longName else it.longName,
                shortName = if (hasShortName()) shortName else it.shortName
            )
            setOwner(user.toProto())
        }
        if (hasChannelUrl()) {
            setChannels(ChannelSet(Uri.parse(channelUrl)))
        }
        if (hasConfig()) {
            setConfig(config { device = config.device })
            setConfig(config { position = config.position })
            setConfig(config { power = config.power })
            setConfig(config { network = config.network })
            setConfig(config { display = config.display })
            setConfig(config { lora = config.lora })
            setConfig(config { bluetooth = config.bluetooth })
        }
        if (hasModuleConfig()) moduleConfig.let {
            setModuleConfig(moduleConfig { mqtt = it.mqtt })
            setModuleConfig(moduleConfig { serial = it.serial })
            setModuleConfig(moduleConfig { externalNotification = it.externalNotification })
            setModuleConfig(moduleConfig { storeForward = it.storeForward })
            setModuleConfig(moduleConfig { rangeTest = it.rangeTest })
            setModuleConfig(moduleConfig { telemetry = it.telemetry })
            setModuleConfig(moduleConfig { cannedMessage = it.cannedMessage })
            setModuleConfig(moduleConfig { audio = it.audio })
            setModuleConfig(moduleConfig { remoteHardware = it.remoteHardware })
        }
        // meshService?.commitEditSettings()
    }

    fun addQuickChatAction(name: String, value: String, mode: QuickChatAction.Mode) {
        viewModelScope.launch(Dispatchers.Main) {
            val action = QuickChatAction(0, name, value, mode, _quickChatActions.value.size)
            quickChatActionRepository.insert(action)
        }
    }

    fun deleteQuickChatAction(action: QuickChatAction) {
        viewModelScope.launch(Dispatchers.Main) {
            quickChatActionRepository.delete(action)
        }
    }

    fun updateQuickChatAction(
        action: QuickChatAction,
        name: String?,
        message: String?,
        mode: QuickChatAction.Mode?
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            val newAction = QuickChatAction(
                action.uuid,
                name ?: action.name,
                message ?: action.message,
                mode ?: action.mode,
                action.position
            )
            quickChatActionRepository.update(newAction)
        }
    }

    fun updateActionPositions(actions: List<QuickChatAction>) {
        viewModelScope.launch(Dispatchers.Main) {
            for (position in actions.indices) {
                quickChatActionRepository.setItemPosition(actions[position].uuid, position)
            }
        }
    }
}

