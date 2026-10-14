package com.implacavel.alarme

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Serviço em primeiro plano que toca o alarme em andamento ([Ajustes.emAndamento], gravado pelo
 * [AlarmeReceiver]) até a missão ser cumprida, ou até o DESLIGAR, em alarme sem missão: esse é o
 * único fim do alarme ([concluir]). Controla a [Sirene] e a notificação que abre a
 * [AlarmeActivity] em tela cheia, e não deixa ninguém fugir:
 * - tela do alarme fechada (Home, arrastar o app, apagar a tela): em [TELA_FECHADA_MS] a música
 *   volta e a tela reabre; o anel da câmera fica onde estava;
 * - depois do "stop", a voz do celular dá bom dia, [PAUSA_DEPOIS_DA_SAUDACAO_MS] depois diz "Activity
 *   starting now", toca o bipe positivo e começa a câmera; a cada 10 min completos, a voz avisa;
 * - na metade, o anel para e vem a verificação: a voz fala 5 frases ([frasesDoDia]) e a pessoa repete
 *   cada uma; sem responder por [SILENCIO_APITO_MS], um apito alto, e em [SILENCIO_ZERA_MS] o anel zera e a
 *   música volta. Depois dela, a câmera fica mais exigente com os olhos;
 *   daí a música só fica calada enquanto a câmera avisa ("olhando") que a pessoa
 *   olha; sem aviso, a vigia bipa a cada [AVISO_SEM_OLHAR_MS], cada vez mais alto (o último, no
 *   volume máximo do celular), e em [DESISTENCIA_MS] religa a música (o anel zera);
 * - a cada [RENOVAR_MS], empurra pra frente a retomada no AlarmManager: se o app cair, travar ou
 *   for encerrado, ou se o celular desligar, o alarme volta sozinho;
 * - se o app caiu [LIMITE_QUEDAS] vezes neste alarme, é defeito, não truque: ele volta com o
 *   DESLIGAR, pra um defeito não prender ninguém num alarme que nunca desliga. Numa queda só,
 *   o desafio continua valendo;
 * - no despertador com conversa e caminhada ([Alarme.caminhada]), depois do bom dia vêm a conversa com a
 *   IA ([MotorDaConversa], no celular) e a caminhada pelo GPS (com chuva, ou sem internet pra saber, a câmera no
 *   lugar; sem a IA, a missão de sempre).
 *   Quem decide tudo são os relógios daqui: a IA só produz o texto das falas.
 *
 * Comandos pela action do Intent, cada um com uma função no companion: tocar, missão cumprida,
 * desligar, tela, silenciar, olhando, volume, dar tempo, ouviu, repetiu, sem voz, respondeu, andando
 * e sem GPS. O comando reexibir vem da notificação arrastada pro lado.
 */
/** Um passo da verificação do meio: a [frase] da vez ([numero] de 5; vazia na fala de abertura e no agradecimento) e se é a [suaVez] de repetir. */
data class Verificacao(val frase: String, val numero: Int, val suaVez: Boolean)

/**
 * A conversa da manhã com a IA: a [falaDaIa] mostrada, se é a [suaVez] de a pessoa falar ([vez] conta as vezes,
 * pra tela recomeçar a escuta a cada uma), se o app está [pensando] (esperando a IA ou a voz gravar), o tempo
 * de conversa pura ([conversadoMs]) e a meta, e se a pergunta agora é se ela quer continuar ([perguntandoSeContinua]).
 */
data class Conversa(
    val falaDaIa: String,
    val suaVez: Boolean,
    val pensando: Boolean,
    val conversadoMs: Long,
    val metaMs: Long,
    val perguntandoSeContinua: Boolean = false,
    val vez: Int = 0,
)

/** A caminhada da manhã: o tempo já [andadoMs] (guardado pelo serviço), a meta e o fim do prazo pra sair de casa ([prazoAte], SystemClock.elapsedRealtime). */
data class Caminhada(val andadoMs: Long, val metaMs: Long, val prazoAte: Long)

/** As etapas do despertador com conversa e caminhada, depois do STOP e do bom dia. */
private enum class EtapaDaManha { CONVERSA, CAMINHADA, CAMERA }

/** Um pedido pra IA: chama o motor com o [aoResponder] (que recebe a fala ou null). Guardado pra pedir de novo numa falha. */
private typealias PedidoDaIa = (aoResponder: (String?) -> Unit) -> Unit

class AlarmeService : Service() {

