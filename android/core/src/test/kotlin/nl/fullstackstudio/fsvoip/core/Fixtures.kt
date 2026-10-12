// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.io.File
import kotlinx.serialization.json.JsonElement

/** The shared fixtures (`../shared/fixtures`), the same files the iOS contract tests decode. */
object Fixtures {
    val directory: File by lazy {
        val path = System.getProperty("fsvoip.fixtures") ?: "../../shared/fixtures"
        File(path).also { require(it.isDirectory) { "Fixtures not found at $path" } }
    }

    fun text(name: String): String = File(directory, name).readText()

    fun json(name: String): JsonElement = FsJson.default.parseToJsonElement(text(name))

    fun all(): Set<String> = directory.listFiles { file -> file.name.endsWith(".json") }!!.map { it.name }.toSet()
}
