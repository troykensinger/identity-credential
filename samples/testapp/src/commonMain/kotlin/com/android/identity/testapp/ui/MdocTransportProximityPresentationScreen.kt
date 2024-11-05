package com.android.identity.testapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.identity.appsupport.ui.permissions.rememberBluetoothPermissionState
import com.android.identity.appsupport.ui.qrcode.ScanQrCodeDialog
import com.android.identity.appsupport.ui.qrcode.ShowQrCodeDialog
import com.android.identity.crypto.Crypto
import com.android.identity.crypto.EcCurve
import com.android.identity.mdoc.connectionmethod.ConnectionMethod
import com.android.identity.mdoc.connectionmethod.ConnectionMethodBle
import com.android.identity.mdoc.engagement.EngagementGenerator
import com.android.identity.mdoc.engagement.EngagementParser
import com.android.identity.mdoc.sessionencryption.SessionEncryption
import com.android.identity.mdoc.transport.MdocTransport
import com.android.identity.mdoc.transport.MdocTransportClosedException
import com.android.identity.mdoc.transport.MdocTransportFactory
import com.android.identity.testapp.Utils
import com.android.identity.util.Constants
import com.android.identity.util.Logger
import com.android.identity.util.UUID
import com.android.identity.util.fromBase64Url
import com.android.identity.util.toBase64Url
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "MdocTransportProximityPresentationScreen"

