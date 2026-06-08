package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

@OptIn(UnstableApi::class)
class NuvioMediaCodecVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    companion object {
        private const val TAG = "NuvioCodecRenderer"
    }

    public override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val capabilities = super.supportsFormat(mediaCodecSelector, format)
        val formatSupport = RendererCapabilities.getFormatSupport(capabilities)
        
        val supportString = when (formatSupport) {
            C.FORMAT_HANDLED -> "FORMAT_HANDLED"
            C.FORMAT_EXCEEDS_CAPABILITIES -> "FORMAT_EXCEEDS_CAPABILITIES"
            C.FORMAT_UNSUPPORTED_SUBTYPE -> "FORMAT_UNSUPPORTED_SUBTYPE"
            C.FORMAT_UNSUPPORTED_TYPE -> "FORMAT_UNSUPPORTED_TYPE"
            else -> "UNKNOWN ($formatSupport)"
        }
        Log.d(TAG, "supportsFormat check: id=${format.id}, resolution=${format.width}x${format.height}, codecs=${format.codecs}, mime=${format.sampleMimeType}, defaultSupport=$supportString")

        // If ExoPlayer says it exceeds capabilities, check if it's due to strict level under-reporting (false negative)
        if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
            try {
                // Query available decoders for this format
                val decoderInfos = getDecoderInfos(mediaCodecSelector, format, /* requiresSecureDecoder= */ false)
                Log.d(TAG, "Exceeds capabilities. Found ${decoderInfos.size} decoders for MIME=${format.sampleMimeType}")
                if (decoderInfos.isNotEmpty()) {
                    val decoderInfo = decoderInfos[0]
                    val functionallySupported = decoderInfo.isFormatFunctionallySupported(format)
                    val sizeAndRateSupported = format.width <= 0 || format.height <= 0 || 
                         decoderInfo.isVideoSizeAndRateSupportedV21(format.width, format.height, format.frameRate.toDouble())
                    
                    val functionallyOrPhysicallySupported = (functionallySupported && sizeAndRateSupported) || 
                         (format.width <= 1920 && format.height <= 1080 && 
                          format.sampleMimeType?.startsWith("video/") == true && 
                          format.sampleMimeType != "video/dolby-vision")

                    Log.d(TAG, "Decoder ${decoderInfo.name}: functionallySupported=$functionallySupported, sizeAndRateSupported=$sizeAndRateSupported, functionallyOrPhysicallySupported=$functionallyOrPhysicallySupported")

                    if (functionallyOrPhysicallySupported) {
                        Log.i(TAG, "Upgrading format support to FORMAT_HANDLED for id=${format.id} (${format.width}x${format.height}) on decoder ${decoderInfo.name}")
                        val adaptiveSupport = RendererCapabilities.getAdaptiveSupport(capabilities)
                        val tunnelingSupport = RendererCapabilities.getTunnelingSupport(capabilities)
                        val hardwareAccelerationSupport = RendererCapabilities.getHardwareAccelerationSupport(capabilities)
                        val decoderSupport = RendererCapabilities.getDecoderSupport(capabilities)
                        
                        return RendererCapabilities.create(
                            C.FORMAT_HANDLED,
                            adaptiveSupport,
                            tunnelingSupport,
                            hardwareAccelerationSupport,
                            decoderSupport
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in capability check override for id=${format.id}", e)
            }
        }
        return capabilities
    }
}
