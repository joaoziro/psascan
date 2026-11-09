package br.psascan.painel.protocolo

import java.util.Locale

/*
 * Faixas de referência (normal / atenção / fora) e análise da sonda lambda —
 * o mesmo de psascan/faixas.py e registro.AnaliseLambda. As regras e as condições vêm do perfil (assets/perfil.json).
 */

enum class Estado { NEUTRO, NORMAL, ATENCAO, FORA }

/** De que lado da faixa normal o valor saiu. */
enum class Lado { ACIMA, ABAIXO }

data class Avaliacao(val estado: Estado, val msg: String = "", val regra: Regra? = null, val lado: Lado? = null) {
    /** Texto do cabeçalho do cartão: NORMAL, ALTO, BAIXO ou ATENÇÃO (bits); vazio se neutro. */
    val rotulo: String get() = when {
        estado == Estado.NEUTRO -> ""
        estado == Estado.NORMAL -> "NORMAL"
        lado == Lado.ACIMA -> "ALTO"
        lado == Lado.ABAIXO -> "BAIXO"
        else -> "ATENÇÃO"
    }
}

object Faixas {
    /**
     * vals: ordem da métrica → número. Condições do perfil que são verdadeiras agora; uma condição vale
     * se todas as suas regras valem, e métrica sem leitura deixa a regra falsa.
     */
    fun contexto(p: Perfil, vals: Map<Int, Double?>): Set<String> {
        val ordemDe = p.metricas.associate { it.id to it.ordem }
        val memo = HashMap<String, Boolean>()
        fun vale(nome: String): Boolean = memo.getOrPut(nome) {
            val c = p.condicoes[nome] ?: return@getOrPut nome == "sempre"
            c.regras.all { r ->
                when {
                    r.condicao != null -> vale(r.condicao)
                    else -> {
                        val x = vals[ordemDe[r.metrica]] ?: return@all false
                        val v = r.valor ?: return@all false
                        when (r.op) {
                            ">" -> x > v; ">=" -> x >= v; "<" -> x < v; "<=" -> x <= v
                            "==" -> x == v; "!=" -> x != v
                            else -> false
                        }
                    }
                }
            }
        }
        return (p.condicoes.keys + "sempre").filter(::vale).toSet()
    }

    fun avaliar(regras: List<Regra>, valor: Double?, ctx: Set<String>): Avaliacao {
        if (valor == null) return Avaliacao(Estado.NEUTRO)
        for (r in regras) {
            if (r.quando !in ctx) continue
            r.normalValor?.let { nv ->
                val ok = valor == nv
                return Avaliacao(if (ok) Estado.NORMAL else Estado.ATENCAO, if (ok) "" else r.msg ?: "", r)
            }
            val (lo, hi) = r.normal ?: continue
            if (valor in lo..hi) return Avaliacao(Estado.NORMAL, "", r)
            val lado = if (valor < lo) Lado.ABAIXO else Lado.ACIMA
            val msg = if (lado == Lado.ABAIXO) r.abaixo ?: "abaixo da faixa" else r.acima ?: "acima da faixa"
            val a = r.atencao
            if (a != null && valor >= a[0] && valor <= a[1]) return Avaliacao(Estado.ATENCAO, msg, r, lado)
            return Avaliacao(Estado.FORA, msg, r, lado)
        }
        return Avaliacao(Estado.NEUTRO)
    }

    /**
     * Lambda (métrica com `analise: lambda`): o estado vem do veredito das trocas rico↔pobre, e só com
     * o motor quente. A condição "quente" do perfil diz quando; sem ela o perfil não restringe.
     */
    fun avaliarLambda(p: Perfil, r: ResumoLambda?, ctx: Set<String>): Avaliacao {
        if ("quente" in p.condicoes && "quente" !in ctx) return Avaliacao(Estado.NEUTRO, "motor frio ou parado: malha aberta")
        val d = r?.diagnostico ?: return Avaliacao(Estado.NEUTRO)
        val e = when {
            d.startsWith("OSCILANDO") -> Estado.NORMAL
            d.startsWith("VARIA") -> Estado.ATENCAO
            d.startsWith("PARADA") -> Estado.FORA
            else -> Estado.NEUTRO
        }
        return Avaliacao(e, d)
    }
}

data class ResumoLambda(
    val diagnostico: String, val amostras: Int, val segundos: Double = 0.0, val min: Int = 0, val max: Int = 0,
    val media: Int = 0, val trocas: Int = 0, val hz: Double = 0.0, val pctRico: Int = 0,
)

/** Sonda de banda estreita: ~100 mV pobre, ~900 mV rico. Conta as trocas com histerese (350/550 mV). */
object AnaliseLambda {
    private const val BAIXO = 350.0
    private const val ALTO = 550.0

    /** amostras: (segundos, mV) em ordem; analisa os últimos [seg] segundos. */
    fun resumo(amostras: List<Pair<Double, Double>>, seg: Double = 10.0): ResumoLambda {
        if (amostras.isEmpty()) return ResumoLambda("aguardando dados", 0)
        val fim = amostras.last().first
        val a = amostras.filter { fim - it.first <= seg }
        if (a.size < 5) return ResumoLambda("aguardando dados", a.size)
        val vs = a.map { it.second }
        val dur = maxOf(a.last().first - a.first().first, 1e-6)
        var trocas = 0
        var lado: String? = null
        for (v in vs) {
            val novo = if (v >= ALTO) "rico" else if (v <= BAIXO) "pobre" else lado
            if (lado != null && novo != lado) trocas++
            lado = novo
        }
        val mn = vs.min(); val mx = vs.max(); val media = vs.average()
        val rico = 100.0 * vs.count { it >= 450 } / vs.size
        val hz = trocas / 2 / dur
        val f = { fmt: String, x: Any -> String.format(Locale.ROOT, fmt, x) }
        val diag = when {
            mx - mn < 150 -> when {
                media < 300 -> "PARADA EM POBRE (~${media.toInt()} mV)"
                media > 600 -> "PARADA EM RICO (~${media.toInt()} mV)"
                else -> "PARADA NO MEIO (~${media.toInt()} mV)"
            }
            trocas < 2 -> "VARIA, MAS DEVAGAR ($trocas troca(s) em ${pyRound0(dur)} s)"
            else -> "OSCILANDO (~${f("%.1f", hz)} Hz)"
        }
        return ResumoLambda(diag, a.size, Math.round(dur * 10) / 10.0, pyRound0(mn), pyRound0(mx), pyRound0(media),
            trocas, Math.round(hz * 100) / 100.0, pyRound0(rico))
    }

    /** round() do Python: metade vai para o par. */
    private fun pyRound0(x: Double) = Math.rint(x).toInt()
}