@Composable
fun MdocTransportProximityPresentationScreen(
    showToast: (message: String) -> Unit,
) {
    val blePermissionState = rememberBluetoothPermissionState()

    val coroutineScope = rememberCoroutineScope()

    val readerShowQrScanner = remember { mutableStateOf(false) }
    var readerAutoCloseConnection by remember { mutableStateOf(true) }
    var readerJob by remember { mutableStateOf<Job?>(null) }
    var readerTransport = remember { mutableStateOf<MdocTransport?>(null) }

    var holderAutoCloseConnection = remember { mutableStateOf(true) }
    var holderJob by remember { mutableStateOf<Job?>(null) }
    var holderTransport = remember { mutableStateOf<MdocTransport?>(null) }
    var holderEncodedDeviceEngagement = remember { mutableStateOf<ByteArray?>(null) }

    if (readerShowQrScanner.value) {
        ScanQrCodeDialog(
            title = { Text(text = "Scan QR code") },
            text = { Text(text = "Scan this QR code on another device") },
            additionalContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = readerAutoCloseConnection,
                        onCheckedChange = { readerAutoCloseConnection = it }
                    )
                    Text(text = "Close transport after receiving first response")
                }
            },
            dismissButton = "Close",
            onCodeScanned = { data ->
                if (data.startsWith("mdoc:")) {
                    readerShowQrScanner.value = false
                    readerJob = coroutineScope.launch() {
                        doReaderFlow(
                            encodedDeviceEngagement = data.substring(5).fromBase64Url(),
                            autoCloseConnection = readerAutoCloseConnection,
                            showToast = showToast,
                            readerTransport = readerTransport,
                        )
                        readerJob = null
                    }
                    true
                } else {
                    false
                }
            },
            onDismiss = { readerShowQrScanner.value = false }
        )
    }

    val holderTransportState = holderTransport.value?.state?.collectAsState()
    val readerTransportState = readerTransport.value?.state?.collectAsState()

    val holderWaitingForRemotePeer = when (holderTransportState?.value) {
        MdocTransport.State.IDLE,
        MdocTransport.State.ADVERTISING,
        MdocTransport.State.SCANNING -> {
            true
        }
        else -> {
            false
        }
    }
    Logger.i(TAG, "holderTransportState=$holderTransportState holderWaitingForRemotePeer=$holderWaitingForRemotePeer")
    if (holderTransport != null && holderWaitingForRemotePeer) {
        val deviceEngagementQrCode = "mdoc:" + holderEncodedDeviceEngagement.value!!.toBase64Url()
        ShowQrCodeDialog(
            title = { Text(text = "Scan QR code") },
            text = { Text(text = "Scan this QR code on another device") },
            additionalContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = holderAutoCloseConnection.value,
                        onCheckedChange = { holderAutoCloseConnection.value = it }
                    )
                    Text(text = "Close transport after first response")
                }
            },
            dismissButton = "Close",
            data = deviceEngagementQrCode,
            onDismiss = {
                holderJob?.cancel()
            }
        )
    }

    if (!blePermissionState.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { blePermissionState.launchPermissionRequest() }
            ) {
                Text("Request BLE permissions")
            }
        }
    } else {
        if (holderTransport.value != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connection State: ${holderTransportState?.value}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    holderTransport.value!!.sendMessage(
                                        SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                                    )
                                    holderTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    error.printStackTrace()
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (Message)")
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    holderTransport.value!!.sendMessage(byteArrayOf())
                                    Logger.i(TAG, "TODO: session-specific termination")
                                    holderTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    error.printStackTrace()
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (Transport-Specific)")
                    }
                    Button(
                        onClick = {
                            try {
                                coroutineScope.launch {
                                    holderTransport.value!!.close()
                                }
                            } catch (error: Throwable) {
                                Logger.e(TAG, "Caught exception", error)
                                error.printStackTrace()
                                showToast("Error: ${error.message}")
                            }
                        }
                    ) {
                        Text("Close (None)")
                    }
                }
            }
        } else if (readerJob != null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connection State: ${readerTransportState?.value}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    Logger.i(TAG, "TODO: send another request")
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    error.printStackTrace()
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Send Another Request")
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    readerTransport.value!!.sendMessage(
                                        SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION)
                                    )
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    error.printStackTrace()
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (Message)")
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                readerTransport.value!!.sendMessage(byteArrayOf())
                                readerTransport.value!!.close()
                            }
                        }
                    ) {
                        Text("Close (Transport-Specific)")
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    readerTransport.value!!.close()
                                } catch (error: Throwable) {
                                    Logger.e(TAG, "Caught exception", error)
                                    error.printStackTrace()
                                    showToast("Error: ${error.message}")
                                }
                            }
                        }
                    ) {
                        Text("Close (None)")
                    }
                }
            }

        } else {
            LazyColumn(
                modifier = Modifier.padding(8.dp)
            ) {
                item {
                    TextButton(
                        onClick = { readerShowQrScanner.value = true },
                        content = { Text("Reader via QR") }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            holderJob = coroutineScope.launch() {
                                doHolderFlow(
                                    connectionMethod = ConnectionMethodBle(
                                        supportsPeripheralServerMode = false,
                                        supportsCentralClientMode = true,
                                        peripheralServerModeUuid = null,
                                        centralClientModeUuid = UUID.randomUUID(),
                                    ),
                                    autoCloseConnection = holderAutoCloseConnection,
                                    showToast = showToast,
                                    holderTransport = holderTransport,
                                    encodedDeviceEngagement = holderEncodedDeviceEngagement,
                                )
                            }
                        },
                        content = { Text("Holder via QR (mdoc central client mode)") }
                    )
                }
                item {
                    TextButton(
                        onClick = {
                            holderJob = coroutineScope.launch() {
                                doHolderFlow(
                                    connectionMethod = ConnectionMethodBle(
                                        supportsPeripheralServerMode = true,
                                        supportsCentralClientMode = false,
                                        peripheralServerModeUuid = UUID.randomUUID(),
                                        centralClientModeUuid = null,
                                    ),
                                    autoCloseConnection = holderAutoCloseConnection,
                                    showToast = showToast,
                                    holderTransport = holderTransport,
                                    encodedDeviceEngagement = holderEncodedDeviceEngagement,
                                )
                            }
                        },
                        content = { Text("Holder via QR (mdoc peripheral server mode)") }
                    )
                }
            }
        }
    }
}

