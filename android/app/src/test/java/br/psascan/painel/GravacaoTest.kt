package br.psascan.painel

import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.ErroLink
import br.psascan.painel.protocolo.SimTransporte
import br.psascan.painel.protocolo.Transporte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter

class GravacaoTest {
    private val perfil = Perfil.de(File("src/main/assets/perfil.json").readText())

    /** Linhas do CSV já separadas em campos (as notas dos testes não têm vírgula). */
    private fun csv(s: String) = s.trimEnd('\n').split("\n").map { it.split(",") }

    @Test fun cabecalhoEColunasDoBloco() {
        val w = StringWriter()
        val g = Gravador(w, perfil)
        val d = ByteArray(25).also { it[0] = 0x11 }
        d[4] = 26           // RPM: byte 4, ×32 = 832
        d[17] = 84          // lambda: byte 17, ×9,8 = 823,2
        d[13] = 1           // bobina (bloco 1)
        val bloco0 = perfil.metricas.filter { it.bloco == 0 }.map { perfil.converter(it, d) }
        g.leitura(0, bloco0)
        g.evento("marca", "parei, no farol")
        g.fechar()
        val l = csv(w.toString())
        val cab = l[0]
        assertEquals(listOf("t_s", "hora", "bloco", "evento", "nota"), cab.take(5))
        assertEquals(5 + perfil.metricas.size, cab.size)
        assertTrue(cab.contains("Sonda lambda (mV)"))
        val linha = l[1]
        assertEquals("0", linha[2])
        assertEquals("832", linha[cab.indexOf("Rotação do motor (rpm)")])
        assertEquals("823.2", linha[cab.indexOf("Sonda lambda (mV)")])
        assertEquals("", linha[cab.indexOf("Tempo de injeção (ms)")])   // bloco 1 não foi lido: vazio
        assertTrue(w.toString().contains(",marca,\"parei, no farol\","))
    }

    @Test fun numerosENomes() {
        assertEquals("14.016", Gravador.numero(14.016))
        assertEquals("832", Gravador.numero(832.0))
        assertEquals("0", Gravador.numero(-0.00001))
        assertEquals("\"a\"\"b\"", Gravador.campo("a\"b"))
        val ms = java.text.SimpleDateFormat("yyyyMMdd HHmm").parse("20261029 0815")!!.time
        assertEquals("306_20261029_0815.csv", Gravador.nomeArquivo(ms) { false })
        val tem = setOf("306_20261029_0815.csv", "306_20261029_0815_2.csv")
        assertEquals("306_20261029_0815_3.csv", Gravador.nomeArquivo(ms) { it in tem })
    }

    /** Simulador que some no meio: a partir de [cair] leituras, toda leitura dá erro de link. */
    private class Caindo(private val sim: SimTransporte, private val cair: Int) : Transporte {
        private var n = 0
        override fun escrever(dados: ByteArray) = sim.escrever(dados)
        override fun ler(timeoutMs: Long): ByteArray {
            if (++n > cair) throw ErroLink("socket fechado (teste)")
            return sim.ler(timeoutMs)
        }
        override fun fechar() {}
    }

    private fun sessao(modo: ModoLeitura, cairDepois: Int? = null, duracaoMs: Long = 2500): Pair<String, EstadoGravacao> = runBlocking {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var aberturas = 0
        val c = Conexao(perfil, escopo) { _ ->
            aberturas++
            if (cairDepois != null && aberturas == 1) Caindo(SimTransporte(perfil), cairDepois) else SimTransporte(perfil)
        }
        val saida = ByteArrayOutputStream()
        var fechou = false
        val g = Gravacao(perfil, escopo, abrir = { Destino("teste.csv", saida, fechar = { fechou = true }) },
            prazoInicioMs = 10_000, prazoFimMs = 10_000)
        g.trocarModo(modo)
        c.ligar()
        g.iniciar(c, "scanner simulador")
        delay(duracaoMs / 2)
        g.marcar("acelerei", Instante.agora())
        delay(duracaoMs / 2)
        val antes = g.estado.value
        g.parar()
        c.desligar()
        escopo.cancel()
        assertTrue("fechou o arquivo", fechou)
        saida.toString(Charsets.UTF_8) to antes
    }

    @Test fun focoLambdaSoLeBloco0() {
        val (txt, _) = sessao(ModoLeitura.FOCO)
        val l = csv(txt)
        val blocos = l.drop(1).map { it[2] }.filter { it.isNotEmpty() }
        assertTrue("leu: ${blocos.size}", blocos.size > 20)
        assertEquals(setOf("0"), blocos.toSet())
    }

    @Test fun normalIntercalaEFalhasNoInicioENoFim() {
        val (txt, est) = sessao(ModoLeitura.NORMAL)
        val l = csv(txt).drop(1)
        val blocos = l.map { it[2] }.filter { it.isNotEmpty() }
        val zeros = blocos.count { it == "0" }; val uns = blocos.count { it == "1" }
        assertTrue("0: $zeros, 1: $uns", uns > 5 && zeros in 2 * uns - 2..2 * uns + 2)
        val eventos = l.map { it[3] }.filter { it.isNotEmpty() }
        assertEquals("inicio", eventos.first())
        assertEquals("fim", eventos.last())
        assertTrue(eventos.toString(), eventos.containsAll(listOf("conectado", "falhas_inicio", "marca", "falhas_fim")))
        // o simulador tem as falhas lidas no carro em 2026-10-25
        assertTrue(txt.contains("falhas_inicio,127 presente"))
        assertEquals(1, est.marcas)
        // o tempo decorrido só anda para frente nas leituras
        val ts = l.filter { it[2].isNotEmpty() }.map { it[0].toDouble() }
        assertEquals(ts.sorted(), ts)
    }

    @Test fun quedaContinuaNoMesmoArquivo() {
        val (txt, est) = sessao(ModoLeitura.FOCO, cairDepois = 40, duracaoMs = 5000)
        val l = csv(txt).drop(1)
        val eventos = l.map { it[3] }.filter { it.isNotEmpty() }
        val iQueda = eventos.indexOf("queda")
        assertTrue(eventos.toString(), iQueda >= 0 && eventos.indexOf("reconectado") > iQueda)
        assertEquals(1, est.quedas)
        // continuou gravando depois de reconectar, no mesmo arquivo
        val iRecon = l.indexOfFirst { it[3] == "reconectado" }
        assertTrue(l.drop(iRecon).count { it[2] == "0" } > 5)
    }
}
