package br.psascan.painel.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.psascan.painel.protocolo.Estado
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.corEstado
import br.psascan.painel.ui.layout.decimais
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.numBr
import br.psascan.painel.ui.layout.rememberFontePele
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Widget `numero_historico`: o cartão da Diagnóstico (cabeçalho NOME: ESTADO, número grande, linha de referência e
 * gráfico dos últimos segundos com a faixa normal); o mesmo de numero_historico.js. Pele `numero_historico`
 * (docs/TEMA.md): espessura da `linha` e `preencher` (área sob a curva).
 */
object WidgetNumeroHistorico : TipoWidget {
    override val tipo = "numero_historico"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val familia = rememberFontePele(c.temaId, null)
        val m = rememberTextMeasurer(cacheSize = 16)
        val fs = (h * .075f).coerceIn(10f, 17f)
        val grande = min(h * .24f, w * .22f).coerceIn(18f, 72f)
        val av = c.avaliacao
        val rot = av.rotulo
        val cor = corEstado(c)
        val un = c.metrica?.unidade.orEmpty()
        val dec = decimais(c)
        val ref = when {
            (av.estado == Estado.ATENCAO || av.estado == Estado.FORA) && av.msg.isNotEmpty() -> av.msg
            av.faixa != null -> "normal " + (if (av.condicao.isNotEmpty()) av.condicao + " " else "") +
                numBr(av.faixa[0], dec) + "–" + numBr(av.faixa[1], dec) + (if (un.isNotEmpty()) " $un" else "")
            else -> av.msg
        }
        val cabecalho = c.titulo + if (rot.isNotEmpty()) ": $rot" else ""
        val raioRotulo = c.tema.raios.rotulo.dp
        val serie = c.historico

        Column(modifier.clipToBounds().padding(horizontal = (fs * .7f).dp, vertical = (fs * .5f).dp),
            verticalArrangement = Arrangement.spacedBy((fs * .2f).dp)) {
            Text(cabecalho.uppercase(), color = if (rot.isNotEmpty()) cor else k.textoFino, fontFamily = familia, fontSize = fs.dpSp(),
                fontWeight = FontWeight.SemiBold, letterSpacing = (fs * .04f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.border(1.dp, if (rot.isNotEmpty()) cor else Color.Transparent, RoundedCornerShape(raioRotulo))
                    .padding(horizontal = (fs * .5f).dp, vertical = (fs * .1f).dp))
            Row(horizontalArrangement = Arrangement.spacedBy((fs * .4f).dp), verticalAlignment = Alignment.Bottom) {
                Text(c.valor?.numero?.let { numBr(it, dec) } ?: "—", color = if (av.estado != Estado.NEUTRO) cor else k.texto,
                    fontFamily = familia, fontSize = grande.dpSp(), lineHeight = grande.dpSp(), fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, style = TextStyle(fontFeatureSettings = "tnum"))
                Text(un, color = k.textoFino, fontFamily = familia, fontSize = (fs * 1.15f).dpSp(), maxLines = 1, softWrap = false)
            }
            Text(ref, color = k.textoFino, fontFamily = familia, fontSize = (fs * .92f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = (fs * 1.1f).dp))
            Canvas(Modifier.weight(1f).fillMaxWidth()) {
                val seg = c.opcoes.inteiro("segundos") ?: 60
                val faixa = if (c.opcoes.bool("mostrar_faixa") != false) av.faixa else null
                val pele = c.tema.peles.numeroHistorico
                grafico(c, m, familia, fs, seg, faixa, serie, pele.linha, pele.preencher, cor)
            }
        }
    }
}

/** Curva suave (pontos médios ligados por quadráticas) pelos pontos [p]; [fecharEm] fecha a área sob a curva (y da base). */
private fun caminhoSuave(p: List<Offset>, fecharEm: Float? = null): Path = Path().apply {
    if (p.isEmpty()) return@apply
    moveTo(p[0].x, p[0].y)
    if (p.size == 1) lineTo(p[0].x + .01f, p[0].y)
    else {
        for (i in 1 until p.size - 1) quadraticTo(p[i].x, p[i].y, (p[i].x + p[i + 1].x) / 2, (p[i].y + p[i + 1].y) / 2)
        lineTo(p.last().x, p.last().y)
    }
    if (fecharEm != null) { lineTo(p.last().x, fecharEm); lineTo(p[0].x, fecharEm); close() }
}

