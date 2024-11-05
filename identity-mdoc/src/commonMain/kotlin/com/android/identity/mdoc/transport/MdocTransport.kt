package com.android.identity.mdoc.transport

import com.android.identity.crypto.EcPublicKey
import com.android.identity.mdoc.connectionmethod.ConnectionMethod
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * An abstraction of a ISO/IEC 18013-5:2021 device retrieval method.
 *
 * Transports are created using [MdocTransportFactory] by passing in a [ConnectionMethod].
 *
 * A [MdocTransport]'s state can be tracked in the [state] property which is [State.IDLE]
 * when constructed from the factory. To open a connection to the other peer, call [open].
 * This changes the state to [State.CONNECTING] and when the connection has been established
 * to [State.CONNECTED]. At this point, the application can use [sendMessage] and
 * [waitForMessage] to exchange messages with the peer.
 *
 * The transport can fail at any time, for example if the other peer sends invalid data
 * or actively disconnects. In this case the state is changed to [State.FAILED] and
 * any calls except for [close] will fail with the [MdocTransportException] exception.
 *
 * The connection can be closed at any time using the [close] method which will transition
 * the state to [State.CLOSED] except if it's already in [State.FAILED].
 *
 * [MdocTransport] instances are thread-safe and methods and properties can be called from
 * any thread or coroutine.
 */
abstract class MdocTransport {
    /**
     * The role of the transport
     */
    enum class Role {
        /** The transport is being used by an _mdoc_. */
        MDOC,

        /** The transport is being used by an _mdoc reader_. */
        MDOC_READER
    }

    /**
     * Possible states for a transport.
     */
    enum class State {
        /** The transport is idle. */
        IDLE,

        /** The transport is being advertised. */
        ADVERTISING,

        /** The transport is scanning. */
        SCANNING,

        /** A remote peer has been identified and the connection is being set up. */
        CONNECTING,

        /** The transport is connected to the remote peer. */
        CONNECTED,

        /** The transport was connected at one point but one of the sides closed the connection. */
        CLOSED,

        /** The connection to the remote peer failed. */
        FAILED
    }

    /**
     * The current state of the transport.
     */
    abstract val state: StateFlow<State>

    /**
     * The role which the transport is for.
     */
    abstract val role: Role

    /**
     * A [ConnectionMethod] which can be sent to the other peer to connect to.
     */
    abstract val connectionMethod: ConnectionMethod

    /**
     * The time spent scanning for the other peer.
     *
     * This is always `null` until [open] completes and it's only set for transports that actually perform active
     * scanning for the other peer. This includes _BLE mdoc central client mode_ for [Role.MDOC] and
     * _BLE mdoc peripheral server mode_ for [Role.MDOC_READER].
     */
    abstract val scanningTime: Duration?

    /**
     * Starts advertising the connection.
     *
     * TODO
     *
     * This is optional for transports to implement.
     */
    abstract suspend fun advertise()

    /**
     * Opens the connection to the other peer.
     *
     * @param eSenderKey This should be set to `EDeviceKey` if using forward engagement or
     * `EReaderKey` if using reverse engagement.
     */
    abstract suspend fun open(eSenderKey: EcPublicKey)

    /**
     * Sends a message to the other peer.
     *
     * This should be formatted as `SessionEstablishment` or `SessionData` according to
     * ISO/IEC 18013-5:2021.
     *
     * This blocks the calling coroutine until the message is sent.
     *
     * @param message the message to send.
     */
    abstract suspend fun sendMessage(message: ByteArray)

    /**
     * Waits for the other peer to send a message.
     *
     * This received message should be formatted as `SessionEstablishment` or `SessionData`
     * according to ISO/IEC 18013-5:2021. Transport-specific session termination is indicated
     * by the returned message being empty.
     *
     * @return the message that was received or `null` if the connection was closed.
     * @throws MdocTransportClosedException if [close] was called from another coroutine while waiting.
     * @throws MdocTransportException if an unrecoverable error occurs.
     */
    abstract suspend fun waitForMessage(): ByteArray

    /**
     * Closes the connection.
     *
     * This can be called from any thread.
     */
    abstract suspend fun close()
}
