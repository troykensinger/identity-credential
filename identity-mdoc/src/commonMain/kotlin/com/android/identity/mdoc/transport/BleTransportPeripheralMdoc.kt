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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class BleTransportPeripheralMdoc(
    override val role: Role,
    private val peripheralManager: BlePeripheralManager,
    private val uuid: UUID
) : MdocTransport() {
    companion object {
        private const val TAG = "BleTransportPeripheralMdoc"
    }

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val connectionMethod: ConnectionMethod
        get() = ConnectionMethodBle(true, false, uuid, null)

    init {
        peripheralManager.setUuids(
            stateCharacteristicUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
            client2ServerCharacteristicUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6"),
            server2ClientCharacteristicUuid = UUID.fromString("00000003-a123-48ce-896b-4c76973373e6"),
            identCharacteristicUuid = null,
        )
        peripheralManager.setCallbacks(
            onError = { error ->
                failTransport(error)
            },
            onClosed = {
                Logger.w(TAG, "BlePeripheralManager close")
                closeWithoutDelay()
            }
        )
    }

    override suspend fun advertise() {
        check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
        peripheralManager.waitForPowerOn()
        peripheralManager.advertiseService(uuid)
        _state.value = State.ADVERTISING
    }

    override val scanningTime: Duration?
        get() = null

    override suspend fun open(eSenderKey: EcPublicKey) {
        check(_state.value == State.IDLE || _state.value == State.ADVERTISING) {
            "Expected state IDLE or ADVERTISING, got ${_state.value}"
        }
        try {
            val alreadyAdvertising = (_state.value == State.ADVERTISING)
            if (!alreadyAdvertising) {
                _state.value = State.ADVERTISING
                peripheralManager.waitForPowerOn()
                peripheralManager.advertiseService(uuid)
            }
            peripheralManager.setESenderKey(eSenderKey)
            // Note: It's not really possible to know someone is connecting to use until they're _actually_
            // connected. I mean, for all we know, someone could be BLE scanning us. So not really possible
            // to go into State.CONNECTING...
            peripheralManager.waitForStateCharacteristicWrite()
            _state.value = State.CONNECTED
        } catch (error: Throwable) {
            failTransport(error)
            throw MdocTransportException("Failed while opening transport", error)
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        try {
            return peripheralManager.incomingMessages.receive()
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
                peripheralManager.writeToStateCharacteristic(0x02)
            } else {
                peripheralManager.sendMessage(message)
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
        peripheralManager.close()
        _state.value = State.FAILED
    }

    private fun closeWithoutDelay() {
        peripheralManager.close()
        _state.value = State.CLOSED
    }

    override suspend fun close() {
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        // TODO: this is a hack but it's to ensure that outgoing writes are flushed...
        delay(500.milliseconds)
        peripheralManager.close()
        _state.value = State.CLOSED
    }
}
