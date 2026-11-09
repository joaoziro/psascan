package br.psascan.painel

import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.Valor
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O que a Conexao avisa durante uma gravação. Chamado da thread do scanner. */
interface Registro {
    fun leitura(bloco: Int, vals: List<Valor>)
    fun evento(tipo: String, nota: String = "", quando: Instante = Instante.agora())
}

/** Um instante nos dois relógios: o monotônico (tempo decorrido) e o de parede (hora real). */
data class Instante(val nano: Long, val ms: Long) {
    companion object {
        fun agora() = Instante(System.nanoTime(), System.currentTimeMillis())
    }
}

/**
 * Uma sessão de gravação = um CSV. Colunas: t_s (desde o início), hora, bloco lido, evento, nota e
 * uma métrica por coluna, na ordem do perfil, com o valor convertido (sem texto, sem unidade, sem faixa).
 * Cada linha de leitura traz só o bloco que foi lido; as colunas do outro bloco ficam vazias, para
 * não confundir valor repetido com valor medido. Eventos (início, queda, marca, falhas, fim) vão no
 * mesmo arquivo. Cada linha é descarregada na hora: se o app morrer, perde no máximo a linha em curso.
 */
class Gravador(
    private val w: Writer, perfil: Perfil,
    /** Chamado a cada [SINCRONIZAR_MS] para mandar ao disco o que o sistema ainda tem em memória. */
    private val sincronizar: () -> Unit = {},
    private val inicio: Instante = Instante.agora(),
) : Registro {
    private val params = perfil.metricas.sortedBy { it.ordem }
    private val coluna = params.withIndex().associate { (i, m) -> m.ordem to i }
    private val hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    private var ultimaSinc = System.nanoTime()
    private var fechado = false
    var linhas = 0
        private set

    init {
        val cab = listOf("t_s", "hora", "bloco", "evento", "nota") +
            params.map { m -> if (m.unidade.isEmpty()) m.nome else "${m.nome} (${m.unidade})" }
        escrever(cab.joinToString(",") { campo(it) })
    }

    @Synchronized
    override fun leitura(bloco: Int, vals: List<Valor>) {
        if (fechado) return
        val cols = arrayOfNulls<String>(params.size)
        for (v in vals) {
            val i = coluna[v.m.ordem] ?: continue
            cols[i] = if (v.m.ehDiscreto) v.raw?.toString() else v.numero?.let(::numero)
        }
        linha(Instante.agora(), bloco.toString(), "", "", cols.map { it ?: "" })
    }

    @Synchronized
    override fun evento(tipo: String, nota: String, quando: Instante) {
        if (fechado) return
        linha(quando, "", tipo, nota, List(params.size) { "" })
    }

    @Synchronized
    fun fechar() {
        if (fechado) return
        fechado = true
        try { w.flush(); sincronizar() } finally { w.close() }
    }

    private fun linha(q: Instante, bloco: String, evento: String, nota: String, cols: List<String>) {
        val t = String.format(Locale.ROOT, "%.3f", (q.nano - inicio.nano) / 1e9)
        escrever((listOf(t, hora.format(Date(q.ms)), bloco, campo(evento), campo(nota)) + cols).joinToString(","))
        linhas++
    }

    private fun escrever(s: String) {
        w.write(s); w.write("\n"); w.flush()
        val agora = System.nanoTime()
        if (agora - ultimaSinc > SINCRONIZAR_MS * 1_000_000) { sincronizar(); ultimaSinc = agora }
    }

    companion object {
        const val SINCRONIZAR_MS = 5000L

        /** Ponto decimal, até 4 casas, sem zeros sobrando: 14.016, 832, 3.38. */
        fun numero(x: Double): String {
            val s = String.format(Locale.ROOT, "%.4f", x).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }

        /** Aspas só quando precisa (vírgula, aspas ou quebra de linha na nota). */
        fun campo(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        private val NOME = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT)

        /** 306_20261029_0815.csv; se já existir (duas no mesmo minuto), _2, _3… */
        fun nomeArquivo(inicioMs: Long, existe: (String) -> Boolean): String {
            val base = "306_" + synchronized(NOME) { NOME.format(Date(inicioMs)) }
            var nome = "$base.csv"
            var k = 2
            while (existe(nome)) nome = "${base}_${k++}.csv"
            return nome
        }
    }
}
