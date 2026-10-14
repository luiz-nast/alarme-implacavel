// Teste da IA do celular sem som e sem tela: carrega o modelo, conversa três falas de mentira e escreve no log o
// tempo de carregar, o tempo até a primeira palavra, a velocidade, a memória e o calor. Só dá pra chamar pelo cabo
// (o adb tem a permissão DUMP, que nenhum app comum tem):
//   adb shell am broadcast -n com.implacavel.alarme/.TesteDaIa
//   adb logcat -s Implacavel
// Com um alarme tocando, não roda (o modelo do alarme e o do teste não cabem juntos na memória).
package com.implacavel.alarme

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.time.LocalTime
import kotlin.concurrent.thread

/** O teste para antes disso: o Android dá uns 60 s pra um aviso de fundo terminar. */
private const val TESTE_MAX_MS = 50_000L

private val FALAS_DE_TESTE = listOf(
    PEDIDO_DE_ABERTURA,
    "I slept badly, but I want to run today.",
    "Can I sleep five more minutes?",
)

class TesteDaIa : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Ajustes.emAndamento != null) {
            Log.w(TAG, "IA teste: tem alarme tocando, o teste não roda")
            return
        }
        val arquivo = ModeloLocal.arquivo(ctx)
        if (arquivo?.length() != TAMANHO_DO_MODELO) {
            Log.w(TAG, "IA teste: modelo não está no celular (${arquivo?.path}, ${arquivo?.length()} bytes)")
            return
        }
        val fim = goAsync()
        thread(name = "teste-da-ia") {
            try {
                testar(ctx.applicationContext, arquivo.path)
            } catch (e: Throwable) {
                Log.w(TAG, "IA teste: falhou (${e.javaClass.simpleName}: ${e.message?.take(160)})")
            } finally {
                fim.finish()
            }
        }
    }

    @OptIn(ExperimentalApi::class) // a medição de velocidade (ExperimentalFlags.enableBenchmark)
    private fun testar(ctx: Context, caminho: String) {
        val inicio = SystemClock.elapsedRealtime()
        Log.i(TAG, "IA teste: começando (memória livre ${memoriaLivreMb(ctx)} MB, calor ${calor(ctx)})")
        ExperimentalFlags.enableBenchmark = true
        Engine(EngineConfig(modelPath = caminho, backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = ctx.cacheDir.path)).use { engine ->
            engine.initialize()
            Log.i(TAG, "IA teste: modelo carregado em ${SystemClock.elapsedRealtime() - inicio} ms (memória do app ${memoriaDoAppMb(ctx)} MB)")
            val manchetes = listOf("Fim da escala 6x1: Senado faz última sessão - O GLOBO", "Petrobras e Vale sobem; dólar cai - g1", "Copa 2026: seleção treina hoje - ge")
            val config = ConversationConfig(
                systemInstruction = Contents.of(instrucaoDoTreinador(manchetes, "", LocalTime.now())),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                prefillPrefaceOnInit = true,
                maxOutputToken = 120,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            engine.createConversation(config).use { conversa ->
                for (fala in FALAS_DE_TESTE) {
                    if (SystemClock.elapsedRealtime() - inicio > TESTE_MAX_MS) {
                        Log.w(TAG, "IA teste: passou de ${TESTE_MAX_MS / 1000} s, parando")
                        return
                    }
                    val antes = SystemClock.elapsedRealtime()
                    val resposta = conversa.sendMessage(fala).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    val b = conversa.getBenchmarkInfo()
                    Log.i(
                        TAG,
                        "IA teste: resposta em ${SystemClock.elapsedRealtime() - antes} ms (1ª palavra ${"%.1f".format(b.timeToFirstTokenInSecond)} s, " +
                            "leu ${b.lastPrefillTokenCount} a ${b.lastPrefillTokensPerSecond.toInt()}/s, escreveu ${b.lastDecodeTokenCount} a ${"%.1f".format(b.lastDecodeTokensPerSecond)}/s, " +
                            "segura=${respostaSegura(limparFala(resposta))})",
                    )
                    Log.i(TAG, "IA teste: \"${limparFala(resposta).take(300)}\"")
                }
            }
        }
        Log.i(TAG, "IA teste: fim em ${SystemClock.elapsedRealtime() - inicio} ms (calor ${calor(ctx)})")
    }

    private fun memoriaLivreMb(ctx: Context): Long =
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem / (1024 * 1024)

    private fun memoriaDoAppMb(ctx: Context): Int =
        ctx.getSystemService(ActivityManager::class.java).getProcessMemoryInfo(intArrayOf(Process.myPid())).first().totalPss / 1024

    /** O estado térmico do Android (0 = frio ... 6 = desligando), do Android 10 em diante. */
    private fun calor(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 29) "${ctx.getSystemService(PowerManager::class.java).currentThermalStatus}" else "?"
}
