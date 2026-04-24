package com.raizlabs.android.dbflow.list

import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.raizlabs.android.dbflow.BaseUnitTest
import org.robolectric.shadows.ShadowLooper
import com.raizlabs.android.dbflow.kotlinextensions.from
import com.raizlabs.android.dbflow.kotlinextensions.select
import com.raizlabs.android.dbflow.models.SimpleModel
import com.raizlabs.android.dbflow.structure.cache.SimpleMapCache
import com.raizlabs.android.dbflow.structure.database.transaction.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowQueryListTest : BaseUnitTest() {

    @Test
    fun validateBuilder() {

        val list = FlowQueryList.Builder<SimpleModel>(select from SimpleModel::class)
            .modelCache(SimpleMapCache<SimpleModel>(55))
            .transact(true)
            .changeInTransaction(true)
            .build()

        assertTrue(list.cursorList().modelCache() is SimpleMapCache<*>)
        assertTrue(list.cursorList().cachingEnabled())
        assertTrue(list.transact())
        assertTrue(list.changeInTransaction())
    }

    @Test
    fun validateNonCachedBuilder() {

        val list = FlowQueryList.Builder<SimpleModel>(select from SimpleModel::class)
            .cacheModels(false)
            .build()

        assertFalse(list.cursorList().cachingEnabled())

    }


    @Test
    fun validateListOperations() {
        val mockSuccess = mock<Transaction.Success>()
        val mockError = mock<Transaction.Error>()
        val list = (select from SimpleModel::class).flowQueryList()
            .newBuilder().success(mockSuccess)
            .error(mockError)
            .build()
        list += SimpleModel("1")
        ShadowLooper.idleMainLooper()

        // verify added
        assertEquals(1, list.count)
        assertFalse(list.isEmpty())

        // verify success called
        verify(mockSuccess).onSuccess(argumentCaptor<Transaction>().capture())

        list -= SimpleModel("1")
        ShadowLooper.idleMainLooper()
        assertEquals(0, list.count)

        list += SimpleModel("1")
        ShadowLooper.idleMainLooper()
        list.removeAt(0)
        ShadowLooper.idleMainLooper()
        assertEquals(0, list.count)

        val elements = arrayListOf(SimpleModel("1"), SimpleModel("2"))
        list.addAll(elements)
        ShadowLooper.idleMainLooper()
        assertEquals(2, list.count)
        list.removeAll(elements)
        ShadowLooper.idleMainLooper()
        assertEquals(0, list.count)

        list.addAll(elements)
        ShadowLooper.idleMainLooper()

        val typedArray = list.toTypedArray()
        assertEquals(typedArray.size, list.size)

        list.clear()
        ShadowLooper.idleMainLooper()
        assertEquals(0, list.size)
    }

}