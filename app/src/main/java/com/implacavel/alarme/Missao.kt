// Telas da missão pra desligar o alarme, mostradas pela AlarmeActivity: 1) FALAR: dizer "stop" (a
// música para); 2) OLHAR: olhar pra câmera de olhos abertos até fechar o anel. Logo depois de o celular
// reiniciar, antes do primeiro desbloqueio, vem antes a etapa DESBLOQUEAR. Os tempos e as regras ficam
// em RegrasMissao.kt; a vigia (bipes, zerar o anel, música de volta), no AlarmeService.
package com.implacavel.alarme

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Na etapa de falar a música alterna: alta pra acordar, baixa pra voz se destacar no microfone. */
private const val MUSICA_ALTA_MS = 5_000L
private const val MUSICA_BAIXA_MS = 4_000L
private const val VOLUME_ESCUTA = 0.25f

private const val PASSO_MS = 100L

/** De quanto em quanto tempo a etapa da câmera avisa o serviço que a pessoa está olhando. */
private const val AVISO_OLHANDO_MS = 1_000L

/** Sem quadro novo da câmera por esse tempo (câmera travada), a última leitura deixa de valer. */
private const val QUADRO_VELHO_MS = 1_000L

/**
 * Etapa 0, só logo depois de o celular reiniciar: antes do primeiro desbloqueio o reconhecimento de
 * voz do Google não roda (o serviço dele cai), então a missão espera o desbloqueio. O botão abre o
 * teclado do PIN por cima do alarme, com um tempo sem a tela do alarme voltar; a música continua.
 */
@Composable
fun EtapaDesbloquear() {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    AvisoComBotao("Desbloqueie o celular\npra fazer a missão", "DESBLOQUEAR") {
        AlarmeService.darTempo(ctx)
        desbloquear(activity) {}
    }
}

/**
 * Etapa 1: ouvir "stop". Sem reconhecimento de voz disponível, aparece um botão no lugar; ele só
 * cala a música e passa pra etapa da câmera, que continua obrigatória. Se a pessoa já tinha olhado
 * um tanto (e saiu da tela), mostra quanto falta dos [metaMs]: o anel continua de onde parou.
 */
@Composable
fun EtapaFalar(metaMs: Long, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val aoDizerStop by rememberUpdatedState(onStop)
    val olhado by AlarmeService.olhado.collectAsStateWithLifecycle()
    var ouvido by remember { mutableStateOf("") }
    var semVoz by remember { mutableStateOf(false) }
    var janelaDeEscuta by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            while (true) {
                janelaDeEscuta = false
                AlarmeService.volume(ctx, 1f)
                delay(MUSICA_ALTA_MS)
                janelaDeEscuta = true
                AlarmeService.volume(ctx, VOLUME_ESCUTA)
                delay(MUSICA_BAIXA_MS)
            }
        } finally {
            // Saiu da etapa ou fechou a tela: a música não pode ficar presa no volume baixo
            AlarmeService.volume(ctx, 1f)
        }
    }
    DisposableEffect(Unit) {
        val ouvinte = OuvinteStop(ctx, onOuviu = { ouvido = it }, onStop = { aoDizerStop() }, onIndisponivel = { semVoz = true })
        ouvinte.comecar()
        onDispose { ouvinte.parar() }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (janelaDeEscuta) "🎤 Fala agora!" else "Pra desligar, diga", color = Color.White, fontSize = 20.sp)
        Text("STOP", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Black)
        Text(
            if (ouvido.isBlank()) "" else "Ouvi: \"$ouvido\"",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (olhado > 0) {
            Spacer(Modifier.height(8.dp))
            Text("Anel guardado: faltam ${tempoRestante(metaMs - olhado)}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (semVoz) {
            Spacer(Modifier.height(12.dp))
            AvisoComBotao("Reconhecimento de voz indisponível", "PARAR A MÚSICA", onClick = onStop)
        }
    }
}

/**
 * Etapa 2: câmera num círculo com um anel que enche enquanto a pessoa olha de olhos abertos, até
 * somar [metaMs]. [onOlhando] recebe o tempo já somado a cada [AVISO_OLHANDO_MS] enquanto ela olha
 * e no instante em que ela para (mantém a música calada; o serviço guarda o tempo, em
 * [AlarmeService.olhado]). Sem olhar, a tela mostra a contagem pra zerar, a partir do último sinal
 * recebido pelo serviço ([AlarmeService.ultimoOlhar]); bipes e zerar são da vigia de lá.
 */
