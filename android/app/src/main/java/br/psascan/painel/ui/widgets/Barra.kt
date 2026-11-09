package br.psascan.painel.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.psascan.painel.protocolo.Estado
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.corEstado
import br.psascan.painel.ui.layout.decimais
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.numBr
import br.psascan.painel.ui.layout.rememberFontePele
import br.psascan.painel.ui.layout.rememberImagemTema
import kotlin.math.max
import kotlin.math.min

/**
 * Widget `barra`: barra horizontal ou vertical, contínua ou em segmentos, com marcas da faixa normal e o valor
 * (o mesmo de barra.js). Pele `barra` (docs/TEMA.md): estilo, segmentos, espaço, raio e imagens `trilho` e `cheia`
 * (a cheia é mostrada só até o valor, recortada).
 */
object WidgetBarra : TipoWidget {
    override val tipo = "barra"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val pele = c.tema.peles.barra
        val vert = c.opcoes.texto("orientacao") == "vertical"
        val fs = (min(w, h) * .17f).coerceIn(10f, 18f)
        val esp = (min(w, h) * .24f).coerceIn(6f, 30f)
        val raio = pele.raio ?: min(esp / 2, 6f)
        val trilho = rememberImagemTema(c.temaId, pele.imagens.trilho)
        val cheia = rememberImagemTema(c.temaId, pele.imagens.cheia)
        val familia = rememberFontePele(c.temaId, null)
        val segs = if (pele.estilo == "segmentos") pele.segmentos.coerceIn(4, 60) else 0

        val (mn, mx) = limitesDaBarra(c.opcoes, c.metrica?.escala?.getOrNull(0), c.metrica?.escala?.getOrNull(1))
        val v = c.valor?.numero
        val f = if (v == null) 0.0 else frac(v, mn, mx)
        val cor = if (c.avaliacao.estado != Estado.NEUTRO) corEstado(c) else k.passou
        val mostrar = c.opcoes.bool("mostrar_valor") != false
        val texto = if (v == null) "—" else numBr(v, decimais(c)) + (c.metrica?.unidade?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        val faixa = c.avaliacao.faixa

        val rotulo: @Composable (Modifier) -> Unit = { mod ->
            Text(c.titulo.uppercase(), mod, color = k.textoFino, fontFamily = familia, fontSize = fs.dpSp(), letterSpacing = (fs * .04f).dpSp(),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = if (vert) TextAlign.Center else TextAlign.Start)
        }
        val valor: @Composable (Modifier) -> Unit = { mod ->
            if (mostrar) Text(texto, mod, color = if (v == null) k.textoFino else k.texto, fontFamily = familia, fontSize = (fs * 1.15f).dpSp(),
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, textAlign = if (vert) TextAlign.Center else TextAlign.End)
        }
        val dados = DadosBarra(vert, segs, pele.espaco, raio, if (segs > 0) ((if (vert) h else w) - 2 * fs) / segs else 0f, f, cor,
            k.aguaTrilho, k.textoFino, trilho, cheia, faixa?.let { listOf(frac(it[0], mn, mx), frac(it[1], mn, mx)) })

        Column(modifier.clipToBounds().padding(horizontal = (fs * .6f).dp, vertical = (fs * .5f).dp),
            verticalArrangement = Arrangement.spacedBy((fs * .4f).dp, if (vert) Alignment.Top else Alignment.CenterVertically),
            horizontalAlignment = if (vert) Alignment.CenterHorizontally else Alignment.Start) {
            if (vert) {
                rotulo(Modifier.fillMaxWidth())
                Canvas(Modifier.width(esp.dp).weight(1f)) { desenharBarra(dados) }
                valor(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(fs.dp), verticalAlignment = Alignment.Bottom) {
                    rotulo(Modifier.weight(1f))
                    valor(Modifier)
                }
                Canvas(Modifier.fillMaxWidth().height(esp.dp)) { desenharBarra(dados) }
            }
        }
    }
}

/**
 * O que o desenho da barra precisa. [lado] = tamanho de um segmento na conta do espaço entre eles (a mesma da web);
 * [marcas] = posições (0 a 1) dos limites da faixa normal, ou null.
 */
private class DadosBarra(
    val vertical: Boolean, val segmentos: Int, val espaco: Float, val raio: Float, val lado: Float, val fracao: Double,
    val cor: Color, val corTrilho: Color, val corMarca: Color, val trilho: ImageBitmap?, val cheia: ImageBitmap?, val marcas: List<Double>?,
)

private fun DrawScope.imagemEsticada(img: ImageBitmap) =
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero,
        IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.High)

/** Trilho (cor ou imagem), preenchimento até a fração (cor, ou a imagem `cheia` recortada) e as marcas da faixa. */
private fun DrawScope.desenharBarra(d: DadosBarra) {
    val w = size.width
    val h = size.height
    val raio = d.raio.dp.toPx()
    val forma = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(raio))) }
    val total = if (d.vertical) h else w
    val gap = max(0f, d.espaco * d.lado.dp.toPx())
    val seg = d.segmentos
    val raioSeg = min(d.raio, 12f).dp.toPx()

    // retângulo do segmento i (o 0 é o do início: embaixo, na vertical)
    fun retSeg(i: Int): Pair<Offset, Size> {
        val tam = (total - gap * (seg - 1)) / seg
        val ini = i * (tam + gap)
        return if (d.vertical) Offset(0f, h - ini - tam) to Size(w, tam) else Offset(ini, 0f) to Size(tam, h)
    }

    // trilho
    if (seg > 0) {
        if (d.trilho != null) clipPath(forma) { imagemEsticada(d.trilho) }
        else for (i in 0 until seg) retSeg(i).let { (o, s) -> drawRoundRect(d.corTrilho, o, s, CornerRadius(raioSeg)) }
    } else if (d.trilho != null) clipPath(forma) { imagemEsticada(d.trilho) }
    else drawRoundRect(d.corTrilho, Offset.Zero, size, CornerRadius(raio))

    // preenchimento: segmentos acesos, ou a fração (a imagem cheia é recortada até lá)
    val frac = if (seg > 0) segmentosAcesos(d.fracao, seg).toFloat() / seg else d.fracao.toFloat()
    if (frac > 0f) {
        if (d.cheia != null) {
            clipPath(forma) {
                if (d.vertical) clipRect(0f, h * (1 - frac), w, h) { imagemEsticada(d.cheia) }
                else clipRect(0f, 0f, w * frac, h) { imagemEsticada(d.cheia) }
            }
        } else if (seg > 0) {
            for (i in 0 until segmentosAcesos(d.fracao, seg)) retSeg(i).let { (o, s) -> drawRoundRect(d.cor, o, s, CornerRadius(raioSeg)) }
        } else {
            clipPath(forma) {
                if (d.vertical) clipRect(0f, h * (1 - frac), w, h) { drawRect(d.cor) }
                else clipRect(0f, 0f, w * frac, h) { drawRect(d.cor) }
            }
        }
    }

    // marcas da faixa normal (passam um pouco da borda do trilho)
    val sobra = 3.dp.toPx()
    val grossura = 2.dp.toPx()
    d.marcas?.forEach { p ->
        if (d.vertical) {
            val y = h - (p * h).toFloat() - grossura / 2
            drawRect(d.corMarca, Offset(-sobra, y), Size(w + 2 * sobra, grossura))
        } else {
            val x = (p * w).toFloat() - grossura / 2
            drawRect(d.corMarca, Offset(x, -sobra), Size(grossura, h + 2 * sobra))
        }
    }
}
