package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras da missão pra desligar (RegrasMissao.kt). */
class MissaoTest {

    @Test
    fun reconheceStopComSotaque() {
        for (frase in listOf("stop", "STOP!", "Stop.", "estop", "istópi", "stopi", "bus stop", "para, stop")) {
            assertTrue(frase, disseStop(frase))
        }
    }

    @Test
    fun ignoraOutrasPalavras() {
        for (frase in listOf("", "top", "bom dia", "para", "pare", "desliga")) {
            assertFalse(frase, disseStop(frase))
        }
    }

    @Test
    fun rostoDeFrenteComOlhosAbertosEstaOlhando() {
        assertEquals(Leitura.OLHANDO, classificarRosto(5f, -3f, 0.9f, 0.8f))
    }

    @Test
    fun olhoFechadoOuSemInformacaoNaoConta() {
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0f, 0f, 0.9f, 0.2f))
        assertEquals(Leitura.OLHOS_FECHADOS, classificarRosto(0f, 0f, null, 0.9f))
    }

    @Test
    fun rostoVirandoNaoConta() {
        assertEquals(Leitura.DE_LADO, classificarRosto(35f, 0f, 0.9f, 0.9f))
        assertEquals(Leitura.DE_LADO, classificarRosto(0f, -30f, 0.9f, 0.9f))
        assertEquals(Leitura.OLHANDO, classificarRosto(22f, -22f, 0.9f, 0.9f)) // até 25° ainda conta
    }

    @Test
    fun piscadaOuFalhaCurtaAindaContaComoOlhando() {
        assertTrue(olhandoComTolerancia(agoraMs = 10_000, ultimaOlhadaMs = 9_300))
        assertFalse(olhandoComTolerancia(agoraMs = 10_000, ultimaOlhadaMs = 8_000))
        assertFalse(olhandoComTolerancia(agoraMs = 10_000, ultimaOlhadaMs = 0)) // nunca olhou
    }

    @Test
    fun anelSobeOlhandoParaSemRostoEDesceDeLado() {
        var progresso = 0f
        repeat(50) { progresso = avancarOlhar(progresso, Leitura.OLHANDO, passoMs = 100) }
        assertEquals(0.5f, progresso, 0.001f)
        // A câmera perdeu o rosto: o anel espera, não cai
        repeat(30) { progresso = avancarOlhar(progresso, Leitura.SEM_ROSTO, passoMs = 100) }
        assertEquals(0.5f, progresso, 0.001f)
        // De lado ou de olhos fechados: desce na mesma velocidade em que sobe
        repeat(10) { progresso = avancarOlhar(progresso, Leitura.DE_LADO, passoMs = 100) }
        assertEquals(0.4f, progresso, 0.001f)
        assertEquals(0f, avancarOlhar(0f, Leitura.OLHOS_FECHADOS, passoMs = 100), 0f)
        assertEquals(1f, avancarOlhar(0.999f, Leitura.OLHANDO, passoMs = 100), 0f)
    }

    @Test
    fun segundosQueFaltam() {
        assertEquals(10, segundosRestantes(0f))
        assertEquals(5, segundosRestantes(0.5f))
        assertEquals(0, segundosRestantes(1f))
    }
}
