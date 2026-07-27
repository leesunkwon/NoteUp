package com.kotlinsun.noteup.ui.common

import android.content.DialogInterface
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.kotlinsun.noteup.R

fun AlertDialog.applyCriticalPositiveAction() {
    getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.noteup_fg_critical),
        )
        setTextColor(ContextCompat.getColor(context, R.color.noteup_fg_neutral_inverted))
    }
}
