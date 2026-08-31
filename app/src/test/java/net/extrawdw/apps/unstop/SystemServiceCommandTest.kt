package net.extrawdw.apps.unstop

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemServiceCommandTest {
    @Test
    fun commandBuildersTargetUnderlyingBinderServices() {
        assertEquals(
            SystemServiceCommand("package", listOf("unstop", "--user", "0", "com.example.push")),
            SystemServiceCommands.packageManager("unstop", "--user", "0", "com.example.push"),
        )
        assertEquals(
            SystemServiceCommand("activity", listOf("broadcast", "--user", "0")),
            SystemServiceCommands.activity("broadcast", "--user", "0"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNulInArguments() {
        SystemServiceCommand("package", listOf("bad\u0000argument"))
    }
}
