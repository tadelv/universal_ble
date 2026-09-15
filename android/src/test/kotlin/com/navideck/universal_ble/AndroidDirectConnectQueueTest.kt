package com.navideck.universal_ble

import kotlin.test.Test

internal class AndroidDirectConnectQueueTest {
    @Test
    fun connectionAdmissionScenarios() = AndroidDirectConnectQueueScenarios.runAll()
}
