package com.tangem.tangemdemo

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment

class NoExtendedLengthSupportDialog : DialogFragment() {

    companion object {
        val TAG: String = NoExtendedLengthSupportDialog::class.java.simpleName
        var allReadyShowed = false
        var message = R.string.the_nfc_adapter_length_apdu
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(activity)
                .setIcon(R.drawable.tangem_logo_small_new)
                .setTitle(R.string.warning)
                .setMessage(message)
                .setPositiveButton(R.string.got_it) { _, _ -> NoExtendedLengthSupportDialog.allReadyShowed = false }
                .create()
    }

}