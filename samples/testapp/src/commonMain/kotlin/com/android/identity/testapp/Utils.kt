package com.android.identity.testapp

import com.android.identity.cbor.Bstr
import com.android.identity.cbor.Cbor
import com.android.identity.cbor.CborArray
import com.android.identity.cbor.DataItem
import com.android.identity.cbor.Simple
import com.android.identity.cbor.Tagged
import com.android.identity.cbor.toDataItem
import com.android.identity.cose.Cose
import com.android.identity.cose.CoseLabel
import com.android.identity.cose.CoseNumberLabel
import com.android.identity.credential.CredentialFactory
import com.android.identity.crypto.Algorithm
import com.android.identity.crypto.EcCurve
import com.android.identity.crypto.EcPrivateKey
import com.android.identity.crypto.EcPublicKey
import com.android.identity.crypto.X509Cert
import com.android.identity.crypto.X509CertChain
import com.android.identity.document.DocumentStore
import com.android.identity.document.NameSpacedData
import com.android.identity.documenttype.knowntypes.DrivingLicense
import com.android.identity.mdoc.credential.MdocCredential
import com.android.identity.mdoc.mso.MobileSecurityObjectGenerator
import com.android.identity.mdoc.mso.MobileSecurityObjectParser
import com.android.identity.mdoc.mso.StaticAuthDataGenerator
import com.android.identity.mdoc.mso.StaticAuthDataParser
import com.android.identity.mdoc.request.DeviceRequestGenerator
import com.android.identity.mdoc.response.DeviceResponseGenerator
import com.android.identity.mdoc.response.DocumentGenerator
import com.android.identity.mdoc.util.MdocUtil
import com.android.identity.securearea.KeyPurpose
import com.android.identity.securearea.SecureArea
import com.android.identity.securearea.SecureAreaRepository
import com.android.identity.securearea.software.SoftwareCreateKeySettings
import com.android.identity.securearea.software.SoftwareSecureArea
import com.android.identity.storage.EphemeralStorageEngine
import com.android.identity.storage.StorageEngine
import com.android.identity.util.Constants
import com.android.identity.util.Logger
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours

object Utils {
    private const val TAG = "MdocUtils"

    fun generateEncodedDeviceRequest(
        encodedSessionTranscript: ByteArray
    ): ByteArray {
        val mdocRequest = DrivingLicense.getDocumentType().sampleRequests.first {
            it.id == "us-transportation"
        }.mdocRequest!!
        val itemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
        for (ns in mdocRequest.namespacesToRequest) {
            for ((de, intentToRetain) in ns.dataElementsToRequest) {
                itemsToRequest.getOrPut(ns.namespace) { mutableMapOf() }
                    .put(de.attribute.identifier, intentToRetain)
            }
        }

        val deviceRequestGenerator = DeviceRequestGenerator(encodedSessionTranscript)
        deviceRequestGenerator.addDocumentRequest(
            docType = mdocRequest.docType,
            itemsToRequest = itemsToRequest,
            requestInfo = null,
            readerKey = null,
            signatureAlgorithm = Algorithm.UNSET,
            readerKeyCertificateChain = null,
        )
        return deviceRequestGenerator.generate()
    }

    fun generateEncodedSessionTranscript(
        encodedDeviceEngagement: ByteArray,
        eReaderKey: EcPublicKey
    ): ByteArray {
        val handover = Simple.NULL
        val encodedEReaderKey = Cbor.encode(eReaderKey.toCoseKey().toDataItem())
        return Cbor.encode(
            CborArray.builder()
                .add(Tagged(24, Bstr(encodedDeviceEngagement)))
                .add(Tagged(24, Bstr(encodedEReaderKey)))
                .add(handover)
                .end()
                .build()
        )
    }

