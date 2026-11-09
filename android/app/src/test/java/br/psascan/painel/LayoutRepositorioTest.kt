package br.psascan.painel

import br.psascan.painel.ui.layout.Catalogo
import br.psascan.painel.ui.layout.GradeLayout
import br.psascan.painel.ui.layout.Layout
import br.psascan.painel.ui.layout.LayoutAusente
import br.psascan.painel.ui.layout.LayoutInvalido
import br.psascan.painel.ui.layout.WidgetLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** O repositório de layouts numa pasta temporária: prontos de assets, do usuário em arquivo, ativo em memória. */
class LayoutRepositorioTest {
    private lateinit var pasta: File
    private lateinit var ativo: AtivoLayout
    private lateinit var repo: LayoutRepositorio

    private class Memoria : AtivoLayout { override var id: String? = null }

    private fun meu(id: String = "teste-meu", nome: String = "Meu") = Layout(1, id, nome, null, GradeLayout(12, 6),
        null, listOf(WidgetLayout("a", "numero", "rpm", 0, 0, 2, 2)))

    @Before fun abrir() {
        pasta = Files.createTempDirectory("layouts-teste").toFile()
        ativo = Memoria()
        val assets = File("src/main/assets/layouts")
        repo = LayoutRepositorio(Catalogo.de(File("src/main/assets/widgets/catalogo.json").readText()),
            object : ProntosLayout {
                override fun ids() = assets.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()
                override fun ler(id: String) = File(assets, "$id.json").readText()
            }, File(pasta, "layouts"), ativo)
    }

    @After fun fechar() { pasta.deleteRecursively() }

    @Test fun listaOsProntosEOsDoUsuario() {
        assertEquals(6, repo.listar().size)
        assertTrue(repo.listar().all { it.embutido && it.valido })
        repo.salvar(meu())
        val l = repo.listar()
        assertEquals(7, l.size)
        val m = l.last()
        assertEquals("teste-meu", m.id); assertEquals("Meu", m.nome); assertFalse(m.embutido); assertEquals(1, m.widgets)
        assertTrue("o ativo padrão é o painel", l.first { it.id == "painel" }.ativo)
    }

    @Test fun salvarComIdDeProntoERecusado() {
        try { repo.salvar(meu(id = "painel")); fail() } catch (e: LayoutInvalido) {
            assertTrue(e.motivo, e.motivo.contains("escolha outro id"))
        }
        assertFalse(File(pasta, "layouts/painel.json").exists())
    }

    @Test fun salvarRecusaLayoutInvalido() {
        val ruim = meu().copy(widgets = listOf(WidgetLayout("a", "numero", "rpm", 11, 0, 2, 2)))
        try { repo.salvar(ruim); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo, e.motivo.startsWith("widgets[0].x")) }
        assertEquals(6, repo.listar().size)
    }

    @Test fun salvarEReler() {
        repo.salvar(meu())
        assertEquals(meu(), repo.layout("teste-meu"))
        repo.salvar(meu(nome = "Outro nome"))   // mesmo id do usuário: substitui
        assertEquals("Outro nome", repo.layout("teste-meu").nome)
        assertEquals(7, repo.listar().size)
    }

    @Test fun ativarEAtivoInvalido() {
        assertEquals("painel", repo.ativoId)
        repo.salvar(meu())
        assertEquals("teste-meu", repo.ativar("teste-meu").id)
        assertEquals("teste-meu", repo.ativoId)
        try { repo.ativar("nao-existe"); fail() } catch (_: LayoutAusente) {}
        assertEquals("o ativo continua", "teste-meu", repo.ativoId)
        // o arquivo do ativo sumiu ou ficou ruim: volta ao painel com o motivo
        ativo.id = "fantasma"
        val (l, motivo) = repo.ativoComErro()
        assertEquals("painel", l.id)
        assertTrue(motivo!!, motivo.contains("não existe mais"))
        File(pasta, "layouts/ruim.json").writeText("{isto não é json")
        ativo.id = "ruim"
        val (l2, motivo2) = repo.ativoComErro()
        assertEquals("painel", l2.id)
        assertTrue(motivo2!!, motivo2.contains("inválido"))
        assertEquals("o layout inválido aparece na lista com o erro", false, repo.listar().first { it.id == "ruim" }.valido)
    }

    @Test fun apagarSoDoUsuario() {
        repo.salvar(meu())
        repo.ativar("teste-meu")
        repo.apagar("teste-meu")
        assertEquals(6, repo.listar().size)
        assertEquals("apagar o ativo volta ao painel", "painel", repo.ativoId)
        assertNull(ativo.id)
        try { repo.apagar("painel"); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo.contains("não pode ser apagado")) }
        try { repo.apagar("teste-meu"); fail() } catch (_: LayoutAusente) {}
        assertEquals("os prontos continuam", 6, repo.listar().size)
    }

    @Test fun importarEExportar() {
        val bytes = repo.exportar("painel")
        try { repo.importar(bytes); fail("o id painel é de um pronto") } catch (e: LayoutInvalido) { assertTrue(e.motivo.contains("escolha outro id")) }
        val outro = String(bytes, Charsets.UTF_8).replaceFirst("\"id\": \"painel\"", "\"id\": \"painel-copia\"").toByteArray()
        val l = repo.importar(outro)
        assertEquals("painel-copia", l.id)
        assertEquals(l, repo.layout("painel-copia"))
        try { repo.importar("lixo".toByteArray()); fail() } catch (e: LayoutInvalido) { assertTrue(e.motivo.startsWith("JSON inválido")) }
        assertEquals(7, repo.listar().size)
        assertFalse("nenhum temporário sobrou", File(pasta, "layouts").listFiles()!!.any { it.name.startsWith(".") })
    }
}
