package com.voicestudio.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A voice sample kept on the phone. It is saved here first, so nothing is lost while the
 * server is unreachable, and uploaded later; [serverId] is set once the server has cloned it.
 */
data class LocalVoice(
    val localId: String,
    val name: String,
    val transcript: String,
    val duration: Double,
    val created: Long,
    val serverId: String? = null,
    /** Ask the server to remove background noise when it uploads. */
    val denoise: Boolean = true,
)

class LocalVoices(context: Context) {
    private val dir = File(context.filesDir, "voices").apply { mkdirs() }

    fun wavFile(localId: String) = File(dir, "$localId.wav")

    fun all(): List<LocalVoice> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { parse(JSONObject(it.readText())) }.getOrNull() }
        .filter { wavFile(it.localId).isFile }
        .sortedByDescending { it.created }

    fun add(sample: Sample, name: String, transcript: String, denoise: Boolean): LocalVoice {
        val v = LocalVoice(
            localId = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name, transcript = transcript, duration = sample.seconds,
            created = System.currentTimeMillis(), denoise = denoise,
        )
        wavFile(v.localId).writeBytes(sample.toWav())
        save(v)
        return v
    }

    fun save(v: LocalVoice) {
        val o = JSONObject().put("localId", v.localId).put("name", v.name).put("transcript", v.transcript)
            .put("duration", v.duration).put("created", v.created).put("serverId", v.serverId).put("denoise", v.denoise)
        File(dir, "${v.localId}.json").writeText(o.toString())
    }

    fun delete(v: LocalVoice) {
        File(dir, "${v.localId}.json").delete()
        wavFile(v.localId).delete()
    }

    private fun parse(o: JSONObject) = LocalVoice(
        localId = o.getString("localId"),
        name = o.optString("name"),
        transcript = o.optString("transcript"),
        duration = o.optDouble("duration", 0.0),
        created = o.optLong("created"),
        serverId = if (o.isNull("serverId")) null else o.optString("serverId").ifEmpty { null },
        denoise = o.optBoolean("denoise", true),
    )
}

private val ADJECTIVES = listOf(
    "Amber", "Bright", "Calm", "Coral", "Golden", "Happy", "Jolly", "Lucky", "Misty", "Quiet",
    "Silver", "Sunny", "Swift", "Velvet", "Warm", "Wild",
)
private val NOUNS = listOf(
    "Falcon", "Koel", "Lotus", "Maple", "Mango", "Monsoon", "Otter", "Panda", "Peacock", "River",
    "Robin", "Sparrow", "Tiger", "Breeze", "Comet", "Echo",
)

fun randomVoiceName() = "${ADJECTIVES.random()} ${NOUNS.random()} ${(10..99).random()}"
