// O "cérebro" da conversa da manhã: recebe o que a pessoa disse e devolve a próxima fala da IA, só texto. A voz,
// a vez de cada um, o relógio e o fim são do AlarmeService. O motor desta versão ainda não existe: o escolhido
// é um modelo no próprio celular (Gemma), sem internet. Sem motor, o despertador com conversa e caminhada toca
// a missão de sempre. O Gemini, pela internet, fica guardado em reserva/gemini (fora do app), pronto pra voltar
// se o modelo do celular não servir.
package com.implacavel.alarme

import android.content.Context

/** Uma fala da conversa, da IA ([daIa]) ou da pessoa. */
data class Turno(val daIa: Boolean, val texto: String)

/**
 * Uma conversa com a IA: [abrir] pede a primeira fala, [responder] manda o que a pessoa disse, [retomar]
 * continua depois de uma interrupção. Cada pedido chama o `aoResponder` uma vez só, na thread principal, em
 * até [RESPOSTA_IA_MAX_MS], com a fala pronta pra voz ([limparFala]; se não for [respostaSegura], a frase
 * fixa no lugar) ou null (sem resposta). Um pedido novo toma o lugar do que ainda não respondeu; depois de
 * [cancelar] ou [desligar], resposta atrasada não chega em ninguém. [criar] e os pedidos voltam na hora: carregar
 * o modelo e gerar a fala é em outra thread (o serviço ainda corta a espera em [RESPOSTA_IA_MAX_MS]).
 */
interface MotorDaConversa {
    fun abrir(aoResponder: (String?) -> Unit)
    fun responder(fala: String, aoResponder: (String?) -> Unit)
    fun retomar(aoResponder: (String?) -> Unit)
    fun cancelar()
    fun desligar()

    companion object {
        /** Dá pra conversar nesta manhã. Ainda não: falta o motor do modelo no celular. */
        @Suppress("UNUSED_PARAMETER")
        fun disponivel(ctx: Context): Boolean = false

        /** Um motor novo, com a [instrucao] do treinador ([instrucaoDoTreinador]) e a [falaNaoNegocia]; null sem motor. */
        @Suppress("UNUSED_PARAMETER")
        fun criar(ctx: Context, instrucao: String, falaNaoNegocia: String): MotorDaConversa? = null
    }
}
