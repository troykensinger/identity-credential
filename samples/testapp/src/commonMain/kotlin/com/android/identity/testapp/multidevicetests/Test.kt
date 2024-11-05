package com.android.identity.testapp.multidevicetests

enum class Test(val description: String) {
    // holder terminates by including status 0x20 in same message as DeviceResponse
    //
    MDOC_CENTRAL_CLIENT_MODE("mdoc central client mode"),
    MDOC_PERIPHERAL_SERVER_MODE("mdoc peripheral server mode"),

    // holder terminates with separate message with status 0x20
    //
    MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_MSG("mdoc cc (holder term MSG)"),
    MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_MSG("mdoc ps (holder term MSG)"),

    // holder terminates with BLE specific termination
    //
    MDOC_CENTRAL_CLIENT_MODE_HOLDER_TERMINATION_BLE("mdoc cc (holder term BLE)"),
    MDOC_PERIPHERAL_SERVER_MODE_HOLDER_TERMINATION_BLE("mdoc ps (holder term BLE)"),

    // reader terminates with message with status 0x20
    //
    MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_MSG("mdoc cc (reader term MSG)"),
    MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_MSG("mdoc ps (reader term MSG)"),

    // reader terminates with BLE specific termination
    //
    MDOC_CENTRAL_CLIENT_MODE_READER_TERMINATION_BLE("mdoc cc (reader term BLE)"),
    MDOC_PERIPHERAL_SERVER_MODE_READER_TERMINATION_BLE("mdoc ps (reader term BLE)"),
}