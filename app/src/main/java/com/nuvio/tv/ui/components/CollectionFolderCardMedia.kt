package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.CollectionFolder

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    // When focusGifEnabled is off, the GIF URL acts as a regular poster (priority over cover image).
    val effectiveCover = if (!folder.focusGifEnabled) {
        firstNonBlank(folder.focusGifUrl, folder.coverImageUrl)
    } else {
        firstNonBlank(folder.coverImageUrl)
    }
    return effectiveCover
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}