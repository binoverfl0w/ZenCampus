package io.github.binoverfl0w.zencampus.model

import kotlinx.serialization.Serializable

@Serializable
data class CampusMessage(val id: String, val subject: String, val body: String,
    val timestamp: Long, val read: Boolean, val sender: String = "", val senderId: String = "",
    val recipients: List<String> = emptyList())

@Serializable
data class CampusPerson(val id: String, val name: String, val username: String = "")
