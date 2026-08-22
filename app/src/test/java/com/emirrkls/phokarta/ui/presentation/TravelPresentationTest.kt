package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.data.TravelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TravelPresentationTest {
    @Test fun formatsMetersAndKilometers() {
        assertEquals("240 m", formatDistance(240.0))
        assertEquals("1.8 km", formatDistance(1_800.0))
    }

    @Test fun errorCopyNeverExposesBackendMessage() {
        val message = TravelError.Server(500, "stack trace secret").toUserMessage()
        assertFalse(message.contains("stack trace"))
    }
}
