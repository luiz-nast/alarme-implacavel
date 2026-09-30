package com.implacavel.alarme

import android.app.Application

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
    }
}
