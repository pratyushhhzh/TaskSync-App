package com.example.tasksync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val fullName: String? = null,
    val timezone: String = "UTC"
)

@Serializable
data class UserSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String? = null
)
