package com.implacavel.alarme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Recebe o disparo agendado pelo [Agendador], atualiza o alarme salvo e põe o [AlarmeService] pra tocar. */
class AlarmeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Agendador.ACAO_DISPARAR) return
        val id = intent.getIntExtra(Agendador.EXTRA_ID, -1)
        val soneca = intent.getBooleanExtra(Agendador.EXTRA_SONECA, false)
        Log.i(TAG, "Disparo recebido: alarme $id, soneca=$soneca")
        if (id != Alarme.ID_TESTE) {
            val alarme = Alarmes.buscar(id) ?: return // foi excluído depois de agendado
            when {
                soneca -> Alarmes.salvar(alarme.copy(sonecaAte = null))
                !alarme.ativo -> {
                    Log.i(TAG, "Disparo ignorado: alarme $id foi desligado depois de agendado")
                    return
                }
                alarme.dias.isEmpty() -> Alarmes.salvar(alarme.copy(ativo = false)) // toca uma vez só
                else -> Agendador.agendar(ctx, alarme) // já deixa a próxima repetição agendada
            }
        }
        AlarmeService.tocar(ctx, id)
    }
}
