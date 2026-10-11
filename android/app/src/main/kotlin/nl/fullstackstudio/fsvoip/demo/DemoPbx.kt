// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import nl.fullstackstudio.fsvoip.core.HttpResponse

/**
 * The pretend "Centrale" API of demo mode (JEST Bouw): overview, devices, ring groups, hours, numbers and their chains. Edits stay in
 * this object, so the screens behave like against the real server: a version goes up on every change, a change shows "pending" for a
 * few seconds, a stale version answers `stale`.
 */
class DemoPbxHandler(private val now: () -> Long = System::currentTimeMillis) : DemoHandler {
    /** Pretend the PBX is frozen: `GET me` says so and every write is refused (`read_only`). */
    @Volatile var readOnly: Boolean = false

    private val lock = Any()
    private val pendingUntil = mutableMapOf<String, Long>()

    private val sharedBox = "vm-algemeen"
    private val welcomeSound = "snd-welkom"
    private val closedSound = "snd-gesloten"
    private val hoursId = "hours-kantoor"
    private val groupAll = "grp-iedereen"
    private val queueId = "queue-support"

    private val devices = linkedMapOf<String, JsonObject>()
    private val groups = linkedMapOf<String, JsonObject>()
    private var hours: JsonObject
    private val numbers = linkedMapOf<String, DemoNumber>()
    private val sounds = listOf(welcomeSound to "Welkom bij JEST Bouw", closedSound to "Buiten kantoortijd", "snd-wacht" to "Wachtmuziek")

    private class DemoNumber(
        val id: String,
        val number: String,
        var name: String?,
        val advanced: Boolean,
        var routing: JsonObject?,
        var isDefault: Boolean,
    ) {
        var version = 1
        var hoursVersion = 1
        var welcomeVersion = 1
        var forwardingVersion = 1
        var hoursEnabled = true
        var week: List<JsonObject> = emptyList()
        var national = true
        var dates: List<Pair<String, String>> = emptyList()
        var closed: JsonObject = buildJsonObject { put("mode", "hangup") }
        var holiday: JsonObject? = null
        var welcomeEnabled = false
        var welcomeSoundId: String? = null
        var kind = "standard"
        var strategy = "all"
        var members: List<JsonObject> = emptyList()
        var unanswered: JsonObject = buildJsonObject { put("mode", "hangup") }
        var greeting: String? = null
        var repeats = 2
        var timeoutSeconds = 8
        var defaultKey: String? = null
        var noChoice: JsonObject = buildJsonObject { put("mode", "hangup") }
        var keys: List<JsonObject> = emptyList()
        var recordingEnabled = false
        var announcement: String? = null
        var billingActive = false
        var summary: List<String> = emptyList()
    }

