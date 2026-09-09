package com.example.dreamlinux.ui.main

import com.example.dreamlinux.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    assertEquals(MainScreenUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun uiState_onItemSaved_isDisplayed() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val state = viewModel.uiState.first { it is MainScreenUiState.Success }
    assertEquals(MainScreenUiState.Success(listOf("Sample")), state)
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
