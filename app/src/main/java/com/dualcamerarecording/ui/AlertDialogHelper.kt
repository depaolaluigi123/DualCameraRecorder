package com.dualcamerarecording.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import com.dualcamerarecording.R

/**
 * Generic utility for showing a simple modal alert dialog.
 *
 * Ported from MicGainLevelerApp's `AlertDialogHelper.kt`. Lets callers show a
 * confirmation dialog with Cancel + Action buttons — used here to confirm
 * closing the app from the back button.
 *
 * Also exposes a `showWithCheckbox` variant for the device-compatibility
 * alert shown at app startup: it embeds a "Don't show again" CheckBox in
 * the dialog and reports the final checkbox state to the caller via the
 * `onDismiss` callback so the preference can be persisted.
 */
object AlertDialogHelper {

    /**
     * Show a modal alert dialog that the user must dismiss.
     */
    fun show(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        onDismiss: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                onDismiss?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .setCancelable(true)
            .show()
    }

    /**
     * Show a modal alert dialog with two buttons: a neutral "Cancel" button
     * and a positive "action" button.
     */
    fun showWithAction(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        actionLabel: CharSequence,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setPositiveButton(actionLabel) { d, _ ->
                d.dismiss()
                onAction?.invoke()
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .setCancelable(true)
            .show()
    }

    /**
     * Show a modal alert dialog with a "Don't show again"-style checkbox
     * embedded in the dialog body. The current checkbox state is reported
     * to [onDismiss] when the dialog is dismissed (either via OK or by
     * tapping outside / pressing back), so the caller can persist it.
     *
     * The checkbox defaults to unchecked. The text is supplied by the
     * caller so it can be localized.
     */
    fun showWithCheckbox(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        checkboxLabel: CharSequence,
        okLabel: CharSequence = context.getString(android.R.string.ok),
        initiallyChecked: Boolean = false,
        onDismiss: ((checked: Boolean) -> Unit)? = null,
    ) {
        // Inflate a custom view that holds the CheckBox. We do not put the
        // CheckBox in the layout XML of the activity because the alert is
        // shown once per launch and has no business in the main activity
        // hierarchy.
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_checkbox, null)
        val checkBox = view.findViewById<CheckBox>(R.id.dialogCheckbox)
        checkBox.text = checkboxLabel
        checkBox.isChecked = initiallyChecked

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(view)
            .setPositiveButton(okLabel) { d, _ -> d.dismiss() }
            .setCancelable(true)
            .create()

        // Report the final checkbox state on every dismiss path (OK button,
        // back press, tap-outside) so the caller can persist "don't show
        // again" regardless of how the user closed the dialog.
        dialog.setOnDismissListener { onDismiss?.invoke(checkBox.isChecked) }
        dialog.show()
    }
}
