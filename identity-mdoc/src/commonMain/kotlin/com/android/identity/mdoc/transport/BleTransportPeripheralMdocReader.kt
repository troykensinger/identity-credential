package com.android.identity.mdoc.transport

import com.android.identity.crypto.EcPublicKey
import com.android.identity.mdoc.connectionmethod.ConnectionMethod
import com.android.identity.mdoc.connectionmethod.ConnectionMethodBle
import com.android.identity.util.Logger
import com.android.identity.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class BleTransportPeripheralMdocReader(
    override val role: Role,
    private val centralManager: BleCentralManager,
    private val uuid: UUID
) : MdocTransport() {
    companion object {
        private const val TAG = "BleTransportPeripheralMdocReader"
    }

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val connectionMethod: ConnectionMethod
        get() = ConnectionMethodBle(true, false, uuid, null)

    init {
        centralManager.setUuids(
            stateCharacteristicUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
            client2ServerCharacteristicUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6"),
            server2ClientCharacteristicUuid = UUID.fromString("00000003-a123-48ce-896b-4c76973373e6"),
            identCharacteristicUuid = null,
        )
        centralManager.setCallbacks(
            onError = { error ->
                failTransport(error)
            },
            onClosed = {
                Logger.w(TAG, "BleCentralManager close")
                closeWithoutDelay()
            }
        )
    }

    override suspend fun advertise() {
        // Nothing to do here.
    }

    private var _scanningTime: Duration? = null
    override val scanningTime: Duration?
        get() = _scanningTime

    override suspend fun open(eSenderKey: EcPublicKey) {
        check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
        try {
            _state.value = State.SCANNING
            centralManager.waitForPowerOn()
            val timeScanningStarted = Clock.System.now()
            centralManager.waitForPeripheralWithUuid(uuid)
            _scanningTime = Clock.System.now() - timeScanningStarted
            _state.value = State.CONNECTING
            centralManager.connectToPeripheral()
            centralManager.requestMtu()
            centralManager.peripheralDiscoverServices(uuid)
            centralManager.peripheralDiscoverCharacteristics()
            // NOTE: ident characteristic isn't used when the mdoc is the GATT server so we don't call
            // centralManager.checkReaderIdentMatches(eSenderKey)
            centralManager.subscribeToCharacteristics()
            centralManager.writeToStateCharacteristic(0x01)
            _state.value = State.CONNECTED
        } catch (error: Throwable) {
            failTransport(error)
            throw MdocTransportException("Failed while opening transport", error)
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        try {
            return centralManager.incomingMessages.receive()
        } catch (error: Throwable) {
            if (_state.value == State.CLOSED) {
                throw MdocTransportClosedException("Transport was closed while waiting for message")
            } else {
                failTransport(error)
                throw MdocTransportException("Failed while waiting for message", error)
            }
        }
    }

    override suspend fun sendMessage(message: ByteArray) {
        check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        try {
            if (message.isEmpty()) {
                centralManager.writeToStateCharacteristic(0x02)
            } else {
                centralManager.sendMessage(message)
            }
        } catch (error: Throwable) {
            failTransport(error)
            throw MdocTransportException("Failed while sending message", error)
        }
    }

    private fun failTransport(error: Throwable) {
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        centralManager.close()
        _state.value = State.FAILED
    }

    private fun closeWithoutDelay() {
        centralManager.close()
        _state.value = State.CLOSED
    }

    override suspend fun close() {
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        // TODO: this is a hack but it's to ensure that outgoing writes are flushed...
        delay(500.milliseconds)
        centralManager.close()
        _state.value = State.CLOSED
    }
}
