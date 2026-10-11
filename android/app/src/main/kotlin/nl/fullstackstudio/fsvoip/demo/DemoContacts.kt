// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.demo

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.fullstackstudio.fsvoip.core.HttpResponse

/**
 * The contacts of the pretend API (`/contacts`, `/contact-lists`): one address book per account, like the real thing (every
 * customer has its own), with ~40 realistic contacts for the administrator's company and a small one for the other. Reads, adds,
 * changes (with the stale check) and deletes (administrators only) like the real server, with `since` + tombstones and ETags on
 * the list snapshots.
 */
class ContactsDemoHandler : DemoHandler {
    private class DemoPhone(val number: String, val label: String, val isPrimary: Boolean)

    private class DemoContact(
        val id: String,
        var name: String,
        var firstName: String?,
        var lastName: String?,
        var company: String?,
        var email: String?,
        var phones: List<DemoPhone>,
        var notes: String?,
        var updatedAt: Instant,
    )

    private class DemoList(val id: String, var name: String, var version: Int, val members: MutableSet<String>)

    private class Tombstone(val id: String, val at: Instant)

    private class Book(val contacts: MutableList<DemoContact>, val lists: MutableList<DemoList>, val timelines: Map<String, List<JsonObject>>) {
        val tombstones = mutableListOf<Tombstone>()
        var created = 0
    }

    private val books = mapOf(
        DemoMode.ADMIN_ID to Book(jestBouwContacts(), jestBouwLists(), jestBouwTimelines()),
        DemoMode.USER_ID to Book(voorbeeldContacts(), mutableListOf(DemoList("vi-klanten", "Klanten", 1, mutableSetOf("vi-01", "vi-03"))), emptyMap()),
    )

    override fun handle(request: DemoRequest): HttpResponse? {
        val first = request.segments.firstOrNull()

        if (first != "contacts" && first != "contact-lists") return null

        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val book = books[token] ?: return demoError(401, "unauthorized")

        synchronized(book) {
            return when {
                request.matches("GET", "contacts") -> page(book, request)
                request.matches("POST", "contacts") -> create(book, request)
                request.matches("GET", "contacts", "*") -> book.find(request.segments[1])?.let { single(book, it) } ?: demoError(404, "not_found")
                request.matches("PATCH", "contacts", "*") -> update(book, request)
                request.matches("DELETE", "contacts", "*") -> delete(book, request, token == DemoMode.ADMIN_ID)
                request.matches("GET", "contacts", "*", "timeline") -> timeline(book, request)
                request.matches("GET", "contact-lists") -> lists(book)
                request.matches("GET", "contact-lists", "*", "contacts") -> snapshot(book, request)
                else -> null
            }
        }
    }

    // MARK: Reads

    private fun Book.find(id: String) = contacts.firstOrNull { it.id == id }