@Composable
fun EtapaOlhar(metaMs: Long, onOlhando: (olhadoMs: Long) -> Unit, onConcluiu: () -> Unit) {
    val ctx = LocalContext.current
    // Tirar a permissão da câmera não é saída: sem ela, o único caminho é liberar de novo
    var respostas by remember { mutableIntStateOf(0) }
    if (!lidoAoVoltar(respostas) { Poder.CAMERA.liberado(ctx) }) {
        PedirCamera(onLiberada = { respostas++ })
        return
    }
    var leitura by remember { mutableStateOf(Leitura.SEM_ROSTO) }
    var quandoLeu by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (leitura == Leitura.SEM_CAMERA) {
        // A câmera deu defeito (a permissão existe): sem como conferir os olhos, desliga no botão
        AvisoComBotao("A câmera não está funcionando.", "DESLIGAR", fundoClaro = true, onClick = onConcluiu)
        return
    }
    val avisarOlhando by rememberUpdatedState(onOlhando)
    val concluir by rememberUpdatedState(onConcluiu)
    // A meta pode subir no meio: alarme de verdade disparando durante o teste (EmAndamento.juntar)
    val meta by rememberUpdatedState(metaMs)
    // Continua do que o serviço guardou: voltou depois de sair da tela, ou a tela foi recriada no meio
    // (ex.: o modo escuro do sistema mudou). Lá ele só zera nos 20 s sem olhar
    var olhadoMs by remember { mutableLongStateOf(AlarmeService.olhado.value) }
    var zeraEm by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        var antes = SystemClock.elapsedRealtime()
        var ultimoAviso = 0L
        var olhavaAntes = false
        while (true) {
            delay(PASSO_MS)
            val agora = SystemClock.elapsedRealtime()
            // Câmera travada vale como sem rosto: imagem velha não enche o anel
            if (agora - quandoLeu > QUADRO_VELHO_MS) leitura = Leitura.SEM_ROSTO
            // Vale o quadro atual: tirou o rosto ou fechou os olhos, o anel para na hora
            val olhando = leitura == Leitura.OLHANDO
            if (olhando != olhavaAntes) {
                Log.i(TAG, "Câmera: ${if (olhando) "olhando" else "parou de olhar ($leitura)"}, anel ${olhadoMs * 100 / meta}%")
            }
            olhadoMs = avancarOlhar(olhadoMs, olhando, passoMs = agora - antes)
            antes = agora
            // A cada segundo olhando, e no instante em que parou: a contagem pra zerar começa daí
            if (olhando && agora - ultimoAviso >= AVISO_OLHANDO_MS || olhavaAntes && !olhando) {
                avisarOlhando(olhadoMs)
                ultimoAviso = agora
            }
            olhavaAntes = olhando
            zeraEm = if (olhando) null else segundosParaZerar(agora - AlarmeService.ultimoOlhar.value)
            if (olhadoMs >= meta) {
                Log.i(TAG, "Missão: anel completo, alarme desligado")
                concluir()
                break
            }
        }
    }

    val (corAlvo, mensagem) = retorno(leitura)
    val cor by animateColorAsState(corAlvo, label = "cor da leitura")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CameraOlhos(Modifier.size(220.dp).clip(CircleShape)) {
                leitura = it
                quandoLeu = SystemClock.elapsedRealtime()
            }
            AnelProgresso(olhadoMs.toFloat() / metaMs, cor, Modifier.size(252.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(mensagem, color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Mais ${tempoRestante(metaMs - olhadoMs)} de olhos abertos", color = VinhoAlarme, fontSize = 16.sp)
        // Linha sempre presente (vazia enquanto olha), pra tela não pular quando a contagem aparece
        Text(zeraEm?.let { "$it s pra zerar" } ?: "", color = VermelhoAviso, fontSize = 28.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * Câmera sem permissão: desbloqueia o celular, se preciso, e pede no diálogo do sistema. Se o
 * Android não mostrar mais o diálogo (negado de vez), abre as Configurações, com um tempo sem a tela
 * do alarme voltar por cima (a música continua). A etapa da câmera relê a permissão quando a tela volta
 * e em [onLiberada]: com a permissão já dada (ex.: nas Configurações em tela dividida), não há diálogo nem volta.
 */
@Composable
private fun PedirCamera(onLiberada: () -> Unit) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            onLiberada()
        } else {
            AlarmeService.darTempo(ctx)
            Poder.CAMERA.abrirConfiguracao(ctx)
        }
    }
    AvisoComBotao("A câmera está sem permissão.\nLibere pra desligar o alarme.", "LIBERAR CÂMERA", fundoClaro = true) {
        desbloquear(activity) { pedir.launch(Manifest.permission.CAMERA) }
    }
}

/** Aviso curto com um botão grande embaixo: os pedidos e as saídas das etapas da missão. */
@Composable
private fun AvisoComBotao(aviso: String, botao: String, fundoClaro: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            aviso,
            color = if (fundoClaro) VinhoAlarme else Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        BotaoGrande(botao, onClick, fundoClaro)
    }
}

/** Com o celular bloqueado, pede o desbloqueio (PIN, digital) antes de [depois]; a tela do alarme continua por cima. */
private fun desbloquear(activity: Activity?, depois: () -> Unit) {
    val bloqueio = activity?.getSystemService(KeyguardManager::class.java)
    if (activity == null || bloqueio?.isKeyguardLocked != true) return depois()
    bloqueio.requestDismissKeyguard(
        activity,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = depois()
        },
    )
}

/** Trilha fraca do anel inteiro + arco forte com o progresso, começando no topo. */
@Composable
private fun AnelProgresso(progresso: Float, cor: Color, modifier: Modifier) {
    Canvas(modifier) {
        val espessura = 12.dp.toPx()
        val canto = Offset(espessura / 2, espessura / 2)
        val area = Size(size.width - espessura, size.height - espessura)
        drawArc(cor.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = canto, size = area, style = Stroke(espessura))
        drawArc(
            cor, -90f, 360f * progresso, useCenter = false, topLeft = canto, size = area,
            style = Stroke(espessura, cap = StrokeCap.Round),
        )
    }
}

private val VermelhoAviso = Color(0xFFC62828)

/** O retorno visual de cada leitura da câmera: cor (verde, âmbar ou vermelho) e mensagem. */
private fun retorno(leitura: Leitura): Pair<Color, String> = when (leitura) {
    Leitura.OLHANDO -> Color(0xFF2E7D32) to "Isso! Continua olhando"
    Leitura.DE_LADO -> Color(0xFFEF8F00) to "Olha direto pra câmera"
    Leitura.OLHOS_FECHADOS -> VermelhoAviso to "Abre esses olhos!"
    Leitura.SEM_ROSTO, Leitura.SEM_CAMERA -> VermelhoAviso to "Cadê você? Aproxima o rosto"
}
