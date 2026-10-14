// A IA da conversa no próprio celular: o Gemma 4 E2B (Google, licença Apache 2.0) rodando no processador pelo
// LiteRT-LM, sem internet. O arquivo do modelo (2,6 GB) não vai no APK: fica na pasta do app no celular
// (Android/data/com.implacavel.alarme/files/), copiado uma vez pelo cabo (adb push). Direto nela, sem subpasta: uma
// subpasta criada pelo cabo fica do usuário do adb, e o app não entra. Carregar leva uns segundos e
// uns 1,5 GB de memória, então o serviço começa a carregar assim que o alarme toca ([preparar]) e tira da memória
// no fim da conversa ([liberar]). Tudo do modelo roda numa thread só, a dele; as respostas chegam na principal.
package com.implacavel.alarme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Executors

/** O modelo: o arquivo do Hugging Face (litert-community/gemma-4-E2B-it-litert-lm), com o tamanho exato dele. */
const val ARQUIVO_DO_MODELO = "gemma-4-E2B-it.litertlm"
const val TAMANHO_DO_MODELO = 2_588_147_712L

/** Memória livre pra carregar o modelo com folga (carregado, o app fica com ~520 MB: medido no S24 FE) sem o Android matar o app. */
private const val MEMORIA_MINIMA = 1_500L * 1024 * 1024

/** Quanto de conversa (instrução, falas e respostas, em tokens) cabe: uns 8 min de conversa, com folga. */
private const val CONTEXTO_TOKENS = 4096

/** Tamanho máximo de uma resposta, em tokens: as falas pedidas têm até 45 palavras. */
private const val RESPOSTA_MAX_TOKENS = 120

/**
 * Uma conversa com o Gemma no celular ([MotorDaConversa]): a [instrucao] do treinador vira a instrução do
 * sistema, e o histórico fica no próprio modelo (não precisa mandar tudo de novo). Se a IA sugerir descansar ou
 * disser que o alarme acabou ([respostaSegura]), vem o [falaNaoNegocia] no lugar.
 */
class ModeloLocal(ctx: Context, private val instrucao: String, private val falaNaoNegocia: String) : MotorDaConversa {

    companion object {
        private val handler = Handler(Looper.getMainLooper())

        // Uma thread só pra tudo do modelo: carregar, conversar e fechar, um de cada vez e na ordem
        private val trabalho = Executors.newSingleThreadExecutor { Thread(it, "gemma").apply { isDaemon = true } }

        @Volatile private var engine: Engine? = null // carregado, pras conversas do mesmo alarme; só mexido na thread do modelo
        @Volatile private var carregando = false // o [preparar] está carregando: a memória que ele ocupa não conta contra

        /** O teste pelo cabo ([TesteDaIa]) está com o modelo dele: um alarme agora não carrega outro (não cabem os dois). */
        @Volatile var emTeste = false

        /** O arquivo do modelo na pasta do app (Android/data/com.implacavel.alarme/files/). */
        fun arquivo(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, ARQUIVO_DO_MODELO) }

        /**
         * Dá pra conversar: celular de 64 bits (a biblioteca só tem essa versão), o arquivo inteiro no lugar e
         * memória livre pra carregar ([MEMORIA_MINIMA]). Já carregado, sim.
         */
        fun disponivel(ctx: Context): Boolean {
            if (emTeste) return false
            if (engine != null || carregando) return true
            if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return false
            if (arquivo(ctx)?.length() != TAMANHO_DO_MODELO) return false
            return memoriaLivre(ctx) >= MEMORIA_MINIMA
        }

        private fun memoriaLivre(ctx: Context): Long = runCatching {
            ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem
        }.getOrDefault(0L)

        /** Começa a carregar o modelo em segundo plano (pode chamar de novo: não carrega duas vezes). */
        fun preparar(ctx: Context) {
            val app = ctx.applicationContext
            carregando = true
            trabalho.execute {
                try {
                    runCatching { carregado(app) }
                } finally {
                    carregando = false
                }
            }
        }

        /** Tira o modelo da memória, depois do que estiver em curso na thread dele. */
        fun liberar() {
            trabalho.execute {
                engine?.let {
                    Log.i(TAG, "IA do celular: modelo fora da memória")
                    runCatching { it.close() }
                }
                engine = null
            }
        }

