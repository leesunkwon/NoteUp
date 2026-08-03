package com.kotlinsun.noteup.ui.settings

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.navGraphViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kotlinsun.noteup.NoteUpApplication
import com.kotlinsun.noteup.R
import com.kotlinsun.noteup.data.preferences.TrashRetention
import com.kotlinsun.noteup.domain.ai.AiModelError
import com.kotlinsun.noteup.domain.ai.AiModelState
import com.kotlinsun.noteup.domain.model.CanvasAppearance
import com.kotlinsun.noteup.domain.model.CanvasInputMode
import com.kotlinsun.noteup.domain.model.PageTemplate
import com.kotlinsun.noteup.domain.model.ThemeMode

internal fun Fragment.settingsViewModel(): Lazy<SettingsViewModel> = navGraphViewModels(
    navGraphId = R.id.settingsGraph,
    factoryProducer = {
        val container = (requireActivity().application as NoteUpApplication).container
        SettingsViewModel.Factory(
            container.appSettingsStore,
            container.customColorPaletteStore,
            container.trashRetentionStore,
            container.trashCleanupService,
            container.versionHistoryStore,
            container.storageUsageService,
            container.onDeviceAiRepository,
        )
    },
)

internal fun Fragment.popBackStackFrom(@IdRes destinationId: Int) {
    val navController = findNavController()
    if (navController.currentDestination?.id == destinationId) {
        navController.popBackStack()
    }
}

internal fun Fragment.showSingleChoiceDialog(
    @StringRes titleRes: Int,
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(titleRes)
        .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
            onSelected(which)
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

@StringRes
internal fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

@StringRes
internal fun CanvasAppearance.labelRes(): Int = when (this) {
    CanvasAppearance.WHITE_PAPER -> R.string.canvas_appearance_white
    CanvasAppearance.DARK_PAPER -> R.string.canvas_appearance_dark
}

@StringRes
internal fun CanvasInputMode.labelRes(): Int = when (this) {
    CanvasInputMode.STYLUS_ONLY -> R.string.canvas_input_mode_stylus_only
    CanvasInputMode.STYLUS_AND_TOUCH -> R.string.canvas_input_mode_stylus_touch
}

@StringRes
internal fun PageTemplate.labelRes(): Int = when (this) {
    PageTemplate.BLANK -> R.string.template_blank
    PageTemplate.LINED -> R.string.template_lined
    PageTemplate.GRID -> R.string.template_grid
}

@StringRes
internal fun TrashRetention.labelRes(): Int = when (this) {
    TrashRetention.DAYS_7 -> R.string.trash_retention_7_days
    TrashRetention.DAYS_30 -> R.string.trash_retention_30_days
    TrashRetention.DAYS_90 -> R.string.trash_retention_90_days
    TrashRetention.NEVER -> R.string.trash_retention_never
}

@StringRes
internal fun AiModelState.statusLabelRes(): Int = when (this) {
    AiModelState.Checking -> R.string.ai_model_status_checking
    is AiModelState.Unsupported -> R.string.ai_model_status_unsupported
    is AiModelState.NotInstalled -> R.string.ai_model_status_not_downloaded
    AiModelState.Queued -> R.string.ai_model_status_queued
    is AiModelState.Downloading -> R.string.ai_model_status_downloading
    AiModelState.Verifying -> R.string.ai_model_status_verifying
    is AiModelState.Ready -> R.string.ai_model_status_ready
    is AiModelState.Failed -> when (error) {
        AiModelError.STORAGE -> R.string.ai_model_download_storage_error
        AiModelError.INTEGRITY -> R.string.ai_model_integrity_error
        AiModelError.NETWORK,
        AiModelError.DOWNLOAD,
        AiModelError.UNKNOWN -> R.string.ai_model_status_failed
    }
}
