package com.implacavel.alarme

import android.app.Application
import android.util.Log

/** Tag dos logs do app. Pra acompanhar um alarme: `adb logcat -s Implacavel`. */
const val TAG = "Implacavel"

/**
 * Início do processo. Roda antes de qualquer tela, receiver ou serviço, inclusive logo após o
 * celular reiniciar, antes do primeiro desbloqueio.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarmes.carregar(this)
        Ajustes.carregar(this)
        Notificacoes.criarCanal(this)
        // O processo morreu com um alarme tocando (celular desligado, app encerrado): ele volta em instantes
        Ajustes.alarmeEmAndamento?.let {
            Log.i(TAG, "Alarme $it foi interrompido no meio: voltando a tocar")
            Agendador.retomar(this, it)
        }
    }
}
