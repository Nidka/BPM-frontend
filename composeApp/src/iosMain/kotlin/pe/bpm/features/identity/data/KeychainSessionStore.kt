@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.identity.data

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.NSBundle
import platform.Security.*

/** Device-only Keychain item, accessible while unlocked; never synchronized to iCloud. */
class KeychainSessionStore : SessionStore {
    private val service = (NSBundle.mainBundle.bundleIdentifier ?: "pe.bpm.app") + ".session.v1"

    override fun read(): String? = query { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Session storage unavailable" }
            val data: CFDataRef = requireNotNull(result.value).reinterpret()
            try {
                val length = CFDataGetLength(data).toInt()
                require(length in 1..16384)
                CFDataGetBytePtr(data)!!.readBytes(length).decodeToString()
            } finally { CFRelease(data) }
        }
    }

    override fun write(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size in 1..12000)
        val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }!!
        try {
            query { query -> dictionary { attributes ->
                CFDictionarySetValue(attributes, kSecValueData, data)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                var status = SecItemUpdate(query, attributes)
                if (status == errSecItemNotFound) {
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                    status = SecItemAdd(query, null)
                }
                check(status == errSecSuccess) { "Session storage unavailable" }
            } }
        } finally { CFRelease(data) }
    }

    override fun clear() = query { query ->
        val status = SecItemDelete(query)
        check(status == errSecSuccess || status == errSecItemNotFound) { "Session storage unavailable" }
    }

    private fun <T> query(block: (CFMutableDictionaryRef) -> T): T {
        val serviceRef = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)!!
        val accountRef = CFStringCreateWithCString(null, "refresh", kCFStringEncodingUTF8)!!
        try {
            return dictionary { query ->
                CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
                CFDictionarySetValue(query, kSecAttrService, serviceRef)
                CFDictionarySetValue(query, kSecAttrAccount, accountRef)
                CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
                block(query)
            }
        } finally { CFRelease(serviceRef); CFRelease(accountRef) }
    }

    private fun <T> dictionary(block: (CFMutableDictionaryRef) -> T): T {
        // Values are kept alive explicitly until the Security API returns.
        val dictionary = CFDictionaryCreateMutable(null, 0, null, null)!!
        return try { block(dictionary) } finally { CFRelease(dictionary) }
    }
}
