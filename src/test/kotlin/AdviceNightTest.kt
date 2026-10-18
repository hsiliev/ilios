package ilios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdviceNightTest {
    @Test fun `charges at night when tomorrow's sun will not fill the battery`() {
        val n = assertNotNull(nightCharge(11.4, 10.0, 4.0, 0.25, 0.13))
        assertTrue(n.charge)
        assertEquals(7.4, n.kwh) // min(room 10.26, 11.4 - 4.0)
        assertEquals(0.89, n.savingEur) // 7.4 x 0.12
    }

    @Test fun `no night charge when the sun fills it or the battery is full`() {
        assertEquals(false, nightCharge(11.4, 10.0, 15.0)!!.charge)
        assertEquals(false, nightCharge(11.4, 99.0, 2.0)!!.charge)
    }

    @Test fun `unknown charge gives no advice`() {
        assertNull(nightCharge(11.4, null, 5.0))
        assertNull(nightCharge(0.0, 50.0, 5.0))
    }
}