    companion object {
        private const val ACAO_TOCAR = "tocar"
        private const val ACAO_CONCLUIR = "concluir"
        private const val ACAO_DESLIGAR = "desligar"
        private const val ACAO_REEXIBIR = "reexibir"
        private const val ACAO_TELA = "tela"
        private const val ACAO_SILENCIAR = "silenciar"
        private const val ACAO_OLHANDO = "olhando"
        private const val ACAO_VOLUME = "volume"
        private const val ACAO_DAR_TEMPO = "dar_tempo"
        private const val ACAO_OUVIU = "ouviu"
        private const val ACAO_REPETIU = "repetiu"
        private const val ACAO_SEM_VOZ = "sem_voz"
        private const val ACAO_RESPONDEU = "respondeu"
        private const val ACAO_ANDANDO = "andando"
        private const val ACAO_SEM_GPS = "sem_gps"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_ABERTA = "aberta"
        private const val EXTRA_OLHADO = "olhado"
        private const val EXTRA_COMEMORAR = "comemorar"
        private const val EXTRA_TEXTO = "texto"
        private const val EXTRA_ANDADO = "andado"

        /**
         * Tela do alarme fechada no meio: tempo até a música voltar e a tela reabrir. O Android ainda leva
         * de 0,5 a 3 s pra mostrar a tela cheia; com 1 s aqui, o total fica abaixo de 5 s.
         */
        private const val TELA_FECHADA_MS = 1_000L

        /** De quanto em quanto tempo a retomada é empurrada pra frente ([Agendador.RETOMADA_MS]). */
        private const val RENOVAR_MS = 5_000L

        /** Tempo sem a tela do alarme voltar, pra desbloquear o celular ou liberar a câmera. A música continua. */
        private const val TEMPO_FORA_MS = 60_000L

        private const val LIMITE_QUEDAS = 2

        /** Entre a saudação falada e o "Activity starting now". */
        private const val PAUSA_DEPOIS_DA_SAUDACAO_MS = 1_500L

        /** Entre o "Let's verify..." e a primeira frase da verificação. */
        private const val PAUSA_ANTES_DAS_FRASES_MS = 1_000L

        /** Se os sons do fim travarem, o alarme termina assim mesmo depois disso. */
        private const val COMEMORACAO_MAX_MS = 20_000L

        /** Se a voz travar, a câmera começa assim mesmo depois disso. */
        private const val SAUDACAO_MAX_MS = 20_000L

        /**
         * Despertador com conversa e caminhada: depois do bom dia, o máximo de espera pela abertura da IA (as
         * notícias vêm antes dela) e pelo clima. Sem a abertura até lá, a missão de sempre.
         */
        private const val PREPARO_MAX_MS = 20_000L

        /** Na conversa, se a voz travar gravando a fala da IA, ela vai só na tela depois disso e a vez passa pra pessoa. */
        private const val VOZ_DA_IA_MAX_MS = 10_000L

        /** Falas fixas, gravadas assim que a voz fica pronta: o início e os avisos de 10, 20 e 30 min. */
        private const val FALA_DO_INICIO = "inicio"
        private const val FALA_DO_FIM = "fim"
        private const val FALA_DE_VERIFICAR = "verificar"
        private const val FALA_DA_METADE = "metade"
        private val FALAS_FIXAS = mapOf(FALA_DO_INICIO to FALA_INICIO, FALA_DO_FIM to FALA_FIM, FALA_DE_VERIFICAR to FALA_VERIFICAR, FALA_DA_METADE to FALA_METADE) +
            (1 until (META_OLHAR_MS / MARCO_MS).toInt()).associate { "minutos_${it * 10}" to falaDosMinutos(it * 10) }

        /** As falas fixas da conversa: gravadas só no despertador com conversa e caminhada (o de sempre fica igual). */
        private const val FALA_DE_CONTINUAR = "continuar"
        private const val FALA_DE_SAIR = "sair"
        private const val FALA_DE_CAMERA = "camera"
        private const val FALA_DE_NAO_NEGOCIAR = "nao_negocia"
        private val FALAS_DA_CONVERSA = mapOf(FALA_DE_CONTINUAR to FALA_CONTINUAR, FALA_DE_SAIR to FALA_SAIR, FALA_DE_CAMERA to FALA_CAMERA, FALA_DE_NAO_NEGOCIAR to FALA_NAO_NEGOCIA)

        private val _tocando = MutableStateFlow<Alarme?>(null)

        /** Alarme tocando agora (null = nenhum). A AlarmeActivity e a tela principal observam isso. */
        val tocando: StateFlow<Alarme?> = _tocando.asStateFlow()

        private val _silenciado = MutableStateFlow(false)

        /** true = a pessoa já disse "stop" e a música está calada: a tela do alarme mostra a etapa da câmera. */
        val silenciado: StateFlow<Boolean> = _silenciado.asStateFlow()

        private val _comConversa = MutableStateFlow(false)

        /** Esta manhã vai ter a conversa com a IA (até decidir o caminho, a tela do bom dia diz "Preparando a manhã…"). */
        val comConversa: StateFlow<Boolean> = _comConversa.asStateFlow()

        private val _saudando = MutableStateFlow(false)

        /** true = acabou de dizer "stop" e a voz está dando bom dia: a tela mostra a saudação, ainda sem câmera. */
        val saudando: StateFlow<Boolean> = _saudando.asStateFlow()

        private val _comemorando = MutableStateFlow(false)

        /** true = a missão foi cumprida e o app comemora (bipe leve, parabéns e o estouro) antes de fechar. */
        val comemorando: StateFlow<Boolean> = _comemorando.asStateFlow()

        private val _verificacao = MutableStateFlow<Verificacao?>(null)

        /** A verificação do meio em andamento (null = fora dela): a tela mostra a frase e, na vez da pessoa, escuta. */
        val verificacao: StateFlow<Verificacao?> = _verificacao.asStateFlow()

        private val _metadeVerificada = MutableStateFlow(false)

        /** A verificação desta rodada já foi feita: o anel passa da metade e a câmera fica mais exigente. */
        val metadeVerificada: StateFlow<Boolean> = _metadeVerificada.asStateFlow()

        private val _ultimaResposta = MutableStateFlow(0L)

        /** Quando (SystemClock.elapsedRealtime) chegou a vez ou a última fala da pessoa na verificação: a tela conta daqui o tempo pra zerar. */
        val ultimaResposta: StateFlow<Long> = _ultimaResposta.asStateFlow()

        private val _ultimoOlhar = MutableStateFlow(0L)

        /**
         * Quando (SystemClock.elapsedRealtime) chegou o último sinal de "olhando", ou o "stop". A vigia
         * conta daqui os bipes e o tempo pra zerar, e a tela do alarme mostra daqui a contagem.
         */
        val ultimoOlhar: StateFlow<Long> = _ultimoOlhar.asStateFlow()

        private val _olhado = MutableStateFlow(0L)

        /**
         * Tempo de olhos abertos já somado no anel da câmera, em ms. Só sobe com a música calada e só
         * zera nos [DESISTENCIA_MS] sem olhar (e no fim do alarme). Sair da tela traz a música de
         * volta, mas o anel fica: depois do "stop", a câmera continua daqui, e a tela recriada no meio também.
         */
        val olhado: StateFlow<Long> = _olhado.asStateFlow()

        private val _metaCamera = MutableStateFlow(META_OLHAR_MS)

        /**
         * Quanto tempo de olhos abertos a etapa da câmera pede agora: o [metaOlhar] do alarme ou, no despertador
         * com conversa e caminhada, o tempo da câmera no lugar da caminhada. Fica pronto antes de [tocando].
         */
        val metaCamera: StateFlow<Long> = _metaCamera.asStateFlow()

        private val _conversa = MutableStateFlow<Conversa?>(null)

        /** A conversa da manhã com a IA em andamento (null = fora dela): a tela mostra a fala da IA e, na vez da pessoa, escuta. */
        val conversa: StateFlow<Conversa?> = _conversa.asStateFlow()

        private val _ultimaFala = MutableStateFlow(0L)

        /** Na conversa: quando (SystemClock.elapsedRealtime) começou a vez da pessoa ou ela falou por último. O silêncio e os bipes contam daqui. */
        val ultimaFala: StateFlow<Long> = _ultimaFala.asStateFlow()

        private val _caminhada = MutableStateFlow<Caminhada?>(null)

        /** A caminhada da manhã em andamento, com o prazo pra sair de casa (null = fora dela): a tela conta o tempo andando pelo GPS. */
        val caminhada: StateFlow<Caminhada?> = _caminhada.asStateFlow()

        private val _ultimoPasso = MutableStateFlow(0L)

        /** Na caminhada: quando (SystemClock.elapsedRealtime) chegou o último sinal de "andando", ou a etapa começou. A vigia e a contagem pro bipe contam daqui. */
        val ultimoPasso: StateFlow<Long> = _ultimoPasso.asStateFlow()

        /** Toca o alarme em andamento (ou atualiza o que já toca). */
        fun tocar(ctx: Context) {
            // Só falha se o disparo veio sem alarme exato (Android 12 sem a permissão): aí o sistema
            // não deixa iniciar serviço em segundo plano e não há o que fazer
            runCatching { ContextCompat.startForegroundService(ctx, comando(ctx, ACAO_TOCAR)) }
                .onFailure { Log.e(TAG, "Serviço: o Android não deixou começar a tocar", it) }
        }

        /** O anel da câmera fechou (ou a câmera deu defeito): fim do alarme. */
        fun missaoCumprida(ctx: Context, comemorar: Boolean = true) {
            ctx.startService(comando(ctx, ACAO_CONCLUIR).putExtra(EXTRA_COMEMORAR, comemorar))
        }

        /** Botão DESLIGAR. Só vale pra alarme sem missão (ou liberado por defeito). */
        fun desligar(ctx: Context) { ctx.startService(comando(ctx, ACAO_DESLIGAR)) }

        /** A tela do alarme apareceu ou sumiu (a AlarmeActivity avisa em onStart e onStop). */
        fun tela(ctx: Context, aberta: Boolean) {
            if (_tocando.value != null) ctx.startService(comando(ctx, ACAO_TELA).putExtra(EXTRA_ABERTA, aberta))
        }

        /** A pessoa disse "stop": cala a música sem encerrar o alarme e liga a vigia. */
        fun silenciar(ctx: Context) { ctx.startService(comando(ctx, ACAO_SILENCIAR)) }

        /**
         * Sinal da etapa da câmera de que a pessoa está olhando (ou olhava até agora), com o tempo
         * [olhadoMs] já somado no anel: a vigia recomeça do zero.
         */
        fun olhando(ctx: Context, olhadoMs: Long) { ctx.startService(comando(ctx, ACAO_OLHANDO).putExtra(EXTRA_OLHADO, olhadoMs)) }

        /** Volume da música, de 0 a 1, relativo ao volume de alarme. */
        fun volume(ctx: Context, fator: Float) { ctx.startService(comando(ctx, ACAO_VOLUME).putExtra(EXTRA_VOLUME, fator)) }

        /** A pessoa vai desbloquear o celular ou liberar a câmera: por [TEMPO_FORA_MS] a tela do alarme não reabre. */
        fun darTempo(ctx: Context) { ctx.startService(comando(ctx, ACAO_DAR_TEMPO)) }

        /** Na verificação, o reconhecedor ouviu alguma fala: a pessoa está respondendo. */
        fun ouviu(ctx: Context) { ctx.startService(comando(ctx, ACAO_OUVIU)) }

        /** Na verificação, a pessoa repetiu a frase da vez. */
        fun repetiu(ctx: Context) { ctx.startService(comando(ctx, ACAO_REPETIU)) }

        /** Na verificação, o reconhecimento de voz não funciona (ex.: inglês sem internet): ela é pulada. */
        fun semVozNaVerificacao(ctx: Context) { ctx.startService(comando(ctx, ACAO_SEM_VOZ)) }

        /** Na conversa, o reconhecimento de voz não funciona: a conversa acaba antes e a manhã segue. */
        fun semVozNaConversa(ctx: Context) { ctx.startService(comando(ctx, ACAO_SEM_VOZ)) }

        /** Na conversa, a pessoa terminou uma fala ([texto], o que o reconhecedor ouviu): vai pra IA. O "ouviu" serve de sinal de fala, como na verificação. */
        fun respondeu(ctx: Context, texto: String) { ctx.startService(comando(ctx, ACAO_RESPONDEU).putExtra(EXTRA_TEXTO, texto)) }

        /**
         * Sinal da etapa da caminhada de que a pessoa está andando (ou andava até agora), com o tempo
         * [andadoMs] já somado: a contagem pro bipe de parado recomeça.
         */
        fun andando(ctx: Context, andadoMs: Long) { ctx.startService(comando(ctx, ACAO_ANDANDO).putExtra(EXTRA_ANDADO, andadoMs)) }

        /** Na caminhada, o GPS não funciona (nenhum fixo, sem permissão ou sem GPS): a câmera fica no lugar do resto. */
        fun semGps(ctx: Context) { ctx.startService(comando(ctx, ACAO_SEM_GPS)) }

        private fun comando(ctx: Context, acao: String) = Intent(ctx, AlarmeService::class.java).setAction(acao)
    }

