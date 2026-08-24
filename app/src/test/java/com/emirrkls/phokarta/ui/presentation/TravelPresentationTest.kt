package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.core.data.TravelError
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TravelPresentationTest {
    @Test fun formatsMetersAndKilometers() {
        assertEquals("240 m", formatDistance(240.0, Locale.US))
        assertEquals("1.8 km", formatDistance(1_800.0, Locale.US))
        assertEquals("1,8 km", formatDistance(1_800.0, Locale("tr", "TR")))
    }

    @Test fun errorCopyNeverExposesBackendMessage() {
        val messageRes = TravelError.Server(500, "stack trace secret").toUserMessageRes()
        assertEquals(R.string.error_server, messageRes)
        assertFalse("stack trace secret" == messageRes.toString())
    }
}