    fun generateEncodedDeviceResponse(encodedSessionTranscript: ByteArray): ByteArray {
        val nsAndDataElements =
            mapOf(DrivingLicense.MDL_NAMESPACE to listOf("sex",
                "portrait",
                "given_name",
                "issue_date",
                "expiry_date",
                "family_name",
                "document_number",
                "issuing_authority"
            ))


        val staticAuthData = StaticAuthDataParser(mdocCredential.issuerProvidedData).parse()

        val mergedIssuerNamespaces = MdocUtil.mergeIssuerNamesSpaces(
            nsAndDataElements,
            documentData,
            staticAuthData
        )
        val issuerAuthCoseSign1 = Cbor.decode(staticAuthData.issuerAuth).asCoseSign1
        val encodedMsoBytes = Cbor.decode(issuerAuthCoseSign1.payload!!)
        val encodedMso = Cbor.encode(encodedMsoBytes.asTaggedEncodedCbor)
        val mso = MobileSecurityObjectParser(encodedMso).parse()

        val documentGenerator = DocumentGenerator(
            mso.docType,
            staticAuthData.issuerAuth,
            encodedSessionTranscript,
        )
        documentGenerator.setIssuerNamespaces(mergedIssuerNamespaces)

        documentGenerator.setDeviceNamespacesSignature(
            NameSpacedData.Builder().build(),
            mdocCredential.secureArea,
            mdocCredential.alias,
            null,
            Algorithm.ES256,
        )

        val deviceResponseGenerator = DeviceResponseGenerator(Constants.DEVICE_RESPONSE_STATUS_OK)
        deviceResponseGenerator.addDocument(documentGenerator.generate())
        return deviceResponseGenerator.generate()
    }

    private lateinit var documentData: NameSpacedData
    private lateinit var mdocCredential: MdocCredential

    private lateinit var storageEngine: StorageEngine
    private lateinit var secureArea: SecureArea
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var credentialFactory: CredentialFactory

    init {
        storageEngine = EphemeralStorageEngine()
        secureAreaRepository = SecureAreaRepository()
        secureArea = SoftwareSecureArea(storageEngine)
        secureAreaRepository.addImplementation(secureArea)
        credentialFactory = CredentialFactory()
        credentialFactory.addCredentialImplementation(MdocCredential::class) {
                document, dataItem -> MdocCredential(document, dataItem)
        }
        provisionDocument()
    }

    private fun NameSpacedData.Builder.addMdlSampleData(
        dataElementName: String
    ) {
        val mdocDocumentType = DrivingLicense.getDocumentType().mdocDocumentType!!

        val mdocDataElement =
            mdocDocumentType.namespaces.get(DrivingLicense.MDL_NAMESPACE)!!.dataElements.get(dataElementName)!!
        val sampleValue = mdocDataElement.attribute.sampleValue
        if (sampleValue != null) {
            this.putEntry(DrivingLicense.MDL_NAMESPACE, dataElementName, Cbor.encode(sampleValue))
        } else {
            Logger.w(TAG, "No sample value for data element $dataElementName")
        }
    }

