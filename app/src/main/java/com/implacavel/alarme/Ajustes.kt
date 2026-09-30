package com.implacavel.alarme

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ajustes e estado que precisam sobreviver ao processo: a música do alarme (um arquivo de áudio
 * escolhido pelo usuário no celular; o app não traz música) e o alarme em andamento. Gravado em
 * [prefsProtegidas], como os alarmes.
 */
object Ajustes {
    data class Musica(val uri: String, val nome: String)

    private lateinit var prefs: SharedPreferences
    private val _musica = MutableStateFlow<Musica?>(null)
    val musica: StateFlow<Musica?> = _musica.asStateFlow()

    /**
     * Alarme tocando que ainda não foi cumprido. Se o processo morrer no meio (celular desligado,
     * app encerrado), o [App] vê isto ao voltar e religa o alarme.
     */
    var alarmeEmAndamento: Int?
        get() = prefs.getInt("emAndamento", -1).takeIf { it >= 0 }
        set(id) = prefs.edit(commit = true) { if (id == null) remove("emAndamento") else putInt("emAndamento", id) }

    /** Chamado uma vez em [App.onCreate]. */
    fun carregar(ctx: Context) {
        prefs = ctx.prefsProtegidas("ajustes")
        _musica.value = prefs.getString("musicaUri", null)?.let { Musica(it, prefs.getString("musicaNome", null) ?: "Música") }
    }

    /** Guarda a música e a permissão de lê-la depois, que sobrevive a reinícios do celular. */
    fun escolherMusica(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val nome = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Música"
        prefs.edit(commit = true) {
            putString("musicaUri", uri.toString())
            putString("musicaNome", nome)
        }
        _musica.value = Musica(uri.toString(), nome)
    }

    fun tirarMusica(ctx: Context) {
        _musica.value?.let {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(it.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit(commit = true) {
            remove("musicaUri")
            remove("musicaNome")
        }
        _musica.value = null
    }
}
