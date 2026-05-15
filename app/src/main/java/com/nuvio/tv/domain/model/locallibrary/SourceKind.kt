package com.nuvio.tv.domain.model.locallibrary

enum class SourceKind {
    JELLYFIN,
    SMB,
    LOCAL_FILE;

    companion object {
        fun fromString(value: String?): SourceKind? = when (value?.trim()?.uppercase()) {
            "JELLYFIN" -> JELLYFIN
            "SMB" -> SMB
            "LOCAL_FILE" -> LOCAL_FILE
            else -> null
        }
    }
}
