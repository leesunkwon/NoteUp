package com.kotlinsun.noteup

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.kotlinsun.noteup.databinding.ActivityMainBinding
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotlinsun.noteup.domain.model.PdfImportError
import com.kotlinsun.noteup.domain.model.PdfImportPreview
import com.kotlinsun.noteup.domain.model.PdfImportUiState
import com.kotlinsun.noteup.ui.pdfimport.PdfImportViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val importViewModel: PdfImportViewModel by viewModels {
        PdfImportViewModel.Factory((application as NoteUpApplication).container.pdfImportService)
    }
    private var importDialog: androidx.appcompat.app.AlertDialog? = null
    private var importDialogKey: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }
        observePdfImport()
        consumePdfIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePdfIntent(intent)
    }

    private fun consumePdfIntent(source: Intent) {
        importViewModel.handleIntent(source)
        if (source.action == Intent.ACTION_VIEW || source.action == Intent.ACTION_SEND) {
            setIntent(Intent(source).apply {
                action = null
                data = null
                removeExtra(Intent.EXTRA_STREAM)
            })
        }
    }

    private fun observePdfImport() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                importViewModel.state.collect(::renderPdfImportState)
            }
        }
    }

    private fun renderPdfImportState(state: PdfImportUiState) {
        when (state) {
            PdfImportUiState.Idle -> dismissImportDialog()
            PdfImportUiState.Inspecting -> showProgressDialog(R.string.pdf_import_inspecting)
            is PdfImportUiState.AwaitingConfirmation -> showImportConfirmation(state.preview)
            is PdfImportUiState.Importing -> showProgressDialog(R.string.pdf_import_progress)
            is PdfImportUiState.Completed -> {
                dismissImportDialog()
                val navController = (supportFragmentManager.findFragmentById(R.id.main) as NavHostFragment)
                    .navController
                navController.popBackStack(R.id.dashboardFragment, false)
                navController.navigate(R.id.canvasFragment, bundleOf("noteId" to state.noteId))
                importViewModel.reset()
            }
            is PdfImportUiState.Error -> showImportError(state.reason)
        }
    }

    private fun showImportConfirmation(preview: PdfImportPreview) {
        if (importDialog?.isShowing == true && importDialogKey == preview.uri.toString()) return
        dismissImportDialog()
        importDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_import_title)
            .setMessage(getString(R.string.pdf_import_confirmation, preview.displayName, preview.pages.size))
            .setNegativeButton(R.string.cancel) { _, _ -> importViewModel.cancel() }
            .setPositiveButton(R.string.pdf_import_action) { _, _ -> importViewModel.confirm(preview) }
            .setOnCancelListener { importViewModel.cancel() }
            .create().also { dialog ->
                importDialogKey = preview.uri.toString()
                dialog.show()
            }
    }

    private fun showProgressDialog(message: Int) {
        if (importDialog?.isShowing == true && importDialogKey == message) return
        dismissImportDialog()
        importDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_import_title)
            .setMessage(message)
            .setCancelable(false)
            .create().also { dialog ->
                importDialogKey = message
                dialog.show()
            }
    }

    private fun showImportError(error: PdfImportError) {
        if (importDialog?.isShowing == true && importDialogKey == error) return
        dismissImportDialog()
        val message = when (error) {
            PdfImportError.UNREADABLE -> R.string.pdf_import_unreadable
            PdfImportError.PROTECTED -> R.string.pdf_import_protected
            PdfImportError.EMPTY -> R.string.pdf_import_empty
            PdfImportError.STORAGE -> R.string.pdf_import_storage_error
            PdfImportError.INVALID -> R.string.pdf_import_invalid
        }
        importDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_import_failed)
            .setMessage(message)
            .setPositiveButton(R.string.close) { _, _ -> importViewModel.reset() }
            .setOnCancelListener { importViewModel.reset() }
            .create().also { dialog ->
                importDialogKey = error
                dialog.show()
            }
    }

    private fun dismissImportDialog() {
        importDialog?.dismiss()
        importDialog = null
        importDialogKey = null
    }
}
