package br.psascan.painel

import androidx.compose.ui.graphics.Color
import br.psascan.painel.ui.EstiloPonteiro
import br.psascan.painel.ui.Pacote
import br.psascan.painel.ui.TemaInvalido
import br.psascan.painel.ui.ValidadorTema
import br.psascan.painel.ui.contraste
import br.psascan.painel.ui.corDeTexto
import br.psascan.painel.ui.escreverPacoteZip
import br.psascan.painel.ui.lerPacoteZip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TemaTest {
    private val textoPadrao = File("src/main/assets/temas/padrao/tema.json").readText()
    private val v = ValidadorTema(textoPadrao)

    private fun recusa(json: String, contem: String, arquivos: Set<String> = emptySet()) {
        try {
            v.validar(json, arquivos)
            fail("devia recusar: $json")
        } catch (e: TemaInvalido) {
            assertTrue("motivo '${e.motivo}' sem '$contem'", e.motivo.contains(contem, ignoreCase = true))
        }
    }

    private fun tema(extra: String) = """{"formato":1,"id":"meu-tema","nome":"Meu"$extra}"""

    @Test fun padraoCarregaEEhValido() {
        val t = v.tema
        assertEquals("padrao", t.id)
        assertEquals(EstiloPonteiro.AGULHA, t.painel.ponteiro.estilo)
        assertEquals(Color(0xFF000000), t.painel.cores.fundo)
        assertEquals(Color(0xFFF4CF5A), t.painel.cores.passou)
        assertEquals(3, t.painel.cores.aro.size)
        assertEquals(4, t.painel.cores.ponteiro.size)
        assertEquals(38f, t.painel.raios.miolo)
        assertFalse(t.painel.escala.numerosPorCima)   // o dono quer a agulha por cima dos números
        assertNotNull(t.interfaceWeb)
        assertNull(t.mascote)
    }

    @Test fun corComTransparencia() {
        assertEquals(Color(0x24, 0x24, 0x24, 0x80), corDeTexto("#24242480"))
        assertNull(corDeTexto("24242480"))
        assertNull(corDeTexto("#fff"))
        assertNull(corDeTexto("#gggggg"))
    }

    @Test fun temaParcialCompletaComOPadrao() {
        val t = v.validar(tema(""","painel":{"cores":{"passou":"#00ff00"},"ponteiro":{"estilo":"seta"}}"""))
        assertEquals("meu-tema", t.id)
        assertEquals(Color(0xFF00FF00), t.painel.cores.passou)
        assertEquals(EstiloPonteiro.SETA, t.painel.ponteiro.estilo)
        assertEquals(0.55f, t.painel.ponteiro.brilho)                  // veio do Padrão
        assertEquals(v.tema.painel.cores.aro, t.painel.cores.aro)
    }

    @Test fun recusaCorRuim() = recusa(tema(""","painel":{"cores":{"passou":"vermelho"}}"""), "cor inválida")
    @Test fun recusaCorEmListaRuim() = recusa(tema(""","painel":{"cores":{"aro":["#000000","#111","#222222"]}}"""), "cor inválida")
    @Test fun recusaListaDeTamanhoErrado() = recusa(tema(""","painel":{"cores":{"aro":["#000000","#111111"]}}"""), "3 cores")
    @Test fun recusaIdRuim() {
        recusa("""{"formato":1,"id":"Meu Tema","nome":"x"}""", "id inválido")
        recusa("""{"formato":1,"id":"a","nome":"x"}""", "id inválido")
        recusa("""{"formato":1,"id":"${"a".repeat(33)}","nome":"x"}""", "id inválido")
    }
    @Test fun recusaFaltaDeId() = recusa("""{"formato":1,"nome":"x"}""", "falta o campo id")
    @Test fun recusaFormatoDiferente() = recusa("""{"formato":2,"id":"abc","nome":"x"}""", "formato")
    @Test fun recusaChaveDesconhecida() {
        recusa(tema(""","painel":{"cores":{"neon":"#ffffff"}}"""), "chave desconhecida")
        recusa(tema(""","extra":1"""), "chave desconhecida")
    }
    @Test fun recusaEnumForaDaLista() {
        recusa(tema(""","painel":{"ponteiro":{"estilo":"triangulo"}}"""), "painel.ponteiro.estilo: valor fora da lista")
        recusa(tema(""","painel":{"animacao":{"ponteiro":"pulo"}}"""), "painel.animacao.ponteiro: valor fora da lista")
        recusa(tema(""","painel":{"fonte":{"app":{"sistema":"comic"}}}"""), "painel.fonte.app.sistema: valor fora da lista")
    }
    @Test fun recusaNumeroForaDaFaixa() {
        recusa(tema(""","painel":{"espessuras":{"aro":9}}"""), "fora da faixa")
        recusa(tema(""","painel":{"raios":{"miolo":10}}"""), "fora da faixa")
        recusa(tema(""","painel":{"animacao":{"rapidez":0}}"""), "fora da faixa")
        recusa(tema(""","painel":{"escala":{"niveis":4}}"""), "fora da faixa")
        recusa(tema(""","painel":{"ponteiro":{"brilho":1.5}}"""), "fora da faixa")
    }
    @Test fun recusaTipoErrado() = recusa(tema(""","painel":{"espessuras":{"aro":"grosso"}}"""), "inválido")
    @Test fun recusaContrasteBaixo() {
        recusa(tema(""","painel":{"cores":{"texto":"#101010"}}"""), "contraste")
        recusa(tema(""","interface":{"claro":{"ink":"#eeeeee"}}"""), "contraste")
    }
    @Test fun recusaJsonQuebrado() = recusa("{nao eh json", "JSON inválido")
    @Test fun recusaArquivoCitadoAusente() {
        recusa(tema(""","painel":{"fonte":{"app":{"arquivo":"arquivos/x.ttf"}}}"""), "não existe")
        v.validar(tema(""","painel":{"fonte":{"app":{"arquivo":"arquivos/x.ttf"}}}"""), setOf("arquivos/x.ttf"))
    }
    @Test fun recusaCaminhoRuimNoTema() {
        recusa(tema(""","painel":{"fonte":{"app":{"arquivo":"../x.ttf"}}}"""), "caminho")
        recusa(tema(""","painel":{"fonte":{"app":{"arquivo":"arquivos/sub/x.ttf"}}}"""), "caminho")
        recusa(tema(""","painel":{"fonte":{"app":{"arquivo":"arquivos/x.exe"}}}"""), "caminho")
    }
    @Test fun mascoteAceitoComImagem() {
        val t = v.validar(tema(""","mascote":{"imagem":"arquivos/f.png","posicao":"canto","nome":"Faísca"}"""), setOf("arquivos/f.png"))
        assertEquals("Faísca", t.mascote?.nome)
        recusa(tema(""","mascote":{"imagem":"arquivos/f.png","posicao":"canto"}"""), "não existe")
    }
    @Test fun interfaceEhAceitaMasNaoUsada() {
        val t = v.validar(tema(""","interface":{"raios":{"r":10}}"""))
        assertEquals(10, t.interfaceWeb!!.jsonObject["raios"]!!.jsonObject["r"]!!.jsonPrimitive.content.toInt())
        recusa(tema(""","interface":{"escuro":{"bg":"azul"}}"""), "cor inválida")
    }

    @Test fun contrasteWcag() {
        assertEquals(21.0, contraste(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contraste(Color.Black, Color.Black), 0.001)
    }

    // ---- .zip

    private fun zip(vararg itens: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, c) in itens) { z.putNextEntry(ZipEntry(n)); z.write(c); z.closeEntry() } }
        return b.toByteArray()
    }
    private val ok = tema("").toByteArray()
    private fun lerZip(b: ByteArray) = lerPacoteZip(ByteArrayInputStream(b))
    private fun recusaZip(b: ByteArray, contem: String) {
        try { lerZip(b); fail("devia recusar") } catch (e: TemaInvalido) {
            assertTrue(e.motivo, e.motivo.contains(contem, ignoreCase = true))
        }
    }

    @Test fun zipValidoELeituraDeVolta() {
        val z = zip("tema.json" to ok, "arquivos/f.ttf" to ByteArray(100))
        val p = lerZip(z)
        assertEquals(setOf("arquivos/f.ttf"), p.arquivos.keys)
        val b = ByteArrayOutputStream(); escreverPacoteZip(b, p)
        val p2 = lerZip(b.toByteArray())
        assertEquals(p.json, p2.json); assertEquals(p.arquivos.keys, p2.arquivos.keys)
    }
    @Test fun zipComPontoPonto() = recusaZip(zip("tema.json" to ok, "arquivos/../x.png" to ByteArray(1)), "proibido")
    @Test fun zipComCaminhoAbsoluto() = recusaZip(zip("tema.json" to ok, "/etc/x.png" to ByteArray(1)), "proibido")
    @Test fun zipComExtensaoProibida() = recusaZip(zip("tema.json" to ok, "arquivos/a.exe" to ByteArray(1)), "não permitido")
    @Test fun zipComSubpasta() = recusaZip(zip("tema.json" to ok, "arquivos/sub/a.png" to ByteArray(1)), "não permitido")
    @Test fun zipComArquivoNaRaiz() = recusaZip(zip("tema.json" to ok, "a.png" to ByteArray(1)), "não permitido")
    @Test fun zipSemTemaJson() = recusaZip(zip("arquivos/a.png" to ByteArray(1)), "falta o tema.json")
    @Test fun zipComArquivoGrande() = recusaZip(zip("tema.json" to ok, "arquivos/a.png" to ByteArray(2 * 1024 * 1024 + 1)), "grande demais")
    @Test fun zipComTotalGrande() {
        val z = zip("tema.json" to ok, *(0 until 3).map { "arquivos/a$it.png" to ByteArray(1_900_000) }.toTypedArray())
        recusaZip(z, "total")
    }
    @Test fun zipCorrompido() = recusaZip(byteArrayOf(1, 2, 3), "tema.json")

    // ---- vetores calculados pela versão Python (se o gerar_dados.py já os produziu)

    @Test fun vetoresDoPython() {
        val raiz = Json.parseToJsonElement(javaClass.classLoader!!.getResource("vetores.json")!!.readText()).jsonObject
        val temas = raiz["temas"]?.jsonObject
        val casos = temas?.get("casos")
        assumeTrue("vetores.json ainda sem 'temas'", casos is JsonArray)
        // o Padrão completado a partir do caso "parcial" tem de ser igual ao do Python
        val parcial = (casos as JsonArray).first { it.jsonObject["nome"]!!.jsonPrimitive.content == "parcial" }
            .jsonObject["json"]!!.jsonPrimitive.content
        assertEquals(temas["completado"], v.completar(parcial))
        for (c in (casos as JsonArray)) {
            val o = c.jsonObject
            val nome = o["nome"]!!.jsonPrimitive.content
            val json = o["json"]!!.let { if (it is JsonObject) it.toString() else it.jsonPrimitive.content }
            val valido = o["valido"]!!.jsonPrimitive.boolean
            try {
                v.validar(json)
                assertTrue("$nome: devia ser recusado", valido)
            } catch (e: TemaInvalido) {
                assertFalse("$nome: devia ser aceito, mas: ${e.motivo}", valido)
                val parte = o["motivo_contem"]?.jsonPrimitive?.content.orEmpty()
                assertTrue("$nome: motivo '${e.motivo}' sem '$parte'", e.motivo.contains(parte, ignoreCase = true))
            }
        }
    }
}
