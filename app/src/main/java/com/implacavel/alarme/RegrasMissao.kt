// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Tempo olhando pra câmera, de olhos abertos, pra desligar o alarme. */
const val META_OLHAR_MS = 20 * 60_000L

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
enum class Leitura { SEM_CAMERA, SEM_ROSTO, DE_LADO, OLHOS_FECHADOS, OLHANDO }

/** Quanto tempo de olhos abertos a missão do [alarme] pede. */
fun metaOlhar(alarme: Alarme): Long = if (alarme.deTeste) META_OLHAR_TESTE_MS else META_OLHAR_MS

/** Aceita "stop" e jeitos de falar ou transcrever com sotaque ("estop", "istópi", "stopi"). */
fun disseStop(texto: String): Boolean {
    val semAcento = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    return Regex("\\b[ei]?stop").containsMatchIn(semAcento)
}

/** Olhando = rosto de frente (até 25° de giro) e os dois olhos abertos (probabilidade acima de 60%). */
fun classificarRosto(giroLateral: Float, giroVertical: Float, olhoEsquerdo: Float?, olhoDireito: Float?): Leitura = when {
    abs(giroLateral) > 25f || abs(giroVertical) > 25f -> Leitura.DE_LADO
    (olhoEsquerdo ?: 0f) > 0.6f && (olhoDireito ?: 0f) > 0.6f -> Leitura.OLHANDO
    else -> Leitura.OLHOS_FECHADOS
}

/**
 * Tempo de olhos abertos acumulado no anel da câmera, depois de [passoMs] (o tempo real desde a
 * última conferência): só sobe se a pessoa está [olhando] agora. Sem isso, para na hora e nunca desce.
 */
fun avancarOlhar(olhadoMs: Long, olhando: Boolean, passoMs: Long): Long = if (olhando) olhadoMs + passoMs else olhadoMs

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
