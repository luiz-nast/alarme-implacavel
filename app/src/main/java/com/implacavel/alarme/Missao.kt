// Telas da missão pra desligar o alarme, mostradas pela AlarmeActivity: 1) FALAR: dizer "stop" (a
// música para); entre as duas, a SAUDAÇÃO falada (Saudacao.kt); 2) OLHAR: olhar pra câmera de olhos abertos até fechar o anel, com o YouTube ao lado
// (PaginaYoutube.kt) e a câmera numa bolinha por cima dele. Logo depois de o celular
// reiniciar, antes do primeiro desbloqueio, vem antes a etapa DESBLOQUEAR. Os tempos e as regras ficam
// em RegrasMissao.kt; a vigia (bipes, zerar o anel, música de volta), no AlarmeService.
package com.implacavel.alarme

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Na etapa de falar a música alterna: alta pra acordar, baixa pra voz se destacar no microfone. */
private const val MUSICA_ALTA_MS = 5_000L
private const val MUSICA_BAIXA_MS = 4_000L
private const val VOLUME_ESCUTA = 0.25f

private const val PASSO_MS = 100L

/** De quanto em quanto tempo a etapa da câmera avisa o serviço que a pessoa está olhando. */
private const val AVISO_OLHANDO_MS = 1_000L

/** Sem quadro novo da câmera por esse tempo (câmera travada), a última leitura deixa de valer. Bem mais que o intervalo da análise (CameraOlhos). */
private const val QUADRO_VELHO_MS = 1_000L

/** Páginas da etapa da câmera: o YouTube à esquerda (o dedo desliza pra direita) e a câmera, onde começa. */
private const val PAGINA_YOUTUBE = 0
private const val PAGINA_CAMERA = 1

/**
 * O círculo da câmera com o anel, na página da câmera (deitado, menor, pra caber com os textos na
 * altura da tela), e a bolinha em que ele encolhe na página do YouTube.
 */
private val CIRCULO = 252.dp
private val BOLHA = 126.dp
private val MARGEM_BOLHA = 16.dp

/** Distância da bolinha até o pé da tela: acima da barra de baixo do YouTube. */
private val BOLHA_ACIMA_DO_FUNDO = 72.dp
private val LEGENDA = 160.dp
private val LEGENDA_ACIMA = 34.dp

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
 * Entre o STOP e a câmera, enquanto a voz do celular dá bom dia ([AlarmeService.saudando]): a câmera
 * começa uns segundos depois que ela termina. [hora] escolhe entre bom dia, boa tarde e boa noite.
 */
@Composable
fun EtapaSaudacao(hora: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${saudacao(hora)}!", color = VinhoAlarme, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("It's time for the activity check", color = VinhoAlarme, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("A câmera já vai começar", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

/** Missão cumprida: enquanto o app comemora (bipe leve, a fala de parabéns e o estouro) antes de fechar. */
@Composable
fun EtapaFim() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Congratulations!", color = VinhoAlarme, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("The activity is done", color = VinhoAlarme, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Bom retorno às suas atividades", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 16.sp)
    }
}

/**
 * Etapa 2: câmera num círculo com um anel que enche enquanto a pessoa olha de olhos abertos, até
 * somar [metaMs]. [onOlhando] recebe o tempo já somado a cada [AVISO_OLHANDO_MS] enquanto ela olha
 * e no instante em que ela para (mantém a música calada; o serviço guarda o tempo, em
 * [AlarmeService.olhado]). Sem olhar, a tela mostra a contagem pra zerar, a partir do último sinal
 * recebido pelo serviço ([AlarmeService.ultimoOlhar]); bipes e zerar são da vigia de lá.
 *
 * Deslizando pra direita, a página do [youtube]: a câmera vira uma bolinha no canto (CameraMovel) e
 * continua valendo. [pagina] é a moldura das telas do alarme (hora em cima, a etapa embaixo), que
 * rola com [rolagem].
 */
