package br.psascan.painel.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.corEstado
import br.psascan.painel.ui.layout.decimais
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.numBr
import br.psascan.painel.ui.layout.rememberFontePele
import kotlin.math.max
import kotlin.math.min

/**
 * Widget `numero`: rótulo em cima, número grande, unidade, cor pela faixa (docs/LAYOUT.md; o mesmo de numero.js).
 * Pele `numero` (docs/TEMA.md): fonte (`fonte_app`, .ttf), brilho e dígitos apagados ("8888" fraco atrás do valor).
 */
object WidgetNumero : TipoWidget {
    override val tipo = "numero"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val pele = c.tema.peles.numero
        val m = c.metrica
        val discreta = m != null && m.ehDiscreto
        val dec = decimais(c)
        val familia = rememberFontePele(c.temaId, pele.fonteApp)
        val v = c.valor
        val texto = when {
            v == null -> "—"
            discreta -> v.texto.ifEmpty { "—" }
            else -> numBr(v.numero, dec)
        }
        val unidade = if (!discreta && c.opcoes.bool("mostrar_unidade") == true && m != null && m.unidade.isNotEmpty() && v != null) m.unidade else ""
        val colorir = c.opcoes.bool("mostrar_estado") == true && v != null && !discreta
        val cor = if (colorir) corEstado(c) else k.texto
        val molde = if (!discreta && pele.apagados && v != null) numBr(m?.escala?.lastOrNull() ?: v.numero, dec).replace(Regex("\\d"), "8") else null

        val fsTitulo = max(8f, min(h * 0.17f, w * 0.13f))
        val dispH = h - if (c.titulo.isNotEmpty()) fsTitulo * 1.3f else 0f
        val n = max(max(texto.length, molde?.length ?: 0), 1)
        val larg = w * 0.94f - if (unidade.isNotEmpty()) min(w * 0.3f, unidade.length * dispH * 0.2f) else 0f
        val fs = max(8f, min(dispH * 0.78f, larg / (n * if (discreta) 0.5f else 0.55f)))
        val brilho = if (pele.brilho > 0f) Shadow(cor, Offset.Zero, fs * 0.3f * pele.brilho) else null

        Column(modifier.clipToBounds(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (c.titulo.isNotEmpty()) Text(c.titulo.uppercase(), color = k.textoApagado, fontSize = fsTitulo.dpSp(),
                letterSpacing = (fsTitulo * 0.06f).dpSp(), lineHeight = (fsTitulo * 1.1f).dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (molde != null) Text(molde, color = k.texto.copy(alpha = 0.12f), fontFamily = familia, fontSize = fs.dpSp(),
                        lineHeight = fs.dpSp(), maxLines = 1, softWrap = false)
                    Text(texto, color = cor, fontFamily = familia, fontSize = fs.dpSp(), lineHeight = fs.dpSp(), maxLines = 1,
                        softWrap = false, style = TextStyle(shadow = brilho))
                }
                if (unidade.isNotEmpty()) Text(unidade, color = k.textoMedio, fontFamily = familia, fontSize = max(8f, fs * 0.38f).dpSp(),
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(start = (fs * 0.1f).dp, bottom = (fs * 0.1f).dp))
            }
        }
    }
}
