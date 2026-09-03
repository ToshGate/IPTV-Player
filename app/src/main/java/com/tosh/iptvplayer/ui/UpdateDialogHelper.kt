package com.tosh.iptvplayer.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tosh.iptvplayer.R
import com.tosh.iptvplayer.data.UpdateChecker
import com.tosh.iptvplayer.model.UpdateInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Shows the "update available" dialog and drives the download → install flow if the user
 * accepts. Shared between the silent startup check and the manual "Verificar atualizações" row
 * in Definições, so both behave identically. */
fun AppCompatActivity.showUpdateDialog(updateChecker: UpdateChecker, updateInfo: UpdateInfo) {
    val notes = updateInfo.releaseNotes.trim().ifBlank { null }
    val message = buildString {
        append("Está disponível a versão ${updateInfo.versionName}.")
        if (notes != null) {
            append("\n\n")
            append(if (notes.length > 400) notes.take(400).trimEnd() + "…" else notes)
        }
    }

    MaterialAlertDialogBuilder(this, R.style.CustomDarkDialog)
        .setTitle("Nova versão disponível")
        .setMessage(message)
        .setPositiveButton("Atualizar") { _, _ -> startUpdateDownload(updateChecker, updateInfo) }
        .setNegativeButton("Mais tarde", null)
        .show()
}

private fun AppCompatActivity.startUpdateDownload(updateChecker: UpdateChecker, updateInfo: UpdateInfo) {
    if (!updateChecker.canRequestInstall()) {
        Toast.makeText(
            this,
            "Autoriza a instalação de apps desta fonte para continuares, depois toca em \"Atualizar\" outra vez.",
            Toast.LENGTH_LONG
        ).show()
        startActivity(updateChecker.installPermissionSettingsIntent())
        return
    }

    Toast.makeText(this, "A descarregar atualização…", Toast.LENGTH_SHORT).show()
    lifecycleScope.launch {
        val file = updateChecker.downloadUpdate(updateInfo)
        if (file != null) {
            updateChecker.promptInstall(file)
        } else {
            Toast.makeText(
                this@startUpdateDownload,
                "Falha ao descarregar a atualização. Tenta novamente mais tarde.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