    private lateinit var sirene: Sirene
    private var voz: Voz? = null // só depois do primeiro desbloqueio ([prepararVoz])
    private var rodadas = 0 // saudações pedidas: cada uma grava num arquivo seu
    private var esperaDesbloqueio: BroadcastReceiver? = null
    private lateinit var processador: PowerManager.WakeLock
    private var telaAberta = true
    private var foraAte = 0L // SystemClock.elapsedRealtime
    private var bipes = 0 // da vigia, desde o último sinal de "olhando"
    private var cumprido = false // depois disso, só a comemoração: nada traz a música de volta
    private val frasesDeHoje = frasesDoDia(LocalDate.now())
    private var apitos = 0 // da verificação, desde a última fala da pessoa
    private var vezComecou = 0L // SystemClock.elapsedRealtime em que começou a vez da pessoa na frase atual
    private var rodadaVerificacao = 0 // muda ao começar ou cancelar: sons atrasados de uma rodada velha não avançam a nova
    private var volumeAoFim: Int? = null // o volume de antes do alarme cumprido, devolvido ao fechar
    private val handler = Handler(Looper.getMainLooper())

    // Despertador com conversa e caminhada ([manhaNova]). O caminho, a etapa e o progresso ficam quando a música
    // volta, como o anel da câmera; só um alarme novo começa do zero ([esquecerManha])
    private var caminho: CaminhoDaManha? = null // decidido depois do primeiro bom dia
    private var etapa: EtapaDaManha? = null
    private var motor: MotorDaConversa? = null // a IA da conversa, com o histórico
    private var iaBloqueada = false // o app já caiu neste alarme: a IA do celular (que pesa) fica de fora
    private var abertura: String? = null // a primeira fala da IA (null = não veio)
    private var aberturaChegou = false
    private var chuva: Boolean? = null // null = não deu pra saber (sem internet, sem localização): sem caminhada
    private var climaChegou = false
    private var saudacaoTerminou = false // o bom dia deste STOP já acabou
    private var rodadaConversa = 0 // muda ao pausar ou acabar: notícias, clima, IA e voz atrasados não avançam a manhã
    private var conversadoMs = 0L // tempo de conversa pura
    private var extras = 0 // vezes que a pessoa quis conversar mais (só uma)
    private var falhas = 0 // vezes seguidas sem resposta da IA
    private var esperaDesde = 0L // SystemClock.elapsedRealtime em que começou a espera pela fala da IA desta vez
    private var ultimoPedido: PedidoDaIa? = null // pra pedir de novo numa falha
    private var pedidosDaIa = 0 // muda a cada pedido e quando ele responde ou vence o prazo: o segundo dos dois não vale
    private var respostaSemRetorno = "" // a fala da pessoa que veio antes do "quer continuar?"
    private var iaFalando = false // a voz da IA está tocando: o relógio da conversa anda
    private var vezDaConversa = 0L // SystemClock.elapsedRealtime em que começou a vez da pessoa
    private var ouvidoEm = 0L // a última fala ouvida na vez da pessoa
    private var bipesDaConversa = 0 // do silêncio, desde a última fala
    private var vezes = 0 // vezes da pessoa ([Conversa.vez])
    private var falasDaIa = 0 // cada fala da IA grava num arquivo seu
    private var ultimoTique = 0L
    private var andado = 0L // tempo andando na caminhada
    private var prazoAte = 0L // fim do prazo pra sair de casa (SystemClock.elapsedRealtime; 0 = ainda não começou)
    private var bipesParado = 0 // da vigia da caminhada, desde o último sinal de "andando"
    private var cameraNoLugarDaCaminhada = false // a câmera desta manhã é a da chuva ou a do GPS sem funcionar

    /**
     * Enquanto toca: empurra a retomada pra frente (se o app morrer, ela dispara e o alarme volta) e
     * mantém o processador acordado (quem acende a tela é a AlarmeActivity).
     */
    private val renovar = object : Runnable {
        override fun run() {
            Agendador.agendarRetomada(this@AlarmeService)
            processador.acquire(RENOVAR_MS * 12)
            handler.postDelayed(this, RENOVAR_MS)
        }
    }

