package com.wibechat.ringkit.adapter

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.wibechat.ringkit.services.BackgroundDeliveryHost
import com.wibechat.ringkit.services.EventDeliveryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * §10.2 rung 2 host selection: 34+ hosts the runner in the shortService
 * EventDeliveryService; 26–33 has no shortService, so the durable expedited
 * WorkManager fallback carries delivery.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundDeliveryHostTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    @Config(sdk = [34])
    fun api34_hosts_delivery_in_the_shortService() {
        BackgroundDeliveryHost.deliver(context)

        val started = shadowOf(context as Application).nextStartedService
        assertEquals(EventDeliveryService::class.java.name, started?.component?.className)
        assertEquals("no work enqueued on 34+", 0, uniqueWorkCount())
    }

    @Test
    @Config(sdk = [31])
    fun api31_enqueues_the_expedited_durable_worker() {
        BackgroundDeliveryHost.deliver(context)

        assertNull("no shortService below 34", shadowOf(context as Application).nextStartedService)
        assertEquals(1, uniqueWorkCount())
    }

    @Test
    @Config(sdk = [28])
    fun api28_also_uses_the_worker_not_a_service() {
        BackgroundDeliveryHost.deliver(context)

        assertNull(shadowOf(context as Application).nextStartedService)
        assertEquals(1, uniqueWorkCount())
    }

    private fun uniqueWorkCount(): Int =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BackgroundDeliveryHost.WORK_NAME)
            .get()
            .size
}
