package com.implacavel.alarme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Textos de hora e dia (Formatacao.kt). */
class FormatacaoTest {
    private val zona = ZoneId.of("America/Sao_Paulo")

    private fun em(mes: Int, dia: Int, hora: Int, minuto: Int) = ZonedDateTime.of(2026, mes, dia, hora, minuto, 0, 0, zona)

    @Test
    fun horaComDoisDigitos() {
        assertEquals("07:05", hhmm(7, 5))
    }

    @Test
    fun resumoDosDias() {
        assertEquals("Uma vez", resumoDias(emptySet()))
        assertEquals("Todo dia", resumoDias((1..7).toSet()))
        assertEquals("Dias úteis", resumoDias(setOf(1, 2, 3, 4, 5)))
        assertEquals("Fim de semana", resumoDias(setOf(6, 7)))
        assertEquals("dom, qua", resumoDias(setOf(3, 7)))
    }

    @Test
    fun tempoAteOAlarme() {
        assertEquals("em 9 h 12 min", tempoAte(em(9, 29, 21, 48), em(9, 30, 7, 0)))
        assertEquals("em 1 min", tempoAte(em(9, 29, 21, 48).plusSeconds(10), em(9, 29, 21, 49)))
        assertEquals("em 2 d 1 h", tempoAte(em(9, 29, 6, 0), em(10, 1, 7, 0)))
    }
}
