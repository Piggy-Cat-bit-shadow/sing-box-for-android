package io.nekohasekai.sfa.utils

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        /** The User-Agent subscription body requests send on first import and every refresh. */
        const val SUBSCRIPTION_USER_AGENT = "sing-box/1.15.0"

        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun getString(url: String, userAgent: String = HTTPClient.userAgent): String {
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        val response = request.execute()
        return response.content.unwrap
    }

    override fun close() {
        client.close()
    }
}
