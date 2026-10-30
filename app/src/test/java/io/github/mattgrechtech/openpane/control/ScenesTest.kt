package io.github.mattgrechtech.openpane.control

import io.github.mattgrechtech.openpane.control.Scenes.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenesTest {

    private val text = """
        21:30 brightness=30 rest=black rest_after=5
        06:30 /dashboard-family/morning brightness=90 rest=clock

        18:00 http://192.168.1.50:8123/dashboard-family/cameras rest_after=20
    """.trimIndent()

    @Test
    fun parsesAndSorts() {
        assertEquals(
            listOf(
                Scene(390, "/dashboard-family/morning", 90, "clock", null),
                Scene(1080, "http://192.168.1.50:8123/dashboard-family/cameras", restAfterMinutes = 20),
                Scene(1290, brightness = 30, restStyle = "black", restAfterMinutes = 5),
            ),
            Scenes.parse(text),
        )
        assertNull(Scenes.problem(text))
    }

    @Test
    fun activeWrapsPastMidnight() {
        val scenes = Scenes.parse(text)
        assertEquals(1290, Scenes.active(scenes, 3 * 60)!!.start) // 03:00 is still the 21:30 scene
        assertEquals(390, Scenes.active(scenes, 390)!!.start)
        assertEquals(390, Scenes.active(scenes, 17 * 60 + 59)!!.start)
        assertEquals(1080, Scenes.active(scenes, 18 * 60)!!.start)
        assertNull(Scenes.active(emptyList(), 600))
    }

    @Test
    fun reportsProblems() {
        assertTrue(Scenes.problem("7am /x")!!.startsWith("line 1: must start with a time"))
        assertTrue(Scenes.problem("07:00 brightness=150")!!.contains("brightness"))
        assertTrue(Scenes.problem("07:00 rest=page")!!.contains("rest must be"))
        assertTrue(Scenes.problem("07:00 /a /b")!!.contains("two pages"))
        assertTrue(Scenes.problem("07:00 colour=red")!!.contains("colour=red"))
        assertTrue(Scenes.problem("07:00 /a\n7:00 /b")!!.contains("two scenes start at 07:00"))
        assertEquals("08:05", Scene(485).label)
    }
}