    /**
     * Vigia da missão, com a música calada: a cada [AVISO_SEM_OLHAR_MS] sem sinal de que a pessoa
     * olha, um bipe mais alto que o anterior; em [DESISTENCIA_MS], o anel zera e a música volta.
     */
    private val vigia = object : Runnable {
        override fun run() {
            if (bipes < BIPES_ATE_ZERAR) {
                bipes++
                Log.i(TAG, "Missão: ${bipes * AVISO_SEM_OLHAR_MS / 1000} s sem olhar pra câmera, bipe $bipes")
                sirene.bipe(bipes)
                // O próximo bipe; depois do último, o resto do tempo até zerar
                handler.postDelayed(this, if (bipes < BIPES_ATE_ZERAR) AVISO_SEM_OLHAR_MS else DESISTENCIA_MS - bipes * AVISO_SEM_OLHAR_MS)
                return
            }
            Log.i(TAG, "Missão: ${DESISTENCIA_MS / 1000} s sem olhar pra câmera, anel zera (estava em ${_olhado.value / 1000} s) e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) } // alerta de novo
        }
    }

    /**
     * A tela do alarme sumiu e não voltou: música de volta e tela reaberta por uma nova notificação em
     * tela cheia. O anel da câmera fica onde estava (com 40 min, uma ligação custaria tudo).
     */
    private val chamarDeVolta = Runnable {
        val alarme = _tocando.value ?: return@Runnable
        Log.i(TAG, "Tela do alarme fechada: música volta e a tela reabre (anel guardado em ${_olhado.value / 1000} s)")
        religarMusica()
        notificacoes().notify(Notificacoes.ID_CHAMADA, criarNotificacao(alarme))
    }

    override fun onCreate() {
        super.onCreate()
        sirene = Sirene(this)
        prepararVoz()
        processador = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmeImplacavel:tocando")
            .apply { setReferenceCounted(false) }
    }

    /**
     * A voz (falas do alarme) abre só com o celular desbloqueado desde que ligou: antes disso o
     * sintetizador não abre e os arquivos não podem ser gravados. Alarme tocando antes do primeiro
     * desbloqueio: a voz abre no desbloqueio, que a missão pede antes do STOP (EtapaDesbloquear).
     */
    private fun prepararVoz() {
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            voz = Voz(this, falasFixas())
            return
        }
        esperaDesbloqueio = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                unregisterReceiver(this)
                esperaDesbloqueio = null
                if (voz == null) voz = Voz(this@AlarmeService, falasFixas())
            }
        }.also { ContextCompat.registerReceiver(this, it, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /**
     * As falas fixas mais as 5 frases de hoje da verificação; no despertador com conversa e caminhada, com a IA do
     * celular pronta, também as da conversa. Se um desses juntar depois, as falas dela não estão gravadas: vão só na tela.
     */
    private fun falasFixas() = FALAS_FIXAS +
        (if (Ajustes.emAndamento?.alarme?.let(::comConversaECaminhada) == true && MotorDaConversa.disponivel(this)) FALAS_DA_CONVERSA else emptyMap()) +
        frasesDeHoje.mapIndexed { i, frase -> "frase_$i" to frase }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao in setOf(ACAO_TOCAR, ACAO_CONCLUIR, ACAO_DESLIGAR, ACAO_SILENCIAR, ACAO_DAR_TEMPO)) Log.i(TAG, "Serviço: comando $acao")
        val alarme = _tocando.value
        when {
            acao == ACAO_TOCAR -> iniciar()
            // Comando atrasado, de quando o alarme já tinha parado: só fecha o serviço
            alarme == null -> pararServico()
            acao == ACAO_CONCLUIR -> concluir(comemorar = intent.getBooleanExtra(EXTRA_COMEMORAR, false))
            // Nem repetindo o DESLIGAR de outro alarme (a notificação sempre traz o mesmo) dá pra fugir da missão
            acao == ACAO_DESLIGAR -> if (!alarme.missao) concluir()
            // Desde o Android 14 dá pra arrastar a notificação pro lado; ela volta enquanto o alarme durar
            acao == ACAO_REEXIBIR -> mostrarNotificacao(alarme)
            acao == ACAO_TELA -> telaMudou(intent.getBooleanExtra(EXTRA_ABERTA, true))
            acao == ACAO_SILENCIAR -> silenciarMusica()
            acao == ACAO_OLHANDO -> olhou(intent.getLongExtra(EXTRA_OLHADO, 0L))
            // Com a tela fechada não há janela de escuta: a música fica no volume cheio
            acao == ACAO_VOLUME -> if (telaAberta) sirene.volume(intent.getFloatExtra(EXTRA_VOLUME, 1f))
            acao == ACAO_DAR_TEMPO -> foraAte = SystemClock.elapsedRealtime() + TEMPO_FORA_MS
            // Na conversa, toda fala na vez da pessoa conta. Na verificação, fala que não acerta a frase só segura
            // a contagem do silêncio até o prazo da frase
            acao == ACAO_OUVIU -> if (_conversa.value?.suaVez == true) {
                ouviuNaConversa()
            } else if (_verificacao.value?.suaVez == true && SystemClock.elapsedRealtime() - vezComecou < PRAZO_FRASE_MS) {
                esperarResposta()
            }
            acao == ACAO_REPETIU -> repetiuFrase()
            acao == ACAO_SEM_VOZ -> if (_conversa.value != null) {
                terminarConversa("sem reconhecimento de voz")
            } else if (_verificacao.value != null && !cumprido) {
                Log.w(TAG, "Missão: sem reconhecimento de voz, a verificação é pulada")
                handler.removeCallbacks(silencio)
                agradecer()
            }
            acao == ACAO_RESPONDEU -> respondeuNaConversa(intent.getStringExtra(EXTRA_TEXTO).orEmpty())
            acao == ACAO_ANDANDO -> andou(intent.getLongExtra(EXTRA_ANDADO, 0L))
            acao == ACAO_SEM_GPS -> semGpsNaCaminhada()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sem a missão cumprida, a retomada continua armada no AlarmManager e o alarme volta
        Log.i(TAG, "Serviço: parou")
        handler.removeCallbacksAndMessages(null)
        notificacoes().cancel(Notificacoes.ID_CHAMADA)
        // Fechado no meio da comemoração (ex.: o Android encerrou o serviço): o volume de antes volta igual
        sirene.desligar(volumeAntes = volumeAoFim.takeIf { cumprido })
        if (cumprido) Ajustes.volumeParaDevolver = null
        esperaDesbloqueio?.let { unregisterReceiver(it) }
        voz?.desligar()
        rodadaConversa++ // notícias, clima e IA atrasados não chegam em ninguém
        motor?.desligar()
        MotorDaConversa.liberar()
        _silenciado.value = false
        _saudando.value = false
        _comemorando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        _olhado.value = 0
        _conversa.value = null
        _caminhada.value = null
        _tocando.value = null
        _comConversa.value = false
        _metaCamera.value = META_OLHAR_MS
        if (processador.isHeld) processador.release()
        super.onDestroy()
    }

    private fun iniciar() {
        val em = Ajustes.emAndamento
        // Comemorando um alarme cumprido: um comando atrasado ou outro alarme que disparou agora não
        // corta a comemoração; o outro alarme toca logo depois dela ([encerrar])
        if (cumprido) {
            mostrarNotificacao(_tocando.value ?: Alarme.teste())
            return
        }
        if (em == null) {
            // Missão cumprida enquanto o comando chegava. Depois de startForegroundService o Android
            // exige mostrar a notificação, mesmo pra parar em seguida
            mostrarNotificacao(Alarme.teste())
            pararServico()
            return
        }
        val quedas = quedasDesde(em.desde)
        val alarme = if (quedas >= LIMITE_QUEDAS) em.alarme.copy(missao = false) else em.alarme
        val jaTocava = _tocando.value != null
        // Alarme novo: nada da conversa e caminhada de antes. A meta da câmera fica pronta antes de a tela abrir
        if (!jaTocava) esquecerManha()
        _metaCamera.value = metaDaCamera(alarme)
        _tocando.value = alarme
        mostrarNotificacao(alarme)
        sirene.preparar(em, comeco = !jaTocava)
        // Retomada à toa, ou outro alarme disparou no meio (a foto já juntou os dois): segue de onde está.
        // Se a meta subiu (alarme de verdade no lugar do teste), a verificação da metade vale de novo
        if (jaTocava) {
            ajustarManha(alarme)
            if (caminho == null) prepararIa(alarme, em)
            if (!cameraNoLugarDaCaminhada && _olhado.value < metaOlhar(alarme) / 2) _metadeVerificada.value = false
            return
        }
        Log.i(TAG, "Serviço: tocando \"${alarme.nome}\" (missão=${alarme.missao}, remédio=${alarme.remedio}, caminhada=${alarme.caminhada}, quedas=$quedas)")
        // O modelo começa a carregar já, com a música, pra estar pronto no fim do bom dia. Se o app já saiu sozinho
        // neste alarme (queda, travamento ou falta de memória), pode ter sido ele: a manhã fica sendo a de sempre
        iaBloqueada = saiuSozinhoDesde(em.desde) || Ajustes.iaTentadaEm == em.desde
        prepararIa(alarme, em)
        telaAberta = true
        _silenciado.value = false
        _saudando.value = false
        _verificacao.value = null
        _metadeVerificada.value = false
        _conversa.value = null
        _caminhada.value = null
        sirene.tocar()
        handler.post(renovar)
    }

    /**
     * Começa a carregar o modelo da IA (o despertador com conversa e caminhada, sem a IA bloqueada) e grava que
     * ela foi tentada neste alarme ([Ajustes.iaTentadaEm]). Já carregado ou carregando, não faz nada de novo.
     */
    private fun prepararIa(alarme: Alarme, em: EmAndamento) {
        if (!comConversaECaminhada(alarme) || iaBloqueada || !MotorDaConversa.disponivel(this)) return
        Ajustes.iaTentadaEm = em.desde
        MotorDaConversa.preparar(this)
    }

    /**
     * O app saiu sozinho desde [desde]: as quedas de [quedasDesde] ou o Android matando por falta de memória (o
     * modelo da IA usa uns 1,5 GB). Do Android 11 em diante.
     */
    private fun saiuSozinhoDesde(desde: Long): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val motivos = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)
        return getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 0)
            .any { it.timestamp > desde && it.reason in motivos }
    }

    /** Quedas do app (erro, erro nativo ou travamento) desde [desde]. O Android só conta isso do 11 em diante. */
    private fun quedasDesde(desde: Long): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        val motivos = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
        return getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(packageName, 0, 0)
            .count { it.timestamp > desde && it.reason in motivos }
    }

    private fun telaMudou(aberta: Boolean) {
        if (cumprido) return
        Log.i(TAG, "Tela do alarme: ${if (aberta) "aberta" else "fechada"}")
        telaAberta = aberta
        handler.removeCallbacks(chamarDeVolta)
        if (aberta) {
            notificacoes().cancel(Notificacoes.ID_CHAMADA)
        } else {
            sirene.volume(1f)
            // Quem foi desbloquear o celular ou liberar a câmera ganha um tempo (a música não para por isso)
            val espera = maxOf(TELA_FECHADA_MS, foraAte - SystemClock.elapsedRealtime())
            handler.postDelayed(chamarDeVolta, espera)
        }
    }

    /**
     * A pessoa disse "stop": a música para e a voz dá bom dia ([textoDaSaudacao]); [PAUSA_DEPOIS_DA_SAUDACAO_MS]
     * depois que ela termina, diz [FALA_INICIO], toca o bipe positivo e aí começa a câmera e a vigia.
     * Sem voz, só a pausa e o bipe. No despertador com conversa e caminhada, o fim do bom dia vai pra
     * [saudacaoFeita], e enquanto a voz fala as notícias, o clima e a abertura da IA chegam ([prepararManha]).
     */
    private fun silenciarMusica() {
        if (cumprido) return
        if (_silenciado.value) return // toque repetido no botão, ou o mesmo STOP chegando de novo
        sirene.calar()
        _silenciado.value = true
        _saudando.value = true
        // Manhã de sempre já começada: um alarme com conversa e caminhada que juntar depois não muda o caminho
        if (manhaNova()) prepararManha() else if (caminho == null) caminho = CaminhoDaManha.ANTIGO
        handler.postDelayed(comecarAtividade, SAUDACAO_MAX_MS)
        val falar: (File?) -> Unit = { arquivo ->
            if (_saudando.value) sirene.falar(arquivo) { handler.postDelayed(anunciarInicio, PAUSA_DEPOIS_DA_SAUDACAO_MS) }
        }
        voz?.gravar("saudacao_${++rodadas}", textoDaSaudacao(LocalTime.now()), falar) ?: falar(null)
    }

    /** "Activity starting now", o bipe positivo e a câmera. No despertador com conversa e caminhada, [saudacaoFeita] no lugar. */
    private val anunciarInicio: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        if (manhaNova()) {
            saudacaoFeita()
            return@Runnable
        }
        sirene.falar(voz?.fixa(FALA_DO_INICIO)) {
            if (_saudando.value) sirene.positivo { comecarAtividade.run() }
        }
    }

    /** Fim da saudação: a tela passa pra câmera e a vigia começa a contar. No despertador com conversa e caminhada, [saudacaoFeita] no lugar. */
    private val comecarAtividade: Runnable = Runnable {
        if (!_saudando.value) return@Runnable
        if (manhaNova()) {
            saudacaoFeita() // a voz travou: o prazo vale como o fim do bom dia
            return@Runnable
        }
        Log.i(TAG, "Missão: saudação feita, começa a câmera")
        _saudando.value = false
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        adiarVigia()
    }

    /**
     * Sinal de "olhando" da etapa da câmera: guarda o tempo já somado no anel e zera a contagem da vigia.
     * A cada 10 min completos ([marcoCompletado]), a voz avisa ("10 minutes passed").
     */
    private fun olhou(olhadoMs: Long) {
        // Sinal atrasado de uma rodada que já acabou, ou de antes da verificação (ou da conversa e da caminhada)
        if (!_silenciado.value || _saudando.value || _verificacao.value != null || _conversa.value != null || _caminhada.value != null || cumprido) return
        val antes = _olhado.value
        _olhado.value = maxOf(antes, olhadoMs)
        val meta = _tocando.value?.let { metaDaCamera(it) } ?: return
        if (!_metadeVerificada.value && _olhado.value >= meta / 2) return iniciarVerificacao()
        marcoCompletado(antes, _olhado.value, meta)?.let { minutos ->
            Log.i(TAG, "Missão: $minutos min de câmera")
            sirene.falar(voz?.fixa("minutos_$minutos"))
        }
        adiarVigia()
    }

    /** O anel zerou (sem olhar, ou sem responder na verificação): a verificação da metade vale de novo (a câmera no lugar da caminhada não tem). */
    private fun zerarAnel() {
        _olhado.value = 0
        _metadeVerificada.value = cameraNoLugarDaCaminhada
    }

    /**
     * Metade da atividade: o anel para (a tela da câmera sai), toca o bipe leve, a voz diz [FALA_VERIFICAR]
     * e, [PAUSA_ANTES_DAS_FRASES_MS] depois, começam as frases.
     */
    private fun iniciarVerificacao() {
        Log.i(TAG, "Missão: metade da atividade, verificação com ${frasesDeHoje.size} frases")
        handler.removeCallbacks(vigia)
        val rodada = ++rodadaVerificacao
        _verificacao.value = Verificacao(frase = "", numero = 0, suaVez = false)
        sirene.leve {
            sirene.falar(voz?.fixa(FALA_DE_VERIFICAR)) {
                if (rodada == rodadaVerificacao) handler.postDelayed({ if (rodada == rodadaVerificacao) falarFrase(0) }, PAUSA_ANTES_DAS_FRASES_MS)
            }
        }
    }

    /** A voz fala a frase [i] (a tela também mostra); quando termina, é a vez da pessoa repetir. */
    private fun falarFrase(i: Int) {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frasesDeHoje[i], numero = i + 1, suaVez = false)
        sirene.falar(voz?.fixa("frase_$i")) {
            if (rodada != rodadaVerificacao) return@falar
            _verificacao.value = _verificacao.value?.copy(suaVez = true)
            vezComecou = SystemClock.elapsedRealtime()
            esperarResposta()
        }
    }

    /** A vez da pessoa (ou ela acabou de falar): a contagem sem resposta recomeça do zero. */
    private fun esperarResposta() {
        _ultimaResposta.value = SystemClock.elapsedRealtime()
        apitos = 0
        handler.removeCallbacks(silencio)
        handler.postDelayed(silencio, SILENCIO_APITO_MS)
    }

    /** Sem resposta na verificação: um apito alto a cada [SILENCIO_APITO_MS] e, em [SILENCIO_ZERA_MS], zera como a vigia. */
    private val silencio = object : Runnable {
        override fun run() {
            if (apitos < (SILENCIO_ZERA_MS / SILENCIO_APITO_MS).toInt() - 1) {
                apitos++
                Log.i(TAG, "Missão: ${apitos * SILENCIO_APITO_MS / 1000} s sem responder na verificação, apito $apitos")
                sirene.apito(apitos)
                handler.postDelayed(this, SILENCIO_APITO_MS)
                return
            }
            Log.i(TAG, "Missão: ${SILENCIO_ZERA_MS / 1000} s sem responder na verificação, anel zera e a música volta")
            zerarAnel()
            religarMusica()
            _tocando.value?.let { mostrarNotificacao(it) }
        }
    }

    /** A pessoa repetiu a frase da vez: o "plim" e a próxima frase, ou o agradecimento depois da última. */
    private fun repetiuFrase() {
        val v = _verificacao.value?.takeIf { it.suaVez } ?: return
        Log.i(TAG, "Missão: frase ${v.numero} de ${frasesDeHoje.size} repetida")
        handler.removeCallbacks(silencio)
        _verificacao.value = v.copy(suaVez = false)
        val rodada = rodadaVerificacao
        sirene.acerto {
            if (rodada != rodadaVerificacao) return@acerto
            if (v.numero < frasesDeHoje.size) falarFrase(v.numero) else agradecer()
        }
    }

    /** Fim da verificação: a voz agradece ([FALA_METADE]) e a câmera volta, agora mais exigente. */
    private fun agradecer() {
        val rodada = rodadaVerificacao
        _verificacao.value = Verificacao(frase = "", numero = frasesDeHoje.size, suaVez = false)
        sirene.falar(voz?.fixa(FALA_DA_METADE)) {
            if (rodada != rodadaVerificacao) return@falar
            Log.i(TAG, "Missão: verificação feita, a segunda metade é mais exigente com os olhos")
            _verificacao.value = null
            // A meta pode ter subido no meio (alarme de verdade tomou o lugar do teste): aí a metade ainda não chegou
            _metadeVerificada.value = _olhado.value >= (_tocando.value?.let(::metaOlhar) ?: 0L) / 2
            adiarVigia()
        }
    }

    /** Sinal de "olhando" (ou o "stop"): a contagem sem olhar recomeça do zero, sem bipes. */
    private fun adiarVigia() {
        _ultimoOlhar.value = SystemClock.elapsedRealtime()
        bipes = 0
        handler.removeCallbacks(vigia)
        handler.postDelayed(vigia, AVISO_SEM_OLHAR_MS)
    }

    /** Música de volta no volume cheio; se estava calada, a missão volta pra etapa de falar. O anel não muda aqui. */
    private fun religarMusica() {
        if (cumprido) return
        handler.removeCallbacks(vigia)
        // No meio da saudação: ela para (a música cala a fala) e a câmera não começa
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        voz?.cancelar()
        // No meio da verificação: ela para; volta do começo na próxima vez que o anel chegar na metade
        rodadaVerificacao++
        handler.removeCallbacks(silencio)
        _verificacao.value = null
        // Na conversa ou na caminhada: a etapa para; o caminho e o progresso ficam pra depois do STOP
        pausarManha()
        if (_silenciado.value) {
            _silenciado.value = false
            sirene.tocar()
        } else {
            sirene.volume(1f)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Despertador com conversa e caminhada: depois do bom dia, a conversa com a IA (5 min de conversa pura) e a
    // caminhada (7 min pra sair e 18 min andando), ou a câmera no lugar dela. Tudo pelos relógios daqui: a IA
    // só produz o texto das falas e nunca encurta, pausa ou acaba nada.

    /** O [alarme] é o despertador com conversa e caminhada (só vale com missão). */
    private fun comConversaECaminhada(alarme: Alarme) = alarme.missao && alarme.caminhada

    /** O alarme tocando é o despertador com conversa e caminhada, e esta manhã não virou a missão de sempre. */
    private fun manhaNova(): Boolean = _tocando.value?.let { comConversaECaminhada(it) } == true && caminho != CaminhoDaManha.ANTIGO

    /**
     * No STOP, enquanto a voz dá bom dia: na primeira vez, busca em segundo plano as notícias e as metas (e
     * com elas a abertura da IA) e a chuva. Sem a IA ([MotorDaConversa.disponivel]), a manhã já fica sendo a missão
     * de sempre, igual à de todo dia. Com o caminho já decidido (a música voltou no meio), não busca nada.
     */
    private fun prepararManha() {
        saudacaoTerminou = false
        if (caminho != null) return
        if (iaBloqueada || !MotorDaConversa.disponivel(this)) {
            Log.i(TAG, "Conversa: ${if (iaBloqueada) "o app já caiu neste alarme" else "sem a IA no celular"}, a missão de sempre")
            MotorDaConversa.liberar()
            caminho = CaminhoDaManha.ANTIGO
            _comConversa.value = false
            return
        }
        Ajustes.emAndamento?.let { Ajustes.iaTentadaEm = it.desde }
        _comConversa.value = true
        val rodada = rodadaConversa
        abertura = null
        aberturaChegou = false
        chuva = null
        climaChegou = false
        motor?.desligar()
        motor = null
        Noticias.buscar { manchetes, metas ->
            if (rodada != rodadaConversa) return@buscar
            val ia = MotorDaConversa.criar(this, instrucaoDoTreinador(manchetes, metas, LocalTime.now()), FALA_NAO_NEGOCIA).also { motor = it }
            if (ia == null) { // o motor não abriu: sem abertura, a missão de sempre
                aberturaChegou = true
                return@buscar decidir()
            }
            ia.abrir { fala ->
                if (rodada != rodadaConversa) return@abrir
                abertura = fala
                aberturaChegou = true
                decidir()
            }
        }
        if (Clima.localizacaoPronta(this)) {
            Clima.chovendo(this) { chove ->
                if (rodada == rodadaConversa) {
                    chuva = chove
                    climaChegou = true
                    decidir()
                }
            }
        } else {
            climaChegou = true // sem localização não há caminhada: a chuva não importa (fica null)
        }
    }

    /**
     * Fim do bom dia (e da pausa depois dele). Na primeira vez, decide o caminho assim que a abertura da IA e o
     * clima chegarem (até [PREPARO_MAX_MS]; a tela segue no bom dia); depois de a música voltar, a manhã continua
     * de onde estava.
     */
    private fun saudacaoFeita() {
        if (saudacaoTerminou) return // o fim do bom dia e o prazo dele chegando os dois
        saudacaoTerminou = true
        if (caminho != null) return continuarManha()
        handler.postDelayed(esperaDaDecisao, PREPARO_MAX_MS)
        decidir()
    }

    /** A abertura da IA ou o clima não chegaram a tempo: decide com o que tem (sem a abertura, a missão de sempre; sem o clima, câmera no lugar da caminhada). */
    private val esperaDaDecisao = Runnable {
        if (caminho != null) return@Runnable
        Log.w(TAG, "Conversa: a abertura da IA ou o clima não chegaram em ${PREPARO_MAX_MS / 1000} s")
        rodadaConversa++ // o que chegar atrasado não vale mais
        aberturaChegou = true
        climaChegou = true
        decidir()
    }

    /**
     * Bom dia feito e respostas na mão: o caminho da manhã ([caminhoDaManha]). Sem a abertura da IA, a missão de
     * sempre ("Activity starting now", o bipe positivo e a câmera de 40 min); com ela, a conversa, e depois a
     * caminhada ([podeCaminhar]: localização liberada e ligada e, com certeza, sem chuva) ou a câmera.
     */
    private fun decidir() {
        if (!saudacaoTerminou || caminho != null || !aberturaChegou) return
        if (!climaChegou && abertura != null) return // sem a abertura é a missão de sempre: a chuva não muda nada
        val alarme = _tocando.value ?: return
        handler.removeCallbacks(esperaDaDecisao)
        val primeira = abertura
        val rua = podeCaminhar(Clima.localizacaoPronta(this), gpsLigado(this), chuva)
        // Um alarme sem conversa e caminhada que juntou no meio (EmAndamento.juntar) também vira a missão de sempre
        val escolhido = if (comConversaECaminhada(alarme)) caminhoDaManha(conversaPossivel = primeira != null, caminhadaPossivel = rua) else CaminhoDaManha.ANTIGO
        caminho = escolhido
        Log.i(TAG, "Conversa: caminho da manhã $escolhido (abertura da IA: ${primeira != null}, rua: $rua, chuva: $chuva)")
        if (escolhido == CaminhoDaManha.ANTIGO || primeira == null) {
            motor?.desligar()
            motor = null
            MotorDaConversa.liberar()
            _comConversa.value = false
            // Igual ao alarme de sempre, com o mesmo prazo se a voz travar
            handler.removeCallbacks(comecarAtividade)
            handler.postDelayed(comecarAtividade, SAUDACAO_MAX_MS)
            anunciarInicio.run()
            return
        }
        etapa = EtapaDaManha.CONVERSA
        continuarManha(primeira)
    }

    /** A etapa da manhã começa, ou volta depois do STOP e do bom dia: a conversa (com a [primeira] fala da IA, ou a IA retomando), a caminhada ou a câmera. */
    private fun continuarManha(primeira: String? = null) {
        handler.removeCallbacks(comecarAtividade)
        handler.removeCallbacks(anunciarInicio)
        _saudando.value = false
        when (etapa) {
            EtapaDaManha.CONVERSA -> abrirConversa(primeira)
            EtapaDaManha.CAMINHADA -> comecarCaminhada()
            EtapaDaManha.CAMERA, null -> comecarCamera()
        }
    }

    /** Quanto de conversa a manhã pede agora: a meta do [alarme], mais o tempo extra se a pessoa quis continuar. */
    private fun metaDaConversa(alarme: Alarme) = metaConversa(alarme) + extras * extraDaConversa(alarme)

    /** A conversa começa com a [primeira] fala da IA ou, sem ela, volta depois da música, com a IA retomando ([MotorDaConversa.retomar]). */
    private fun abrirConversa(primeira: String?) {
        val alarme = _tocando.value ?: return
        Log.i(TAG, "Conversa: ${if (primeira != null) "começa" else "volta"}, ${conversadoMs / 1000} s de ${metaDaConversa(alarme) / 1000}")
        falhas = 0
        _conversa.value = Conversa(falaDaIa = "", suaVez = false, pensando = true, conversadoMs = conversadoMs, metaMs = metaDaConversa(alarme), vez = vezes)
        ultimoTique = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tiqueDaConversa)
        handler.postDelayed(tiqueDaConversa, TIQUE_CONVERSA_MS)
        if (primeira != null) return falarDaIa(primeira)
        val ia = motor ?: return terminarConversa("sem a IA")
        pedirAIa { ia.retomar(it) }
    }

    /**
     * O relógio da conversa, a cada [TIQUE_CONVERSA_MS]: soma o tempo de conversa pura ([conversaAnda]: a voz da
     * IA tocando, ou a pessoa falando) e, na vez dela, toca os bipes do silêncio ([bipesDoSilencio]), sem zerar
     * nada nem trazer a música. Na pergunta "quer continuar?", nem relógio nem bipe: calado por
     * [SILENCIO_CONVERSA_MS], vale como não.
     */
    private val tiqueDaConversa = object : Runnable {
        override fun run() {
            val c = _conversa.value ?: return
            val agora = SystemClock.elapsedRealtime()
            val passo = agora - ultimoTique
            ultimoTique = agora
            handler.postDelayed(this, TIQUE_CONVERSA_MS)
            val calado = agora - _ultimaFala.value
            if (c.perguntandoSeContinua) {
                if (c.suaVez && calado >= SILENCIO_CONVERSA_MS) terminarConversa("${SILENCIO_CONVERSA_MS / 1000} s sem responder se quer continuar")
                return
            }
            conversadoMs = avancarConversa(conversadoMs, conversaAnda(iaFalando, c.suaVez, agora - vezDaConversa, agora - ouvidoEm), passo)
            if (conversadoMs != c.conversadoMs) _conversa.value = c.copy(conversadoMs = conversadoMs)
            if (!c.suaVez) return
            val n = bipesDoSilencio(calado)
            if (n > bipesDaConversa) {
                bipesDaConversa = n
                Log.i(TAG, "Conversa: ${calado / 1000} s calado na sua vez, bipe $n")
                sirene.bipe(n)
            }
        }
    }

    /** Pede a próxima fala da IA ([pedido] chama o motor); enquanto ela não vem, a tela mostra "pensando" e o relógio para. */
    private fun pedirAIa(pedido: PedidoDaIa) {
        if (falhas == 0) esperaDesde = SystemClock.elapsedRealtime() // fala nova: a espera conta daqui (pedir de novo não recomeça)
        ultimoPedido = pedido
        _conversa.value = _conversa.value?.copy(suaVez = false, pensando = true)
        val rodada = rodadaConversa
        val este = ++pedidosDaIa
        val vale = { rodada == rodadaConversa && este == pedidosDaIa } // só a primeira de resposta e prazo conta
        // O motor promete responder em [RESPOSTA_IA_MAX_MS]; se não cumprir, o serviço não fica preso em "Pensando…"
        handler.postDelayed({
            if (vale()) {
                pedidosDaIa++
                motor?.cancelar()
                iaRespondeu(null)
            }
        }, RESPOSTA_IA_MAX_MS)
        pedido { fala ->
            if (vale()) {
                pedidosDaIa++
                iaRespondeu(fala)
            }
        }
    }

    /** A pessoa disse [texto]: vai pra IA. */
    private fun responderNaIa(texto: String) {
        val ia = motor ?: return terminarConversa("sem a IA")
        pedirAIa { ia.responder(texto, it) }
    }

    /**
     * A resposta da IA: a [fala] vai pra voz. Sem ela (o motor pode já ter tentado de novo), pede o mesmo outra
     * vez; na [FALHAS_DA_IA_ATE_DESISTIR]ª seguida, ou com [RESPOSTA_IA_MAX_MS] de espera por esta fala (somando
     * os pedidos de novo), a conversa acaba antes e a manhã segue.
     */
    private fun iaRespondeu(fala: String?) {
        if (fala != null) {
            falhas = 0
            return falarDaIa(fala)
        }
        falhas++
        val esperou = SystemClock.elapsedRealtime() - esperaDesde
        if (falhas >= FALHAS_DA_IA_ATE_DESISTIR || esperou >= RESPOSTA_IA_MAX_MS) {
            return terminarConversa("a IA não respondeu ($falhas vezes seguidas, ${esperou / 1000} s)")
        }
        Log.w(TAG, "Conversa: a IA não respondeu ($falhas de $FALHAS_DA_IA_ATE_DESISTIR), pede de novo")
        ultimoPedido?.let { pedirAIa(it) }
    }

    /**
     * A voz fala o [texto] da IA (gravado agora, num arquivo novo; o [FALA_NAO_NEGOCIA] já está gravado) e a
     * tela mostra; o relógio anda enquanto ela toca. Quando acaba, é a vez da pessoa. Se a voz travar gravando,
     * em [VOZ_DA_IA_MAX_MS] a fala vai só na tela e a vez passa pra pessoa assim mesmo.
     */
    private fun falarDaIa(texto: String) {
        val rodada = rodadaConversa
        var foi = false // a gravação e o prazo dela chegando os dois: só o primeiro vale
        val tocar: (File?) -> Unit = tocar@{ arquivo ->
            if (rodada != rodadaConversa || foi) return@tocar
            foi = true
            _conversa.value = _conversa.value?.copy(falaDaIa = texto, pensando = false)
            iaFalando = arquivo != null
            sirene.falar(arquivo) {
                if (rodada != rodadaConversa) return@falar
                iaFalando = false
                suaVezNaConversa()
            }
        }
        if (texto == FALA_NAO_NEGOCIA) return tocar(voz?.fixa(FALA_DE_NAO_NEGOCIAR))
        val v = voz ?: return tocar(null)
        handler.postDelayed({
            if (!foi && rodada == rodadaConversa) {
                Log.w(TAG, "Conversa: a voz não gravou a fala da IA em ${VOZ_DA_IA_MAX_MS / 1000} s, vai só na tela")
                tocar(null)
            }
        }, VOZ_DA_IA_MAX_MS)
        v.gravar("ia_${++falasDaIa}", texto, tocar)
    }

    /** A voz da IA acabou: é a vez da pessoa falar (a tela escuta). O silêncio conta daqui. */
    private fun suaVezNaConversa() {
        val c = _conversa.value ?: return
        val agora = SystemClock.elapsedRealtime()
        vezDaConversa = agora
        bipesDaConversa = 0
        _ultimaFala.value = agora
        _conversa.value = c.copy(suaVez = true, pensando = false, vez = ++vezes)
    }

    /** O reconhecedor ouviu fala na vez da pessoa: o relógio anda, e o silêncio (e os bipes) recomeça do zero. */
    private fun ouviuNaConversa() {
        val agora = SystemClock.elapsedRealtime()
        ouvidoEm = agora
        _ultimaFala.value = agora
        bipesDaConversa = 0
    }

    /**
     * A pessoa terminou de falar ([texto]): vai pra IA. Com a meta da conversa cumprida, em vez da IA vem a
     * pergunta se ela quer continuar (uma vez só); na pergunta, a resposta decide; depois do tempo extra, acaba.
     */
    private fun respondeuNaConversa(texto: String) {
        val c = _conversa.value?.takeIf { it.suaVez && etapa == EtapaDaManha.CONVERSA } ?: return
        if (texto.isBlank()) return
        ouviuNaConversa()
        Log.i(TAG, "Conversa: ele respondeu (${texto.length} letras), ${conversadoMs / 1000} s de ${c.metaMs / 1000}")
        _conversa.value = c.copy(suaVez = false, pensando = true)
        when {
            c.perguntandoSeContinua -> respondeuSeContinua(texto)
            conversadoMs < c.metaMs -> responderNaIa(texto)
            extras == 0 -> perguntarSeContinua(texto)
            else -> terminarConversa("tempo de conversa completo, com o extra")
        }
    }

    /** Meta da conversa cumprida: a voz pergunta se a pessoa quer continuar ([FALA_CONTINUAR]). A [resposta] dela, ainda sem retorno da IA, fica guardada. */
    private fun perguntarSeContinua(resposta: String) {
        Log.i(TAG, "Conversa: meta cumprida, pergunta se quer continuar")
        respostaSemRetorno = resposta
        _conversa.value = _conversa.value?.copy(falaDaIa = FALA_CONTINUAR, pensando = false, perguntandoSeContinua = true)
        val rodada = rodadaConversa
        sirene.falar(voz?.fixa(FALA_DE_CONTINUAR)) { if (rodada == rodadaConversa) suaVezNaConversa() }
    }

    /** A resposta ao "quer continuar?": sim ([querContinuar]), a meta sobe e a IA responde as duas falas; não, ou sem entender, acaba. */
    private fun respondeuSeContinua(resposta: String) {
        val alarme = _tocando.value ?: return
        if (querContinuar(resposta) != true) return terminarConversa("ele não quis continuar")
        extras++
        Log.i(TAG, "Conversa: ele quis continuar, mais ${extraDaConversa(alarme) / 1000} s")
        _conversa.value = _conversa.value?.copy(perguntandoSeContinua = false, metaMs = metaDaConversa(alarme))
        responderNaIa(pedidoDeContinuar(respostaSemRetorno, resposta))
    }

    /**
     * Fim da conversa (meta cumprida, a pessoa não quis mais, ou a IA ou o reconhecimento de voz falharam): a voz
     * fala a frase fixa da próxima etapa ([FALA_SAIR] ou [FALA_CAMERA]) e ela começa.
     */
    private fun terminarConversa(motivo: String) {
        if (etapa != EtapaDaManha.CONVERSA) return
        Log.i(TAG, "Conversa: fim ($motivo), ${conversadoMs / 1000} s de conversa")
        val rodada = ++rodadaConversa // nada que estava pendente (IA, voz, fala da pessoa) vale mais
        handler.removeCallbacks(tiqueDaConversa)
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar() // antes da caminhada ou da câmera: menos memória e menos calor
        iaFalando = false
        val caminhar = caminho == CaminhoDaManha.CONVERSA_E_CAMINHADA
        etapa = if (caminhar) EtapaDaManha.CAMINHADA else EtapaDaManha.CAMERA
        val (fala, texto) = if (caminhar) FALA_DE_SAIR to FALA_SAIR else FALA_DE_CAMERA to FALA_CAMERA
        _conversa.value = _conversa.value?.copy(falaDaIa = texto, suaVez = false, pensando = false, perguntandoSeContinua = false)
        sirene.falar(voz?.fixa(fala)) {
            if (rodada != rodadaConversa) return@falar
            _conversa.value = null
            continuarManha()
        }
    }

    /**
     * A caminhada, com o prazo pra sair de casa (marcado no relógio na primeira vez; a música voltar não dá mais
     * prazo): a tela conta o tempo andando pelo GPS e a vigia bipa quem fica parado depois do prazo.
     */
    private fun comecarCaminhada() {
        val alarme = _tocando.value ?: return
        val agora = SystemClock.elapsedRealtime()
        if (prazoAte == 0L) prazoAte = agora + prazoSair(alarme)
        Log.i(TAG, "Caminhada: ${andado / 1000} s andados de ${metaCaminhada(alarme) / 1000}, ${maxOf(0L, prazoAte - agora) / 1000} s de prazo pra sair")
        _caminhada.value = Caminhada(andadoMs = andado, metaMs = metaCaminhada(alarme), prazoAte = prazoAte)
        adiarVigiaDaCaminhada()
    }

    /** Sinal de "andando" da etapa da caminhada: guarda o tempo andado e a contagem pro bipe recomeça. */
    private fun andou(andadoMs: Long) {
        // Sinal atrasado, de quando a música voltou ou a câmera tomou o lugar
        if (_caminhada.value == null || !_silenciado.value || _saudando.value || cumprido) return
        andado = maxOf(andado, andadoMs)
        _caminhada.value = _caminhada.value?.copy(andadoMs = andado) // a tela recriada no meio continua daqui
        adiarVigiaDaCaminhada()
    }

    /** O 1º bipe de parado fica pra [PARADO_MS] depois de agora ou do fim do prazo pra sair ([primeiroBipeParado]): no prazo, nenhum. */
    private fun adiarVigiaDaCaminhada() {
        val agora = SystemClock.elapsedRealtime()
        _ultimoPasso.value = agora
        bipesParado = 0
        handler.removeCallbacks(vigiaDaCaminhada)
        handler.postDelayed(vigiaDaCaminhada, primeiroBipeParado(agora, prazoAte) - agora)
    }

    /** Vigia da caminhada: parado depois do prazo pra sair, um bipe a cada [ENTRE_BIPES_PARADO_MS], mais alto até o máximo, até voltar a andar. Não zera nada. */
    private val vigiaDaCaminhada = object : Runnable {
        override fun run() {
            bipesParado++
            Log.i(TAG, "Caminhada: parado depois do prazo pra sair, bipe $bipesParado")
            sirene.bipe(bipesParado)
            handler.postDelayed(this, ENTRE_BIPES_PARADO_MS)
        }
    }

    /** O GPS não funciona na caminhada: a câmera fica no lugar do que faltava andar. A tela pode avisar de novo: vale uma vez. */
    private fun semGpsNaCaminhada() {
        if (_caminhada.value == null || etapa != EtapaDaManha.CAMINHADA || cumprido) return
        Log.w(TAG, "Caminhada: GPS sem funcionar, a câmera fica no lugar do resto (${andado / 1000} s andados)")
        etapa = EtapaDaManha.CAMERA
        comecarCamera()
    }

    /** A meta da câmera: a de sempre ([metaOlhar]) ou, no lugar da caminhada, a da chuva ([metaCameraSemCaminhada]) ou o que faltava andar. */
    private fun metaDaCamera(alarme: Alarme): Long = when {
        !cameraNoLugarDaCaminhada -> metaOlhar(alarme)
        caminho == CaminhoDaManha.CONVERSA_E_CAMERA -> metaCameraSemCaminhada(alarme)
        else -> (metaCaminhada(alarme) - andado).coerceAtLeast(1_000L)
    }

    /** A câmera no lugar da caminhada: sem a verificação do meio (o olho já exigente) e com a vigia de sempre. */
    private fun comecarCamera() {
        val alarme = _tocando.value ?: return
        handler.removeCallbacks(vigiaDaCaminhada)
        _caminhada.value = null
        cameraNoLugarDaCaminhada = true
        _metaCamera.value = metaDaCamera(alarme)
        _metadeVerificada.value = true
        Log.i(TAG, "Missão: câmera no lugar da caminhada, ${_metaCamera.value / 1000} s (anel em ${_olhado.value / 1000} s)")
        adiarVigia()
    }

    /** A música voltou: a etapa da manhã para (o que chegar depois de antes não vale), e o caminho e o progresso ficam. */
    private fun pausarManha() {
        rodadaConversa++
        handler.removeCallbacks(tiqueDaConversa)
        handler.removeCallbacks(vigiaDaCaminhada)
        handler.removeCallbacks(esperaDaDecisao)
        iaFalando = false
        saudacaoTerminou = false
        _conversa.value = null
        _caminhada.value = null
        if (caminho == null) {
            // Ainda decidindo: no próximo STOP, tudo de novo
            motor?.desligar()
            motor = null
        } else {
            motor?.cancelar() // a conversa fica, com o histórico
        }
    }

    /** Alarme novo (ou um que não tem mais conversa e caminhada): a manhã começa do zero. */
    private fun esquecerManha() {
        rodadaConversa++
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
        caminho = null
        etapa = null
        conversadoMs = 0
        extras = 0
        falhas = 0
        andado = 0
        prazoAte = 0
        cameraNoLugarDaCaminhada = false
    }

    /**
     * Outro alarme juntou no meio ([EmAndamento.juntar]): os tempos seguem o alarme novo (o teste pede bem
     * menos). Se ele não tem conversa e caminhada, a manhã nova para e a música volta: depois do STOP, a
     * missão de sempre.
     */
    private fun ajustarManha(alarme: Alarme) {
        if (caminho == null || caminho == CaminhoDaManha.ANTIGO) return
        if (!comConversaECaminhada(alarme)) {
            Log.i(TAG, "Serviço: o alarme novo não tem conversa e caminhada, a música volta e depois vem a missão de sempre")
            religarMusica()
            esquecerManha()
        }
        _conversa.value = _conversa.value?.copy(metaMs = metaDaConversa(alarme))
        _caminhada.value = _caminhada.value?.copy(metaMs = metaCaminhada(alarme))
        _metaCamera.value = metaDaCamera(alarme)
    }

    /** Missão cumprida (ou DESLIGAR, em alarme sem missão): o único fim do alarme. A limpeza acontece em [onDestroy]. */
    private fun concluir(comemorar: Boolean = false) {
        if (cumprido) return
        cumprido = true
        Log.i(TAG, "Serviço: alarme cumprido")
        handler.removeCallbacksAndMessages(null) // nenhuma renovação atrasada pode rearmar a retomada
        rodadaConversa++ // nem a IA nem uma voz atrasada da conversa falam mais
        motor?.desligar()
        motor = null
        MotorDaConversa.liberar()
        // Cumprido já fica gravado: se o app cair na comemoração, o alarme não volta
        volumeAoFim = Ajustes.emAndamento?.volume
        Ajustes.volumeParaDevolver = volumeAoFim
        Ajustes.emAndamento = null
        Agendador.cancelarRetomada(this)
        if (!comemorar) return encerrar()
        // Missão cumprida: bipe leve, a fala de parabéns e o estouro, aí fecha
        _comemorando.value = true
        sirene.calar()
        handler.postDelayed(::encerrar, COMEMORACAO_MAX_MS)
        sirene.leve { sirene.falar(voz?.fixa(FALA_DO_FIM)) { sirene.comemoracao(::encerrar) } }
    }

    /**
     * O fim de verdade: devolve o volume de antes do alarme e fecha o serviço (a limpeza acontece em
     * [onDestroy]). Se outro alarme disparou na comemoração, ele toca em instantes, pela retomada.
     */
    private fun encerrar() {
        handler.removeCallbacksAndMessages(null)
        sirene.desligar(volumeAntes = volumeAoFim)
        Ajustes.volumeParaDevolver = null
        Ajustes.emAndamento?.let {
            Log.i(TAG, "Serviço: outro alarme disparou na comemoração, toca em seguida")
            Agendador.agendarRetomada(this, 0)
        }
        pararServico()
    }

    /** Fecha só este serviço, sem mexer no alarme em andamento. */
    private fun pararServico() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Vira serviço em primeiro plano exibindo a notificação do alarme. */
    private fun mostrarNotificacao(alarme: Alarme) {
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notificacoes.ID_TOCANDO, criarNotificacao(alarme), tipo)
    }

    private fun criarNotificacao(alarme: Alarme): Notification {
        val telaCheia = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Com a missão ligada, "Desligar" abre a tela da missão em vez de desligar direto
        val desligar = if (alarme.missao) telaCheia else comandoPendente(1, ACAO_DESLIGAR)
        val agora = hhmm(LocalTime.now())
        return NotificationCompat.Builder(this, Notificacoes.CANAL_ALARME)
            .setSmallIcon(R.drawable.ic_alarme)
            .setContentTitle(alarme.nome)
            .setContentText(if (alarme.missao) "$agora · diga STOP e olhe pra câmera" else "$agora · tocando")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setColor(getColor(R.color.vermelho_alarme))
            .setColorized(true)
            .setContentIntent(telaCheia)
            .setFullScreenIntent(telaCheia, true)
            .setDeleteIntent(comandoPendente(2, ACAO_REEXIBIR))
            .addAction(0, "Desligar", desligar)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun comandoPendente(codigo: Int, acao: String): PendingIntent =
        PendingIntent.getService(this, codigo, comando(this, acao), PendingIntent.FLAG_IMMUTABLE)

    private fun notificacoes() = getSystemService(NotificationManager::class.java)
}
