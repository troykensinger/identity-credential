package com.android.identity.mdoc.transport

import com.android.identity.crypto.EcPublicKey
import com.android.identity.util.UUID
import kotlinx.coroutines.channels.Channel

interface BleCentralManager {
    val incomingMessages: Channel<ByteArray>
    
    fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
    )

    fun setCallbacks(
        /**
         * Called if an error occurs asynchronously and the error isn't bubbled back
         * to one of the methods on this object.
         */
        onError: (error: Throwable) -> Unit,

        /**
         * Called on transport-specific termination.
         */
        onClosed: () -> Unit
    )

    suspend fun waitForPowerOn()

    suspend fun waitForPeripheralWithUuid(uuid: UUID)

    suspend fun connectToPeripheral()

    suspend fun requestMtu()

    suspend fun peripheralDiscoverServices(uuid: UUID)

    suspend fun peripheralDiscoverCharacteristics()

    suspend fun checkReaderIdentMatches(eSenderKey: EcPublicKey)

    suspend fun subscribeToCharacteristics()

    suspend fun writeToStateCharacteristic(value: Int)

    suspend fun sendMessage(message: ByteArray)

    fun close()
}