    init {
        fun device(id: String, name: String, ext: String, email: String? = null, configure: (MutableMap<String, JsonElement>) -> Unit = {}) {
            val map = mutableMapOf<String, JsonElement>(
                "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "version" to JsonPrimitive(1),
                "musicSoundId" to JsonNull, "voicemailGreetingSoundId" to JsonNull, "extension" to JsonPrimitive(ext),
                "email" to (email?.let(::JsonPrimitive) ?: JsonNull), "voicemailEnabled" to JsonPrimitive(true), "voicemailToEmail" to JsonPrimitive(email != null),
                "voicemailAttachFile" to JsonPrimitive(false), "outboundNumberId" to JsonNull, "dnd" to JsonPrimitive(false), "suspended" to JsonPrimitive(false),
                "recordCalls" to JsonPrimitive(false), "noAnswerSeconds" to JsonPrimitive(25), "noAnswerTarget" to JsonNull, "busyTarget" to JsonNull,
                "notRegisteredTarget" to JsonNull, "forwardAlways" to JsonNull, "followMe" to JsonArray(emptyList()),
                "registration" to JsonNull,
            )
            configure(map)
            devices[id] = JsonObject(map)
        }

        fun reg(count: Int, agent: String?) = buildJsonObject { put("connected", count > 0); put("count", count); put("agent", agent?.let(::JsonPrimitive) ?: JsonNull) }

        device("dev-100", "Receptie", "100") { it["registration"] = reg(1, "Yealink T54W") }
        device("dev-101", "Pieter Jansen", "101", "pieter@jest-bouw.nl") {
            it["dnd"] = JsonPrimitive(true)
            it["forwardAlways"] = target("external", number = "+31612345678")
            it["registration"] = reg(0, null)
        }
        device("dev-102", "Jan de Vries", "102") {
            it["registration"] = reg(1, "FSVoip Android")
            it["followMe"] = buildJsonArray { add(buildJsonObject { put("target", target("external", number = "+31687654321")); put("delaySeconds", 10); put("timeoutSeconds", 20) }) }
        }
        device("dev-103", "Werkplaats", "103") { it["registration"] = reg(1, "Grandstream HT801") }
        device("dev-104", "Magazijn en expeditie, Bart van der Berg-Oosterhuis (tijdelijk)", "104") { it["registration"] = reg(0, null) }

        groups[groupAll] = buildJsonObject {
            put("id", groupAll); put("name", "Iedereen"); put("version", 2); put("extension", "200"); put("strategy", "all")
            put("members", JsonArray(listOf("dev-100", "dev-101", "dev-103").map { member(it, 0, 25) }))
            put("timeoutTarget", target("voicemail", sharedBox)); put("sync", "ok")
        }
        groups["grp-werkplaats"] = buildJsonObject {
            put("id", "grp-werkplaats"); put("name", "Werkplaats en magazijn"); put("version", 1); put("extension", "201"); put("strategy", "sequence")
            put("members", JsonArray(listOf(member("dev-103", 0, 20), member("dev-104", 20, 20))))
            put("timeoutTarget", JsonNull); put("sync", "ok")
        }

        hours = buildJsonObject {
            put("id", hoursId); put("name", "Kantoortijden"); put("extension", "500"); put("timezone", "Europe/Amsterdam")
            put("week", JsonArray((1..5).map { day(it, "09:00", "17:00") }))
            put("openTarget", target("ring_group", groupAll)); put("closedTarget", target("voicemail", sharedBox))
            put("holidays", buildJsonArray {
                add(buildJsonObject { put("name", "Eerste kerstdag"); put("date", JsonNull); put("rule", "christmas_day"); put("target", JsonNull) })
                add(buildJsonObject { put("name", "Bouwvak"); put("date", "2026-08-03"); put("rule", JsonNull); put("target", target("voicemail", sharedBox)) })
            })
            put("version", 6); put("sync", "ok")
        }

        fun number(id: String, number: String, name: String?, advanced: Boolean, routing: JsonObject?, default: Boolean = false) =
            DemoNumber(id, number, name, advanced, routing, default).also { numbers[id] = it }

        number("num-1", "0850607848", "Hoofdnummer", false, target("business_hours", hoursId), default = true).apply {
            week = (1..5).map { day(it, "09:00", "17:00") }
            dates = listOf("Bouwvak" to "2026-08-03")
            closed = buildJsonObject { put("mode", "voicemail"); put("boxId", sharedBox) }
            welcomeEnabled = true; welcomeSoundId = welcomeSound
            members = listOf(memberOf("dev-100", 0, 25), memberOf("dev-101", 0, 25), memberOf("dev-102", 5, 20))
            unanswered = buildJsonObject { put("mode", "voicemail"); put("boxId", sharedBox) }
            version = 4; hoursVersion = 6; welcomeVersion = 3; forwardingVersion = 3
            billingActive = false
        }
        number("num-2", "0850607849", null, false, target("ivr", "ivr-menu")).apply {
            week = listOf(day(1, "08:00", "12:00"), day(1, "13:00", "17:30"), day(2, "08:00", "17:30"))
            national = false
            closed = buildJsonObject { put("mode", "message"); put("soundId", closedSound) }
            holiday = buildJsonObject { put("mode", "hangup") }
            kind = "menu"; greeting = welcomeSound
            defaultKey = "1"
            keys = listOf(key("1", target("extension", "dev-100")), key("2", target("external", number = "0612345678")), key("3", target("queue", queueId)))
            recordingEnabled = true; announcement = closedSound; billingActive = true
            version = 2
        }
        number("num-3", "0850607850", "Support", true, target("queue", queueId)).apply {
            summary = listOf("Openingstijden: Kantoortijden", "Wachtrij: Support (3 medewerkers)", "Keuzemenu met een submenu")
            recordingEnabled = true; billingActive = true
            version = 2
        }
    }

