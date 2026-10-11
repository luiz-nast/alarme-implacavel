// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.net.URI
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Tempo olhando pra câmera, de olhos abertos, pra desligar o alarme. */
const val META_OLHAR_MS = 40 * 60_000L

/** No botão "Testar agora" a câmera pede só isto: teste tem que ser rápido. */
const val META_OLHAR_TESTE_MS = 30_000L

/**
 * Sem olhar pra câmera por esse tempo, aparece na tela quanto falta pra zerar e toca um bipe; depois,
 * a cada mais esse tempo, um bipe mais alto ([volumeDoBipe]).
 */
const val AVISO_SEM_OLHAR_MS = 3_000L

/**
 * Sem sinal de que a pessoa está olhando pra câmera por esse tempo, a vigia do AlarmeService zera o
 * anel e volta a tocar a música: a missão recomeça. Fechar a tela traz a música de volta, mas não zera.
 */
const val DESISTENCIA_MS = 20_000L

/** Bipes antes de zerar: um a cada [AVISO_SEM_OLHAR_MS], todos antes de [DESISTENCIA_MS] (aos 3, 6 … 18 s). */
val BIPES_ATE_ZERAR = ((DESISTENCIA_MS - 1) / AVISO_SEM_OLHAR_MS).toInt()

/** O que a câmera está vendo agora. */
enum class Leitura { SEM_CAMERA, SEM_ROSTO, OLHOS_FECHADOS, OLHANDO }

/** Quanto tempo de olhos abertos a missão do [alarme] pede. */
fun metaOlhar(alarme: Alarme): Long = if (alarme.deTeste) META_OLHAR_TESTE_MS else META_OLHAR_MS

/** Aceita "stop" e jeitos de falar ou transcrever com sotaque ("estop", "istópi", "stopi"). */
fun disseStop(texto: String): Boolean {
    val semAcento = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    return Regex("\\b[ei]?stop").containsMatchIn(semAcento)
}

/**
 * Olho aberto pro ML Kit: a média dos dois olhos (probabilidade de aberto) acima disto. Olho fechado
 * dá perto de 0. Cada olho acima de 60% obrigava a arregalar; a média acima de 30% deixava passar
 * quem ainda estava dormindo (o dono voltou a dormir depois da missão). 45% fica no meio.
 */
private const val OLHO_ABERTO = 0.45f

/**
 * Olhando = os olhos abertos ([OLHO_ABERTO]), com o rosto em qualquer ângulo (não precisa estar de
 * frente: quem está acordando não fica reto). Sem informação de nenhum olho, conta como fechado.
 */
fun classificarRosto(olhoEsquerdo: Float?, olhoDireito: Float?): Leitura {
    val olhos = listOfNotNull(olhoEsquerdo, olhoDireito)
    return if (olhos.isNotEmpty() && olhos.average() > OLHO_ABERTO) Leitura.OLHANDO else Leitura.OLHOS_FECHADOS
}

/**
 * Tempo de olhos abertos acumulado no anel da câmera, depois de [passoMs] (o tempo real desde a
 * última conferência): só sobe se a pessoa está [olhando] agora. Sem isso, para na hora e nunca desce.
 */
fun avancarOlhar(olhadoMs: Long, olhando: Boolean, passoMs: Long): Long = if (olhando) olhadoMs + passoMs else olhadoMs

/** A cada tanto de câmera completa, a voz avisa ("10 minutes passed"). */
const val MARCO_MS = 10 * 60_000L

/**
 * Marco de [MARCO_MS] completado quando o anel passou de [antesMs] pra [depoisMs], em minutos (10, 20,
 * 30), ou null. O fim da [metaMs] não conta: aí o alarme já desliga.
 */
fun marcoCompletado(antesMs: Long, depoisMs: Long, metaMs: Long): Int? {
    val marco = depoisMs / MARCO_MS * MARCO_MS
    return if (marco > antesMs && marco in 1 until metaMs) (marco / 60_000).toInt() else null
}

/** Contagem na tela: depois de [AVISO_SEM_OLHAR_MS] sem olhar, os segundos que faltam pro anel zerar; antes, null. */
fun segundosParaZerar(semOlharMs: Long): Int? =
    if (semOlharMs < AVISO_SEM_OLHAR_MS) null else ceil((DESISTENCIA_MS - semOlharMs) / 1000.0).toInt().coerceAtLeast(0)

/**
 * Volume de alarme do celular no bipe número [n] (1 = o primeiro), num celular com [maximo] degraus:
 * sobe por igual a cada bipe, do levinho (1/6 do máximo) ao último, no máximo. Os degraus do Android
 * já seguem o ouvido (cada um, uns dB a mais), e o bipe passa por cima da trava do volume do alarme.
 */
fun volumeDoBipe(n: Int, maximo: Int): Int =
    (maximo * n.coerceIn(1, BIPES_ATE_ZERAR).toFloat() / BIPES_ATE_ZERAR).roundToInt().coerceAtLeast(1)

/** Sites que a página do YouTube abre: o próprio YouTube e o Google (aviso de cookies e login). */
private val SITES_DA_PAGINA = listOf("youtube.com", "youtu.be", "google.com", "google.com.br")

/**
 * Na página do YouTube da etapa da câmera, só abre (ali mesmo) link de site. Os outros ("intent:",
 * "vnd.youtube:", "market:"...) abririam outro app por cima do alarme. Na página [principal], só o
 * YouTube e o Google: o resto também fica sem efeito. Partes de dentro da página (anúncios, vídeos
 * embutidos) vêm de qualquer site.
 */
fun linkFicaNaPagina(url: String, principal: Boolean = true): Boolean {
    val endereco = runCatching { URI(url) }.getOrNull() ?: return false
    if (endereco.scheme?.lowercase() !in setOf("https", "http")) return false
    val site = endereco.host?.lowercase() ?: return false
    return !principal || SITES_DA_PAGINA.any { site == it || site.endsWith(".$it") }
}
