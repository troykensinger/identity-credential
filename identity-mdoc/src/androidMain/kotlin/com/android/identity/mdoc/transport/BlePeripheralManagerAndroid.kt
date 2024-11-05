package com.android.identity.mdoc.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import com.android.identity.cbor.Bstr
import com.android.identity.cbor.Cbor
import com.android.identity.cbor.Tagged
import com.android.identity.crypto.Algorithm
import com.android.identity.crypto.Crypto
import com.android.identity.crypto.EcPublicKey
import com.android.identity.util.AndroidInitializer
import com.android.identity.util.Logger
import com.android.identity.util.UUID
import com.android.identity.util.toHex
import com.android.identity.util.toJavaUuid
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.Error
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class BlePeripheralManagerAndroid: BlePeripheralManager {
    companion object {
        private const val TAG = "BlePeripheralManagerAndroid"
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null

    private var negotiatedMtu = -1
    private var maxCharacteristicSizeMemoized = 0
    private val maxCharacteristicSize: Int
        get() {
            if (maxCharacteristicSizeMemoized > 0) {
                return maxCharacteristicSizeMemoized
            }
            var mtuSize = negotiatedMtu
            if (mtuSize == -1) {
                Logger.w(TAG, "MTU not negotiated, defaulting to 23. Performance will suffer.")
                mtuSize = 23
            }
            maxCharacteristicSizeMemoized = min(512, mtuSize - 3)
            Logger.i(TAG, "Using maxCharacteristicSize $maxCharacteristicSizeMemoized")
            return maxCharacteristicSizeMemoized
        }

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

    override fun setCallbacks(onError: (Throwable) -> Unit, onClosed: () -> Unit) {
        this.onError = onError
        this.onClosed = onClosed
    }

    internal enum class WaitState {
        SERVICE_ADDED,
        STATE_CHARACTERISTIC_WRITTEN,
        START_ADVERTISING,
        CHARACTERISTIC_WRITE_COMPLETED,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
    )

    private var waitFor: WaitFor? = null

    private fun setWaitCondition(
        state: WaitState,
        continuation: CancellableContinuation<Boolean>
    ) {
        check(waitFor == null)
        waitFor = WaitFor(
            state,
            continuation,
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

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private val context = AndroidInitializer.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var gattServer: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var identCharacteristic: BluetoothGattCharacteristic? = null
    private var identValue: ByteArray? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var device: BluetoothDevice? = null

    private val gattServerCallback = object: BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Logger.d(TAG, "onServiceAdded: $status")
            if (waitFor?.state == WaitState.SERVICE_ADDED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    resumeWait()
                } else {
                    resumeWaitWithException(Error("onServiceAdded: Expected GATT_SUCCESS got $status"))
                }
            } else {
                Logger.w(TAG, "onServiceAdded but not waiting")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Logger.d(TAG, "onConnectionStateChange: ${device.address} $status + $newState")
            // TODO: explain why this doesn't work...
            //if (newState == 0) {
            //    onError(Error("Central unexpectedly disconnected"))
            //}
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Logger.d(TAG, "onCharacteristicReadRequest: ${device.address} $requestId " +
                    "$offset ${characteristic.uuid}")
            if (characteristic == identCharacteristic) {
                if (identValue == null) {
                    // TODO: might need to wait for it being set...
                    onError(Error("Received request for ident before it's set.."))
                } else {
                    gattServer!!.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        identValue ?: byteArrayOf()
                    )
                }
            } else {
                Logger.w(TAG, "Read on unexpected characteristic with UUID ${characteristic.uuid}")
            }

        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Logger.d(TAG, "onCharacteristicWriteRequest: ${device.address} $requestId " +
                    "$offset ${characteristic.uuid} ${value.toHex()}")

            if (responseNeeded) {
                gattServer!!.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }

            if (characteristic.uuid == stateCharacteristicUuid.toJavaUuid()) {
                if (value contentEquals byteArrayOf(0x01)) {
                    if (waitFor?.state == WaitState.STATE_CHARACTERISTIC_WRITTEN) {
                        this@BlePeripheralManagerAndroid.device = device
                        // Since the central found us, we can stop advertising....
                        advertiser?.stopAdvertising(advertiseCallback)
                        resumeWait()
                    }
                } else if (value contentEquals byteArrayOf(0x02)) {
                    Logger.i(TAG, "Received transport-specific termination message")
                    runBlocking {
                        incomingMessages.send(byteArrayOf())
                    }
                } else {
                    Logger.w(TAG, "Ignoring unexpected write to state characteristic")
                }
            } else if (characteristic.uuid == client2ServerCharacteristicUuid.toJavaUuid()) {
                try {
                    handleIncomingData(value)
                } catch (e: Throwable) {
                    onError(Error("Error processing incoming data", e))
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (Logger.isDebugEnabled) {
                Logger.d(
                    TAG, "onDescriptorWriteRequest: ${device.address}" +
                            "${descriptor.characteristic.uuid} $offset ${value.toHex()}"
                )
            }
            if (responseNeeded) {
                gattServer!!.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
            Logger.d(TAG, "Negotiated MTU $mtu for $${device.address}")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Logger.d(TAG, "onNotificationSent $status for ${device.address}")
            if (waitFor?.state == WaitState.CHARACTERISTIC_WRITE_COMPLETED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    resumeWaitWithException(Error("onNotificationSent: Expected GATT_SUCCESS but got $status"))
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onNotificationSent but not waiting")
            }
        }
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (waitFor?.state == WaitState.START_ADVERTISING) {
                resumeWait()
            } else {
                Logger.w(TAG, "Unexpected AdvertiseCallback.onStartSuccess() callback")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            if (waitFor?.state == WaitState.START_ADVERTISING) {
                resumeWaitWithException(Error("Started advertising failed with $errorCode"))
            } else {
                Logger.w(TAG, "Unexpected AdvertiseCallback.onStartFailure() callback")
            }
        }
    }

    private var incomingMessage = ByteStringBuilder()

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

    override suspend fun waitForPowerOn() {
        // Not needed on Android
        return
    }

    // This is what the 16-bit UUID 0x29 0x02 is encoded like.
    private var clientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun addCharacteristic(
        characteristicUuid: UUID,
        properties: Int,
        permissions: Int,
    ): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid.toJavaUuid(),
            properties,
            permissions
        )
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            val descriptor = BluetoothGattDescriptor(
                clientCharacteristicConfigUuid.toJavaUuid(),
                BluetoothGattDescriptor.PERMISSION_WRITE
            )
            descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            characteristic.addDescriptor(descriptor)
        }
        service!!.addCharacteristic(characteristic)
        return characteristic
    }

    override suspend fun advertiseService(uuid: UUID) {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        service = BluetoothGattService(
            uuid.toJavaUuid(),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        stateCharacteristic = addCharacteristic(
            characteristicUuid = stateCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY + BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        readCharacteristic = addCharacteristic(
            characteristicUuid = client2ServerCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        writeCharacteristic = addCharacteristic(
            characteristicUuid = server2ClientCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        if (identCharacteristicUuid != null) {
            identCharacteristic = addCharacteristic(
                characteristicUuid = identCharacteristicUuid!!,
                properties = BluetoothGattCharacteristic.PROPERTY_READ,
                permissions = BluetoothGattCharacteristic.PERMISSION_READ
            )
        }
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.SERVICE_ADDED, continuation)

            gattServer!!.addService(service!!)
        }

        advertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            throw Error("Advertiser not available, is Bluetooth off?")
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(uuid.toJavaUuid()))
            .build()
        Logger.d(TAG, "Started advertising UUID $uuid")
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.START_ADVERTISING, continuation)
            advertiser!!.startAdvertising(settings, data, advertiseCallback)
        }
    }

    override suspend fun setESenderKey(eSenderKey: EcPublicKey) {
        val ikm = Cbor.encode(Tagged(24, Bstr(Cbor.encode(eSenderKey.toCoseKey().toDataItem()))))
        val info = "BLEIdent".encodeToByteArray()
        val salt = byteArrayOf()
        identValue = Crypto.hkdf(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
    }

    override suspend fun waitForStateCharacteristicWrite() {
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.STATE_CHARACTERISTIC_WRITTEN, continuation)
        }
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

    private suspend fun writeToCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val rc = gattServer!!.notifyCharacteristicChanged(
                    device!!,
                    characteristic,
                    false,
                    value)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                throw Error("Error notifyCharacteristicChanged on characteristic ${characteristic.uuid} rc=$rc")
            }
        } else {
            characteristic.setValue(value)
            if (!gattServer!!.notifyCharacteristicChanged(
                device!!,
                characteristic,
                false)
            ) {
                throw Error("Error notifyCharacteristicChanged on characteristic ${characteristic.uuid}")
            }
        }
        suspendCancellableCoroutine<Boolean> { continuation ->
            setWaitCondition(WaitState.CHARACTERISTIC_WRITE_COMPLETED, continuation)
        }
    }

    override suspend fun writeToStateCharacteristic(value: Int) {
        writeToCharacteristic(stateCharacteristic!!, byteArrayOf(value.toByte()))
    }

    override fun close() {
        device = null
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.removeService(service)
        gattServer?.close()
        gattServer = null
        service = null
        incomingMessages.close()
    }
}