package com.implacavel.alarme

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/** Tag dos logs do app. Pra acompanhar um alarme: `adb logcat -s Implacavel`. */
const val TAG = "Implacavel"

/**
 * SharedPreferences no armazenamento protegido pelo dispositivo, que pode ser lido logo depois de o
 * celular reiniciar, antes do primeiro desbloqueio. É isso que deixa o alarme tocar mesmo se o
 * celular reiniciar de madrugada.
 */
fun Context.prefsProtegidas(nome: String): SharedPreferences =
    createDeviceProtectedStorageContext().getSharedPreferences(nome, Context.MODE_PRIVATE)

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
            Agendador.tocarDaqui(this, it, 2_000)
        }
    }
}
