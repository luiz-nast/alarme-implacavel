package com.implacavel.alarme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Lista de alarmes salvos, fonte única da verdade: a tela observa [lista]; receivers e serviço
 * leem e gravam por aqui. Tudo roda na thread principal, então não há concorrência.
 *
 * Grava JSON no armazenamento "protegido pelo dispositivo", que pode ser lido logo depois de o
 * celular reiniciar, antes do primeiro desbloqueio. É isso que deixa o alarme tocar mesmo se o
 * celular reiniciar de madrugada.
 */
object Alarmes {
    private lateinit var prefs: SharedPreferences
    private val _lista = MutableStateFlow<List<Alarme>>(emptyList())
    val lista: StateFlow<List<Alarme>> = _lista.asStateFlow()

    /** Chamado uma vez em [App.onCreate], antes de qualquer outro uso. */
    fun carregar(ctx: Context) {
        prefs = ctx.createDeviceProtectedStorageContext().getSharedPreferences("alarmes", Context.MODE_PRIVATE)
        val json = JSONArray(prefs.getString("lista", null) ?: "[]")
        _lista.value = (0 until json.length()).map { Alarme.deJson(json.getJSONObject(it)) }
    }

    fun buscar(id: Int): Alarme? = _lista.value.firstOrNull { it.id == id }

    fun novoId(): Int = (_lista.value.maxOfOrNull { it.id } ?: 0) + 1

    /** Insere ou substitui (pelo id), mantendo a lista em ordem de horário. */
    fun salvar(alarme: Alarme) =
        gravar((_lista.value.filter { it.id != alarme.id } + alarme).sortedWith(compareBy({ it.hora }, { it.minuto })))

    fun remover(id: Int) = gravar(_lista.value.filter { it.id != id })

    private fun gravar(lista: List<Alarme>) {
        // commit e não apply: receivers podem ser encerrados pelo sistema logo em seguida
        prefs.edit(commit = true) { putString("lista", JSONArray(lista.map { it.paraJson() }).toString()) }
        _lista.value = lista
    }
}
