package com.android.identity.mdoc.transport

import com.android.identity.mdoc.connectionmethod.ConnectionMethod

expect class MdocTransportFactory {
    companion object {
        fun createTransport(
            connectionMethod: ConnectionMethod,
            role: MdocTransport.Role,
        ): MdocTransport
    }
}