// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil

/** Tempo olhando pra câmera, de olhos abertos, pra desligar o alarme. */
const val META_OLHAR_MS = 10_000L

/** Sem olhar pra câmera por esse tempo, a música volta a tocar e a missão recomeça. */
const val DESISTENCIA_MS = 20_000L

/** O que a câmera está vendo agora. */
enum class Leitura { SEM_CAMERA, SEM_ROSTO, DE_LADO, OLHOS_FECHADOS, OLHANDO }

/** Aceita "stop" e jeitos de falar ou transcrever com sotaque ("estop", "istópi", "stopi"). */
fun disseStop(texto: String): Boolean {
    val semAcento = Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    return Regex("\\b[ei]?stop").containsMatchIn(semAcento)
}

/** Olhando = rosto de frente (até 20° de giro) e os dois olhos abertos (probabilidade acima de 60%). */
fun classificarRosto(giroLateral: Float, giroVertical: Float, olhoEsquerdo: Float?, olhoDireito: Float?): Leitura = when {
    abs(giroLateral) > 20f || abs(giroVertical) > 20f -> Leitura.DE_LADO
    (olhoEsquerdo ?: 0f) > 0.6f && (olhoDireito ?: 0f) > 0.6f -> Leitura.OLHANDO
    else -> Leitura.OLHOS_FECHADOS
}

/** Progresso de 0 a 1 do anel da câmera: sobe olhando e desce duas vezes mais rápido sem olhar. */
fun avancarOlhar(progresso: Float, olhando: Boolean, passoMs: Long): Float {
    val passo = passoMs.toFloat() / META_OLHAR_MS
    return (if (olhando) progresso + passo else progresso - 2 * passo).coerceIn(0f, 1f)
}

fun segundosRestantes(progresso: Float): Int = ceil((1 - progresso) * META_OLHAR_MS / 1000.0).toInt()
