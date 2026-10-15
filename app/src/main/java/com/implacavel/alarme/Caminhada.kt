// Tela da caminhada da manhã (despertador "conversa + caminhada"), mostrada pela AlarmeActivity depois da
// conversa: o prazo pra sair de casa e a caminhada contada pelo GPS, num anel. Sem plano B: dentro de casa, sem
// sinal, com a localização desligada ou sem a permissão dela, o tempo não anda e os bipes não param. As regras e os
// tempos ficam em RegrasCaminhada.kt; a vigia (bipes de parado), no AlarmeService.
package com.implacavel.alarme

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** O GPS manda um fixo por segundo, mesmo parado: o mais rápido que o GPS do celular dá. */
private const val INTERVALO_GPS_MS = 1_000L

/** De quanto em quanto tempo a etapa confere o relógio: contagens, fixo velho e o aviso pro serviço. */
private const val CONFERIR_MS = 250L

/** De quanto em quanto tempo, andando, a etapa avisa o serviço. */
private const val AVISO_ANDANDO_MS = 1_000L

/** Sem fixo novo por esse tempo (o GPS manda um por segundo), o último deixa de valer: "sem sinal", e parado. */
private const val FIXO_VELHO_MS = 4_000L

private val ANEL = 220.dp

private val VerdeAndando = Color(0xFF2E7D32)
private val VermelhoParado = Color(0xFFC62828)

/** O último fixo do GPS como a tela mostra: o [movimento], a [velocidade] (m/s), a [precisao] (m) e [quando] chegou (SystemClock.elapsedRealtime). */
private data class LeituraGps(val movimento: Movimento, val velocidade: Float?, val precisao: Float?, val quando: Long)

/**
 * Etapa da caminhada: um anel que enche com o tempo andando a pé ([passoDeCaminhada], um fixo do GPS
 * por segundo) até somar [metaMs], começando de [andadoInicialMs] (o que o serviço já guardou: a tela
 * voltou depois de sair, ou foi recriada no meio). Até [prazoAte] (SystemClock.elapsedRealtime) é o
 * prazo pra sair de casa: andar nele já conta, e a tela mostra quanto falta. [onAndando] recebe o tempo
 * já somado a cada [AVISO_ANDANDO_MS] andando e no instante em que para; depois do prazo, parado, a
 * tela mostra a contagem pro bipe a partir do último sinal recebido pelo serviço ([ultimoPasso],
 * [segundosProBipe]); os bipes são da vigia de lá.
 *
 * Funciona sem internet: é o GPS puro, ouvindo os satélites. Sem sinal (dentro de casa), com a localização desligada
 * (botão pra ligar) ou sem a permissão dela (botão pra liberar), não conta: tem que ir pra rua. [onDefeito] só num
 * celular sem GPS nenhum, pra um defeito não prender ninguém (como a câmera que não abre).
 * [onConcluiu] uma vez, com o anel completo.
 */
