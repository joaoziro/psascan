package br.psascan.painel

import br.psascan.painel.protocolo.AnaliseLambda
import br.psascan.painel.protocolo.Avaliacao
import br.psascan.painel.protocolo.Faixas
import br.psascan.painel.protocolo.ResumoLambda
import br.psascan.painel.protocolo.ErroLink
import br.psascan.painel.protocolo.EscritaBloqueada
import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.Psa5
import br.psascan.painel.protocolo.Sagem
import br.psascan.painel.protocolo.Scanner
import br.psascan.painel.protocolo.Transporte
import br.psascan.painel.protocolo.Valor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** O que o painel mostra (métricas "rpm", "velocidade", "agua", "ar", "bateria" e a luz de falha do perfil). null = ainda não leu. */
data class Leitura(
    val rpm: Double? = null, val vel: Double? = null, val agua: Double? = null,
    val ar: Double? = null, val bat: Double? = null, val luz: Boolean? = null,
)

/** Tudo do modo diagnóstico: último valor, 60 s de histórico (segundos, número) e o estado de cada métrica. */
data class Diagnostico(
    val valores: Map<Int, Valor> = emptyMap(),
    val historico: Map<Int, List<Pair<Double, Double>>> = emptyMap(),
    val avaliacoes: Map<Int, Avaliacao> = emptyMap(),
    val lambda: ResumoLambda? = null,
    val agora: Double = 0.0,
)

enum class EstadoLink { DESCONECTADO, CONECTANDO, CONECTADO, RECONECTANDO }

/**
 * Ordem dos blocos lidos durante uma gravação, um bloco por volta. A central só entrega blocos
 * inteiros; o bloco principal é o da métrica de lambda (a que mais interessa ler rápido). Na SL96 o
 * bloco 0 tem lambda, RPM, MAP, borboleta (e temperaturas, bateria…) e o 1 tem injeção, motor de
 * passo, avanço, relés e luz.
 */
enum class ModoLeitura(val texto: String) {
    NORMAL("normal"),        // os outros blocos a cada duas voltas do principal
    FOCO("foco lambda");     // só o bloco principal, na taxa máxima

    fun blocos(p: Perfil): List<Int> {
        val principal = p.blocoPrincipal
        val outros = p.blocos.map { it.id }.filter { it != principal }
        return if (this == FOCO || outros.isEmpty()) listOf(principal) else outros.flatMap { listOf(principal, it) } + principal
    }
}

/** taxaHz: leituras do bloco 0 (o da lambda) por segundo. */
data class Status(
    val link: EstadoLink = EstadoLink.DESCONECTADO, val etapa: String = "", val erro: String = "",
    val taxaHz: Double = 0.0, val reconexoes: Int = 0,
)

data class FalhaLida(
    val codigo: Int, val tipo: Int, val presente: Boolean,
    val nome: String, val tipoNome: String, val causas: List<String>,
) {
    val hex get() = "%03X".format(codigo)
}

/**
 * Uma thread só fala com o scanner (como a trava do conexao.py). Enquanto ligada: conecta e lê
 * um bloco por volta: no painel o bloco 0 (RPM, velocidade, água, ar, bateria) e o 1 a cada 3
 * (luz de falha); no diagnóstico todos alternados; gravando, a ordem de [ModoLeitura].
 * Ler/apagar falhas entram na fila entre uma leitura e outra.
 * Se o Bluetooth cair, fecha tudo e reconecta sozinha com espera crescente (0,5 s até 5 s).
 */
