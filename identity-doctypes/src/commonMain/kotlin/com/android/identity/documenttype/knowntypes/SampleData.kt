package com.android.identity.documenttype.knowntypes

import com.android.identity.util.fromBase64Url
import kotlinx.datetime.LocalDate

/**
 * Sample data used across multiple document types.
 *
 * This data is based on the fictional person
 * [Erika Mustermann](https://en.wiktionary.org/wiki/Erika_Mustermann)
 * and a fictional country called Utopia.
 *
 * Note: The ISO-3166-1 Alpha-2 country code used for Utopia is UT. This value does not
 * appear in that standard.
 */
internal object SampleData {

    const val GIVEN_NAME = "Erika"
    const val FAMILY_NAME = "Mustermann"
    const val GIVEN_NAME_BIRTH = "Erika"
    const val FAMILY_NAME_BIRTH = "Mustermann"
    const val GIVEN_NAMES_NATIONAL_CHARACTER = "Ерика"
    const val FAMILY_NAME_NATIONAL_CHARACTER = "Бабіак"

    val birthDate = LocalDate.parse("1971-09-01")
    const val BIRTH_COUNTRY = "ZZ"  // Note: ZZ is a user-assigned country-code as per ISO 3166-1
    val issueDate = LocalDate.parse("2024-03-15")
    val expiryDate = LocalDate.parse("2028-09-01")
    const val ISSUING_COUNTRY = "ZZ"  // Note: ZZ is a user-assigned country-code as per ISO 3166-1
    const val ISSUING_AUTHORITY_MDL = "Utopia Department of Motor Vehicles"
    const val ISSUING_AUTHORITY_EU_PID = "Utopia Central Registry"
    const val ISSUING_AUTHORITY_PHOTO_ID = "Utopia Central Registry"
    const val DOCUMENT_NUMBER = "987654321"
    const val PERSON_ID = "24601"

    const val UN_DISTINGUISHING_SIGN = "UTO"
    const val ADMINISTRATIVE_NUMBER = "123456789"
    const val SEX_ISO218 = 2
    const val HEIGHT_CM = 175
    const val WEIGHT_KG = 68
    const val BIRTH_PLACE = "Sample City"
    const val BIRTH_STATE = "Sample State"
    const val BIRTH_CITY = "Sample City"
    const val RESIDENT_ADDRESS = "Sample Street 123, 12345 Sample City, Sample State, Utopia"
    val portrait = erikaMustermannLowresBase64.fromBase64Url()
    val portraitCaptureDate = LocalDate.parse("2020-03-14")
    const val AGE_IN_YEARS = 53
    const val AGE_BIRTH_YEAR = 1971
    const val AGE_OVER_13 = true
    const val AGE_OVER_16 = true
    const val AGE_OVER_18 = true
    const val AGE_OVER_21 = true
    const val AGE_OVER_25 = true
    const val AGE_OVER_60 = false
    const val AGE_OVER_62 = false
    const val AGE_OVER_65 = false
    const val AGE_OVER_68 = false
    const val ISSUING_JURISDICTION = "State of Utopia"
    const val NATIONALITY = "ZZ"  // Note: ZZ is a user-assigned country-code as per ISO 3166-1
    const val RESIDENT_STREET = "Sample Street"
    const val RESIDENT_HOUSE_NUMBER = "123"
    const val RESIDENT_POSTAL_CODE = "12345"
    const val RESIDENT_CITY = "Sample City"
    const val RESIDENT_STATE = "Sample State"
    const val RESIDENT_COUNTRY = "ZZ"  // Note: ZZ is a user-assigned country-code as per ISO 3166-1

    // TODO
    //val signatureUsualMark

