// A chuva de agora no lugar da pessoa, pra decidir entre a caminhada na rua e a câmera: a última localização
// que o celular já conhece (sem ligar o GPS) e o tempo atual do Open-Meteo (sem chave). Pro Open-Meteo só vão a
// latitude e a longitude arredondadas (uns 1 km). Sem localização, sem internet ou com erro, não dá pra saber
// (null): a caminhada vira câmera. A regra da chuva e a leitura da resposta são funções puras (testadas em ConversaTest).
package com.implacavel.alarme

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** Chuva de verdade, não garoa de sensor: pelo menos isso nos últimos 15 min (é o que o Open-Meteo soma). */
private const val CHUVA_MINIMA_MM = 0.2

/**
 * Está chovendo? Com [precipitacaoMm] de pelo menos [CHUVA_MINIMA_MM], ou com [codigo] do tempo (WMO) de
 * garoa ou chuva (51 a 67), pancada de chuva (80 a 82) ou tempestade (95 a 99). Sem os dados, não (a resposta sem
 * nenhum dos dois vira "não dá pra saber" em [chuvaDoOpenMeteo]).
 */
fun estaChovendo(precipitacaoMm: Double?, codigo: Int?): Boolean =
    (precipitacaoMm != null && precipitacaoMm >= CHUVA_MINIMA_MM) ||
        (codigo != null && (codigo in 51..67 || codigo in 80..82 || codigo in 95..99))

/**
 * A chuva na resposta do Open-Meteo ([estaChovendo] com `current.precipitation` e `current.weather_code`). Resposta
 * quebrada, ou sem nenhum dos dois, null: não dá pra saber (e aí é câmera).
 */
fun chuvaDoOpenMeteo(json: String): Boolean? = runCatching {
    val agora = JSONObject(json).getJSONObject("current")
    val mm = (agora.opt("precipitation") as? Number)?.toDouble()
    val codigo = (agora.opt("weather_code") as? Number)?.toInt()
    if (mm == null && codigo == null) null else estaChovendo(mm, codigo)
}.getOrNull()

/** O pedido do tempo de agora no Open-Meteo, com o lugar arredondado em 2 casas (uns 1 km), sempre com ponto decimal. */
fun enderecoDoClima(latitude: Double, longitude: Double): String =
    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=precipitation,weather_code".format(Locale.US, latitude, longitude)

object Clima {
    private val handler = Handler(Looper.getMainLooper())
    private var pedidos = 0 // muda a cada [cancelar]: resposta de uma busca velha não chega

    /**
     * Dá pra conferir a caminhada pelo GPS: localização precisa liberada e a localização do celular ligada
     * (no Android 8, o GPS ligado).
     */
    fun localizacaoPronta(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val gps = ctx.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) gps.isLocationEnabled else gps.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    }

    /** Se está chovendo agora no último lugar conhecido, entregue na thread principal ([aoSaber]) em até uns 10 s. */
    fun chovendo(ctx: Context, aoSaber: (Boolean?) -> Unit) {
        val numero = pedidos
        val lugar = ultimoLugar(ctx)
        if (lugar == null) Log.i(TAG, "Clima: sem localização conhecida, não dá pra saber se chove")
        thread(isDaemon = true, name = "clima") {
            val chuva = lugar?.let { runCatching { baixarTexto(enderecoDoClima(it.latitude, it.longitude))?.let(::chuvaDoOpenMeteo) }.getOrNull() }
            Log.i(TAG, "Clima: ${when (chuva) { true -> "chovendo"; false -> "sem chuva"; null -> "não deu pra saber (sem internet?)" }}")
            handler.post { if (numero == pedidos) aoSaber(chuva) }
        }
    }

    /** A busca em curso não entrega mais nada. */
    fun cancelar() {
        pedidos++
    }

    /**
     * A localização mais nova que o celular já tem (do "fused", da rede ou do GPS), sem ligar nada. Pode ser
     * antiga: pra chuva, basta a cidade. Null sem permissão ou sem nenhuma.
     */
    @SuppressLint("MissingPermission") // conferida logo no começo
    private fun ultimoLugar(ctx: Context): Location? {
        val permitido = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (!permitido) return null
        val gps = ctx.getSystemService(LocationManager::class.java) ?: return null
        val fontes = runCatching { gps.allProviders }.getOrDefault(emptyList())
        // "fused" é o LocationManager.FUSED_PROVIDER, que só tem nome no Android 12+
        return listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it in fontes }
            .mapNotNull { runCatching { gps.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }
}
