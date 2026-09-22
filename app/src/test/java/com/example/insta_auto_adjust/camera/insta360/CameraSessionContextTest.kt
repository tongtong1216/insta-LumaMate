package com.example.insta_auto_adjust.camera.insta360

import com.example.insta_auto_adjust.camera.contract.FrameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CameraSessionContextTest {
    @Test
    fun `new connection rotates epoch and records frame source`() {
        val context = CameraSessionContext()

        val firstEpoch = context.beginConnection(FrameSource.SDK_DECODED)
        val secondEpoch = context.beginConnection(FrameSource.SDK_RENDERED_PREVIEW)

        assertNotEquals(firstEpoch, secondEpoch)
        assertEquals(FrameSource.SDK_RENDERED_PREVIEW, context.frameSource)
    }

    @Test
    fun `disconnection clears frame source`() {
        val context = CameraSessionContext()
        context.beginConnection(FrameSource.SDK_RENDERED_PREVIEW)

        context.markDisconnected()

        assertEquals("disconnected", context.connectionEpoch)
        assertEquals(FrameSource.UNKNOWN, context.frameSource)
    }
}
