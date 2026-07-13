package com.kotlinsun.noteup.ui.pdfimport

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kotlinsun.noteup.data.pdf.PdfImportException
import com.kotlinsun.noteup.data.pdf.PdfImportService
import com.kotlinsun.noteup.domain.model.PdfImportError
import com.kotlinsun.noteup.domain.model.PdfImportPreview
import com.kotlinsun.noteup.domain.model.PdfImportUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PdfImportViewModel(private val service: PdfImportService) : ViewModel() {
    private val _state = MutableStateFlow<PdfImportUiState>(PdfImportUiState.Idle)
    val state = _state.asStateFlow()
    private var consumedRequest: String? = null
    private var pendingRequest: Pair<String, Uri>? = null

    fun handleIntent(intent: Intent) {
        val uri = extractUri(intent) ?: return
        val request = "${intent.action}:$uri"
        if (request == consumedRequest || pendingRequest?.first == request) return
        if (_state.value !is PdfImportUiState.Idle) {
            pendingRequest = request to uri
            return
        }
        inspect(request, uri)
    }

    private fun inspect(request: String, uri: Uri) {
        consumedRequest = request
        _state.value = PdfImportUiState.Inspecting
        viewModelScope.launch {
            runCatching { service.inspect(uri) }
                .onSuccess { _state.value = PdfImportUiState.AwaitingConfirmation(it) }
                .onFailure { error ->
                    _state.value = PdfImportUiState.Error(
                        (error as? PdfImportException)?.reason ?: PdfImportError.INVALID,
                    )
                }
        }
    }

    fun confirm(preview: PdfImportPreview) {
        if ((_state.value as? PdfImportUiState.AwaitingConfirmation)?.preview != preview) return
        _state.value = PdfImportUiState.Importing(preview.displayName)
        viewModelScope.launch {
            runCatching { service.import(preview) }
                .onSuccess { _state.value = PdfImportUiState.Completed(it) }
                .onFailure { error ->
                    _state.value = PdfImportUiState.Error(
                        (error as? PdfImportException)?.reason ?: PdfImportError.STORAGE,
                    )
                }
        }
    }

    fun cancel() {
        if (_state.value is PdfImportUiState.AwaitingConfirmation) reset()
    }

    fun reset() {
        _state.value = PdfImportUiState.Idle
        consumedRequest = null
        pendingRequest?.let { (request, uri) ->
            pendingRequest = null
            inspect(request, uri)
        }
    }

    private fun extractUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (intent.type == PDF_MIME_TYPE) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null
        else -> null
    }

    class Factory(private val service: PdfImportService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PdfImportViewModel(service) as T
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }
}
