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
import org.json.JSONObject

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
     * O alarme que tocou e ainda não foi cumprido. Só [AlarmeService] apaga, quando a missão é
     * cumprida. Se o processo morrer no meio (celular desligado, app encerrado ou caído), o [App]
     * vê isto ao voltar e religa o alarme. Gravação ilegível conta como nenhum, pra não derrubar o app.
     */
    var emAndamento: EmAndamento?
        get() = runCatching { prefs.getString("alarmeEmAndamento", null)?.let { EmAndamento.deJson(JSONObject(it)) } }.getOrNull()
        set(valor) = prefs.edit(commit = true) {
            if (valor == null) remove("alarmeEmAndamento") else putString("alarmeEmAndamento", valor.paraJson().toString())
        }

    /**
     * Volume de alarme de antes de um alarme que já foi cumprido mas ainda está comemorando (os sons do
     * fim tocam a 40%). Gravado antes de apagar o [emAndamento]: se o app morrer na comemoração, o [App]
     * devolve o volume ao voltar, e um alarme que dispare nesse meio guarda este como o "de antes".
     */
    var volumeParaDevolver: Int?
        get() = prefs.getInt("volumeParaDevolver", -1).takeIf { it >= 0 }
        set(valor) = prefs.edit(commit = true) { if (valor == null) remove("volumeParaDevolver") else putInt("volumeParaDevolver", valor) }

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
