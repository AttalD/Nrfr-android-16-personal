package com.github.nrfr

import com.github.nrfr.region.OpenTransaction
import org.junit.Assert.*
import org.junit.Test

/**
 * 事务日志的序列化 —— 崩溃恢复的正确性完全依赖它。
 *
 * Round-tripping matters because the journal is the *only* thing that survives process death, and
 * a baseline that deserialises wrong would make recovery restore the wrong value.
 */
class TransactionJournalTest {

    private fun tx(
        country: String? = "cn",
        numeric: String? = "46000",
        name: String? = "China Mobile",
        configKey: String? = null
    ) = OpenTransaction(
        subId = 2, slot = 1, profileName = "美国（仅国家码）",
        baselineCountryIso = country,
        baselineOperatorNumeric = numeric,
        baselineOperatorName = name,
        baselineConfigCountryKey = configKey,
        startedAtMillis = 1_700_000_000_000L
    )

    @Test
    fun `round trips a full baseline`() {
        val original = tx(configKey = "cn")
        val restored = OpenTransaction.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `absent config key survives as null, not as empty string`() {
        // "key was absent" and "key was empty" must stay distinguishable: cleanup has to leave an
        // originally-absent key absent.
        val restored = OpenTransaction.fromJson(tx(configKey = null).toJson())
        assertNull(restored!!.baselineConfigCountryKey)
    }

    @Test
    fun `null baselines survive`() {
        val restored = OpenTransaction.fromJson(tx(country = null, numeric = null, name = null).toJson())
        assertNotNull(restored)
        assertNull(restored!!.baselineCountryIso)
        assertNull(restored.baselineOperatorNumeric)
        assertNull(restored.baselineOperatorName)
    }

    @Test
    fun `identity fields survive exactly`() {
        val restored = OpenTransaction.fromJson(tx().toJson())!!
        assertEquals(2, restored.subId)
        assertEquals(1, restored.slot)
        assertEquals("cn", restored.baselineCountryIso)
        assertEquals("46000", restored.baselineOperatorNumeric)
        assertEquals("China Mobile", restored.baselineOperatorName)
        assertEquals(1_700_000_000_000L, restored.startedAtMillis)
    }

    @Test
    fun `corrupt json yields null rather than throwing`() {
        // A crash mid-write must not make the app unable to start.
        assertNull(OpenTransaction.fromJson("not json"))
        assertNull(OpenTransaction.fromJson(""))
        assertNull(OpenTransaction.fromJson("{}"))
        assertNull(OpenTransaction.fromJson("""{"subId":2}"""))
    }

    @Test
    fun `unicode profile names survive`() {
        val restored = OpenTransaction.fromJson(tx().toJson())!!
        assertEquals("美国（仅国家码）", restored.profileName)
    }
}
