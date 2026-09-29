package com.implacavel.alarme

import android.app.Application

/**
 * Início do processo. Roda antes de qualquer tela, receiver ou serviço, inclusive logo após o
 * celular reiniciar, antes do primeiro desbloqueio.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarmes.carregar(this)
        Notificacoes.criarCanal(this)
    }
}
