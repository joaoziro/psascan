package br.psascan.painel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.Conexao
import br.psascan.painel.Diagnostico
import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.Estado
import br.psascan.painel.protocolo.Metrica
import br.psascan.painel.protocolo.Regra
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

fun corEstado(e: Estado?) = when (e) {
    Estado.NORMAL -> Cor.verde
    Estado.ATENCAO -> Cor.passou
    Estado.FORA -> Cor.vermelho
    else -> Cor.apagado
}

/** Número no jeito brasileiro, com as casas decimais da métrica. */
fun numeroBr(x: Double, m: Metrica): String {
    return String.format(Locale.ROOT, "%.${m.decimais}f", x).replace('.', ',')
}

/** Todas as métricas em cartões: valor, estado pela faixa de referência e 60 s de gráfico. */
@Composable
fun DiagnosticoTela(d: Diagnostico, perfil: Perfil, modifier: Modifier = Modifier) {
    if (d.valores.isEmpty()) {
        Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("Aguardando a primeira leitura da central…", color = Cor.apagado, fontSize = 14.sp)
        }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(230.dp), modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(perfil.metricas, key = { it.ordem }) { m -> Cartao(m, d, perfil) }
    }
}

/** Caixa larga com contorno colorido e "NOME: ESTADO". */
@Composable
private fun Rotulo(texto: String, cor: Color) {
    Text(texto, color = cor, fontSize = 10.sp, letterSpacing = 1.2.sp, maxLines = 1,
        modifier = Modifier.fillMaxWidth().border(1.dp, cor, RoundedCornerShape(2.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
private fun Cartao(p: Metrica, d: Diagnostico, perfil: Perfil) {
    val v = d.valores[p.ordem]
    val av = d.avaliacoes[p.ordem]
    val neutro = av == null || av.estado == Estado.NEUTRO
    val cor = if (neutro) Cor.fino else corEstado(av?.estado)
    val regra = av?.regra ?: p.faixas.firstOrNull()
    val cond = regra?.let { perfil.condicoes[it.quando]?.rotulo }.orEmpty()
    val ehLambda = p.analise == "lambda"
    Column(Modifier.padding(4.dp)) {
        Rotulo(p.curto.uppercase() + if (neutro) "" else ": " + av!!.rotulo, cor)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            val (txt, un) = when {
                v == null -> "—" to ""
                p.ehDiscreto -> v.texto to ""
                else -> (v.numero?.let { numeroBr(it, p) } ?: v.texto) to p.unidade
            }
            Text(txt, color = if (neutro || av?.estado == Estado.NORMAL) Cor.grande else cor,
                fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(" $un", color = Cor.medio, fontSize = 16.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        val linha = when {
            ehLambda -> d.lambda?.diagnostico?.lowercase() ?: ""
            !av?.msg.isNullOrEmpty() -> av!!.msg
            // a faixa sempre diz em que condição vale ("normal em marcha lenta 8–16 °")
            regra?.normal != null -> listOf("normal", cond, faixa(regra.normal, p), p.unidade)
                .filter { it.isNotEmpty() }.joinToString(" ")
            else -> p.obs ?: p.nome
        }
        Text(linha, color = if (av?.msg.isNullOrEmpty()) Cor.apagado else cor, fontSize = 11.sp, maxLines = 1)
        GraficoHistorico(d.historico[p.ordem].orEmpty(), d.agora, p, regra,
            Modifier.fillMaxWidth().height(78.dp).padding(top = 6.dp))
    }
}

private fun faixa(f: List<Double>, p: Metrica) = numeroBr(f[0], p) + "–" + numeroBr(f[1], p)

/**
 * Gráfico de histórico: fundo de linhas horizontais finas, curva suave branca, o trecho
 * fora da faixa normal em âmbar, linha vermelha do
 * "agora" na direita, eixo de tempo com marquinhas embaixo e a unidade no canto.
 */
@Composable
fun GraficoHistorico(serie: List<Pair<Double, Double>>, agora: Double, p: Metrica, regra: Regra?, modifier: Modifier) {
    val m = rememberTextMeasurer(cacheSize = 16)
    Canvas(modifier) {
        val janela = Conexao.JANELA_S
        val eixo = 12.dp.toPx()                 // faixa de baixo com o tempo
        val w = size.width; val h = size.height - eixo
        // lambda: só a linha de 450 mV (rico acima, pobre abaixo); oscilar é o normal, então não pinta trecho
        val normal = if (p.analise == "lambda") listOf(450.0, 450.0) else regra?.normal
        val (lo, hi) = when {
            p.tipo == "bit" -> -0.2 to 1.2
            p.analise == "lambda" -> 0.0 to 1000.0
            else -> {
                val vs = serie.map { it.second }
                var a = minOf(vs.minOrNull() ?: 0.0, normal?.get(0) ?: Double.MAX_VALUE)
                var b = maxOf(vs.maxOrNull() ?: 1.0, normal?.get(1) ?: -Double.MAX_VALUE)
                if (a == Double.MAX_VALUE) a = 0.0
                if (abs(b - a) < 1e-6) { a -= 1; b += 1 }
                val folga = (b - a) * .15
                (a - folga) to (b + folga)
            }
        }
        val y = { v: Double -> (h - (v - lo) / (hi - lo) * h).toFloat().coerceIn(1f, h - 1f) }
        val x = { t: Double -> ((1 - (agora - t) / janela) * w).toFloat() }

        // fundo: linhas horizontais finas
        var yy = 0f
        while (yy <= h) { drawLine(Temas.painel.cores.graficoGrade, Offset(0f, yy), Offset(w, yy), 1f); yy += 4.dp.toPx() }
        // limites da faixa normal, discretos
        if (normal != null) for (lim in normal.distinct())
            drawLine(Temas.painel.cores.graficoLimite, Offset(0f, y(lim)), Offset(w, y(lim)), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        // eixo do tempo: linha de base, marquinhas a cada 10 s e "1:00 … 0:00"
        drawLine(Cor.fino, Offset(0f, h), Offset(w, h), 1f)
        for (k in 1 until 6) {
            val xx = k / 6f * w
            drawLine(Cor.fino, Offset(xx, h + 2.dp.toPx()), Offset(xx, h + 5.dp.toPx()), 1f)
        }
        val estilo = androidx.compose.ui.text.TextStyle(color = Cor.fino, fontSize = 8.sp)
        drawText(m.measure("1:00", estilo), topLeft = Offset(0f, h + 2.dp.toPx()))
        val zero = m.measure("0:00", estilo)
        drawText(zero, topLeft = Offset(w - zero.size.width, h + 2.dp.toPx()))
        val un = m.measure(p.unidade, estilo.copy(color = Cor.medio))
        drawText(un, topLeft = Offset(w - un.size.width - 4.dp.toPx(), h - un.size.height - 2.dp.toPx()))

        // curva suave (pontos médios ligados por quadráticas); bits em degrau
        val pts = serie.filter { agora - it.first <= janela }.map { (t, v) -> Offset(x(t), y(v)) }
        if (pts.size >= 2) {
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (k in 1 until pts.size) {
                    val a = pts[k - 1]; val b = pts[k]
                    if (p.tipo == "bit") { lineTo(b.x, a.y); lineTo(b.x, b.y) }
                    else { val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2); quadraticTo(a.x, a.y, mid.x, mid.y) }
                }
                lineTo(pts.last().x, pts.last().y)
            }
            val traco = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            clipRect(0f, 0f, w, h) { drawPath(path, Cor.grande, style = traco) }
            // trecho fora da faixa normal pintado por cima
            if (normal != null && normal[0] < normal[1] && !p.ehDiscreto) {
                val yHi = y(normal[1]); val yLo = y(normal[0])
                clipRect(0f, 0f, w, yHi) { drawPath(path, Cor.passou, style = traco) }
                clipRect(0f, yLo, w, h) { drawPath(path, Cor.passou, style = traco) }
            }
        }
        // linha vermelha do "agora"
        drawLine(Cor.vermelho.copy(alpha = .8f), Offset(w - 1f, 0f), Offset(w - 1f, h), 1.dp.toPx())
    }
}

