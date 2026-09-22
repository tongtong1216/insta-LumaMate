package com.example.insta_auto_adjust.camera.insta360

import com.example.insta_auto_adjust.camera.contract.FrameSource
import java.util.UUID

/**
 * Connection-owned metadata. The connection layer must rotate the epoch after every successful
 * reconnect so proposals created for a previous camera session become invalid.
 */
class CameraSessionContext {
    @Volatile
    var connectionEpoch: String = "disconnected"
        private set

    @Volatile
    var frameSource: FrameSource = FrameSource.UNKNOWN
        private set

    fun beginConnection(source: FrameSource = FrameSource.UNKNOWN): String {
        connectionEpoch = UUID.randomUUID().toString()
        frameSource = source
        return connectionEpoch
    }

    fun updateFrameSource(source: FrameSource) {
        frameSource = source
    }

    fun markDisconnected() {
        connectionEpoch = "disconnected"
        frameSource = FrameSource.UNKNOWN
    }
}
