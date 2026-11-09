package br.psascan.painel.ui.widgets

import br.psascan.painel.ui.layout.Opcoes
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Contas puras dos widgets `mostrador`, `barra` e `numero_historico` (sem Compose, testadas na JVM). São as mesmas
 * funções de psascan/static/widgets/mostrador.js, barra.js e numero_historico.js.
 */

/** Normaliza [v] em [0, 1] entre [min] e [max] (limitado). */
fun frac(v: Double, min: Double, max: Double): Double = ((v - min) / (if (max - min == 0.0) 1.0 else max - min)).coerceIn(0.0, 1.0)

/** Passo "bonito" para a escala ficar com uns 8 números. */
fun passoAuto(faixa: Double): Double {
    val bruto = faixa / 8
    val mag = 10.0.pow(floor(log10(bruto)))
    for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) if (m * mag >= bruto - 1e-12) return m * mag
    return 10 * mag
}

/** A escala efetiva do mostrador: [maior] = passo dos números, [menor] = divisões entre números, [div] = divisor do rótulo. */
data class EscalaMostrador(val min: Double, val max: Double, val maior: Double, val menor: Int, val div: Double, val alerta: Double?) {
    val faixa get() = max - min

    /** Quantos traços finos cabem de [min] a [max]. */
    val total get() = max(1, (faixa / (maior / menor)).roundToInt())

    /** Valor do traço [k] (0 a [total]). */
    fun valorDe(k: Int) = min + k * faixa / total
}

/** Escala pelas opções do layout, depois por [minM]/[maxM] da métrica, depois pelos passos calculados. */
fun escalaEfetiva(opcoes: Opcoes, minM: Double?, maxM: Double?): EscalaMostrador {
    val o = opcoes.objeto("escala")
    val min = o?.numero("min") ?: minM ?: 0.0
    var max = o?.numero("max") ?: maxM ?: (min + 100)
    if (max <= min) max = min + 1
    val maior = o?.numero("maior")?.takeIf { it > 0 } ?: passoAuto(max - min)
    var menor = (o?.numero("menor") ?: 10.0).roundToInt().coerceIn(1, 20)
    // não deixa a régua fina passar de ~400 traços
    while ((max - min) / (maior / menor) > 400 && menor > 1) menor--
    val div = o?.numero("divisor_rotulo")?.takeIf { it > 0 } ?: 1.0
    return EscalaMostrador(min, max, maior, menor, div, o?.numero("alerta"))
}

/** Rótulo da escala: até 2 casas, vírgula decimal. */
fun fmtRotulo(x: Double): String {
    val r = Math.round(x * 100) / 100.0
    return if (r == Math.rint(r)) r.toLong().toString() else r.toString().replace('.', ',')
}

enum class NivelTraco { GRANDE, MEDIO, FINO }

/** Nível do traço [k]: grande a cada [menor] traços, médio na metade (se [menor] for par), fino nos outros. */
fun nivelDe(k: Int, menor: Int): NivelTraco {
    val medioPasso = if (menor % 2 == 0) menor / 2 else 0
    return when {
        k % menor == 0 -> NivelTraco.GRANDE
        medioPasso != 0 && k % medioPasso == 0 -> NivelTraco.MEDIO
        else -> NivelTraco.FINO
    }
}

/** Raio e tamanho (unidades do mostrador) dos números, conforme o tamanho do maior rótulo. */
fun medidasDosNumeros(e: EscalaMostrador): Pair<Float, Float> {
    val n = fmtRotulo(e.max / e.div).length
    return (if (n <= 2) 60f else 58.5f) to (if (n <= 2) 16f else if (n == 3) 12.5f else 10.5f)
}

/** Ângulo (graus a partir do topo, sentido horário) de [v] numa escala de [min] a [max] entre [g0] e [g1]. */
fun anguloDe(v: Double, min: Double, max: Double, g0: Float, g1: Float): Float = g0 + ((v - min) / (max - min)).toFloat() * (g1 - g0)

/** O traço/número já foi passado pelo ponteiro? (só então ganha a cor "passou") */
fun passouPeloPonteiro(v: Double, valor: Double, min: Double) = valor > min && v <= valor + 1e-9

