package com.wibechat.ringkit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the §5.1 mirror: the data file must define EVERY input × state cell
 * exactly once (15 × 6 = 90) — a missing cell means the table drifted from the
 * spec (or vice versa) and the generated suite silently lost coverage.
 */
@RunWith(RobolectricTestRunner::class)
class TransitionTableCompletenessTest {

    @Test
    fun everyCellDefinedExactlyOnce() {
        val spec = TestSupport.loadSpec("transition_table.json")
        val states = spec.getJSONArray("states").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        val inputs = spec.getJSONArray("inputs").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        assertEquals("state columns", 6, states.size)
        assertEquals("input rows", 15, inputs.size)

        val seen = HashMap<Pair<String, String>, Int>()
        val cells = spec.getJSONArray("cells")
        for (i in 0 until cells.length()) {
            val cell = cells.getJSONObject(i)
            val key = cell.getString("input") to cell.getString("state")
            assertTrue("unknown input ${key.first}", key.first in inputs)
            assertTrue("unknown state ${key.second}", key.second in states)
            seen[key] = (seen[key] ?: 0) + 1
            assertTrue("cell $key has no cases", cell.getJSONArray("cases").length() > 0)
        }

        val missing = mutableListOf<Pair<String, String>>()
        inputs.forEach { input ->
            states.forEach { state ->
                when (seen[input to state] ?: 0) {
                    0 -> missing.add(input to state)
                    1 -> Unit
                    else -> throw AssertionError("duplicate cell: $input × $state")
                }
            }
        }
        assertTrue("missing §5.1 cells: $missing", missing.isEmpty())
        assertEquals("total cells", inputs.size * states.size, seen.values.sum())
    }
}
