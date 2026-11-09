package br.psascan.painel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.psascan.painel.EstadoGravacao
import br.psascan.painel.EstadoLink
import br.psascan.painel.Status
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Modos do app, embaixo em todas as telas: o atual fica âmbar preenchido. Gravar/marcar/parar à direita. */
@Composable
fun BarraModos(
    atual: String, simulador: Boolean, g: EstadoGravacao, aoModo: (String) -> Unit, aoScanner: () -> Unit,
    aoGravar: () -> Unit, aoMarcar: () -> Unit, aoParar: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((id, nome) in listOf("painel" to "PAINEL", "diagnostico" to "DIAGNÓSTICO", "falhas" to "FALHAS",
            "gravacoes" to "GRAVAÇÕES"))
            Selo(nome, if (id == atual) Cor.passou else Cor.fino, { aoModo(id) }, marcado = id == atual)
        Spacer(Modifier.weight(1f))
        when {
            g.parando -> Text("parando…", color = Cor.passou, fontSize = 12.sp)
            g.ativa -> {
                Selo("MARCAR", Cor.passou, aoMarcar)
                Selo("■ PARAR", Cor.vermelho, aoParar)
            }
            else -> Selo("● GRAVAR", Cor.vermelho, aoGravar)
        }
        Selo(if (simulador) "SIMULADOR" else "SCANNER", Cor.verde, aoScanner)
    }
}

@Composable
fun Topo(st: Status, simulador: Boolean, g: EstadoGravacao) {
    var hora by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) { hora = f.format(Date()); delay(1000) }
    }
    val (txt, ok) = when (st.link) {
        EstadoLink.CONECTADO -> "lendo · lambda %.1f Hz".format(st.taxaHz).replace('.', ',') to true
        EstadoLink.CONECTANDO -> "conectando · ${st.etapa}" to false
        EstadoLink.RECONECTANDO -> "reconectando…" to false
        EstadoLink.DESCONECTADO -> "desconectado" to false
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape)
            .border(3.dp, if (ok) Cor.verde else Cor.apagado, CircleShape))
        Text((if (simulador) "simulador · " else "") + txt, color = Cor.apagado, fontSize = 13.sp,
            modifier = Modifier.padding(start = 6.dp))
        Spacer(Modifier.weight(1f))
        if (g.ativa) {
            val quedas = if (g.quedas == 0) "" else " · ${g.quedas} queda" + if (g.quedas > 1) "s" else ""
            Text("● REC ${duracao(g.inicioMs, agoraMs())} · ${g.modo.texto}$quedas", color = Cor.vermelho,
                fontSize = 13.sp, modifier = Modifier.padding(end = 14.dp))
        }
        Text(hora, color = Cor.grande.copy(alpha = .8f), fontSize = 13.sp)
    }
}

@Composable
fun Selo(texto: String, cor: Color, aoTocar: (() -> Unit)? = null, tam: Int = 12, marcado: Boolean = false) {
    Text(texto, color = cor, fontSize = tam.sp, letterSpacing = (tam * .12).sp,
        modifier = Modifier.clip(RoundedCornerShape(Temas.painel.raios.rotulo.dp)).border(1.dp, cor, RoundedCornerShape(Temas.painel.raios.rotulo.dp))
            .background(if (marcado) cor.copy(alpha = .12f) else Color.Transparent)
            .let { if (aoTocar != null) it.clickable(onClick = aoTocar) else it }
            .padding(horizontal = (tam * .75).dp, vertical = (tam / 6).dp))
}
