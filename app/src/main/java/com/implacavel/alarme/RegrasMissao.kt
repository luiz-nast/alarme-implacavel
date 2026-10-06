// Regras da missão pra desligar o alarme (dizer STOP e olhar pra câmera). Funções puras, testadas em MissaoTest.
package com.implacavel.alarme

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/** Tempo olhando pra câmera, de olhos abertos, pra desligar o alarme. */
const val META_OLHAR_MS = 20 * 60_000L

/** No botão "Testar agora" a câmera pede só isto: teste tem que ser rápido. */
const val META_OLHAR_TESTE_MS = 30_000L

/**
 * Sem olhar pra câmera por esse tempo, aparece na tela quanto falta pra zerar e toca um bipe; depois,
 * a cada mais esse tempo, um bipe mais alto.
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
fun metaOlhar(alarme: Alarme): Long = if (alarme.id == Alarme.ID_TESTE) META_OLHAR_TESTE_MS else META_OLHAR_MS

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

/** O primeiro bipe toca esse tanto abaixo do volume de alarme. */
private const val DB_PRIMEIRO_BIPE = -12f

/**
 * Volume do bipe número [n] (1 = o primeiro), de 0 a 1 do volume de alarme: do primeiro, a
 * [DB_PRIMEIRO_BIPE], ao último, no máximo, subindo por igual em decibéis (como o ouvido sente).
 */
fun volumeDoBipe(n: Int): Float {
    val db = DB_PRIMEIRO_BIPE * (BIPES_ATE_ZERAR - n.coerceIn(1, BIPES_ATE_ZERAR)) / (BIPES_ATE_ZERAR - 1)
    return 10f.pow(db / 20)
}
