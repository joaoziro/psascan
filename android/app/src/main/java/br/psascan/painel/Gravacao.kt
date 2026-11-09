package br.psascan.painel

import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.Valor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O que a tela mostra da gravação. arquivo vazio = nenhuma em curso. */
data class EstadoGravacao(
    val ativa: Boolean = false, val parando: Boolean = false, val arquivo: String = "",
    val inicioMs: Long = 0, val linhas: Int = 0, val marcas: Int = 0, val quedas: Int = 0,
    val modo: ModoLeitura = ModoLeitura.NORMAL, val aviso: String = "",
)

/** Onde a gravação escreve: o stream, o fsync e o fechamento (Arquivos no app, memória nos testes). */
class Destino(val nome: String, val saida: OutputStream, val sincronizar: () -> Unit = {}, val fechar: () -> Unit = {})

/**
 * Uma sessão de gravação por vez. Iniciar abre o CSV e põe a Conexao no ritmo do modo; quedas do
 * Bluetooth só viram eventos no mesmo arquivo (a Conexao reconecta sozinha). Falhas da central são
 * lidas no início e no fim. Um arquivo novo só começa depois de parar e iniciar de novo.
 */
class Gravacao(
    private val perfil: Perfil, private val escopo: CoroutineScope,
    private val abrir: (inicioMs: Long) -> Destino,
    /** Avisa quando a gravação começa/termina (liga e desliga o serviço em primeiro plano). */
    private val aoMudar: (ativa: Boolean) -> Unit = {},
    private val prazoInicioMs: Long = 30_000, private val prazoFimMs: Long = 15_000,
) : Registro {
    private val _estado = MutableStateFlow(EstadoGravacao())
    val estado: StateFlow<EstadoGravacao> = _estado.asStateFlow()

    @Volatile private var gravador: Gravador? = null   // lido da thread do scanner
    private var destino: Destino? = null
    private var conexao: Conexao? = null
    private var falhasInicio: Job? = null

    val ativa get() = gravador != null

    /** cabecalho: o que vai na nota do evento "inicio" além da data e do modo (scanner, versão do app). */
    fun iniciar(c: Conexao, cabecalho: String = "") {
        if (gravador != null) return
        val ini = Instante.agora()
        val d = try { abrir(ini.ms) } catch (e: Exception) {
            _estado.update { it.copy(aviso = "Não deu para criar o arquivo: ${e.message}") }
            return
        }
        val g = Gravador(d.saida.bufferedWriter(Charsets.UTF_8), perfil, d.sincronizar, ini)
        gravador = g; destino = d; conexao = c
        val modo = _estado.value.modo
        val data = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ini.ms))
        g.evento("inicio", listOf(data, "modo ${modo.texto}", cabecalho).filter { it.isNotEmpty() }.joinToString("; "), ini)
        _estado.value = EstadoGravacao(ativa = true, arquivo = d.nome, inicioMs = ini.ms, modo = modo,
            linhas = g.linhas)
        c.modoGravacao = modo
        c.registro = this
        aoMudar(true)
        falhasInicio = escopo.launch { registrarFalhas(c, "falhas_inicio", prazoInicioMs) }
    }

    /** Lê as falhas do fim, escreve "fim" e fecha o arquivo. motivo vai na nota do "fim". */
    suspend fun parar(motivo: String = "") {
        val c = conexao ?: return
        if (_estado.value.parando) return
        _estado.update { it.copy(parando = true) }
        falhasInicio?.cancel()
        registrarFalhas(c, "falhas_fim", prazoFimMs)
        evento("fim", motivo)
        c.registro = null
        c.modoGravacao = null
        val d = destino
        try { gravador?.fechar() } catch (_: Exception) {}
        try { d?.fechar?.invoke() } catch (_: Exception) {}
        gravador = null; destino = null; conexao = null
        _estado.update { EstadoGravacao(modo = it.modo, aviso = if (d != null) "Salvo: ${d.nome}" else "") }
        aoMudar(false)
    }

    fun trocarModo(m: ModoLeitura) {
        _estado.update { it.copy(modo = m) }
        if (gravador == null) return
        conexao?.modoGravacao = m
        evento("modo", m.texto)
    }

    /** quando: o instante do toque em MARCAR (a nota pode ter sido digitada depois). */
    fun marcar(nota: String, quando: Instante) {
        if (gravador == null) return
        _estado.update { it.copy(marcas = it.marcas + 1) }
        evento("marca", nota.trim(), quando)
    }

    /** Leitura ou apagamento de falhas feito pela tela durante a gravação. */
    fun falhasDaTela(tipo: String, lista: List<FalhaLida>?) {
        if (gravador != null) evento(tipo, lista?.let(::descrever) ?: "")
    }

    fun avisar(texto: String) = _estado.update { it.copy(aviso = texto) }

    // Registro: vem da thread do scanner. Erro de disco não pode virar "queda do Bluetooth" na Conexao.
    override fun leitura(bloco: Int, vals: List<Valor>) = escrevendo { g ->
        g.leitura(bloco, vals)
        _estado.update { it.copy(linhas = g.linhas) }
    }

    override fun evento(tipo: String, nota: String, quando: Instante) = escrevendo { g ->
        g.evento(tipo, nota, quando)
        _estado.update { it.copy(linhas = g.linhas, quedas = it.quedas + if (tipo == "queda") 1 else 0) }
    }

    private inline fun escrevendo(fn: (Gravador) -> Unit) {
        val g = gravador ?: return
        try { fn(g) } catch (e: Exception) {
            _estado.update { it.copy(aviso = "Erro ao gravar no arquivo: ${e.message}") }
        }
    }

    /** Tenta até o prazo (a central pode estar reconectando); se não der, registra que não leu. */
    private suspend fun registrarFalhas(c: Conexao, tipo: String, prazoMs: Long) {
        val fim = System.nanoTime() + prazoMs * 1_000_000
        var erro = "tempo esgotado"
        while (true) {
            val resta = (fim - System.nanoTime()) / 1_000_000
            if (resta <= 0) break
            try {
                val l = withTimeoutOrNull(resta) { c.lerFalhas() }
                if (l != null) { evento(tipo, descrever(l)); return }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                erro = e.message ?: e.javaClass.simpleName
            }
            delay(minOf(2000L, maxOf(0L, (fim - System.nanoTime()) / 1_000_000)))
        }
        evento("${tipo}_nao_lidas", erro)
    }

    companion object {
        fun descrever(l: List<FalhaLida>) = if (l.isEmpty()) "nenhuma" else
            l.joinToString("; ") { "${it.hex} ${if (it.presente) "presente" else "memorizada"} (${it.nome})" }
    }
}