    private fun page(book: Book, request: DemoRequest): HttpResponse {
        val now = Instant.now()
        val since = request.query["since"]?.let { runCatching { Instant.parse(it) }.getOrNull() }

        // The server counts two minutes of overlap: a contact can come twice.
        val from = since?.minusSeconds(120)
        val changed = book.contacts.filter { from == null || !it.updatedAt.isBefore(from) }
        val deleted = if (from == null) emptyList() else book.tombstones.filter { !it.at.isBefore(from) }.map { it.id }

        val limit = (request.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val offset = request.query["cursor"]?.let { runCatching { String(Base64.getUrlDecoder().decode(it)).removePrefix("offset:").toInt() }.getOrNull() } ?: 0
        val slice = changed.drop(offset).take(limit)
        val next = if (offset + limit < changed.size) Base64.getUrlEncoder().withoutPadding().encodeToString("offset:${offset + limit}".toByteArray()) else null

        return demoJson(
            200,
            buildJsonObject {
                put("contacts", JsonArray(slice.map { it.json() }))
                // The tombstones come on the first page.
                put("deleted", JsonArray(if (offset == 0) deleted.map(::JsonPrimitive) else emptyList()))
                put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
                put("serverTime", JsonPrimitive(request.query["serverTimeHint"] ?: now.toString()))
            },
        )
    }

    private fun single(book: Book, contact: DemoContact, status: Int = 200) = demoJson(status, buildJsonObject { put("contact", contact.detailJson(book)) })

    private fun lists(book: Book) = demoJson(
        200,
        buildJsonObject {
            put(
                "lists",
                JsonArray(
                    book.lists.map {
                        buildJsonObject {
                            put("id", it.id)
                            put("name", it.name)
                            put("version", it.version)
                            put("contactCount", it.members.size)
                        }
                    },
                ),
            )
        },
    )

    private fun snapshot(book: Book, request: DemoRequest): HttpResponse {
        val list = book.lists.firstOrNull { it.id == request.segments[1] } ?: return demoError(404, "not_found")
        val etag = "\"${list.version}\""

        if (request.headers["If-None-Match"] == etag && request.query["cursor"] == null) return HttpResponse(304, mapOf("ETag" to etag), ByteArray(0))

        val members = book.contacts.filter { it.id in list.members }

        return demoJson(
            200,
            buildJsonObject {
                put("list", buildJsonObject { put("id", list.id); put("name", list.name); put("version", list.version) })
                put(
                    "contacts",
                    JsonArray(
                        members.map { contact ->
                            buildJsonObject {
                                put("id", contact.id)
                                put("name", contact.name)
                                put("company", contact.company?.let(::JsonPrimitive) ?: JsonNull)
                                put("phones", JsonArray(contact.phones.map { buildJsonObject { put("number", it.number); put("label", it.label) } }))
                                put("email", contact.email?.let(::JsonPrimitive) ?: JsonNull)
                            }
                        },
                    ),
                )
                put("nextCursor", JsonNull)
            },
            mapOf("ETag" to etag),
        )
    }

    private fun timeline(book: Book, request: DemoRequest): HttpResponse {
        val contact = book.find(request.segments[1]) ?: return demoError(404, "not_found")
        val all = book.timelines[contact.id] ?: emptyList()
        val limit = (request.query["limit"]?.toIntOrNull() ?: 30).coerceIn(1, 100)
        val offset = request.query["cursor"]?.toIntOrNull() ?: 0
        val next = if (offset + limit < all.size) (offset + limit).toString() else null

        return demoJson(
            200,
            buildJsonObject {
                put("items", JsonArray(all.drop(offset).take(limit)))
                put("nextCursor", next?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }

    // MARK: Writes

    private fun create(book: Book, request: DemoRequest): HttpResponse {
        val body = request.bodyObject()
        val phones = parsePhones(body["phones"]) ?: emptyList()

        validate(body, phones)?.let { return it }

        book.created++
        val now = Instant.now()
        val contact = DemoContact(
            id = "new-${book.created}",
            name = body.text("name") ?: "",
            firstName = body.text("firstName"),
            lastName = body.text("lastName"),
            company = body.text("company"),
            email = body.text("email"),
            phones = phones,
            notes = body.text("notes"),
            updatedAt = now,
        )
        book.contacts += contact
        setMembership(book, contact.id, body["listIds"])

        return single(book, contact, status = 201)
    }

    private fun update(book: Book, request: DemoRequest): HttpResponse {
        val contact = book.find(request.segments[1]) ?: return demoError(404, "not_found")
        val body = request.bodyObject()
        val expected = body.text("expectedUpdatedAt") ?: return demoError(400, "invalid_request", ""","message":"expectedUpdatedAt is required"""")

        if (expected != contact.updatedAt.toString()) return demoError(409, "stale")

        val phones = if (body.containsKey("phones")) parsePhones(body["phones"]) else null

        validate(body, phones ?: contact.phones, partial = true)?.let { return it }

        var changed = false

        fun <T> change(old: T, new: T, apply: (T) -> Unit) {
            if (old != new) {
                apply(new)
                changed = true
            }
        }

        if (body.containsKey("name")) change(contact.name, body.text("name") ?: "") { contact.name = it }
        if (body.containsKey("firstName")) change(contact.firstName, body.text("firstName")) { contact.firstName = it }
        if (body.containsKey("lastName")) change(contact.lastName, body.text("lastName")) { contact.lastName = it }
        if (body.containsKey("company")) change(contact.company, body.text("company")) { contact.company = it }
        if (body.containsKey("email")) change(contact.email, body.text("email")) { contact.email = it }
        if (body.containsKey("notes")) change(contact.notes, body.text("notes")) { contact.notes = it }

        if (phones != null && phones.map { Triple(it.number, it.label, it.isPrimary) } != contact.phones.map { Triple(it.number, it.label, it.isPrimary) }) {
            contact.phones = phones
            changed = true
        }

        if (body.containsKey("listIds")) {
            val before = book.lists.filter { contact.id in it.members }.map { it.id }.toSet()
            setMembership(book, contact.id, body["listIds"])

            if (before != book.lists.filter { contact.id in it.members }.map { it.id }.toSet()) changed = true
        }

        // Nothing different: nothing written, and the version stays.
        if (changed) contact.updatedAt = Instant.now()

        return demoJson(200, buildJsonObject { put("contact", contact.detailJson(book)); put("changed", changed) })
    }

    private fun delete(book: Book, request: DemoRequest, isAdmin: Boolean): HttpResponse {
        if (!isAdmin) return demoError(403, "forbidden", ""","required":"admin"""")

        val contact = book.find(request.segments[1]) ?: return demoError(404, "not_found")

        book.contacts.remove(contact)
        book.lists.forEach { if (contact.id in it.members) { it.members.remove(contact.id); it.version++ } }
        book.tombstones += Tombstone(contact.id, Instant.now())

        return demoJson(200, """{"deleted":1}""")
    }

    private fun setMembership(book: Book, id: String, listIds: JsonElement?) {
        val wanted = (listIds as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: return

        for (list in book.lists) {
            val inList = id in list.members

            if (list.id in wanted && !inList) { list.members += id; list.version++ }
            if (list.id !in wanted && inList) { list.members -= id; list.version++ }
        }
    }

    private fun validate(body: JsonObject, phones: List<DemoPhone>, partial: Boolean = false): HttpResponse? {
        if (phones.any { it.number.count { char -> char.isDigit() } < 3 }) {
            return demoError(400, "invalid_request", ""","code":"invalid_phone","field":"phones"""")
        }

        val email = body.text("email")

        if (email != null && !email.contains('@')) return demoError(400, "invalid_request", ""","code":"invalid_email","field":"email"""")

        if (!partial && phones.isEmpty() && email == null) return demoError(400, "invalid_request", ""","message":"a contact needs a number or an e-mail address"""")

        return null
    }

    private fun parsePhones(element: JsonElement?): List<DemoPhone>? = (element as? JsonArray)?.map {
        val phone = it.jsonObject

        DemoPhone(phone.text("number") ?: "", phone.text("label") ?: "other", phone["isPrimary"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    // MARK: JSON

    private fun DemoContact.json(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("firstName", firstName?.let(::JsonPrimitive) ?: JsonNull)
        put("lastName", lastName?.let(::JsonPrimitive) ?: JsonNull)
        put("company", company?.let(::JsonPrimitive) ?: JsonNull)
        put("email", email?.let(::JsonPrimitive) ?: JsonNull)
        put("phones", JsonArray(phones.map { buildJsonObject { put("number", it.number); put("label", it.label); put("isPrimary", it.isPrimary) } }))
        put("tags", JsonArray(emptyList()))
        put("updatedAt", updatedAt.toString())
    }

    private fun DemoContact.detailJson(book: Book): JsonObject = buildJsonObject {
        json().forEach { (key, value) -> put(key, value) }
        put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
        put("listIds", JsonArray(book.lists.filter { id in it.members }.map { JsonPrimitive(it.id) }))
    }

    // MARK: Data

    private companion object {
        val longAgo: Instant = Instant.parse("2026-10-01T09:00:00.000Z")

        fun person(id: String, first: String, last: String, company: String?, vararg numbers: String, email: String? = null, notes: String? = null) = DemoContact(
            id = id,
            name = "$first $last",
            firstName = first,
            lastName = last,
            company = company,
            email = email,
            phones = numbers.mapIndexed { index, number -> DemoPhone(number, if (number.startsWith("+316")) "mobile" else "work", index == 0) },
            notes = notes,
            updatedAt = longAgo,
        )

        fun business(id: String, name: String, vararg numbers: String, email: String? = null, notes: String? = null) = DemoContact(
            id = id,
            name = name,
            firstName = null,
            lastName = null,
            company = name,
            email = email,
            phones = numbers.mapIndexed { index, number -> DemoPhone(number, if (number.startsWith("+316")) "mobile" else "work", index == 0) },
            notes = notes,
            updatedAt = longAgo,
        )

        fun jestBouwContacts(): MutableList<DemoContact> = mutableListOf(
            business("jb-01", "Bakkerij Smit", "+31701234567", email = "info@bakkerijsmit.nl", notes = "Levert brood op maandag."),
            person("jb-02", "Pieter", "de Groot", "De Groot Installatie", "+31612345678", "+31201234567", email = "pieter@degroot-installatie.nl", notes = "Heeft de cv-ketel in het magazijn vervangen. Bellen na 16:00 lukt het best."),
            person("jb-03", "Anja", "Bakker", "Gemeente Voorbeeld", "+31623456789", email = "a.bakker@voorbeeld.nl"),
            person("jb-04", "Henk", "van Dijk", null, "+31634567890"),
            person("jb-05", "Mireille", "Jansen", "Jansen Advies", "+31645678901", "+31302345678", email = "m.jansen@jansenadvies.nl"),
            person("jb-06", "Karim", "El Amrani", null, "+31656789012"),
            person("jb-07", "Sanne", "Visser", "Visser & Zonen", "+31667890123"),
            person("jb-08", "Willem", "Mulder", null, "+31678901234"),
            business("jb-09", "Loodgietersbedrijf De Waal", "+31107654321", email = "planning@dewaal-loodgieters.nl"),
            person("jb-10", "Inge", "Smeets", "Tandartspraktijk Smeets", "+31689012345"),
            person("jb-11", "Ahmed", "Yilmaz", null, "+31690123456"),
            person("jb-12", "Tom", "Brouwer", "Brouwer Transport", "+31611223344", "+31765551234"),
            person("jb-13", "Femke", "de Vries", null, "+31622334455"),
            person("jb-14", "Joost", "Kramer", "Kramer Groenvoorziening", "+31633445566"),
            business("jb-15", "Bouwmarkt Hendriks", "+31884001000", email = "zakelijk@hendriks-bouwmarkt.nl"),
            business("jb-16", "Dakdekkersbedrijf Van Leeuwen", "+31152123456"),
            person("jb-17", "Rik", "Peeters", "Elektro Peeters", "+31644556677", email = "rik@elektropeeters.nl"),
            business("jb-18", "Schildersbedrijf De Kleur", "+31703334455"),
            business("jb-19", "Aannemer Bos & Zn", "+31181223344", email = "info@bos-aannemers.nl"),
            person("jb-20", "Arjan", "Hoekstra", "Hoekstra Beton", "+31655667788"),
            person("jb-21", "Lotte", "van den Berg", "Gemeente Voorbeeld", "+31306667788", email = "l.vandenberg@voorbeeld.nl", notes = "Vergunningen, aanspreekpunt voor de verbouwing aan de Kanaalweg."),
            person("jb-22", "Noor", "Hassan", null, "+31666778899"),
            person("jb-23", "Bram", "Verhoeven", "Verhoeven Staalbouw", "+31677889900"),
            person("jb-24", "Cees", "Dekker", null, "+31688990011"),
            person("jb-25", "Els", "Vermeulen", "Notariskantoor Vermeulen", "+31235551212", email = "els@vermeulen-notaris.nl"),
            person("jb-26", "Gerrit", "Postma", null, "+31699001122"),
            person("jb-27", "Hanneke", "Boersma", "Boersma Accountants", "+31501234567", email = "hanneke@boersma-accountants.nl"),
            person("jb-28", "Ivo", "Maas", null, "+31610012233"),
            person("jb-29", "Jolanda", "de Boer", "De Boer Interieur", "+31621123344"),
            person("jb-30", "Kees", "van der Meer", "Meer Machineverhuur", "+31632234455", "+31402345678"),
            person("jb-31", "Linda", "Prins", null, "+31643345566"),
            person("jb-32", "Martijn", "Koster", "Koster Elektrotechniek", "+31654456677"),
            person("jb-33", "Nadia", "Benali", null, "+31665567788"),
            person("jb-34", "Olaf", "Wiersma", "Wiersma Hout", "+31676678899"),
            person("jb-35", "Petra", "Smits", "Smits Isolatie", "+31687789900", email = "petra@smitsisolatie.nl"),
            person("jb-36", "Rob", "Hermans", "Hermans Kozijnen", "+31698890011"),
            person("jb-37", "Thijs", "Veenstra", "Veenstra Dakbedekking", "+31619901122"),
            person("jb-38", "Zoë", "Smit", null, "+31620012233"),
            person("jb-39", "Ömer", "Kaya", "Kaya Tegelwerken", "+31631123344"),
            business("jb-40", "24/7 Sleepdienst Hofman", "+31885009000"),
        )

        fun jestBouwLists(): MutableList<DemoList> = mutableListOf(
            DemoList("jb-klanten", "Klanten", 3, mutableSetOf("jb-01", "jb-05", "jb-07", "jb-10", "jb-13", "jb-21", "jb-25", "jb-27")),
            DemoList("jb-leveranciers", "Leveranciers", 1, mutableSetOf("jb-02", "jb-09", "jb-12", "jb-14", "jb-15", "jb-17", "jb-30", "jb-34", "jb-35", "jb-36")),
        )

        fun voorbeeldContacts(): MutableList<DemoContact> = mutableListOf(
            person("vi-01", "Marloes", "Geurts", "Installatiebedrijf Geurts", "+31612300001", email = "marloes@geurts-installatie.nl"),
            person("vi-02", "Dennis", "Rood", null, "+31612300002"),
            business("vi-03", "Kantoorcentrum Zuid", "+31101112233"),
            person("vi-04", "Fatima", "Bouzid", "Bouzid Administratie", "+31612300004"),
            person("vi-05", "Johan", "Vos", null, "+31612300005"),
            business("vi-06", "Technische Groothandel Delta", "+31204448899"),
        )

        fun item(id: String, kind: String, title: String, summary: String?, at: String, ref: String?) = buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("title", title)
            put("summary", summary?.let(::JsonPrimitive) ?: JsonNull)
            put("occurredAt", at)
            put("ref", ref?.let(::JsonPrimitive) ?: JsonNull)
        }

        fun jestBouwTimelines(): Map<String, List<JsonObject>> = mapOf(
            "jb-02" to listOf(
                item("t1", "order", "Bestelling geplaatst", "3 producten, € 84,50", "2026-10-09T14:02:11.000Z", "W-1042"),
                item("t2", "ticket", "Verzoek ontvangen", "Vraag over de levertijd", "2026-10-08T09:15:00.000Z", "#12"),
                item("t3", "call", "Gesprek", null, "2026-10-05T11:40:00.000Z", null),
                item("t4", "appointment", "Afspraak ingepland", "Opname cv-ketel, werkplaats", "2026-10-02T08:30:00.000Z", null),
                item("t5", "form", "Contactformulier ingevuld", "Offerte aanvragen", "2026-09-28T16:20:00.000Z", null),
                item("t6", "order", "Bestelling verzonden", "Pakket onderweg", "2026-09-21T10:05:00.000Z", "W-1031"),
                item("t7", "mail", "Mail verstuurd", "Bevestiging offerte", "2026-09-18T13:00:00.000Z", null),
                item("t8", "order", "Bestelling geplaatst", "1 product, € 19,95", "2026-09-12T19:44:00.000Z", "W-1030"),
            ),
            "jb-01" to listOf(
                item("b1", "order", "Bestelling geplaatst", "12 producten, € 212,00", "2026-10-06T07:50:00.000Z", "W-1040"),
                item("b2", "call", "Gesprek", null, "2026-10-01T10:12:00.000Z", null),
            ),
            "jb-05" to listOf(item("m1", "ticket", "Verzoek ontvangen", "Offerte bijwerken", "2026-10-07T15:30:00.000Z", "#14")),
        )
    }
}
