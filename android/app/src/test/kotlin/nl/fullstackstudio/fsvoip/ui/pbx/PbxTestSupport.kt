// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui.pbx

import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.jsonObject
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.ChainStepRequest
import nl.fullstackstudio.fsvoip.core.FsJson
import nl.fullstackstudio.fsvoip.core.NumberChain
import nl.fullstackstudio.fsvoip.core.NumberRecordingPatch
import nl.fullstackstudio.fsvoip.core.PbxDevicePatch
import nl.fullstackstudio.fsvoip.core.PbxDevicesResponse
import nl.fullstackstudio.fsvoip.core.PbxHoursPatch
import nl.fullstackstudio.fsvoip.core.PbxHoursResponse
import nl.fullstackstudio.fsvoip.core.PbxNumbersPage
import nl.fullstackstudio.fsvoip.core.PbxOverview
import nl.fullstackstudio.fsvoip.core.PbxRingGroupCreate
import nl.fullstackstudio.fsvoip.core.PbxRingGroupCreated
import nl.fullstackstudio.fsvoip.core.PbxRingGroupPatch
import nl.fullstackstudio.fsvoip.core.PbxRingGroupsResponse
import nl.fullstackstudio.fsvoip.core.PbxRoutingPatch
import nl.fullstackstudio.fsvoip.core.PbxSoundChoice

/** The shared fixtures, decoded with the app's models (the same files the contract tests use). */
object Fx {
    private val directory: File by lazy {
        val path = System.getProperty("fsvoip.fixtures") ?: "../../shared/fixtures"
        File(path).also { require(it.isDirectory) { "Fixtures not found at $path" } }
    }

    fun text(name: String): String = File(directory, "$name.json").readText()

    fun <T> decode(name: String, serializer: KSerializer<T>): T = FsJson.default.decodeFromString(serializer, text(name))

    fun overview() = decode("pbx-overview", PbxOverview.serializer())
    fun devices() = decode("pbx-devices", PbxDevicesResponse.serializer())
    fun ringGroups() = decode("pbx-ring-groups", PbxRingGroupsResponse.serializer())
    fun hours() = decode("pbx-hours", PbxHoursResponse.serializer())
    fun numbers() = decode("numbers-page", PbxNumbersPage.serializer())
    fun chainSimple() = decode("number-chain-simple", NumberChain.serializer())
    fun chainMenu() = decode("number-chain-menu", NumberChain.serializer())
    fun chainAdvanced() = decode("number-chain-advanced", NumberChain.serializer())
}

/** The Dutch strings of the app, read from the resource XML (a plain JVM test has no Android resources). */
class DutchText : PbxText {
    private val strings: Map<Int, String> by lazy {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        val values = mutableMapOf<String, String>()
        val dir = File("src/main/res/values")

        for (file in dir.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!) {
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).forEach { match ->
                values[match.groupValues[1]] = match.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&").replace("&lt;", "<")
            }
        }

        names.mapNotNull { (id, name) -> values[name]?.let { id to it } }.toMap()
    }

    override fun get(id: Int, vararg args: Any): String {
        val template = strings[id] ?: return "<$id>"
        var text = template

        args.forEachIndexed { index, arg ->
            text = text.replace("%${index + 1}\$s", arg.toString()).replace("%${index + 1}\$d", arg.toString())
        }

        return if (args.isNotEmpty()) text.replaceFirst("%s", args[0].toString()).replaceFirst("%d", args[0].toString()) else text
    }
}

val dutchLocale: Locale = Locale("nl", "NL")

/** An in-memory phone system: every call is recorded and answered from a queue of results. */
class FakePbxService(
    var overviewValue: PbxOverview = Fx.overview(),
    var devicesValue: PbxDevicesResponse = Fx.devices(),
    var ringGroupsValue: PbxRingGroupsResponse = Fx.ringGroups(),
    var hoursValue: PbxHoursResponse = Fx.hours(),
    var numbersValue: PbxNumbersPage = Fx.numbers(),
    var chain: NumberChain = Fx.chainSimple(),
) : PbxServicing {
    val calls = mutableListOf<String>()
    var failure: Exception? = null
    var overviewLoads = 0
    var onSaveChain: ((ChainStepRequest) -> NumberChain)? = null

    private fun fail() {
        failure?.let { throw it }
    }

    override suspend fun overview(): PbxOverview {
        overviewLoads += 1
        fail()
        return overviewValue
    }

    override suspend fun devices(): PbxDevicesResponse {
        fail()
        return devicesValue
    }

    override suspend fun updateDevice(id: String, patch: PbxDevicePatch) {
        calls += "updateDevice:$id"
        fail()
    }

    override suspend fun ringGroups(): PbxRingGroupsResponse {
        fail()
        return ringGroupsValue
    }

    override suspend fun createRingGroup(group: PbxRingGroupCreate): PbxRingGroupCreated {
        calls += "createRingGroup"
        fail()
        return PbxRingGroupCreated("new")
    }

    override suspend fun updateRingGroup(id: String, patch: PbxRingGroupPatch) {
        calls += "updateRingGroup:$id"
        fail()
    }

    override suspend fun hours(): PbxHoursResponse {
        fail()
        return hoursValue
    }

    override suspend fun updateHours(id: String, patch: PbxHoursPatch) {
        calls += "updateHours:$id"
        fail()
    }

    override suspend fun setNumberRouting(numberId: String, patch: PbxRoutingPatch) {
        calls += "routing:$numberId"
        fail()
    }

    override suspend fun numbers(): PbxNumbersPage {
        fail()
        return numbersValue
    }

    override suspend fun numberChain(numberId: String): NumberChain {
        fail()
        return chain
    }

    override suspend fun saveChainStep(numberId: String, step: ChainStepRequest): NumberChain {
        calls += "step:${step.step.wire}"
        fail()
        return onSaveChain?.invoke(step) ?: chain
    }

    override suspend fun setNumberRecording(numberId: String, patch: NumberRecordingPatch): NumberChain {
        calls += "recording"
        fail()
        return chain
    }

    override suspend fun sounds(): List<PbxSoundChoice> = emptyList()
}

fun testScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

/** The `stale` answer of a chain step: the fixture's fresh chain (version 5). */
fun staleChain(): ApiException = ApiException.StaleChain(FsJson.default.parseToJsonElement(Fx.text("error-stale-chain")).jsonObject["chain"]!!)
