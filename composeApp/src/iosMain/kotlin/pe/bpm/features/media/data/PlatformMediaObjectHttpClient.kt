@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.media.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.HttpRequestBuilder
import platform.Foundation.*

internal actual fun HttpRequestBuilder.configureMediaObjectRequest() = Unit

internal actual fun mediaObjectHttpEngine(): HttpClientEngine = Darwin.create {
    configureSession {
        setURLCache(null)
        setHTTPCookieStorage(null)
        setURLCredentialStorage(null)
        HTTPShouldSetCookies = false
        requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
    }
    configureRequest { HTTPShouldHandleCookies = false }
}

internal actual fun includeMediaObjectHeader(name: String): Boolean = true