    private const val erikaMustermannLowresBase64 =
        "_9j_4QDKRXhpZgAATU0AKgAAAAgABgESAAMAAAABAAEAAAEaAAUAAAABAAAAVgEbAAUAAAABAAAAXgEoAAMAAAABAAIAAAITAAMAAAABAAEAAIdpAAQAAAABAAAAZgAAAAAAAABIAAAAAQAAAEgAAAABAAeQAAAHAAAABDAyMjGRAQAHAAAABAECAwCgAAAHAAAABDAxMDCgAQADAAAAAQABAACgAgAEAAAAAQAAAHigAwAEAAAAAQAAAJmkBgADAAAAAQAAAAAAAAAAAAD_2wCEABMTExMTEyATEyAuICAgLj4uLi4uPk4-Pj4-Pk5eTk5OTk5OXl5eXl5eXl5xcXFxcXGDg4ODg5OTk5OTk5OTk5MBFxgYJiMmQCMjQJppVmmampqampqampqampqampqampqampqampqampqampqampqampqampqampqampqampqamv_dAAQACP_AABEIAJkAeAMBIgACEQEDEQH_xAGiAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgsQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29_j5-gEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoLEQACAQIEBAMEBwUEBAABAncAAQIDEQQFITEGEkFRB2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4-Tl5ufo6ery8_T19vf4-fr_2gAMAwEAAhEDEQA_AN09aSlPWkqjMKSiq1zOIIyx60m7FKNxtzdR26_MeewrnZ7ueY_3RSud37-fv0FRbWblh9FH9ag0tbREWe7UF1A4zTpNsf8AvfyqsMnk00IchANW0HGUxVHNSb2T6U2gRpxSHOJRxVox4G6PmsmOY5wQK0YD_EnHtUFIuQz4xWipDDK1mCMScrwamgcxnY_Q9KcXYmUS9RS0VqYn_9DdPWkpT1pyr_Ee1NuxCQxiI13v-ArDnBmkLyfcTr9fStSZi5yPoBVSeMsRAvQdaxudKjZWMcgyt5z9B90VME2r5h6noKveQNwHYUyVN_yD6fhRcfKYwiaZ8DkVHOAD5SdB1rbdPIi2LwzfpVEW_GccVXMTyGaiblNTxx_LsboelaCW4UD8KUQ4QH0NHMCgZnl7Ttq5BlCCOlWXi4BojjxSuHKXF4-YfiKmwHGRUScYpwzG-OxpDLcZyuD2qSqwyDkU_dWqkYuHY__R3sc09_uhBSDrTW4FRNlU0MjTMhP92hYhgt69KlgXEf1qYAAeyjFQa3M50C1CqDl26Vccb22iop8D5eir1pFmfICxz6094lRQtPt186bOOFqcrvn9l5oGQmIAqo9hQYR5R9v6VPKwV1-uakOPs7GmIpBA0a0zZg1JE3BWn9cH8KBERXaPpTmG-Me1SFcpTYvuUyRsbfLUm6osbZCKlpkn_9LogOcVBKcCrQ71RnP7zA-lZM2iXk4RagkkGAvSrBwI_wAKznZVTzHpMqKJ42wpc8Z6VmTXCytsj6CknuJyAyIAvbcev4VFBPu-8gx2K9KLDTVzTtgI4ifWkjI6dzT12smBUfl7DxSLsRTDMme3QfQVMZF8kqaqyuAcGozcQqpzzTQmRF1XoatIwYZqj9otSdpXaffin58n5kOU9PSiwty-h-UrSQ-lLHjJYdDg02H7zChEMWQYYGkqaYVV4piP_9PpGO0Gs6Q_vc-9XpunFZ79M1gzogi_I2Ih9BVbZuUBh0qaTkJ9BTCD2oLjsU5beTbiNuPf3_pVMRPEhTgcYHGAB7CtMsah2hjzTuHIh1qX2DfyaWR_mxU6qFFVZMZzSKRBKARWZll3AAc4xWzgGqbRANlaaZLjcpwRNu-ZcgEn5vft9KnELInHI9KspwKnXpzTbFGFiK0fKY9BipYeJmHrUC4jmOOhFTx_66pQpItTcrVLirsn3SKqbaohH__U6JxkEelUpRhDVz-I-lVJ_u1gzoiS5-RPypcVXLYix6YNWN1BaK7gjpUEJPmYq820jNNWJRzQVfQeRyRn8KgdabLbxyMHb7w6EUTHcuAaARGPbpVaX71OjhWLO09acQu7NA0JGvFSHgU4YA4qJmBpDIJCAyt-FWU_1tZ92cGMe9XoTl1_CmjKZelwBioOKklbnFQ1Rmf_1ehf72KqTclVHerbj5jj6VnTMFZn9OBWDOiJXeUCby_UfyrSiIeNWHpXOStn5s4YVqaTN5kJjPVDj8KSLLrggg4zUZnVfvfLVsioWjDUxplcyxnoagaZOhIonthjI4PtVHyvWkacqsWhKnqKQyRnjIquIFJ6VaWNFGAKZA1SRlT26UopQMcUuMUhoz7w4kStC3OCD6Vj3MgN1t_ugVrxgrwe1VbQye5MzZIptNYYAIpu-gk__9bomwFJ_KsW6PGwVtSDisSUZasGdMTPlXinaexiuBjo3BqWReKWGPa4agLHQU3pS4yMim7hQUQvVZhjtVljxUBpFrYi_CkpdwphYCgVh3vUM0ojTd-VPBLHAqlecAChA9EZOSzOx6muoj-ZA395c1zCYrprZcRRD_ZFayOeJI33MegqDFWmG1gD34pfLWpKP__X6Kb7tZTLzmtKbsKqbM8VgzpjsU3TiqPmYnUe9brxjIArDvLd4JFmA-XP5U0hnRRnikKA1UtrhHUYNXQc0hkSxDFQPH2q7kYqtIaCkUXQYqELVkkU3FIYIMCs28NamOKy7zLMI4xljwBTRMtivaIrNjHC8muhRcRKRVKC1FvFtOCcYNaDEKAvYcVTM0rCS_MoqtipFb5vLPcU_wAv2oEf_9DbJ3HFP209etTHpWNjouQqo3U94VZSCOKkH3hU9WkS5HLz6a8R32vT0_wqsl_JA2JRiurl-7WDqHVfpS5SlLQniuophlSKHxjg1Ss-tah6VFi1IzulNM0aDkirr9Kon75pqIucYJZZ8CBcD-8elX7a0SD5zzIerf4VZt_9Qn0q2O1WlYnmM-cfLmqTPuYfStiX7lUe_wCFS0O5TDYlUn2q_wCcvqKibqKKLE3P_9k"
}