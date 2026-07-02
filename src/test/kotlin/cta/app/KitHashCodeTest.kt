package cta.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kit.equals is id-based, so hashCode must be too. A constant hashCode is legal but
 * collapses every kit into a single hash bucket, making membership checks on the
 * MutableSet<Kit> collections (DeviceRequest.kits / Donor.kits via addKit/removeKit)
 * O(n) per lookup. Kits are always persisted (id assigned) before being added to those
 * sets, so hashing by id is safe here.
 */
class KitHashCodeTest {
    @Test
    fun `equal kits have equal hash codes`() {
        val a = Kit(id = 5, model = "ThinkPad T480")
        val b = Kit(id = 5, model = "Latitude 5400")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `persisted kits do not all share one hash bucket`() {
        val hashes = (1L..100L).map { Kit(id = it, model = "model-$it").hashCode() }.toSet()
        assertTrue(hashes.size > 1, "Kit.hashCode() must vary by id, not return a constant")
    }
}