    private fun provisionDocument() {
        val documentStore = DocumentStore(
            storageEngine,
            secureAreaRepository,
            credentialFactory
        )

        // Create the document...
        val document = documentStore.createDocument(
            "testDocument"
        )

        val nsdBuilder = NameSpacedData.Builder()
        nsdBuilder.addMdlSampleData("sex")
        nsdBuilder.addMdlSampleData("portrait")
        nsdBuilder.addMdlSampleData("given_name")
        nsdBuilder.addMdlSampleData("issue_date")
        nsdBuilder.addMdlSampleData("expiry_date")
        nsdBuilder.addMdlSampleData("family_name")
        nsdBuilder.addMdlSampleData("document_number")
        nsdBuilder.addMdlSampleData("issuing_authority")
        documentData = nsdBuilder.build()

        document.applicationData.setNameSpacedData("documentData", documentData)
        val overrides: MutableMap<String, Map<String, ByteArray>> = HashMap()
        val exceptions: MutableMap<String, List<String>> = HashMap()

        // Create an authentication key... make sure the authKey used supports both
        // mdoc ECDSA and MAC authentication.
        val now = Clock.System.now()
        val timeSigned = now - 1.hours
        val timeValidityBegin =  now - 1.hours
        val timeValidityEnd = now + 24.hours
        mdocCredential = MdocCredential(
            document,
            null,
            "AuthKeyDomain",
            secureArea,
            SoftwareCreateKeySettings.Builder()
                .setKeyPurposes(setOf(KeyPurpose.SIGN, KeyPurpose.AGREE_KEY))
                .build(),
            "org.iso.18013.5.1.mDL"
        )

        // Generate an MSO and issuer-signed data for this authentication key.
        val msoGenerator = MobileSecurityObjectGenerator(
            "SHA-256",
            DrivingLicense.MDL_DOCTYPE,
            mdocCredential.attestation.publicKey
        )
        msoGenerator.setValidityInfo(timeSigned, timeValidityBegin, timeValidityEnd, null)
        val issuerNameSpaces = MdocUtil.generateIssuerNameSpaces(
            documentData,
            Random,
            16,
            overrides
        )
        for (nameSpaceName in issuerNameSpaces.keys) {
            val digests = MdocUtil.calculateDigestsForNameSpace(
                nameSpaceName,
                issuerNameSpaces,
                Algorithm.SHA256
            )
            msoGenerator.addDigestIdsForNamespace(nameSpaceName, digests)
        }
        val validFrom = Clock.System.now() - 1.hours
        val validUntil = validFrom + 24.hours

        val documentSignerKeyPub = EcPublicKey.fromPem(
"""-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnmiWAMGIeo2E3usWRLL/EPfh1Bw5
JHgq8RYzJvraMj5QZSh94CL/nlEi3vikGxDP34HjxZcjzGEimGg03sB6Ng==
-----END PUBLIC KEY-----""",
            EcCurve.P256
        )

        val documentSignerKey = EcPrivateKey.fromPem(
            """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg/ANvinTxJAdR8nQ0
NoUdBMcRJz+xLsb0kmhyMk+lkkGhRANCAASeaJYAwYh6jYTe6xZEsv8Q9+HUHDkk
eCrxFjMm+toyPlBlKH3gIv+eUSLe+KQbEM/fgePFlyPMYSKYaDTewHo2
-----END PRIVATE KEY-----""",
            documentSignerKeyPub
        )

        val documentSignerCert = X509Cert.fromPem(
"""-----BEGIN CERTIFICATE-----
MIIBITCBx6ADAgECAgEBMAoGCCqGSM49BAMCMBoxGDAWBgNVBAMMD1N0YXRlIE9mIFV0b3BpYTAe
Fw0yNDExMDcyMTUzMDdaFw0zNDExMDUyMTUzMDdaMBoxGDAWBgNVBAMMD1N0YXRlIE9mIFV0b3Bp
YTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABJ5olgDBiHqNhN7rFkSy/xD34dQcOSR4KvEWMyb6
2jI+UGUofeAi/55RIt74pBsQz9+B48WXI8xhIphoNN7AejYwCgYIKoZIzj0EAwIDSQAwRgIhALkq
UIVeaSW0xhLuMdwHyjiwTV8USD4zq68369ZW6jBvAiEAj2smZAXJB04x/s3exzjnI5BQprUOSfYE
uku1Jv7gA+A=
-----END CERTIFICATE-----""""
        )

        val mso = msoGenerator.generate()
        val taggedEncodedMso = Cbor.encode(Tagged(24, Bstr(mso)))

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        //
        // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
        //
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier.toDataItem()
            )
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                X509CertChain(listOf(documentSignerCert)).toDataItem()
            )
        )
        val encodedIssuerAuth = Cbor.encode(
            Cose.coseSign1Sign(
                documentSignerKey,
                taggedEncodedMso,
                true,
                Algorithm.ES256,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = StaticAuthDataGenerator(
            MdocUtil.stripIssuerNameSpaces(issuerNameSpaces, exceptions),
            encodedIssuerAuth
        ).generate()

        // Now that we have issuer-provided authentication data we certify the authentication key.
        mdocCredential.certify(
            issuerProvidedAuthenticationData,
            timeValidityBegin,
            timeValidityEnd
        )
    }


}