        /** O modelo pronto, carregando agora se ainda não estava. Só na thread do modelo. Lança erro se não deu. */
        private fun carregado(ctx: Context): Engine {
            engine?.let { return it }
            val caminho = arquivo(ctx)?.path ?: error("sem a pasta do app")
            val inicio = SystemClock.elapsedRealtime()
            val novo = Engine(EngineConfig(modelPath = caminho, backend = Backend.CPU(), maxNumTokens = CONTEXTO_TOKENS, cacheDir = ctx.cacheDir.path))
            try {
                novo.initialize()
            } catch (e: Throwable) {
                runCatching { novo.close() }
                Log.w(TAG, "IA do celular: o modelo não carregou (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                throw e
            }
            Log.i(TAG, "IA do celular: modelo carregado em ${SystemClock.elapsedRealtime() - inicio} ms")
            engine = novo
            return novo
        }

        /** O texto de uma resposta do modelo: as partes de texto juntas. */
        private fun textoDe(resposta: Message): String = resposta.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    private val app = ctx.applicationContext
    @Volatile private var conversa: Conversation? = null // criada no primeiro pedido, com a instrução, na thread do modelo
    @Volatile private var escrevendo = false // o modelo está gerando uma resposta agora
    @Volatile private var pedidos = 0 // muda a cada pedido e cancelamento: resposta de um pedido velho não chega
    @Volatile private var desligado = false

    override fun abrir(aoResponder: (String?) -> Unit) = perguntar(PEDIDO_DE_ABERTURA, aoResponder)

    override fun responder(fala: String, aoResponder: (String?) -> Unit) = perguntar(fala, aoResponder)

    override fun retomar(aoResponder: (String?) -> Unit) = perguntar(if (conversa == null) PEDIDO_DE_ABERTURA else PEDIDO_DE_RETOMADA, aoResponder)

    /** O pedido em curso fica sem resposta (e o modelo para de escrever). A conversa fica. */
    override fun cancelar() {
        pedidos++
        // Só com uma resposta em curso: parado, o cancelamento poderia valer pro próximo pedido
        if (escrevendo) conversa?.let { runCatching { it.cancelProcess() } }
    }

    /** Fim desta conversa: nenhum pedido sai nem responde mais. O modelo fica carregado até o [liberar]. */
    override fun desligar() {
        if (desligado) return
        desligado = true
        cancelar()
        trabalho.execute {
            conversa?.let { runCatching { it.close() } }
            conversa = null
        }
    }

    private fun perguntar(fala: String, aoResponder: (String?) -> Unit) {
        if (desligado) return
        cancelar() // um pedido por vez
        val numero = pedidos
        val inicio = SystemClock.elapsedRealtime()
        trabalho.execute {
            if (numero != pedidos || desligado) return@execute // cancelado antes de chegar a vez dele
            val resposta = try {
                val c = conversaPronta()
                if (numero != pedidos || desligado) return@execute // cancelado enquanto carregava ou lia a instrução
                escrevendo = true
                textoDe(c.sendMessage(fala))
            } catch (e: Throwable) {
                if (numero == pedidos) Log.w(TAG, "IA do celular: sem resposta (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                null
            } finally {
                escrevendo = false
            }
            handler.post { entregar(numero, resposta, SystemClock.elapsedRealtime() - inicio, aoResponder) }
        }
    }

    /** A conversa com a instrução do treinador, criada no primeiro pedido (já lendo a instrução). Só na thread do modelo. */
    private fun conversaPronta(): Conversation = conversa ?: carregado(app).createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(instrucao),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            prefillPrefaceOnInit = true,
            maxOutputToken = RESPOSTA_MAX_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        ),
    ).also { conversa = it }

    private fun entregar(numero: Int, resposta: String?, demora: Long, aoResponder: (String?) -> Unit) {
        if (numero != pedidos || desligado) return // cancelado, ou já tem um pedido mais novo
        val limpa = resposta?.let(::limparFala)?.ifEmpty { null }
        val segura = limpa == null || respostaSegura(limpa)
        if (!segura) Log.i(TAG, "Conversa: a IA sugeriu descansar ou parar, vai a frase fixa no lugar")
        val texto = if (segura) limpa else falaNaoNegocia
        Log.i(TAG, "Conversa: ${if (texto != null) "IA respondeu (${texto.length} letras)" else "IA sem resposta"} em $demora ms")
        aoResponder(texto)
    }
}
