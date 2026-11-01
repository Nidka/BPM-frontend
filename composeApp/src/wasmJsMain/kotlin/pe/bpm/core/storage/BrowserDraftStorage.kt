@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.storage

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** IndexedDB por origen; solo confirma escritura cuando la transacción finaliza. */
class BrowserDraftStorage : DraftStorage {
    override suspend fun read(key: String): String? = access("read", key)
    override suspend fun write(key: String, value: String) { require(value.encodeToByteArray().size <= MAX_DRAFT_BYTES); access("write", key, value) }
    override suspend fun remove(key: String) { access("remove", key) }
    override suspend fun compareAndSet(key: String, expected: String?, value: String): Boolean {
        require(value.encodeToByteArray().size <= MAX_DRAFT_BYTES)
        return access("compare", key, value, expected) == "changed"
    }
    private suspend fun access(operation: String, key: String, value: String = "", expected: String? = null): String? = suspendCancellableCoroutine { continuation ->
        accessDraft(operation, key, value, expected, { result -> if (continuation.isActive) continuation.resume(result) }, {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Almacenamiento local no disponible"))
        })
    }
}

// Comparación y escritura comparten una transacción readwrite, también entre pestañas.
@JsFun("""(operation, key, value, expected, resolve, reject) => {
    let request;
    try { request = indexedDB.open('bpm-drafts-v1', 1); } catch (_) { reject(); return; }
    let settled = false;
    const fail = () => { if (!settled) { settled = true; reject(); } };
    request.onupgradeneeded = () => request.result.createObjectStore('documents');
    request.onerror = request.onblocked = fail;
    request.onsuccess = () => {
        const db = request.result;
        if (settled) { db.close(); return; }
        db.onversionchange = () => db.close();
        try {
            const tx = db.transaction('documents', operation === 'read' ? 'readonly' : 'readwrite');
            const store = tx.objectStore('documents');
            const item = operation === 'read' || operation === 'compare' ? store.get(key) : operation === 'write' ? store.put(value, key) : store.delete(key);
            let result = null;
            item.onsuccess = () => {
                if (operation === 'read' || operation === 'compare') {
                    const actual = item.result === undefined ? null : item.result;
                    if (operation === 'read') result = actual;
                    else if (actual === expected) { store.put(value, key); result = 'changed'; }
                }
            };
            tx.oncomplete = () => { db.close(); if (!settled) { settled = true; resolve(result); } };
            tx.onabort = tx.onerror = () => { db.close(); fail(); };
        } catch (_) { db.close(); fail(); }
    };
}""")
private external fun accessDraft(operation: String, key: String, value: String, expected: String?, resolve: (String?) -> Unit, reject: () -> Unit)
