package edu.colorado.rrassist.llm

import edu.colorado.rrassist.settings.RRAssistConfig
import edu.colorado.rrassist.utils.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class LlmHealthCheck {
    companion object {
        fun test(cfg: RRAssistConfig): Boolean = runBlocking {
            val client = LlmFactory.create(cfg)
            val probe = listOf(
                ChatMsg(Role.System, "Reply with only the word OK."),
                ChatMsg(Role.User, "Say OK")
            )

            val mark = TimeSource.Monotonic.markNow()
            Log.info("Health check started for provider=${cfg.provider} baseUrl=${cfg.baseUrl} model=${cfg.model}")

//            TODO: timeout is not caught and handled properly
            val success = try {
                val resp = withTimeout((cfg.timeoutSeconds.toLong()).seconds) {
                    client.chat(probe)  // suspend call: OK
                }
                val s = resp.trim().lowercase()
                val ok = s == "ok" || s.startsWith("ok")
                Log.info("Health check response=\"${s.take(60)}\" ok=$ok")

                ok
            } catch (t: Throwable) {
                Log.warn("Health check failed for ${cfg.provider}: ${t.message ?: t::class.simpleName}")
                Log.debug("Exception", t)
                false
            }
            val elapsed = mark.elapsedNow()
            Log.info("Health check completed in $elapsed (success=$success)")
            success
        }
    }

}