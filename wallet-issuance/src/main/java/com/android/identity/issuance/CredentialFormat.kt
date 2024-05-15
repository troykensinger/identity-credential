package com.android.identity.issuance

import com.android.identity.cbor.annotation.CborSerializable

/**
 * An enumeration of Credential Formats that an issuer may support.
 */
enum class CredentialFormat {

    /**
     * This CredentialFormat for mdoc as defined in
     * [ISO/IEC 18013-5:2021](https://www.iso.org/standard/69084.html).
     *
     * For this format, the [CredentialData.data]
     * contains CBOR conforming to the follow CDDL:
     * ```
     * StaticAuthData = {
     *   "digestIdMapping": DigestIdMapping,
     *   "issuerAuth" : IssuerAuth
     * }
     *
     * DigestIdMapping = {
     *   NameSpace =&gt; [ + IssuerSignedItemMetadataBytes ]
     * }
     *
     * IssuerSignedItemMetadataBytes = #6.24(bstr .cbor IssuerSignedItemMetadata)
     *
     * IssuerSignedItemMetadata = {
     *   "digestID" : uint,                           ; Digest ID for issuer data auth
     *   "random" : bstr,                             ; Random value for issuer data auth
     *   "elementIdentifier" : DataElementIdentifier, ; Data element identifier
     *   "elementValue" : DataElementValueOrNull      ; Placeholder for Data element value
     * }
     *
     * ; Set to null to use value previously provisioned or non-null
     * ; to use a per-MSO value
     * ;
     * DataElementValueOrNull = null // DataElementValue   ; "//" means or in CDDL
     *
     * ; Defined in ISO 18013-5
     * ;
     * NameSpace = String
     * DataElementIdentifier = String
     * DataElementValue = any
     * DigestID = uint
     * IssuerAuth = COSE_Sign1 ; The payload is MobileSecurityObjectBytes
     * ```
     */
    MDOC_MSO,

    /**
     * SD-JWT Verifiable Credential according to [draft-ietf-oauth-sd-jwt-vc-03]
     * (https://datatracker.ietf.org/doc/draft-ietf-oauth-sd-jwt-vc/).
     *
     * For this format, the [CredentialData.data] member contains the serialized format of the
     * SD-JWT as defined in section 5 of [draft-ietf-oauth-selective-disclosure-jwt-08]
     * (https://datatracker.ietf.org/doc/draft-ietf-oauth-selective-disclosure-jwt/).
     */
    SD_JWT_VC,
}