class Conexao(
    private val perfil: Perfil, private val escopo: CoroutineScope,
    /** Abre o transporte; recebe o log desta conexão para as mensagens do Bluetooth irem para a tela. */
    private val abrir: (log: (String) -> Unit) -> Transporte,
) {
    private val fio = Executors.newSingleThreadExecutor { Thread(it, "scanner").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val pedidos = Channel<Pedido>(Channel.UNLIMITED)
    private var job: Job? = null
    @Volatile private var scanner: Scanner? = null   // volatile: desligar() fecha de outra thread
    private var ecu: Psa5? = null

    private val _leitura = MutableStateFlow(Leitura())
    val leitura: StateFlow<Leitura> = _leitura.asStateFlow()
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _log = MutableStateFlow(listOf<String>())
    /** Últimas linhas (bytes enviados/recebidos, etapas, erros) para a tela de detalhes. */
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _diag = MutableStateFlow(Diagnostico())
    val diag: StateFlow<Diagnostico> = _diag.asStateFlow()

    /** true no modo diagnóstico: alterna os dois blocos (senão o bloco 1 só a cada 3 do 0). */
    @Volatile var completo = false
    /** Gravando: a ordem dos blocos vem daqui (vale mais que [completo]). */
    @Volatile var modoGravacao: ModoLeitura? = null
    /**
     * Comandos de escrita (apagar falhas…) só rodam com isto ligado. Começa desligado a cada Conexao,
     * ou seja, a cada vez que o app abre; a tela de Falhas o liga e desliga.
     */
    @Volatile var escrita = false
    /** Gravando: recebe cada leitura e as quedas/reconexões. */
    @Volatile var registro: Registro? = null
    private val t0 = System.nanoTime()
    /** Histórico de cada métrica (ordem → (segundos, número)) dos últimos [JANELA_HIST_S] s; só o laço escreve, a tela lê com a trava. */
    private val hist = HashMap<Int, ArrayDeque<Pair<Double, Double>>>()
    private val ultimos = HashMap<Int, Valor>()

    private sealed class Pedido {
        class LerFalhas(val r: CompletableDeferred<List<FalhaLida>>) : Pedido()
        class Apagar(val r: CompletableDeferred<Unit>) : Pedido()
    }

    private val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    fun nota(t: String) = _log.update { (it + "${hora.format(Date())}  $t").takeLast(400) }

    val ligada get() = job?.isActive == true

    fun ligar() {
        if (ligada) return
        job = escopo.launch(fio) { laco() }
    }

    /** Para de vez (troca de scanner ou app fechando). Fecha o socket já, para destravar uma leitura em curso. */
    fun desligar() {
        job?.cancel()
        job = null
        try { scanner?.fechar() } catch (_: Exception) {}
        escopo.launch(fio) {
            fecharLink(EstadoLink.DESCONECTADO)
            falharPendentes(ErroLink("conexão encerrada"))
        }.invokeOnCompletion { fio.close() }
    }

    suspend fun lerFalhas(): List<FalhaLida> = CompletableDeferred<List<FalhaLida>>().also {
        if (!ligada) throw ErroLink("conexão encerrada")
        pedidos.send(Pedido.LerFalhas(it))
    }.await()

    suspend fun apagarFalhas() = CompletableDeferred<Unit>().also {
        if (perfil.apagarFalhas == null) throw EscritaBloqueada("o perfil ${perfil.nome} não tem comando validado para apagar falhas")
        if (!escrita) throw EscritaBloqueada("comandos de escrita desabilitados (somente leitura)")
        if (!ligada) throw ErroLink("conexão encerrada")
        pedidos.send(Pedido.Apagar(it))
    }.await()

    private fun fecharLink(novo: EstadoLink) {
        try { scanner?.fechar() } catch (_: Exception) {}
        scanner = null; ecu = null
        _status.update { it.copy(link = novo) }
    }

    private suspend fun laco() {
        var espera = 500L
        var n = 0
        var tTaxa = System.nanoTime(); var nTaxa = 0
        while (currentCoroutineContext().isActive) {
            try {
                val e = ecu ?: conectar()
                espera = 500L
                val p = pedidos.tryReceive().getOrNull()
                if (p != null) { atender(e, p); continue }
                val ordem = modoGravacao?.blocos(perfil) ?: if (completo) completoOrdem else painelOrdem
                val bloco = ordem[n++ % ordem.size]
                val vals = e.lerParametros(listOf(bloco))
                registro?.leitura(bloco, vals)
                publicar(vals)
                if (bloco == perfil.blocoPrincipal) nTaxa++
                val agora = System.nanoTime()
                if (agora - tTaxa > 2_000_000_000L) {
                    val hz = nTaxa / ((agora - tTaxa) / 1e9)
                    _status.update { it.copy(taxaHz = hz) }
                    tTaxa = agora; nTaxa = 0
                }
            } catch (e: ErroLink) {
                falhou(e)
                delay(espera)
                espera = minOf(espera * 2, 5000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {   // quadro estranho, permissão, bug: trata como queda em vez de fechar o app
                falhou(ErroLink("${e.javaClass.simpleName}: ${e.message ?: "erro inesperado"}"))
                delay(espera)
                espera = minOf(espera * 2, 5000L)
            }
        }
    }

    private fun falhou(e: ErroLink) {
        nota("falha de comunicação (${e.message}); reconectando")
        val tipo = if (_status.value.link == EstadoLink.CONECTADO) "queda" else "reconexao_falhou"
        registro?.evento(tipo, e.message ?: "")
        _status.update { it.copy(erro = e.message ?: "", reconexoes = it.reconexoes + 1) }
        fecharLink(EstadoLink.RECONECTANDO)
        falharPendentes(e)
    }

    /** Pedidos pendentes não podem ficar esperando para sempre (a tela ficaria em "lendo…"). */
    private fun falharPendentes(e: ErroLink) {
        while (true) {
            when (val p = pedidos.tryReceive().getOrNull() ?: break) {
                is Pedido.LerFalhas -> p.r.completeExceptionally(e)
                is Pedido.Apagar -> p.r.completeExceptionally(e)
            }
        }
    }

    private fun conectar(): Psa5 {
        _status.update { it.copy(link = EstadoLink.CONECTANDO, etapa = "abrir Bluetooth") }
        val s = Scanner(abrir(::nota), ::nota)
        scanner = s
        val e = Psa5(s, perfil) { escrita }
        val v = e.conectar { etapa -> nota(etapa); _status.update { it.copy(etapa = etapa) } }
        nota("conectado; scanner hw ${v.hw} firmware ${v.firmware}")
        registro?.evento(if (_status.value.reconexoes > 0) "reconectado" else "conectado",
            "scanner hw ${v.hw} firmware ${v.firmware}")
        _status.update { it.copy(link = EstadoLink.CONECTADO, etapa = "conectado", erro = "") }
        ecu = e
        return e
    }

    /** O pedido já saiu da fila: se a central falhar no meio, responde o erro a quem pediu antes de reconectar. */
    private fun atender(e: Psa5, p: Pedido) {
        try {
            executar(e, p)
        } catch (x: Exception) {
            when (p) {
                is Pedido.LerFalhas -> p.r.completeExceptionally(x)
                is Pedido.Apagar -> p.r.completeExceptionally(x)
            }
            if (x is EscritaBloqueada) return   // recusado antes de falar com a central: o link está bom
            throw x
        }
    }

    private fun executar(e: Psa5, p: Pedido) {
        when (p) {
            is Pedido.LerFalhas -> {
                val lst = e.lerFalhas() ?: throw ErroLink("a central não respondeu à leitura de falhas")
                p.r.complete(lst.map(::descrever))
            }
            is Pedido.Apagar -> {
                val r = e.escrever(perfil.apagarFalhas!!.id)
                nota("apagar: resposta ${r.joinToString(" ") { "%02x".format(it) }}")
                p.r.complete(Unit)
            }
        }
    }

    private fun descrever(f: Sagem.Falha): FalhaLida {
        val cod = perfil.falhas.codigos[f.codigo.toString()]
        val nome = when {
            cod == null -> "código 0x%X (não identificado)".format(f.codigo)
            cod.nivel == "pendente" -> "${cod.nome} (nome provável)"
            else -> cod.nome
        }
        val tipo = perfil.falhas.tipos[f.tipo.toString()]
        val tipoNome = when {
            tipo == null -> "tipo ${f.tipo}"
            tipo.nivel == "pendente" -> "${tipo.nome} (provável)"
            else -> tipo.nome
        }
        return FalhaLida(f.codigo, f.tipo, f.presente, nome, tipoNome, cod?.causas ?: emptyList())
    }

    private fun publicar(vals: List<Valor>) {
        val por = vals.associateBy { it.m.ordem }
        val t = (System.nanoTime() - t0) / 1e9
        for (v in vals) {
            ultimos[v.m.ordem] = v
            val n = v.numero ?: continue
            synchronized(hist) {
                val h = hist.getOrPut(v.m.ordem) { ArrayDeque() }
                h.addLast(t to n)
                while (t - h.first().first > JANELA_HIST_S) h.removeFirst()
            }
        }
        val numeros = ultimos.mapValues { it.value.numero }
        val ctx = Faixas.contexto(perfil, numeros)
        val ordemLambda = perfil.lambda?.ordem
        val recente = synchronized(hist) { hist.mapValues { (_, h) -> ultimosSegundos(h, t, JANELA_S) } }
        val lam = ordemLambda?.let { o -> recente[o]?.let { AnaliseLambda.resumo(it) } }
        val av = ultimos.mapValues { (o, v) ->
            if (o == ordemLambda) Faixas.avaliarLambda(perfil, lam, ctx) else Faixas.avaliar(perfil.regras(o), v.numero, ctx)
        }
        _diag.value = Diagnostico(HashMap(ultimos), recente, av, lam, t)
        val num = { id: String -> perfil.metricaPorId(id)?.let { por[it.ordem]?.numero } }
        val luz = perfil.luzFalha?.let { por[it.ordem]?.raw?.let { r -> r == 1 } }
        _leitura.update { l ->
            l.copy(
                rpm = num("rpm") ?: l.rpm, vel = num("velocidade") ?: l.vel,
                agua = num("agua") ?: l.agua, ar = num("ar") ?: l.ar,
                bat = num("bateria") ?: l.bat, luz = luz ?: l.luz,
            )
        }
    }

    /** Histórico (segundos desde a conexão, número) dos últimos [segundos] s da métrica de `ordem`; vazio se não houve leitura. */
    fun historico(ordem: Int, segundos: Double): List<Pair<Double, Double>> = synchronized(hist) {
        val h = hist[ordem] ?: return emptyList()
        ultimosSegundos(h, h.last().first, segundos)
    }

    private fun ultimosSegundos(h: ArrayDeque<Pair<Double, Double>>, agora: Double, seg: Double): List<Pair<Double, Double>> {
        var i = h.size
        while (i > 0 && agora - h[i - 1].first <= seg) i--
        return h.subList(i, h.size).toList()
    }

    /** Ordens de blocos fora de gravação: o painel quase só precisa do principal; o diagnóstico lê todos. */
    private val outrosBlocos = perfil.blocos.map { it.id }.filter { it != perfil.blocoPrincipal }
    private val painelOrdem = perfil.blocoPrincipal.let { p ->
        if (outrosBlocos.isEmpty()) listOf(p) else outrosBlocos.flatMap { listOf(p, it, p, p) }
    }
    private val completoOrdem = listOf(perfil.blocoPrincipal) + outrosBlocos

    companion object {
        /** Janela do gráfico da Diagnóstico e da análise da lambda. */
        const val JANELA_S = 60.0
        /** Janela guardada para os widgets de histórico da tela personalizável (10 min, o máximo de `numero_historico`). */
        const val JANELA_HIST_S = 600.0
    }
}
