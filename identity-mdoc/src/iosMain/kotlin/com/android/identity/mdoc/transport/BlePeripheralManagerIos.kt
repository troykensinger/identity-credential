package com.android.identity.mdoc.transport

import com.android.identity.cbor.Bstr
import com.android.identity.cbor.Cbor
import com.android.identity.cbor.Tagged
import com.android.identity.crypto.Algorithm
import com.android.identity.crypto.Crypto
import com.android.identity.crypto.EcPublicKey
import com.android.identity.util.Logger
import com.android.identity.util.UUID
import com.android.identity.util.toByteArray
import com.android.identity.util.toHex
import com.android.identity.util.toNSData
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.bytestring.ByteStringBuilder
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBUUID
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.Foundation.NSArray
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class BlePeripheralManagerIos: BlePeripheralManager {
    companion object {
        private const val TAG = "BlePeripheralManagerIos"
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null

    override fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
    ) {
        this.stateCharacteristicUuid = stateCharacteristicUuid
        this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
        this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
        this.identCharacteristicUuid = identCharacteristicUuid
    }

    private lateinit var onError: (error: Throwable) -> Unit
    private lateinit var onClosed: () -> Unit

    override fun setCallbacks(
        onError: (Throwable) -> Unit,
        onClosed: () -> Unit
    ) {
        this.onError = onError
        this.onClosed = onClosed
    }

    internal enum class WaitState {
        POWER_ON,
        WAIT_FOR_STATE_CHARACTERISTIC_WRITE,
        CHARACTERISTIC_READY_TO_WRITE,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
        val characteristic: CBCharacteristic? = null,
    )

    private var waitFor: WaitFor? = null

    private fun setWaitCondition(
        state: WaitState,
        continuation: CancellableContinuation<Boolean>,
        characteristic: CBCharacteristic? = null
    ) {
        check(waitFor == null)
        waitFor = WaitFor(
            state,
            continuation,
            characteristic
        )
    }

    private fun resumeWait() {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resume(true)
    }

    private fun resumeWaitWithException(exception: Throwable) {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resumeWithException(exception)
    }

    private var maxCharacteristicSize = -1

    private var incomingMessage = ByteStringBuilder()

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private fun handleIncomingData(chunk: ByteArray) {
        if (chunk.size < 1) {
            throw Error("Invalid data length ${chunk.size} for Client2Server characteristic")
        }
        incomingMessage.append(chunk, 1, chunk.size)
        when {
            chunk[0].toInt() == 0x00 -> {
                // Last message.
                val newMessage = incomingMessage.toByteString().toByteArray()
                incomingMessage = ByteStringBuilder()
                runBlocking {
                    incomingMessages.send(newMessage)
                }
            }

            chunk[0].toInt() == 0x01 -> {
                if (chunk.size != maxCharacteristicSize) {
                    Logger.w(TAG, "Client2Server received ${chunk.size} bytes which is not the " +
                            "expected $maxCharacteristicSize bytes")
                }
            }

            else -> {
                throw Error("Invalid first byte ${chunk[0]} in Client2Server data chunk, " +
                            "expected 0 or 1")
            }
        }
    }

    private val peripheralManager: CBPeripheralManager

    private val peripheralManagerDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (waitFor?.state == WaitState.POWER_ON) {
                if (peripheralManager.state == CBPeripheralManagerStatePoweredOn) {
                    resumeWait()
                } else {
                    resumeWaitWithException(Error("Excepted poweredOn, got ${peripheralManager.state}"))
                }
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            for (attRequest in didReceiveWriteRequests) {
                attRequest as CBATTRequest

                if (attRequest.characteristic == stateCharacteristic) {
                    val data = attRequest.value?.toByteArray() ?: byteArrayOf()
                    if (waitFor?.state == WaitState.WAIT_FOR_STATE_CHARACTERISTIC_WRITE) {
                        if (!(data contentEquals byteArrayOf(0x01))) {
                            resumeWaitWithException(
                                Error("Expected 0x01 to be written to state characteristic, got ${data.toHex()}")
                            )
                        } else {
                            // Now that the central connected, figure out how big the buffer is for writes.
                            val maximumUpdateValueLength = attRequest.central.maximumUpdateValueLength.toInt()
                            maxCharacteristicSize = min(maximumUpdateValueLength, 512)
                            Logger.i(TAG, "Using $maxCharacteristicSize as maximum data size for characteristics")

                            // Since the central found us, we can stop advertising....
                            peripheralManager.stopAdvertising()

                            resumeWait()
                        }
                    } else {
                        if (data contentEquals byteArrayOf(0x02)) {
                            Logger.i(TAG, "Received transport-specific termination message")
                            runBlocking {
                                incomingMessages.send(byteArrayOf())
                            }
                        } else {
                            Logger.w(TAG, "Got write to state characteristics without waiting for it")
                        }
                    }
                } else if (attRequest.characteristic == readCharacteristic) {
                    val data = attRequest.value?.toByteArray() ?: byteArrayOf()
                    try {
                        handleIncomingData(data)
                    } catch (e: Throwable) {
                        onError(Error("Error processing incoming data", e))
                    }
                } else {
                    Logger.w(TAG, "Unexpected write to characteristic with UUID " +
                            attRequest.characteristic.UUID.UUIDString)
                }
            }
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
            val attRequest = didReceiveReadRequest
            if (attRequest.characteristic == identCharacteristic) {
                if (identValue == null) {
                    // TODO: might need to wait for it being set...
                    onError(Error("Received request for ident before it's set.."))
                } else {
                    attRequest.value = identValue!!.toNSData()
                    peripheralManager.respondToRequest(attRequest, CBATTErrorSuccess)
                }
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            if (waitFor?.state == WaitState.CHARACTERISTIC_READY_TO_WRITE) {
                resumeWait()
            }
        }

    }

    init {
        peripheralManager = CBPeripheralManager(
            delegate = peripheralManagerDelegate,
            queue = null,
            options = null
        )
    }

    override suspend fun waitForPowerOn() {
        if (peripheralManager.state != CBPeripheralManagerStatePoweredOn) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.POWER_ON, continuation)
            }
        }
    }

    private var service: CBMutableService? = null

    private var readCharacteristic: CBMutableCharacteristic? = null
    private var writeCharacteristic: CBMutableCharacteristic? = null
    private var stateCharacteristic: CBMutableCharacteristic? = null
    private var identCharacteristic: CBMutableCharacteristic? = null
    private var identValue: ByteArray? = null

    override suspend fun advertiseService(uuid: UUID) {
        service = CBMutableService(
            type = CBUUID.UUIDWithString(uuid.toString()),
            primary = true
        )
        stateCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(stateCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify +
                    CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        readCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(client2ServerCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        writeCharacteristic = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(server2ClientCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify,
            value = null,
            permissions = CBAttributePermissionsReadable + CBAttributePermissionsWriteable,
        )
        if (identCharacteristicUuid != null) {
            identCharacteristic = CBMutableCharacteristic(
                type = CBUUID.UUIDWithString(identCharacteristicUuid.toString()),
                properties = CBCharacteristicPropertyRead,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
        }
        service!!.setCharacteristics(
            (service!!.characteristics ?: listOf<CBMutableCharacteristic>()) +
            listOf(
                stateCharacteristic,
                readCharacteristic,
                writeCharacteristic,
            ) +
            if (identCharacteristic != null) listOf(identCharacteristic) else listOf()
        )
        peripheralManager.addService(service!!)
        peripheralManager.startAdvertising(
            mapOf(
                CBAdvertisementDataServiceUUIDsKey to
                        (listOf(CBUUID.UUIDWithString(uuid.toString())) as NSArray)
            )
        )
    }

    override suspend fun setESenderKey(eSenderKey: EcPublicKey) {
        val ikm = Cbor.encode(Tagged(24, Bstr(Cbor.encode(eSenderKey.toCoseKey().toDataItem()))))
        val info = "BLEIdent".encodeToByteArray()
        val salt = byteArrayOf()
        identValue = Crypto.hkdf(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
    }

    override suspend fun waitForStateCharacteristicWrite() {
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.WAIT_FOR_STATE_CHARACTERISTIC_WRITE, continuation)
        }
    }

    private suspend fun writeToCharacteristic(
        characteristic: CBMutableCharacteristic,
        value: ByteArray,
    ) {
        while (true) {
            val wasSent = peripheralManager.updateValue(
                value = value.toNSData(),
                forCharacteristic = characteristic,
                onSubscribedCentrals = null
            )
            if (wasSent) {
                Logger.i(TAG, "Wrote to characteristic ${characteristic.UUID}")
                break
            }
            Logger.i(TAG, "Not ready to send to characteristic ${characteristic.UUID}, waiting")
            suspendCancellableCoroutine<Boolean> { continuation ->
                setWaitCondition(WaitState.CHARACTERISTIC_READY_TO_WRITE, continuation)
            }
        }
    }

    override suspend fun writeToStateCharacteristic(value: Int) {
        writeToCharacteristic(stateCharacteristic!!, byteArrayOf(value.toByte()))
    }

    override suspend fun sendMessage(message: ByteArray) {
        Logger.i(TAG, "sendMessage ${message.size} length")
        val maxChunkSize = maxCharacteristicSize - 1  // Need room for the leading 0x00 or 0x01
        var offset = 0
        do {
            val moreDataComing = offset + maxChunkSize < message.size
            var size = message.size - offset
            if (size > maxChunkSize) {
                size = maxChunkSize
            }
            val builder = ByteStringBuilder(size + 1)
            builder.append(if (moreDataComing) 0x01 else 0x00)
            builder.append(message, offset, offset + size)
            val chunk = builder.toByteString().toByteArray()

            writeToCharacteristic(writeCharacteristic!!, chunk)

            offset += size
        } while (offset < message.size)
        Logger.i(TAG, "sendMessage completed")
    }

    override fun close() {
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        service = null
        peripheralManager.delegate = null
        incomingMessages.close()
    }

}

