package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReconcilerTest {
    private val day = LocalDate.of(2026, 11, 5)

    private fun existing(id: Long, amount: Long, source: TxSource, status: TxStatus = TxStatus.BOOKED, ext: String? = null, date: LocalDate = day) =
        ExistingTx(id, date, amount, ext, source, status)

    private fun incoming(name: String, amount: Long, ext: String? = null, date: LocalDate = day) =
        IncomingTx(date, amount, ext, name)

    @Test
    fun sameExternalIdIsADuplicate() {
        val result = Reconciler.reconcile(
            listOf(existing(1, -500, TxSource.IMPORT, ext = "a")),
            listOf(incoming("x", -500, "a"), incoming("y", -700, "b")),
            TxSource.IMPORT,
        )
        assertEquals(listOf("y"), result.toInsert)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun repeatedIdWithinOneDeliveryIsImportedOnce() {
        val result = Reconciler.reconcile(emptyList(), listOf(incoming("x", -500, "a"), incoming("x2", -500, "a")), TxSource.API)
        assertEquals(listOf("x"), result.toInsert)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun bookedTransactionConfirmsPendingNotification() {
        val result = Reconciler.reconcile(
            listOf(existing(7, -1299, TxSource.NOTIFICATION, TxStatus.PENDING)),
            listOf(incoming("bank", -1299, "b1", day.plusDays(2))),
            TxSource.API,
        )
        assertEquals(emptyList<String>(), result.toInsert)
        assertEquals(listOf(7L to "bank"), result.toConfirm)
    }

    @Test
    fun importedRowAlreadyFetchedFromBankIsADuplicate() {
        val result = Reconciler.reconcile(
            listOf(existing(3, -1299, TxSource.API, ext = "b1")),
            listOf(incoming("csv", -1299, "csv:1")),
            TxSource.IMPORT,
        )
        assertEquals(0, result.toInsert.size)
        assertEquals(0, result.toConfirm.size)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun twoEqualPurchasesNeedTwoExistingEntriesToMatch() {
        val result = Reconciler.reconcile(
            listOf(existing(1, -250, TxSource.MANUAL)),
            listOf(incoming("a", -250, "x1"), incoming("b", -250, "x2")),
            TxSource.IMPORT,
        )
        assertEquals(listOf(1L to "a"), result.toConfirm)
        assertEquals(listOf("b"), result.toInsert)
    }

    @Test
    fun sameSourceAndFarApartDatesAreNotMatched() {
        val result = Reconciler.reconcile(
            listOf(
                existing(1, -250, TxSource.IMPORT, ext = "old"),
                existing(2, -900, TxSource.MANUAL, date = day.minusDays(20)),
            ),
            listOf(incoming("a", -250, "new"), incoming("b", -900, "new2")),
            TxSource.IMPORT,
        )
        assertEquals(listOf("a", "b"), result.toInsert)
    }

    @Test
    fun notificationIsNotSwallowedByYesterdaysEqualBooking() {
        val result = Reconciler.reconcile(
            listOf(existing(1, -250, TxSource.API, ext = "b1", date = day.minusDays(1))),
            listOf(incoming("push", -250, "nt:1")),
            TxSource.NOTIFICATION,
        )
        assertEquals(listOf("push"), result.toInsert)
    }

    @Test
    fun notificationForAlreadyBookedTransactionIsDropped() {
        val result = Reconciler.reconcile(
            listOf(existing(1, -250, TxSource.MANUAL)),
            listOf(incoming("push", -250)),
            TxSource.NOTIFICATION,
        )
        assertEquals(0, result.toInsert.size)
        assertEquals(0, result.toConfirm.size)
    }
}