@Composable
fun EtapaOlhar(
    metaMs: Long,
    onDefeito: () -> Unit,
    youtube: YoutubeDaMissao,
    pagina: @Composable (conteudo: @Composable () -> Unit) -> Unit,
    rolagem: ScrollState,
    onOlhando: (olhadoMs: Long) -> Unit,
    onConcluiu: () -> Unit,
) {
    val ctx = LocalContext.current
    // Tirar a permissão da câmera não é saída: sem ela, o único caminho é liberar de novo
    var respostas by remember { mutableIntStateOf(0) }
    if (!lidoAoVoltar(respostas) { Poder.CAMERA.liberado(ctx) }) {
        pagina { PedirCamera(onLiberada = { respostas++ }) }
        return
    }
    var leitura by remember { mutableStateOf(Leitura.SEM_ROSTO) }
    var quandoLeu by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (leitura == Leitura.SEM_CAMERA) {
        // A câmera deu defeito (a permissão existe): sem como conferir os olhos, desliga no botão
        pagina { AvisoComBotao("A câmera não está funcionando.", "DESLIGAR", fundoClaro = true, onClick = onDefeito) }
        return
    }
    val avisarOlhando by rememberUpdatedState(onOlhando)
    val concluir by rememberUpdatedState(onConcluiu)
    // A meta pode subir no meio: alarme de verdade disparando durante o teste (EmAndamento.juntar)
    val meta by rememberUpdatedState(metaMs)
    // Continua do que o serviço guardou: voltou depois de sair da tela, ou a tela foi recriada no meio
    // (ex.: o tamanho da fonte do sistema mudou). Lá ele só zera nos 20 s sem olhar
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
    val paginas = rememberPagerState(initialPage = PAGINA_CAMERA) { 2 }
    val configuracao = LocalConfiguration.current
    val circulo = if (configuracao.screenWidthDp > configuracao.screenHeightDp) {
        (configuracao.screenHeightDp - 190).dp.coerceIn(150.dp, CIRCULO)
    } else {
        CIRCULO
    }
    val escopo = rememberCoroutineScope()
    var lugarDoCirculo by remember { mutableStateOf<Offset?>(null) }
    // O YouTube só carrega quando a pessoa começa a ir pra página dele (e de novo, se a página dele caiu)
    LaunchedEffect(Unit) {
        snapshotFlow { paginas.currentPage + paginas.currentPageOffsetFraction < PAGINA_CAMERA - 0.05f }
            .collect { if (it) youtube.abrir() }
    }
    // No YouTube, voltar volta a página dele; sem mais pra onde, volta pra câmera
    BackHandler(enabled = paginas.currentPage == PAGINA_YOUTUBE) {
        if (!youtube.voltar()) escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) }
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(paginas, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { p ->
            if (p == PAGINA_YOUTUBE) {
                PaginaYoutube(youtube)
            } else {
                pagina {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // O lugar do círculo: a câmera é desenhada por cima (CameraMovel), pra poder virar a bolinha
                        Spacer(Modifier.size(circulo).onGloballyPositioned { lugarDoCirculo = it.positionInRoot() })
                        Spacer(Modifier.height(16.dp))
                        Text(mensagem, color = cor, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("Mais ${tempoRestante(metaMs - olhadoMs)} de olhos abertos", color = VinhoAlarme, fontSize = 16.sp)
                        // Linha sempre presente (vazia enquanto olha), pra tela não pular quando a contagem aparece
                        Text(zeraEm?.let { "$it s pra zerar" } ?: "", color = VermelhoAviso, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("👉 Deslize pra direita: YouTube", color = VinhoAlarme.copy(alpha = 0.7f), fontSize = 15.sp)
                    }
                }
            }
        }
        CameraMovel(
            paginas,
            rolagem,
            circulo,
            lugarDoCirculo = { lugarDoCirculo },
            progresso = olhadoMs.toFloat() / metaMs,
            cor = cor,
            legenda = zeraEm?.let { "$it s pra zerar" } ?: "faltam ${tempoRestante(metaMs - olhadoMs)}",
            alerta = zeraEm != null,
            onLeitura = {
                leitura = it
                quandoLeu = SystemClock.elapsedRealtime()
            },
        )
    }
}

/**
 * A câmera com o anel, desenhada por cima das páginas. Na página da câmera fica no [lugarDoCirculo];
 * indo pro YouTube, segue o dedo e encolhe até a bolinha no canto de baixo à direita, como a da
 * chamada de vídeo do WhatsApp. É sempre a mesma câmera: trocar de página não para a detecção.
 * Na bolinha, dá pra arrastá-la, e um toque volta pra câmera. O círculo grande cobre o meio da tela,
 * onde o dedo costuma deslizar: arrastar nele pro lado troca de página, e pra cima ou pra baixo rola.
 */