/** Início e fim (graus) de uma barra do arco: o trecho aceso e o vermelho depois do `alerta`. */
data class TrechosArco(val aceso: ClosedFloatingPointRange<Float>?, val alerta: ClosedFloatingPointRange<Float>?)

/** Arco contínuo da pele: aceso de [g0] até o valor (parando em `alerta`), e o trecho depois do alerta em outra cor. */
fun trechosDoArco(f: Double, g0: Float, g1: Float, anguloAlerta: Float?): TrechosArco {
    val gv = g0 + f.toFloat() * (g1 - g0)
    val ga = anguloAlerta?.coerceIn(min(g0, g1), max(g0, g1)) ?: g1
    val aceso = if (gv - g0 > .05f) g0..min(gv, ga) else null
    val alerta = if (gv > ga + .05f && anguloAlerta != null) ga..gv else null
    return TrechosArco(aceso, alerta)
}

enum class CorSegmento { APAGADO, NORMAL, ALERTA }

/** Segmentos do arco da pele: aceso o segmento cujo centro está até o valor; ALERTA se o centro está na zona do alerta. */
fun segmentosDoArco(n: Int, f: Double, min: Double, max: Double, alerta: Double?): List<CorSegmento> =
    List(n) { i ->
        val centro = (i + .5) / n
        when {
            !(centro <= f + 1e-9 && f > 0) -> CorSegmento.APAGADO
            alerta != null && min + centro * (max - min) >= alerta -> CorSegmento.ALERTA
            else -> CorSegmento.NORMAL
        }
    }

/** Cor da barra do arco de baixo: vermelho a partir de 75 % da faixa, "passou" abaixo de 25 %, o resto "ok". */
enum class NivelArcoInferior { BAIXO, MEIO, ALTO, SEM_VALOR }

fun nivelDoArcoInferior(fracao: Double?): NivelArcoInferior = when {
    fracao == null -> NivelArcoInferior.SEM_VALOR
    fracao < .25 -> NivelArcoInferior.BAIXO
    fracao >= .75 -> NivelArcoInferior.ALTO
    else -> NivelArcoInferior.MEIO
}

/** Os quatro dígitos dos rolos: faixas grandes (RPM) arredondam de 10 em 10; sem valor, "----". */
fun digitosDosRolos(valor: Double?, faixa: Double): String {
    if (valor == null) return "----"
    val n = if (faixa >= 1000) (valor / 10).roundToInt() * 10 else valor.roundToInt()
    return max(0, n).toString().padStart(4, '0').takeLast(4)
}

// ---------------------------------------------------------------------------------------------
// Barra

/** Quantos segmentos ficam acesos para a fração [f]. */
fun segmentosAcesos(f: Double, segmentos: Int): Int = (f * segmentos).roundToInt()

/** Limites da barra: opções do layout, depois a escala da métrica, depois 0–100 (nunca iguais). */
fun limitesDaBarra(opcoes: Opcoes, minM: Double?, maxM: Double?): Pair<Double, Double> {
    val mn = opcoes.numero("min") ?: minM ?: 0.0
    var mx = opcoes.numero("max") ?: maxM ?: 100.0
    if (mx == mn) mx = mn + 1
    return mn to mx
}

// ---------------------------------------------------------------------------------------------
// Número com gráfico

/** Limites do eixo Y do gráfico: dados e faixa normal, com 12 % de margem; sem dados, 0 a 1. */
fun limitesDoGrafico(valores: List<Double>, faixa: List<Double>?): Pair<Double, Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (v in valores) { lo = min(lo, v); hi = max(hi, v) }
    if (faixa != null && faixa.size >= 2) { lo = min(lo, faixa[0]); hi = max(hi, faixa[1]) }
    if (lo.isInfinite()) { lo = 0.0; hi = 1.0 }
    if (abs(hi - lo) < 1e-9) { lo -= .5; hi += .5 }
    val mg = (hi - lo) * .12
    return (lo - mg) to (hi + mg)
}

/** Passo (s) das marquinhas do eixo de tempo conforme a janela de [segundos]. */
fun passoDoEixoDeTempo(segundos: Int): Int = if (segundos <= 30) 5 else if (segundos <= 90) 10 else if (segundos <= 200) 30 else 60
