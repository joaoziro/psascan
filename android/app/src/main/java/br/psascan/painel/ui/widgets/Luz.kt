package br.psascan.painel.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.rememberFontePele
import br.psascan.painel.ui.layout.rememberImagemTema
import kotlin.math.max
import kotlin.math.min

/**
 * Widget `luz`: ícone do motor que acende (métrica de bit, como a luz da injeção, ou "há falha presente" com
 * `fonte: falhas`); o mesmo de luz.js. Pele `luz`: imagens `apagada`/`acesa` da pasta do tema e brilho.
 */
object WidgetLuz : TipoWidget {
    override val tipo = "luz"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val pele = c.tema.peles.luz
        val rotulo = c.opcoes.texto("rotulo").orEmpty()
        val hRot = if (rotulo.isNotEmpty()) max(8f, min(h * 0.18f, w * 0.14f)) else 0f
        val lado = max(8f, min(min(w, h - hRot * 1.4f) * 0.5f, 64f))   // ícone discreto, como no painel
        val aceso = if (c.opcoes.texto("fonte") == "falhas") c.falhas?.presentes?.isNotEmpty() == true
        else c.valor?.numero == 1.0
        val apagada = rememberImagemTema(c.temaId, pele.imagens.apagada)
        val acesa = rememberImagemTema(c.temaId, pele.imagens.acesa)
        val familia = rememberFontePele(c.temaId, null)

        Column(modifier.clipToBounds(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (pele.imagens.apagada != null || pele.imagens.acesa != null) {
                // imagens da pele: a apagada embaixo, a acesa por cima; se só uma existir, a outra é ela mesma esmaecida
                val temAp = pele.imagens.apagada != null
                val temAc = pele.imagens.acesa != null
                Box(Modifier.size(lado.dp)) {
                    val base = apagada ?: acesa
                    val topo = acesa ?: apagada
                    if (base != null) Image(base, null, Modifier.size(lado.dp).alpha(if (aceso) (if (temAp && temAc) 0f else 0.25f) else 1f),
                        contentScale = ContentScale.Fit)
                    if (topo != null) Image(topo, null, Modifier.size(lado.dp).alpha(if (aceso) 1f else 0f), contentScale = ContentScale.Fit)
                }
            } else {
                val cor = if (aceso) k.ambar else k.luzApagada
                val brilho = (pele.brilho / 0.8f).coerceIn(0f, 1.25f)
                Canvas(Modifier.size(lado.dp, (lado * 30f / 33f).dp)) {
                    val e = size.width / 33f
                    val p = caminhoMotor(e)
                    if (aceso && brilho > 0f) drawPath(p, cor.copy(alpha = 0.35f * brilho), style = Stroke(5f * e, join = StrokeJoin.Round))
                    drawPath(p, cor, style = Stroke(2.2f * e, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (rotulo.isNotEmpty()) Text(rotulo.uppercase(), color = k.textoApagado, fontFamily = familia, fontSize = hRot.dpSp(),
                letterSpacing = (hRot * 0.06f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** O contorno do motor (símbolo da luz de injeção), desenhado numa caixa de 33 × 30 unidades; [e] = pixels por unidade. */
fun caminhoMotor(e: Float): Path = Path().apply {
    fun m(x: Float, y: Float) = moveTo(x * e, y * e)
    fun l(x: Float, y: Float) = lineTo(x * e, y * e)
    m(1.5f, 10.5f); l(5.5f, 10.5f); l(8.5f, 7.6f); l(17.4f, 7.6f); l(21f, 11.4f); l(25f, 11.4f); l(25f, 8.8f)
    l(28f, 8.8f); quadraticTo(31.5f * e, 8.8f * e, 31.5f * e, 12.3f * e); l(31.5f, 20.7f)
    quadraticTo(31.5f * e, 24.2f * e, 28f * e, 24.2f * e); l(25f, 24.2f); l(25f, 22.5f); l(21f, 22.5f)
    l(21f, 27.5f); l(9.5f, 27.5f); l(5.5f, 23.5f); l(1.5f, 23.5f); close()
    m(7.5f, 3.5f); l(18.5f, 3.5f); m(13f, 3.5f); l(13f, 7.6f)
}
