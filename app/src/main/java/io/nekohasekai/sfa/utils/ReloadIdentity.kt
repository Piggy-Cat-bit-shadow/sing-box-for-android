package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.sfa.ktx.unwrap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The kernel's answer to a request-identified reload.
 *
 * [supported] false is a CAPABILITY answer from a core that predates the RPC: the reload was still
 * performed through the legacy call, but nothing can confirm which attempt it was. [instanceId] is
 * the kernel instance left running, [activeRevision] is the profile revision it serves, and [stale]
 * means the caller's expected instance was already replaced when the request arrived.
 */
@Serializable
data class ServiceReloadReadBack(
    val supported: Boolean = false,
    val unconfirmed: Boolean = false,
    val requestId: String = "",
    val instanceId: String = "",
    val activeRevision: String = "",
    val stale: Boolean = false,
    val status: String = "",
)

/**
 * The client half of the reload identity contract.
 *
 * `serviceReload()` was Empty -> Empty: the UI learned only that *a* reload happened. This keeps the
 * last instance the kernel named, sends it as the expectation of the next attempt, and reads the
 * echo so a late answer cannot be confused with a newer attempt. An old core still reloads - the
 * libbox entry falls back to the legacy RPC - and the result says `supported = false` instead of
 * claiming a confirmation nobody produced.
 */
object ReloadIdentity {
    private const val TAG = "ReloadIdentity"

    private val counter = AtomicLong(0)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastInstanceId: String = ""

    @Volatile
    var lastOutcome: ServiceReloadReadBack? = null
        private set

    /** Runs one reload with its own request identity. Call from an IO dispatcher. */
    fun reload(): ServiceReloadReadBack {
        val requestId = "android-" + System.currentTimeMillis() + "-" + counter.incrementAndGet()
        val document =
            CommandTarget.standaloneClient()
                .serviceReloadWithRequest(requestId, lastInstanceId)
                .unwrap
        val readBack =
            runCatching {
                json.decodeFromString<ServiceReloadReadBack>(document)
            }.getOrNull()
        lastOutcome = readBack
        if (readBack == null) {
            Log.w(TAG, "reload $requestId: the answer could not be read")
            return ServiceReloadReadBack(requestId = requestId)
        }
        if (readBack.supported && !readBack.unconfirmed) {
            if (readBack.instanceId.isNotEmpty()) {
                lastInstanceId = readBack.instanceId
            }
            if (readBack.stale) {
                Log.w(
                    TAG,
                    "reload $requestId: applied against an instance this process had not seen",
                )
            }
        } else {
            Log.w(
                TAG,
                "reload $requestId: the running core cannot confirm which instance it left",
            )
        }
        return readBack
    }
}
