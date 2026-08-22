package dev.haquickaccess.tv.data

import java.net.URI

object UrlValidator {
    fun normalize(input: String): Result<String> = runCatching {
        val uri = URI(input.trim())
        require(uri.scheme == "https") { "Use an HTTPS URL" }
        require(!uri.host.isNullOrBlank()) { "Enter a Home Assistant host" }
        require(uri.userInfo == null && uri.fragment == null) { "URL cannot contain credentials or a fragment" }
        URI("https", null, uri.host, uri.port, uri.path?.trimEnd('/'), null, null).toString().trimEnd('/')
    }
}