@Composable
fun EtapaCaminhada(
    andadoInicialMs: Long,
    metaMs: Long,
    prazoAte: Long,
    ultimoPasso: Long,
    onAndando: (andadoMs: Long) -> Unit,
    onDefeito: () -> Unit,
    onConcluiu: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val avisarAndando by rememberUpdatedState(onAndando)
    val avisarDefeito by rememberUpdatedState(onDefeito)
    val concluir by rememberUpdatedState(onConcluiu)
    // A meta pode subir no meio: alarme de verdade disparando durante o teste (EmAndamento.juntar)
    val meta by rememberUpdatedState(metaMs)
    // Continua do que o serviço guardou; parado, o tempo andado fica onde está e nunca desce
    var andadoMs by remember { mutableLongStateOf(andadoInicialMs) }
    var leitura by remember { mutableStateOf<LeituraGps?>(null) }
    var anterior by remember { mutableStateOf<Fixo?>(null) } // o último fixo válido: o passo é medido a partir dele
    var ligada by remember { mutableStateOf(gpsLigado(ctx)) }
    var permitida by remember { mutableStateOf(Poder.LOCALIZACAO.liberado(ctx)) }
    var agora by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Com a permissão (de novo, se ela foi tirada e liberada no meio), o GPS começa a ouvir
    DisposableEffect(permitida) {
        if (!permitida) {
            Log.w(TAG, "Caminhada: sem permissão de localização, o tempo não anda")
            return@DisposableEffect onDispose {}
        }
        val gps = ctx.getSystemService(LocationManager::class.java)
        val ouvinte = object : LocationListener {
            override fun onLocationChanged(local: Location) {
                val atual = fixoDe(local)
                val chegou = SystemClock.elapsedRealtime()
                leitura = LeituraGps(movimento(anterior, atual), velocidade(anterior, atual), atual.precisaoM, chegou)
                andadoMs += passoDeCaminhada(anterior, atual)
                if (fixoValido(atual)) anterior = atual
            }

            // Antes do Android 11 estes três não têm corpo pronto: sem eles, o app cai quando a localização liga ou desliga
            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Chamado só antes do Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            ouvirGps(gps, ouvinte)
            Log.i(TAG, "Caminhada: GPS ligado, ${andadoMs / 1000} s já andados de ${meta / 1000}")
        } catch (e: SecurityException) {
            // Permissão tirada entre a conferência e o pedido: o laço abaixo relê e mostra o botão
            Log.w(TAG, "Caminhada: sem permissão de localização", e)
            permitida = false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Caminhada: o celular não tem GPS, defeito: o alarme desliga", e)
            avisarDefeito()
        }
        onDispose { gps.removeUpdates(ouvinte) }
    }

    LaunchedEffect(Unit) {
        var ultimoAviso = 0L
        var andavaAntes = false
        while (true) {
            delay(CONFERIR_MS)
            agora = SystemClock.elapsedRealtime()
            val ligadaAgora = gpsLigado(ctx)
            if (ligadaAgora != ligada) Log.i(TAG, "Caminhada: localização ${if (ligadaAgora) "ligada" else "desligada"}")
            ligada = ligadaAgora
            permitida = Poder.LOCALIZACAO.liberado(ctx) // liberada nas Configurações, ou tirada no meio
            val andando = recente(leitura, agora)?.movimento == Movimento.ANDANDO
            if (andando != andavaAntes) Log.i(TAG, "Caminhada: ${retorno(recente(leitura, agora)).second}, ${andadoMs / 1000} s de ${meta / 1000}")
            // A cada segundo andando, e no instante em que parou: a contagem pro bipe começa daí
            if (andando && agora - ultimoAviso >= AVISO_ANDANDO_MS || andavaAntes && !andando) {
                avisarAndando(andadoMs)
                ultimoAviso = agora
            }
            andavaAntes = andando
            if (andadoMs >= meta) {
                Log.i(TAG, "Missão: caminhada completa, alarme desligado")
                concluir()
                break
            }
        }
    }

    val andando = recente(leitura, agora)?.movimento == Movimento.ANDANDO
    val (corAlvo, status) = when {
        !permitida -> VermelhoParado to "A localização está sem permissão"
        !ligada -> VermelhoParado to "Ligue a localização"
        else -> retorno(recente(leitura, agora))
    }
    val cor by animateColorAsState(corAlvo, label = "cor da caminhada")
    val noPrazo = avisoDoPrazo(agora, prazoAte)
    val bipeEm = if (andando) null else segundosProBipe(agora, ultimoPasso, prazoAte)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ANEL), contentAlignment = Alignment.Center) {
            AnelDaCaminhada((andadoMs.toFloat() / metaMs.coerceAtLeast(1)).coerceIn(0f, 1f), cor, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tempoAndado(andadoMs), color = VinhoAlarme, fontSize = 56.sp, fontWeight = FontWeight.Black)
                Text("de ${tempoAndado(metaMs)} andando", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(status, color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        // Linha sempre presente, pra tela não pular: o prazo pra sair ou, parado depois dele, a contagem pro bipe
        if (noPrazo != null) {
            Text(noPrazo, color = VinhoAlarme, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        } else {
            val aviso = bipeEm?.let { if (it > 0) "Bipe em $it s" else "🔊 Anda que o bipe para" }.orEmpty()
            Text(aviso, color = VermelhoParado, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }
        if (!permitida) {
            Spacer(Modifier.height(12.dp))
            PedirLocalizacao()
        } else if (!ligada) {
            Spacer(Modifier.height(12.dp))
            BotaoGrande("LIGAR LOCALIZAÇÃO", { desbloquear(activity) { abrirLocalizacao(ctx) } }, fundoClaro = true)
        }
        Spacer(Modifier.height(8.dp))
        Text("Só conta andando a pé, na rua", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

/**
 * O GPS do celular está ligado (do Android 9 em diante, segue o botão da localização): sem ele, a
 * caminhada não tem como contar. É o que a etapa da caminhada confere.
 */
fun gpsLigado(ctx: Context): Boolean = ctx.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)

/** O último fixo, se ainda vale (chegou há até [FIXO_VELHO_MS]); senão null: sem sinal. */
private fun recente(leitura: LeituraGps?, agora: Long): LeituraGps? = leitura?.takeIf { agora - it.quando <= FIXO_VELHO_MS }

/** O retorno de cada leitura do GPS (null = sem sinal): cor (verde andando, vermelho no resto) e mensagem. */
private fun retorno(leitura: LeituraGps?): Pair<Color, String> {
    if (leitura == null) return VermelhoParado to "Sem sinal de GPS: vá pra rua, longe de prédios"
    val v = leitura.velocidade?.let(::textoDaVelocidade).orEmpty()
    return when (leitura.movimento) {
        Movimento.ANDANDO -> VerdeAndando to "Andando $v ✓"
        Movimento.PARADO -> VermelhoParado to "Parado: anda!"
        Movimento.RAPIDO_DEMAIS -> VermelhoParado to "Rápido demais ($v): só vale a pé"
        Movimento.IMPRECISO -> VermelhoParado to "GPS fraco${leitura.precisao?.let { " (±${it.roundToInt()} m)" }.orEmpty()}: vá pra rua, longe de prédios"
        Movimento.FALSO -> VermelhoParado to "Localização falsa não vale"
    }
}

/** O fixo do Android sem o Android, pras regras de RegrasCaminhada.kt. */
private fun fixoDe(local: Location) = Fixo(
    // O relógio do fixo, que não pula; se o celular não disser, a hora em que chegou
    tempoMs = (local.elapsedRealtimeNanos / 1_000_000).takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
    latitude = local.latitude,
    longitude = local.longitude,
    precisaoM = if (local.hasAccuracy()) local.accuracy else null,
    velocidadeMs = if (local.hasSpeed()) local.speed else null,
    precisaoVelocidade = if (local.hasSpeedAccuracy()) local.speedAccuracyMetersPerSecond else null,
    falso = localizacaoFalsa(local),
)

/** O fixo veio de um app de localização falsa (opção de desenvolvedor). */
@Suppress("DEPRECATION") // isFromMockProvider, o único jeito antes do Android 12
private fun localizacaoFalsa(local: Location): Boolean = if (Build.VERSION.SDK_INT >= 31) local.isMock else local.isFromMockProvider

/**
 * Pede ao GPS um fixo por segundo, mesmo parado (distância mínima 0), entregue no fio principal. Sem a permissão,
 * lança SecurityException; celular sem GPS, IllegalArgumentException.
 */
@SuppressLint("MissingPermission") // quem chama trata a falta da permissão
private fun ouvirGps(gps: LocationManager, ouvinte: LocationListener) {
    gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVALO_GPS_MS, 0f, ouvinte, Looper.getMainLooper())
}

/**
 * Localização sem permissão: desbloqueia o celular, se preciso, e pede no diálogo do sistema (como a câmera, em
 * PedirCamera). Se o Android não mostrar mais o diálogo (negado de vez), abre as Configurações do app, com um tempo
 * sem a tela do alarme voltar por cima. A tela relê a permissão a cada conferência.
 */
@Composable
private fun PedirLocalizacao() {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Poder.LOCALIZACAO.liberado(ctx)) {
            AlarmeService.darTempo(ctx)
            Poder.LOCALIZACAO.abrirConfiguracao(ctx)
        }
    }
    BotaoGrande("LIBERAR LOCALIZAÇÃO", { desbloquear(activity) { pedir.launch(Poder.LOCALIZACAO.pedido) } }, fundoClaro = true)
}

/** Abre a localização nas Configurações, numa tarefa separada (como [Poder.abrirConfiguracao]), com um tempo sem a tela do alarme voltar por cima. */
private fun abrirLocalizacao(ctx: Context) {
    AlarmeService.darTempo(ctx)
    runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w(TAG, "Caminhada: não abriu as Configurações da localização", it) }
}


/** Trilha fraca do anel inteiro + arco forte com o tempo andado, começando no topo (como o anel da câmera). */
@Composable
private fun AnelDaCaminhada(progresso: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val espessura = 14.dp.toPx()
        val canto = Offset(espessura / 2, espessura / 2)
        val area = Size(size.width - espessura, size.height - espessura)
        drawArc(cor.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = canto, size = area, style = Stroke(espessura))
        drawArc(
            cor, -90f, 360f * progresso, useCenter = false, topLeft = canto, size = area,
            style = Stroke(espessura, cap = StrokeCap.Round),
        )
    }
}
