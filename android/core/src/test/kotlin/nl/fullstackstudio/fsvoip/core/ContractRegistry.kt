// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

/**
 * Feature contract tests (one class per area) add one line here. Each entry runs all tests of that class and returns the
 * fixture names it decoded; [ContractTest.everyFixtureIsCovered] checks that together they cover every shared fixture.
 */
object ContractRegistry {
    val features: List<() -> Set<String>> = listOf(
        { CallsContractTest().coverage() },
        { PbxContractTest().coverage() },
        { ContactsContractTest().coverage() },
        { CallExpContractTest().coverage() },
    )
}
