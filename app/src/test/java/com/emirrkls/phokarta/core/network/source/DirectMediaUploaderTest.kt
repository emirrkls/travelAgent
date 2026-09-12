package com.emirrkls.phokarta.core.network.source

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectMediaUploaderTest {
    @Test
    fun `presigned upload uses isolated client without bearer authorization`() = runTest {
        val server = MockWebServer()
        val file = File.createTempFile("phokarta-upload", ".jpg")
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            file.writeBytes(byteArrayOf(1, 2, 3, 4))

            val result = DirectMediaUploader(OkHttpClient()).put(
                url = server.url("/synthetic.jpg").toString(),
                headers = mapOf("x-amz-meta-test" to "accepted"),
                file = file,
                contentType = "image/jpeg",
                byteSize = file.length(),
            )

            assertEquals(DirectUploadResult.Success, result)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("accepted", request.getHeader("x-amz-meta-test"))
            assertNull(request.getHeader("Authorization"))
        } finally {
            file.delete()
            server.shutdown()
        }
    }
}
