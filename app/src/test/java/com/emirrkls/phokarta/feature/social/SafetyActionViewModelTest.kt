package com.emirrkls.phokarta.feature.social

import com.emirrkls.phokarta.R
import com.emirrkls.phokarta.TestTravelRepository
import com.emirrkls.phokarta.core.data.TravelError
import com.emirrkls.phokarta.core.model.ReportReason
import com.emirrkls.phokarta.core.model.ReportTargetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyActionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val userId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun blockSuccessEmitsEventAndInvalidates() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val viewModel = SafetyActionViewModel(repository)
        viewModel.openBlock(userId)
        viewModel.confirmBlock()
        advanceUntilIdle()

        assertEquals(listOf(userId), repository.blockCalls)
        assertEquals(1, repository.invalidateAfterBlockCount)
        assertEquals(SafetyEvent.UserBlocked, viewModel.uiState.value.event)
        assertNull(viewModel.uiState.value.blockUserId)
    }

    @Test
    fun blockOfflineKeepsTargetVisible() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.blockError = TravelError.Offline()
        val viewModel = SafetyActionViewModel(repository)
        viewModel.openBlock(userId)
        viewModel.confirmBlock()
        advanceUntilIdle()

        assertEquals(userId, viewModel.uiState.value.blockUserId)
        assertEquals(R.string.block_offline, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.event)
    }

    @Test
    fun reportKeepsDetailsOnFailure() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        repository.reportError = TravelError.Offline()
        val viewModel = SafetyActionViewModel(repository)
        viewModel.openReportUser(userId)
        viewModel.selectReason(ReportReason.SPAM)
        viewModel.updateDetails("looks like ads")
        viewModel.submitReport()
        advanceUntilIdle()

        assertEquals("looks like ads", viewModel.uiState.value.details)
        assertEquals(ReportReason.SPAM, viewModel.uiState.value.selectedReason)
        assertEquals(R.string.report_offline, viewModel.uiState.value.error)
        assertTrue(repository.reportCalls.isEmpty())
    }

    @Test
    fun reportSuccessOffersBlock() = runTest(dispatcher) {
        val repository = TestTravelRepository()
        val viewModel = SafetyActionViewModel(repository)
        viewModel.openReportVisit("visit-1", userId)
        viewModel.selectReason(ReportReason.HARASSMENT)
        viewModel.submitReport()
        advanceUntilIdle()

        assertEquals(listOf(Triple(ReportTargetType.VISIT, "visit-1", ReportReason.HARASSMENT)), repository.reportCalls)
        assertEquals(SafetyEvent.ReportSubmitted, viewModel.uiState.value.event)
        assertEquals(userId, viewModel.uiState.value.offerBlockUserId)
    }
}