private suspend fun doHolderFlow(
    connectionMethod: ConnectionMethod,
    autoCloseConnection: MutableState<Boolean>,
    showToast: (message: String) -> Unit,
    holderTransport:  MutableState<MdocTransport?>,
    encodedDeviceEngagement: MutableState<ByteArray?>
) {
    val transport = MdocTransportFactory.createTransport(
        connectionMethod,
        MdocTransport.Role.MDOC,
    )
    holderTransport.value = transport
    val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
    val engagementGenerator = EngagementGenerator(
        eSenderKey = eDeviceKey.publicKey,
        version = "1.0"
    )
    engagementGenerator.addConnectionMethods(listOf(transport!!.connectionMethod))
    encodedDeviceEngagement.value = engagementGenerator.generate()
    try {
        transport.open(eDeviceKey.publicKey)

        var sessionEncryption: SessionEncryption? = null
        var encodedSessionTranscript: ByteArray? = null
        while (true) {
            Logger.i(TAG, "Waiting for message from reader...")
            val sessionData = transport!!.waitForMessage()
            if (sessionData.isEmpty()) {
                showToast("Received transport-specific session termination message from reader")
                transport.close()
                break
            }

            if (sessionEncryption == null) {
                val eReaderKey = SessionEncryption.getEReaderKey(sessionData)
                encodedSessionTranscript = Utils.generateEncodedSessionTranscript(
                    encodedDeviceEngagement.value!!,
                    eReaderKey
                )
                sessionEncryption = SessionEncryption(
                    SessionEncryption.Role.MDOC,
                    eDeviceKey,
                    eReaderKey,
                    encodedSessionTranscript,
                )
            }
            val (message, status) = sessionEncryption.decryptMessage(sessionData)

            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                showToast("Received session termination message from reader")
                transport.close()
                break
            }

            // TODO: show consent prompt

            val encodedDeviceResponse = Utils.generateEncodedDeviceResponse(encodedSessionTranscript!!)
            transport.sendMessage(
                sessionEncryption.encryptMessage(
                    encodedDeviceResponse,
                    if (autoCloseConnection.value) {
                        Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                    } else {
                        null
                    }
                )
            )
            if (autoCloseConnection.value) {
                showToast("Response sent, autoclosing connection")
                transport.close()
                break
            } else {
                showToast("Response sent, keeping connection open")
            }
        }
    } catch (_: MdocTransportClosedException) {
        // Nothing to do, this is thrown when transport.close() is called from another coroutine, that
        // is, the onClick handlers for the close buttons.
        Logger.i(TAG, "Ending holderJob due to MdocTransportClosedException")
    } catch (error: Throwable) {
        Logger.e(TAG, "Caught exception", error)
        error.printStackTrace()
        showToast("Error: ${error.message}")
    } finally {
        transport.close()
        holderTransport.value = null
        encodedDeviceEngagement.value = null
    }
}

private suspend fun doReaderFlow(
    encodedDeviceEngagement: ByteArray,
    autoCloseConnection: Boolean,
    showToast: (message: String) -> Unit,
    readerTransport:  MutableState<MdocTransport?>
) {
    val deviceEngagement = EngagementParser(encodedDeviceEngagement).parse()
    val connectionMethod = deviceEngagement.connectionMethods[0]
    val eDeviceKey = deviceEngagement.eSenderKey
    val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

    val transport = MdocTransportFactory.createTransport(
        connectionMethod,
        MdocTransport.Role.MDOC_READER,
    )
    readerTransport.value = transport
    val encodedSessionTranscript = Utils.generateEncodedSessionTranscript(
        encodedDeviceEngagement,
        eReaderKey.publicKey
    )
    val sessionEncryption = SessionEncryption(
        SessionEncryption.Role.MDOC_READER,
        eReaderKey,
        eDeviceKey,
        encodedSessionTranscript,
    )
    val encodedDeviceRequest = Utils.generateEncodedDeviceRequest(encodedSessionTranscript)
    try {
        transport.open(eDeviceKey)
        transport.sendMessage(
            sessionEncryption.encryptMessage(
                messagePlaintext = encodedDeviceRequest,
                statusCode = null
            )
        )
        while (true) {
            val sessionData = transport.waitForMessage()
            if (sessionData.isEmpty()) {
                showToast("Received transport-specific session termination message from holder")
                transport.close()
                break
            }

            val (message, status) = sessionEncryption.decryptMessage(sessionData)
            Logger.i(TAG, "Holder sent ${message?.size} bytes status $status")
            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                showToast("Received session termination message from holder")
                Logger.i(TAG, "Holder indicated they closed the connection. " +
                        "Closing and ending reader loop")
                transport.close()
                break
            }
            if (autoCloseConnection) {
                showToast("Response received, autoclosing connection")
                Logger.i(TAG, "Holder did not indicate they are closing the connection. " +
                        "Auto-close is enabled, so sending termination message, closing, and " +
                        "ending reader loop")
                transport.sendMessage(SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION))
                transport.close()
                break
            }
            showToast("Response received, keeping connection open")
            Logger.i(TAG, "Holder did not indicate they are closing the connection. " +
                    "Auto-close is not enabled so waiting for message from holder")
            // "Send additional request" and close buttons will act further on `transport`
        }
    } catch (_: MdocTransportClosedException) {
        // Nothing to do, this is thrown when transport.close() is called from another coroutine, that
        // is, the onClick handlers for the close buttons.
        Logger.i(TAG, "Ending reader flow due to MdocTransportClosedException")
    } catch (error: Throwable) {
        Logger.e(TAG, "Caught exception", error)
        error.printStackTrace()
        showToast("Error: ${error.message}")
    } finally {
        transport.close()
        readerTransport.value = null
    }
}
