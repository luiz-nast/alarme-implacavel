// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil

/** Tempo olhando pra câmera, de olhos abertos, pra desligar o alarme. */
const val META_OLHAR_MS = 10_000L

/**
 * Sem sinal de que a pessoa está olhando pra câmera por esse tempo (inclusive com a tela do alarme
 * fechada), a vigia do AlarmeService volta a tocar a música e a missão recomeça.
 */
const val DESISTENCIA_MS = 20_000L

/** O que a câmera está vendo agora. */
enum class Leitura { SEM_CAMERA, SEM_ROSTO, DE_LADO, OLHOS_FECHADOS, OLHANDO }

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
 * Progresso de 0 a 1 do anel da câmera depois de [passoMs]: só sobe com a [leitura] do quadro atual
 * em OLHANDO. Com qualquer outra (olhos fechados, de lado, sem rosto), para na hora e nunca desce.
 */
fun avancarOlhar(progresso: Float, leitura: Leitura, passoMs: Long): Float =
    if (leitura == Leitura.OLHANDO) (progresso + passoMs.toFloat() / META_OLHAR_MS).coerceAtMost(1f) else progresso

fun segundosRestantes(progresso: Float): Int = ceil((1 - progresso) * META_OLHAR_MS / 1000.0).toInt()
