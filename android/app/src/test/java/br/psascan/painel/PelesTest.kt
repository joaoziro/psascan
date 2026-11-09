package br.psascan.painel

import br.psascan.painel.ui.TemaInvalido
import br.psascan.painel.ui.ValidadorTema
import br.psascan.painel.ui.corDeTexto
import br.psascan.painel.ui.sobrepor
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** painel.peles (docs/TEMA.md): o desenho de cada tipo de widget, só dados. */
class PelesTest {
    private val v = ValidadorTema(File("src/main/assets/temas/padrao/tema.json").readText())
    private fun tema(peles: String) = """{"formato":1,"id":"meu-tema","nome":"Meu","painel":{"peles":$peles}}"""
    private fun recusa(peles: String, contem: String, arquivos: Set<String> = emptySet()) {
        try { v.validar(tema(peles), arquivos); fail("devia recusar: $peles") }
        catch (e: TemaInvalido) { assertTrue("motivo '${e.motivo}' sem '$contem'", e.motivo.contains(contem, ignoreCase = true)) }
    }

    @Test fun semPelesVemOsPadroesDoMotor() {
        val p = v.tema.painel.peles
        assertNull(p.numero.fonteApp); assertEquals(0f, p.numero.brilho); assertFalse(p.numero.apagados)
        assertNull(p.luz.imagens.acesa); assertEquals(.8f, p.luz.brilho); assertFalse(p.estado.caixa)
        assertEquals(-128f, p.mostrador.anguloInicio); assertEquals("continua", p.barra.estilo)
    }

    @Test fun pelesValidasSaoLidas() {
        val arq = setOf("arquivos/seg7.ttf", "arquivos/off.png", "arquivos/fundo.webp", "arquivos/agulha.png")
        val t = v.validar(tema("""{
            "numero":{"fonte_app":"arquivos/seg7.ttf","brilho":0.5,"apagados":true},
            "luz":{"imagens":{"apagada":"arquivos/off.png"},"brilho":0.3},
            "estado":{"caixa":true,"fonte":null},
            "mostrador":{"angulo_inicio":-90,"angulo_fim":150,
              "imagens":{"fundo":"arquivos/fundo.webp","agulha":{"arquivo":"arquivos/agulha.png","pivo":[0.5,0.9],"comprimento":0.4}},
              "arco":{"estilo":"segmentos","segmentos":48,"cor":"#2fd6a0"},
              "digital":{"posicao":[100,120],"tamanho":34,"brilho":0.6,"apagados":true}}}"""), arq).painel.peles
        assertEquals("arquivos/seg7.ttf", t.numero.fonteApp); assertEquals(.5f, t.numero.brilho); assertTrue(t.numero.apagados)
        assertEquals("arquivos/off.png", t.luz.imagens.apagada); assertNull(t.luz.imagens.acesa)
        assertTrue(t.estado.caixa)
        assertEquals(150f, t.mostrador.anguloFim); assertEquals(.9f, t.mostrador.imagens.agulha!!.pivo[1])
        assertEquals("segmentos", t.mostrador.arco!!.estilo); assertTrue(t.mostrador.digital!!.apagados)
    }

    @Test fun pelesInvalidasSaoRecusadasComOCaminho() {
        recusa("""{"numero":{"cor":"#fff"}}""", "chave desconhecida: painel.peles.numero.cor")
        recusa("""{"botao":{}}""", "chave desconhecida: painel.peles.botao")
        recusa("""{"numero":{"brilho":2}}""", "painel.peles.numero.brilho")
        recusa("""{"luz":{"imagens":{"acesa":"arquivos/nao-tem.png"}}}""", "não existe")
        recusa("""{"luz":{"imagens":{"acesa":"../fora.png"}}}""", "caminho de arquivo inválido")
        recusa("""{"numero":{"fonte_app":"arquivos/x.png"}}""", "deve ser ttf", setOf("arquivos/x.png"))
        recusa("""{"barra":{"estilo":"pontilhada"}}""", "painel.peles.barra.estilo")
        recusa("""{"mostrador":{"arco":{"segmentos":500}}}""", "painel.peles.mostrador.arco.segmentos")
        recusa("""{"mostrador":{"angulo_inicio":10,"angulo_fim":10}}""", "angulo")
    }

    // ---- peles.mostrador.cores e os temas embutidos

    @Test fun osCincoTemasEmbutidosCarregam() {
        val raiz = File("src/main/assets/temas")
        val ids = raiz.list()!!.sorted()
        assertEquals(listOf("brasa", "lume", "padrao", "vela", "visor"), ids)
        for (id in ids) {
            val pasta = File(raiz, id)
            val arquivos = File(pasta, "arquivos").list()?.map { "arquivos/$it" }?.toSet().orEmpty()
            val t = v.validar(File(pasta, "tema.json").readText(), arquivos)
            assertEquals(id, t.id)
        }
    }

    @Test fun coresDoMostradorSobrepoeAsDoPainel() {
        val t = v.validar(tema("""{"mostrador":{"cores":{"texto":"#102030","miolo":["#111111","#222222"],"alerta":"#c4210f"}}}""")).painel
        val k = t.peles.mostrador.cores
        val c = t.cores.sobrepor(k)
        assertEquals(corDeTexto("#102030"), c.texto); assertEquals(corDeTexto("#c4210f"), c.alerta)
        assertEquals(listOf(corDeTexto("#111111")!!, corDeTexto("#222222")!!), c.miolo)
        assertEquals(t.cores.passou, c.passou); assertEquals(t.cores.aro, c.aro)   // o resto fica
        assertTrue(t.cores.texto != c.texto)
        assertEquals(t.cores, t.cores.sobrepor(JsonObject(emptyMap())))
    }

    @Test fun lumeTemCoresSoDoMostrador() {
        val lume = File("src/main/assets/temas/lume")
        val arq = File(lume, "arquivos").list()!!.map { "arquivos/$it" }.toSet()
        val t = v.validar(File(lume, "tema.json").readText(), arq).painel
        assertTrue(t.peles.mostrador.cores.isNotEmpty())
        assertTrue(t.cores.sobrepor(t.peles.mostrador.cores).texto != t.cores.texto)
    }

    @Test fun coresDoMostradorInvalidasSaoRecusadasComOCaminho() {
        recusa("""{"mostrador":{"cores":{"inexistente":"#ffffff"}}}""", "chave desconhecida: painel.peles.mostrador.cores.inexistente")
        recusa("""{"mostrador":{"cores":{"texto":"branco"}}}""", "painel.peles.mostrador.cores.texto")
        recusa("""{"mostrador":{"cores":{"miolo":["#111111"]}}}""", "painel.peles.mostrador.cores.miolo deve ter 2 cores")
        recusa("""{"mostrador":{"cores":{"aro":["#111111","#222222","#33333"]}}}""", "painel.peles.mostrador.cores.aro[2]")
        recusa("""{"mostrador":{"cores":{"aro":"#111111"}}}""", "painel.peles.mostrador.cores.aro")
    }
}
