package br.psascan.painel.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.psascan.painel.ui.layout.Contexto
import br.psascan.painel.ui.layout.TipoWidget
import br.psascan.painel.ui.layout.dpSp
import br.psascan.painel.ui.layout.rememberFontePele
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Widget `falhas`: quantas falhas presentes e memorizadas na última leitura da central, com os códigos; o mesmo
 * de falhas.js. Pele `falhas`: fonte (`fonte_app`). A leitura vem do app ([Contexto.falhas]); null = ainda não lida.
 */
object WidgetFalhas : TipoWidget {
    override val tipo = "falhas"

    @Composable
    override fun Desenhar(c: Contexto, modifier: Modifier) {
        val w = c.area.largura.value
        val h = c.area.altura.value
        val k = c.tema.cores
        val familia = rememberFontePele(c.temaId, c.tema.peles.falhas.fonteApp)
        val compacto = h < 90f
        val fsN = max(10f, min(if (compacto) h * 0.5f else h * 0.26f, w * 0.12f))
        val fsP = max(8f, min(fsN * 0.38f, w * 0.05f))
        val fsL = max(9f, min(fsP * 1.5f, 16f))
        val f = c.falhas
        Column(modifier.clipToBounds().padding(horizontal = (w * 0.03f).dp, vertical = (h * 0.04f).dp),
            verticalArrangement = if (compacto) Arrangement.Center else Arrangement.spacedBy((h * 0.04f).dp)) {
            if (f == null) {
                Text("falhas ainda não lidas", color = k.textoApagado, fontFamily = familia, fontSize = max(9f, fsP * 1.3f).dpSp(), maxLines = 1)
                return@Column
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("${f.presentes.size}", color = k.alerta, fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = fsN.dpSp(),
                    lineHeight = fsN.dpSp(), maxLines = 1)
                Rotulo("presentes", k.textoApagado, fsP, familia)
                Text("${f.memorizadas.size}", color = k.ambar, fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = fsN.dpSp(),
                    lineHeight = fsN.dpSp(), maxLines = 1)
                Rotulo("memorizadas", k.textoApagado, fsP, familia)
                Text(f.hora, color = k.textoApagado, fontFamily = familia, fontSize = fsP.dpSp(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            if (!(c.opcoes.bool("mostrar_codigos") ?: true) || compacto) return@Column
            val itens = f.presentes.map { it to k.alerta } + f.memorizadas.map { it to k.ambar }
            if (itens.isEmpty()) {
                Text("nenhuma falha", color = k.textoApagado, fontFamily = familia, fontSize = max(9f, fsP * 1.3f).dpSp(), maxLines = 1)
                return@Column
            }
            val dispo = h - h * 0.08f - h * 0.34f
            var cabem = max(1, floor(dispo / (fsL * 1.35f)).toInt())
            if (itens.size > cabem) cabem = max(1, cabem - 1)
            for ((x, cor) in itens.take(cabem)) Row(horizontalArrangement = Arrangement.spacedBy((fsL * 0.6f).dp)) {
                Text(x.hex, color = cor, fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = fsL.dpSp(), lineHeight = (fsL * 1.35f).dpSp(),
                    maxLines = 1)
                Text(x.nome, color = k.textoMedio, fontFamily = familia, fontSize = fsL.dpSp(), lineHeight = (fsL * 1.35f).dpSp(), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (itens.size > cabem) Text("+${itens.size - cabem}", color = k.textoApagado, fontFamily = familia, fontSize = fsL.dpSp(),
                lineHeight = (fsL * 1.35f).dpSp())
        }
    }
}

@Composable
private fun Rotulo(texto: String, cor: Color, fs: Float, familia: FontFamily) {
    Text(texto.uppercase(), color = cor, fontFamily = familia, fontSize = fs.dpSp(), letterSpacing = (fs * 0.05f).dpSp(), maxLines = 1,
        modifier = Modifier.padding(start = (fs * 0.3f).dp, end = (fs * 0.9f).dp))
}
