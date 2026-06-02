package com.example.voicegmail.contacts

import com.google.gson.annotations.SerializedName

/**
 * Subset of the Google People API response shape needed for read-only
 * retrieval of a user's contacts.  Only the fields actually consumed by
 * [ContactsRepository] are declared — everything else is ignored by Gson.
 *
 * Spec: https://developers.google.com/people/api/rest/v1/people.connections/list
 */
data class PeopleConnectionsResponse(
    @SerializedName("connections")   val connections:   List<Person>? = null,
    @SerializedName("nextPageToken") val nextPageToken: String? = null,
    @SerializedName("nextSyncToken") val nextSyncToken: String? = null,
    @SerializedName("totalPeople")   val totalPeople:   Int?    = null,
    @SerializedName("totalItems")    val totalItems:    Int?    = null
)

data class Person(
    @SerializedName("resourceName")   val resourceName:   String? = null,
    @SerializedName("names")          val names:          List<PersonName>?  = null,
    @SerializedName("emailAddresses") val emailAddresses: List<PersonEmail>? = null
)

data class PersonName(
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("givenName")   val givenName:   String? = null,
    @SerializedName("familyName")  val familyName:  String? = null
)

data class PersonEmail(
    @SerializedName("value") val value: String? = null,
    @SerializedName("type")  val type:  String? = null
)