    // MARK: Routing of requests

    override fun handle(request: DemoRequest): HttpResponse? = synchronized(lock) {
        val account = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

        if (request.matches("GET", "me") && readOnly) return frozenMe(request)

        val segments = request.segments

        if (segments.firstOrNull() != "pbx") return null
        if (account != DemoMode.ADMIN_ID) return demoJson(403, """{"error":"forbidden","required":"admin"}""")

        if (request.method != "GET" && readOnly) return demoError(409, "read_only")

        return when {
            request.matches("GET", "pbx", "overview") -> demoJson(200, overview())
            request.matches("GET", "pbx", "devices") -> demoJson(200, buildJsonObject { put("devices", JsonArray(devices.values.map(::withSync))); put("targets", targets()); put("numbers", numberRefs()) })
            request.matches("PATCH", "pbx", "devices", "*") -> patchObject(request, devices, segments[2])
            request.matches("GET", "pbx", "ring-groups") -> demoJson(200, buildJsonObject {
                put("ringGroups", JsonArray(groups.values.map(::withSync))); put("devices", deviceRefs()); put("targets", targets())
            })
            request.matches("POST", "pbx", "ring-groups") -> createGroup(request)
            request.matches("PATCH", "pbx", "ring-groups", "*") -> patchObject(request, groups, segments[2])
            request.matches("GET", "pbx", "hours") -> demoJson(200, buildJsonObject {
                put("hours", JsonArray(listOf(withSync(hours)))); put("targets", targets()); put("holidayRules", holidayRules())
            })
            request.matches("PATCH", "pbx", "hours", "*") -> patchHours(request)
            request.matches("GET", "pbx", "sounds") -> demoJson(200, buildJsonObject {
                put("sounds", JsonArray(sounds.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
            })
            request.matches("GET", "pbx", "numbers") -> demoJson(200, buildJsonObject { put("numbers", JsonArray(numbers.values.map(::entry))) })
            request.matches("GET", "pbx", "numbers", "*", "chain") -> numbers[segments[2]]?.let { demoJson(200, chain(it)) } ?: demoError(404, "not_found")
            request.matches("PUT", "pbx", "numbers", "*", "chain", "*") -> putStep(request)
            request.matches("PATCH", "pbx", "numbers", "*", "routing") -> patchRouting(request)
            request.matches("PATCH", "pbx", "numbers", "*", "recording") -> patchRecording(request)
            else -> null
        }
    }

    private fun frozenMe(request: DemoRequest): HttpResponse? {
        val response = CoreDemoHandler().handle(request) ?: return null
        val text = String(response.body ?: ByteArray(0), Charsets.UTF_8).replace("\"state\":\"active\",\"readOnly\":false", "\"state\":\"frozen\",\"readOnly\":true")

        return HttpResponse(response.status, response.headers, text.toByteArray(Charsets.UTF_8))
    }

    // MARK: Building blocks

    private fun target(type: String, id: String? = null, number: String? = null): JsonObject = buildJsonObject {
        put("type", type)
        if (id != null) put("id", id)
        if (number != null) put("number", number)
    }

    private fun day(day: Int, from: String, to: String) = buildJsonObject { put("day", day); put("from", from); put("to", to) }

    private fun member(deviceId: String, delay: Int, timeout: Int) = buildJsonObject { put("extensionId", deviceId); put("delaySeconds", delay); put("timeoutSeconds", timeout) }

    private fun memberOf(deviceId: String, delay: Int, timeout: Int) = buildJsonObject { put("deviceId", deviceId); put("delaySeconds", delay); put("timeoutSeconds", timeout) }

    private fun key(digit: String, target: JsonObject) = buildJsonObject { put("digit", digit); put("target", target); put("editable", target["type"]?.jsonPrimitive?.contentOrNull != "queue") }

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun sync(id: String): String = if ((pendingUntil[id] ?: 0) > now()) "pending" else "ok"

    private fun touch(id: String) {
        pendingUntil[id] = now() + 4_000
    }

    private fun withSync(obj: JsonObject): JsonObject = JsonObject(obj + ("sync" to JsonPrimitive(sync(obj["id"]!!.jsonPrimitive.content))))

    private fun targets(): JsonArray = buildJsonArray {
        fun option(type: String, id: String?, name: String, extension: String?, ofDevice: Boolean = false) = add(buildJsonObject {
            put("value", if (id == null) type else "$type:$id"); put("type", type); put("id", id?.let(::JsonPrimitive) ?: JsonNull); put("name", name)
            put("extension", extension?.let(::JsonPrimitive) ?: JsonNull)
            if (ofDevice) put("ofDevice", true)
        })

        for (device in devices.values) option("extension", device["id"]!!.jsonPrimitive.content, device["name"]!!.jsonPrimitive.content, device["extension"]!!.jsonPrimitive.content)
        for (group in groups.values) option("ring_group", group["id"]!!.jsonPrimitive.content, group["name"]!!.jsonPrimitive.content, group["extension"]!!.jsonPrimitive.content)
        option("queue", queueId, "Support", "300")
        option("business_hours", hoursId, "Kantoortijden", "500")
        option("voicemail", sharedBox, "Algemeen", "900")
        for (device in devices.values) option("voicemail", device["id"]!!.jsonPrimitive.content, device["name"]!!.jsonPrimitive.content, device["extension"]!!.jsonPrimitive.content, ofDevice = true)
        option("external", null, "Extern nummer", null)
    }

    private fun numberRefs() = JsonArray(numbers.values.map { buildJsonObject { put("id", it.id); put("number", it.number) } })

    private fun deviceRefs() = JsonArray(devices.values.map { buildJsonObject { put("id", it["id"]!!); put("name", it["name"]!!); put("extension", it["extension"]!!) } })

    private fun holidayRules() = buildJsonArray {
        for ((rule, label) in listOf("new_year" to "Nieuwjaarsdag", "kings_day" to "Koningsdag", "christmas_day" to "Eerste kerstdag", "boxing_day" to "Tweede kerstdag")) {
            add(buildJsonObject { put("rule", rule); put("label", label) })
        }
    }

    private fun nameOf(type: String?, id: String?): String = when (type) {
        "extension", "voicemail" -> devices[id]?.get("name")?.jsonPrimitive?.contentOrNull ?: "Algemeen"
        "ring_group" -> groups[id]?.get("name")?.jsonPrimitive?.contentOrNull ?: "Groep"
        "queue" -> "Support"
        "business_hours" -> "Kantoortijden"
        "ivr" -> "Keuzemenu"
        else -> "Onbekend"
    }

    private fun flowNode(kind: String, id: String? = null, name: String? = null, number: String? = null, branches: List<JsonObject> = emptyList()) = buildJsonObject {
        put("kind", kind); put("id", id?.let(::JsonPrimitive) ?: JsonNull); put("name", name?.let(::JsonPrimitive) ?: JsonNull)
        put("extension", JsonNull); put("ofDevice", false); put("number", number?.let(::JsonPrimitive) ?: JsonNull); put("dnd", false)
        put("branches", JsonArray(branches))
    }

    private fun branch(kind: String, node: JsonObject, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) = buildJsonObject {
        put("when", buildJsonObject { put("kind", kind); extra?.invoke(this) }); put("node", node)
    }

    private fun flowOf(target: JsonObject?): JsonObject {
        val type = target?.get("type")?.jsonPrimitive?.contentOrNull ?: return flowNode("none")
        val id = stringOf(target["id"])

        return when (type) {
            "business_hours" -> flowNode(
                "business_hours", id, nameOf(type, id),
                branches = listOf(
                    branch("open", flowNode("ring_group", groupAll, "Iedereen", branches = listOf(branch("no_answer", flowNode("voicemail", sharedBox, "Algemeen")) { put("seconds", 25) }))),
                    branch("closed", flowNode("voicemail", sharedBox, "Algemeen")),
                ),
            )
            "ivr" -> flowNode(
                "ivr", id, "Keuzemenu",
                branches = listOf(
                    branch("key", flowNode("extension", "dev-100", "Receptie")) { put("digit", "1") },
                    branch("key", flowNode("external", number = "0612345678")) { put("digit", "2") },
                    branch("key", flowNode("queue", queueId, "Support")) { put("digit", "3") },
                    branch("no_choice", flowNode("extension", "dev-100", "Receptie")),
                ),
            )
            "external" -> flowNode("external", number = stringOf(target["number"]))
            else -> flowNode(type, id, nameOf(type, id))
        }
    }

    private fun overview(): JsonObject = buildJsonObject {
        put("pbx", buildJsonObject { put("name", "JEST Bouw"); put("state", if (readOnly) "frozen" else "active"); put("readOnly", readOnly) })
        put("numbers", JsonArray(numbers.values.map { number ->
            buildJsonObject {
                put("id", number.id); put("number", number.number); put("routing", number.routing ?: JsonNull); put("blockListId", JsonNull)
                put("isDefault", number.isDefault); put("version", number.version); put("sync", sync(number.id))
                put("recordCalls", number.recordingEnabled); put("recordAnnouncementSoundId", number.announcement?.let(::JsonPrimitive) ?: JsonNull)
                put("recordBillingActive", number.billingActive); put("flow", flowOf(number.routing))
            }
        }))
        put("entryFlow", JsonNull)
        put("deviceCount", devices.size); put("connectedCount", devices.values.count { it["registration"]?.jsonObject?.get("connected")?.jsonPrimitive?.booleanOrNull == true })
        put("ringGroupCount", groups.size); put("outboundBlocked", false)
    }

    // MARK: Plain patches

    private fun bumped(id: String, current: JsonObject, body: JsonObject, skip: Set<String> = setOf("version")): Pair<JsonObject?, HttpResponse?> {
        val version = current["version"]?.jsonPrimitive?.intOrNull ?: 1
        val sent = body["version"]?.jsonPrimitive?.intOrNull

        if (sent != null && sent != version) return null to demoJson(409, """{"error":"stale","version":$version}""")

        val merged = current.toMutableMap()

        for ((key, value) in body) {
            if (key !in skip) merged[key] = value
        }

        merged["version"] = JsonPrimitive(version + 1)
        touch(id)

        return JsonObject(merged) to null
    }

    private fun patchObject(request: DemoRequest, store: MutableMap<String, JsonObject>, id: String): HttpResponse {
        val current = store[id] ?: return demoError(404, "not_found")
        val (updated, failure) = bumped(id, current, request.bodyObject())

        if (failure != null) return failure

        store[id] = updated!!

        return demoOk()
    }

    private fun createGroup(request: DemoRequest): HttpResponse {
        val body = request.bodyObject()
        val id = "grp-" + UUID.randomUUID().toString().take(8)
        val extension = (200 + groups.size + 1).toString()

        groups[id] = buildJsonObject {
            put("id", id); put("name", body["name"] ?: JsonPrimitive("Nieuwe groep")); put("version", 1); put("extension", extension)
            put("strategy", body["strategy"] ?: JsonPrimitive("all")); put("members", body["members"] ?: JsonArray(emptyList()))
            put("timeoutTarget", body["timeoutTarget"] ?: JsonNull); put("sync", "pending")
        }
        touch(id)

        return demoJson(201, """{"ringGroupId":"$id"}""")
    }

    private fun patchHours(request: DemoRequest): HttpResponse {
        val body = request.bodyObject()
        val holidayTarget = body["holidayTarget"]
        val labels = holidayRules().associate { it.jsonObject["rule"]!!.jsonPrimitive.content to it.jsonObject["label"]!!.jsonPrimitive.content }
        val converted = body["holidays"]?.jsonArray?.let { list ->
            JsonArray(list.map { item ->
                val o = item.jsonObject
                val rule = stringOf(o["rule"])

                buildJsonObject {
                    put("name", rule?.let { labels[it] ?: it } ?: stringOf(o["name"]) ?: "Gesloten")
                    put("date", stringOf(o["date"])?.let(::JsonPrimitive) ?: JsonNull)
                    put("rule", rule?.let(::JsonPrimitive) ?: JsonNull)
                    put("target", if (rule == null && holidayTarget != null) holidayTarget else JsonNull)
                }
            })
        }
        val patch = JsonObject(body.filterKeys { it != "holidays" && it != "holidayTarget" } + (converted?.let { mapOf("holidays" to it) } ?: emptyMap()))
        val (updated, failure) = bumped(hoursId, hours, patch)

        if (failure != null) return failure

        hours = updated!!

        return demoOk()
    }

    private fun patchRouting(request: DemoRequest): HttpResponse {
        val number = numbers[request.segments[2]] ?: return demoError(404, "not_found")
        val body = request.bodyObject()
        val sent = body["version"]?.jsonPrimitive?.intOrNull

        if (sent != null && sent != number.version) return demoJson(409, """{"error":"stale","version":${number.version}}""")

        number.routing = (body["target"] as? JsonObject)
        number.version += 1
        touch(number.id)

        return demoOk()
    }

    // MARK: Numbers

    private fun entry(number: DemoNumber): JsonObject = buildJsonObject {
        put("id", number.id); put("number", number.number); put("name", number.name?.let(::JsonPrimitive) ?: JsonNull)
        put("mode", if (number.advanced) "advanced" else "simple")
        put("summary", buildJsonObject {
            put("hours", if (number.advanced) "Uit" else if (number.hoursEnabled) hoursLine(number) else "Uit")
            put("welcome", number.welcomeEnabled || number.kind == "menu")
            put("forwarding", if (number.advanced) "advanced" else number.kind)
            put("recording", number.recordingEnabled)
        })
        put("sync", sync(number.id)); put("version", number.version)
    }

    private fun hoursLine(number: DemoNumber): String {
        val days = number.week.map { it["day"]!!.jsonPrimitive.int }.toSet()
        val first = number.week.firstOrNull() ?: return "Gesloten"

        return if (days == setOf(1, 2, 3, 4, 5)) "ma-vr ${stripZero(first["from"]!!.jsonPrimitive.content)}-${stripZero(first["to"]!!.jsonPrimitive.content)}" else "${days.size} dagen"
    }

    private fun stripZero(time: String) = time.removePrefix("0")

    private val JsonPrimitive.int: Int get() = intOrNull ?: 0

    private fun fallbackJson(source: JsonObject): JsonObject {
        val mode = stringOf(source["mode"])

        return if (mode == "voicemail") {
            val box = stringOf(source["boxId"]) ?: sharedBox
            buildJsonObject { put("mode", "voicemail"); put("boxId", box); put("ofDevice", box != sharedBox) }
        } else {
            source
        }
    }

    private fun chain(number: DemoNumber): JsonObject = buildJsonObject {
        put("id", number.id); put("number", number.number); put("name", number.name?.let(::JsonPrimitive) ?: JsonNull)
        put("version", number.version); put("mode", if (number.advanced) "advanced" else "simple"); put("sync", sync(number.id))

        if (number.advanced) {
            put("hours", JsonNull); put("welcome", JsonNull); put("forwarding", JsonNull)
            put("advanced", buildJsonObject { putJsonArray("summary") { number.summary.forEach { add(JsonPrimitive(it)) } } })
        } else {
            put("hours", if (number.hoursEnabled) hoursChain(number) else JsonNull)
            put("welcome", if (number.welcomeEnabled) buildJsonObject {
                put("menuId", "welcome-${number.id}"); put("version", number.welcomeVersion); put("soundId", number.welcomeSoundId?.let(::JsonPrimitive) ?: JsonNull)
                put("sharedWith", JsonArray(emptyList()))
            } else JsonNull)
            put("forwarding", forwardingChain(number))
            put("advanced", JsonNull)
        }

        put("recording", buildJsonObject {
            put("enabled", number.recordingEnabled); put("announcementSoundId", number.announcement?.let(::JsonPrimitive) ?: JsonNull)
            put("billingActive", number.billingActive); put("available", true)
            put("cost", if (number.billingActive) JsonNull else buildJsonObject { put("priceE4", 20000); put("vatIncluded", false) })
        })
        put("options", buildJsonObject {
            put("devices", deviceRefs())
            put("sounds", JsonArray(sounds.map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }))
            put("groups", JsonArray(groups.values.map { buildJsonObject { put("id", it["id"]!!); put("name", it["name"]!!) } }))
        })
    }

    private fun hoursChain(number: DemoNumber) = buildJsonObject {
        put("id", "hours-${number.id}"); put("version", number.hoursVersion); put("week", JsonArray(number.week))
        put("holidays", buildJsonObject {
            put("national", number.national)
            putJsonArray("rules") { if (number.national) listOf("new_year", "easter_sunday", "easter_monday", "kings_day", "ascension_day", "whit_sunday", "whit_monday", "christmas_day", "boxing_day").forEach { add(JsonPrimitive(it)) } }
            putJsonArray("dates") { number.dates.forEach { (name, date) -> add(buildJsonObject { put("name", name); put("date", date) }) } }
        })
        put("closed", fallbackJson(number.closed)); put("holiday", number.holiday?.let(::fallbackJson) ?: JsonNull)
        putJsonArray("sharedWith") { if (number.id == "num-2") add(JsonPrimitive("Hoofdnummer")) }
    }

    private fun forwardingChain(number: DemoNumber): JsonObject = buildJsonObject {
        if (number.kind == "menu") {
            put("kind", "menu"); put("menuId", "menu-${number.id}"); put("version", number.forwardingVersion)
            put("greetingSoundId", number.greeting?.let(::JsonPrimitive) ?: JsonNull); put("repeats", number.repeats); put("timeoutSeconds", number.timeoutSeconds)
            put("defaultKey", number.defaultKey?.let(::JsonPrimitive) ?: JsonNull); put("noChoice", fallbackJson(number.noChoice))
            put("keys", JsonArray(number.keys)); putJsonArray("sharedWith") {}
        } else {
            put("kind", "standard"); put("groupId", "group-${number.id}"); put("version", number.forwardingVersion); put("strategy", number.strategy)
            put("members", JsonArray(number.members)); put("unanswered", fallbackJson(number.unanswered))
            putJsonArray("sharedWith") { if (number.id == "num-1") add(JsonPrimitive("Servicenummer")) }
        }
    }

    private fun stale(number: DemoNumber): HttpResponse = demoJson(409, buildJsonObject { put("error", "stale"); put("chain", chain(number)) })

    private fun putStep(request: DemoRequest): HttpResponse {
        val number = numbers[request.segments[2]] ?: return demoError(404, "not_found")
        val step = request.segments[4]
        val body = request.bodyObject()

        if (number.advanced && step != "name") return demoError(409, "advanced")

        val sent = body["numberVersion"]?.jsonPrimitive?.intOrNull
        val versions = body["versions"] as? JsonObject

        if (sent != null && sent != number.version) return stale(number)

        if (versions != null) {
            for ((_, value) in versions) {
                val v = value.jsonPrimitive.intOrNull ?: continue
                if (v !in setOf(number.hoursVersion, number.welcomeVersion, number.forwardingVersion)) return stale(number)
            }
        }

        when (step) {
            "name" -> number.name = stringOf(body["name"])?.takeIf { it.isNotBlank() }
            "hours" -> {
                number.hoursEnabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

                body["week"]?.jsonArray?.let { number.week = it.map(JsonElement::jsonObject) }
                body["holidays"]?.jsonObject?.let { h ->
                    h["national"]?.jsonPrimitive?.booleanOrNull?.let { number.national = it }
                    h["dates"]?.jsonArray?.let { list -> number.dates = list.map { stringOf(it.jsonObject["name"])!! to stringOf(it.jsonObject["date"])!! } }
                }
                body["closed"]?.jsonObject?.let { number.closed = it }
                if ("holiday" in body) number.holiday = body["holiday"] as? JsonObject
                number.hoursVersion += 1
            }
            "closed" -> {
                body["closed"]?.jsonObject?.let { number.closed = it }
                if ("holiday" in body) number.holiday = body["holiday"] as? JsonObject
                number.hoursVersion += 1
            }
            "welcome" -> {
                number.welcomeEnabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                number.welcomeSoundId = stringOf(body["soundId"]) ?: number.welcomeSoundId
                number.welcomeVersion += 1
            }
            "forwarding" -> {
                number.kind = stringOf(body["kind"]) ?: "standard"

                if (number.kind == "menu") {
                    number.greeting = stringOf(body["greetingSoundId"])
                    number.repeats = body["repeats"]?.jsonPrimitive?.intOrNull ?: number.repeats
                    number.timeoutSeconds = body["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: number.timeoutSeconds
                    number.defaultKey = stringOf(body["defaultKey"])
                    body["noChoice"]?.jsonObject?.let { number.noChoice = it }
                    body["keys"]?.jsonArray?.let { list -> number.keys = list.map { key(stringOf(it.jsonObject["digit"])!!, it.jsonObject["target"]!!.jsonObject) } }
                } else {
                    number.strategy = stringOf(body["strategy"]) ?: number.strategy
                    body["members"]?.jsonArray?.let { number.members = it.map(JsonElement::jsonObject) }
                    body["unanswered"]?.jsonObject?.let { number.unanswered = it }
                }

                number.forwardingVersion += 1
            }
            else -> return demoError(404, "not_found")
        }

        number.version += 1
        touch(number.id)

        return demoJson(200, chain(number))
    }

    private fun patchRecording(request: DemoRequest): HttpResponse {
        val number = numbers[request.segments[2]] ?: return demoError(404, "not_found")
        val body = request.bodyObject()
        val sent = body["version"]?.jsonPrimitive?.intOrNull

        if (sent != null && sent != number.version) return stale(number)

        val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: number.recordingEnabled
        val accepted = body["costAccepted"]?.jsonPrimitive?.booleanOrNull == true

        if (enabled && !number.billingActive && !accepted) {
            return demoJson(422, """{"error":"cost_not_accepted","cost":{"priceE4":20000,"vatIncluded":false}}""")
        }

        number.recordingEnabled = enabled
        number.billingActive = enabled
        if ("announcementSoundId" in body) number.announcement = stringOf(body["announcementSoundId"])
        number.version += 1
        touch(number.id)

        return demoJson(200, chain(number))
    }
}
