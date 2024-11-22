package com.android.identity.mdoc.transport

import com.android.identity.mdoc.connectionmethod.ConnectionMethod
import com.android.identity.mdoc.connectionmethod.ConnectionMethodBle

actual class MdocTransportFactory {
    actual companion object {
        actual fun createTransport(
            connectionMethod: ConnectionMethod,
            role: MdocTransport.Role,
        ): MdocTransport {
            when (connectionMethod) {
                is ConnectionMethodBle -> {
                    if (connectionMethod.supportsCentralClientMode &&
                        connectionMethod.supportsPeripheralServerMode) {
                        throw IllegalArgumentException(
                            "Only Central Client or Peripheral Server mode is supported at one time, not both"
                        )
                    } else if (connectionMethod.supportsCentralClientMode) {
                        return when (role) {
                            MdocTransport.Role.MDOC -> {
                                BleTransportCentralMdoc(
                                    role,
                                    BleCentralManagerAndroid(),
                                    connectionMethod.centralClientModeUuid!!
                                )
                            }
                            MdocTransport.Role.MDOC_READER -> {
                                BleTransportCentralMdocReader(
                                    role,
                                    BlePeripheralManagerAndroid(),
                                    connectionMethod.centralClientModeUuid!!
                                )
                            }
                        }
                    } else {
                        return when (role) {
                            MdocTransport.Role.MDOC -> {
                                BleTransportPeripheralMdoc(
                                    role,
                                    BlePeripheralManagerAndroid(),
                                    connectionMethod.peripheralServerModeUuid!!
                                )
                            }
                            MdocTransport.Role.MDOC_READER -> {
                                BleTransportPeripheralMdocReader(
                                    role,
                                    BleCentralManagerAndroid(),
                                    connectionMethod.peripheralServerModeUuid!!
                                )
                            }
                        }
                    }
                }
                else -> {
                    throw IllegalArgumentException("$connectionMethod is not supported")
                }
            }
        }
    }
}