@Composable
private fun CameraMovel(
    paginas: PagerState,
    rolagem: ScrollState,
    circulo: Dp,
    lugarDoCirculo: () -> Offset?,
    progresso: Float,
    cor: Color,
    legenda: String,
    alerta: Boolean,
    onLeitura: (Leitura) -> Unit,
) {
    val escopo = rememberCoroutineScope()
    val densidade = LocalDensity.current
    val direcao = LocalLayoutDirection.current
    val barras = WindowInsets.safeDrawing
    var tela by remember { mutableStateOf(IntSize.Zero) }
    var origem by remember { mutableStateOf(Offset.Zero) }
    var arrasto by remember { mutableStateOf(Offset.Zero) } // quanto a pessoa moveu a bolinha do canto

    // Tudo aqui é lido só na hora de posicionar e desenhar: deslizar não recompõe a tela a cada quadro
    // 0 = página da câmera, 1 = página do YouTube
    fun naBolha() = (PAGINA_CAMERA - paginas.currentPage - paginas.currentPageOffsetFraction).coerceIn(0f, 1f)
    val escalaBolha = BOLHA / circulo
    fun escala() = 1f - (1f - escalaBolha) * naBolha()
    fun lado() = with(densidade) { BOLHA.toPx() }
    // Dentro da tela, fora das barras do sistema, com lugar pra legenda em cima. Antes de a tela ser
    // medida (tamanho zero), fica no canto de cima
    fun limitar(canto: Offset) = with(densidade) {
        val margem = MARGEM_BOLHA.toPx()
        val minimo = Offset(barras.getLeft(this, direcao) + margem, barras.getTop(this) + margem + LEGENDA_ACIMA.toPx())
        val maximo = Offset(
            tela.width - barras.getRight(this, direcao) - lado() - margem,
            tela.height - barras.getBottom(this) - lado() - margem,
        )
        Offset(canto.x.coerceIn(minimo.x, maxOf(minimo.x, maximo.x)), canto.y.coerceIn(minimo.y, maxOf(minimo.y, maximo.y)))
    }
    fun cantoPadrao() = with(densidade) {
        Offset(
            tela.width - barras.getRight(this, direcao) - MARGEM_BOLHA.toPx() - lado(),
            tela.height - barras.getBottom(this) - BOLHA_ACIMA_DO_FUNDO.toPx() - lado(),
        )
    }
    fun cantoDaBolha() = limitar(cantoPadrao() + arrasto)
    fun assentar() = escopo.launch { paginas.animateScrollToPage(if (naBolha() > 0.25f) PAGINA_YOUTUBE else PAGINA_CAMERA) }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            tela = it.size
            origem = it.positionInRoot()
        },
    ) {
        Box(
            Modifier
                .offset { lerp((lugarDoCirculo() ?: origem) - origem, cantoDaBolha(), naBolha()).round() }
                .size(circulo)
                .graphicsLayer {
                    scaleX = escala()
                    scaleY = escala()
                    transformOrigin = TransformOrigin(0f, 0f)
                    alpha = if (lugarDoCirculo() == null) 0f else 1f // até a página da câmera dizer onde é o círculo
                    // Só o círculo pega o toque: os cantos do quadrado são do YouTube
                    shape = CircleShape
                    clip = true
                }
                .pointerInput(Unit) {
                    detectTapGestures { if (naBolha() > 0.99f) escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) } }
                }
                .pointerInput(Unit) {
                    var moverBolha = false
                    var deLado: Boolean? = null // no círculo grande: o primeiro movimento decide se troca de página ou rola
                    var percorrido = 0f
                    fun soltar() {
                        when {
                            !moverBolha -> assentar()
                            // Na bolinha encolhida o movimento conta em dobro: um toque tremido vira arrasto
                            percorrido < viewConfiguration.touchSlop -> escopo.launch { paginas.animateScrollToPage(PAGINA_CAMERA) }
                        }
                    }
                    detectDragGestures(
                        onDragStart = {
                            moverBolha = naBolha() > 0.99f
                            deLado = null
                            percorrido = 0f
                        },
                        onDragEnd = ::soltar,
                        onDragCancel = ::soltar,
                    ) { mudanca, delta ->
                        mudanca.consume()
                        val naTela = delta * escala() // o arrasto vem no tamanho do círculo, que encolhe
                        percorrido += naTela.getDistance()
                        if (moverBolha) {
                            arrasto = limitar(cantoPadrao() + arrasto + naTela) - cantoPadrao()
                        } else {
                            val lado = deLado ?: (abs(naTela.x) >= abs(naTela.y)).also { deLado = it }
                            if (lado) paginas.dispatchRawDelta(-naTela.x) else rolagem.dispatchRawDelta(-naTela.y)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            CameraOlhos(Modifier.size(circulo - 32.dp).clip(CircleShape), onLeitura)
            AnelProgresso(progresso, cor, Modifier.fillMaxSize())
        }
        // Em cima da bolinha, o tempo que falta ou, em vermelho, a contagem pra zerar (só na página do YouTube)
        Box(
            Modifier
                .offset {
                    val canto = cantoDaBolha()
                    IntOffset((canto.x + lado() / 2).toInt() - LEGENDA.roundToPx() / 2, (canto.y - LEGENDA_ACIMA.toPx()).toInt())
                }
                .width(LEGENDA)
                .graphicsLayer { alpha = naBolha() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                legenda,
                Modifier
                    .background(if (alerta) VermelhoAviso else Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
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

/** O retorno visual de cada leitura da câmera: cor (verde ou vermelho) e mensagem. */
private fun retorno(leitura: Leitura): Pair<Color, String> = when (leitura) {
    Leitura.OLHANDO -> Color(0xFF2E7D32) to "Isso! Continua olhando"
    Leitura.OLHOS_FECHADOS -> VermelhoAviso to "Abre esses olhos!"
    Leitura.SEM_ROSTO, Leitura.SEM_CAMERA -> VermelhoAviso to "Cadê você? Aproxima o rosto"
}