/** O gráfico: grade, banda da faixa normal, eixo de tempo, linha do "agora", curva (âmbar fora da faixa) e o ponto do agora. */
private fun DrawScope.grafico(
    c: Contexto, m: TextMeasurer, familia: FontFamily, fs: Float, seg: Int, faixa: List<Double>?,
    serie: List<Pair<Double, Double>>, espessura: Float, preencher: Boolean, corAgora: Color,
) {
    val k = c.tema.cores
    val d = density
    val W = size.width
    val H = size.height
    if (W < 12 * d || H < 12 * d) return
    val agora = c.agora
    val t0 = agora - seg
    val pts = serie.filter { it.first >= t0 }
    val (lo, hi) = limitesDoGrafico(pts.map { it.second }, faixa)

    val baixo = (fs * 1.5f).roundToInt() * d             // espaço do eixo de tempo
    val dir = 2 * d
    val topo = 2 * d
    val gw = W - dir
    val gy = topo
    val gh = H - baixo - topo
    if (gh < 8 * d) return
    val x = { t: Double -> ((t - t0) / seg * gw).toFloat() }
    val y = { v: Double -> (gy + (1 - (v - lo) / (hi - lo)) * gh).toFloat() }
    val fio = max(1f, d)

    // grade horizontal fina
    for (i in 0..3) {
        val yy = Math.round(gy + gh * i / 3) + .5f * fio
        drawLine(k.graficoGrade, Offset(0f, yy), Offset(gw, yy), fio)
    }
    // faixa normal como banda suave, com os limites tracejados
    if (faixa != null) {
        val ya = y(faixa[1]); val yb = y(faixa[0])
        drawRect(k.ok.copy(alpha = k.ok.alpha * .12f), Offset(0f, ya), Size(gw, yb - ya))
        val tracejado = PathEffect.dashPathEffect(floatArrayOf(3 * d, 3 * d))
        for (yy in listOf(ya, yb)) drawLine(k.graficoLimite, Offset(0f, yy), Offset(gw, yy), fio, pathEffect = tracejado)
    }
    // eixo de tempo: marquinhas e os rótulos "−60 s" e "agora"
    val passo = passoDoEixoDeTempo(seg)
    val yEixo = gy + gh + d
    drawLine(k.textoFino, Offset(0f, yEixo + .5f * fio), Offset(gw, yEixo + .5f * fio), fio)
    var s = 0
    while (s <= seg) {
        val xx = Math.round(gw - s.toFloat() / seg * gw) + .5f * fio
        val grandeTraco = s % (passo * 2) == 0
        drawLine(k.textoFino, Offset(xx, yEixo), Offset(xx, yEixo + (if (grandeTraco) 5 else 3) * d), fio)
        s += passo
    }
    val estilo = TextStyle(color = k.textoFino, fontSize = (fs * .8f).roundToInt().toFloat().times(d).toSp(), fontFamily = familia)
    drawText(m.measure("−$seg s", estilo), topLeft = Offset(0f, yEixo + 5 * d))
    val agoraTxt = m.measure("agora", estilo)
    drawText(agoraTxt, topLeft = Offset(gw - agoraTxt.size.width, yEixo + 5 * d))
    // linha do "agora" à direita
    val xa = Math.round(gw) - .5f * fio
    drawLine(k.graficoLimite, Offset(xa, gy), Offset(xa, gy + gh), fio)

    if (pts.isEmpty()) return
    val px = pts.map { Offset(x(it.first), y(it.second)) }
    val esp = espessura * d
    val traco = Stroke(esp, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (preencher && px.size > 1) drawPath(caminhoSuave(px, gy + gh), k.texto.copy(alpha = k.texto.alpha * .15f))
    // curva clara, e o trecho fora da faixa em âmbar (recorte acima e abaixo da faixa)
    val curva = caminhoSuave(px)
    drawPath(curva, k.texto.copy(alpha = k.texto.alpha * .9f), style = traco)
    if (faixa != null) {
        val ya = y(faixa[1]); val yb = y(faixa[0])
        clipRect(0f, 0f, W, max(0f, ya)) { drawPath(curva, k.ambar, style = traco) }
        clipRect(0f, yb, W, H) { drawPath(curva, k.ambar, style = traco) }
    }
    // ponto do agora
    drawCircle(corAgora, esp * 1.6f, px.last())
}
