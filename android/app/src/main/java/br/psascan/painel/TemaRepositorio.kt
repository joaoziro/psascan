package br.psascan.painel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import br.psascan.painel.ui.ItemTema
import br.psascan.painel.ui.Pacote
import br.psascan.painel.ui.Tema
import br.psascan.painel.ui.TemaCarregado
import br.psascan.painel.ui.TemaInvalido
import br.psascan.painel.ui.ValidadorTema
import br.psascan.painel.ui.LimitesTema
import br.psascan.painel.ui.ehZip
import br.psascan.painel.ui.escreverPacoteZip
import br.psascan.painel.ui.familiaDoSistema
import br.psascan.painel.ui.lerPacoteZip
import java.io.File

/**
 * Onde moram os temas (docs/TEMA.md): os embutidos em assets/temas/<id>/, os importados em
 * filesDir/temas/<id>/ e o id do ativo em SharedPreferences. Tema ativo ausente ou inválido → Padrão.
 */
class TemaRepositorio(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("painel", Context.MODE_PRIVATE)
    private val pastaImportados get() = File(ctx.filesDir, "temas")
    private val validador = ValidadorTema(
        ctx.assets.open("$ASSETS/$PADRAO/tema.json").bufferedReader().use { it.readText() })

    private fun idsEmbutidos(): List<String> = (ctx.assets.list(ASSETS) ?: emptyArray()).sorted()
    private fun idsImportados(): List<String> =
        (pastaImportados.listFiles { f -> f.isDirectory && File(f, "tema.json").isFile }?.map { it.name } ?: emptyList()).sorted()

    private fun lerEmbutido(id: String): Pacote {
        val json = ctx.assets.open("$ASSETS/$id/tema.json").bufferedReader().use { it.readText() }
        val arquivos = (ctx.assets.list("$ASSETS/$id/arquivos") ?: emptyArray()).associate { n ->
            "arquivos/$n" to ctx.assets.open("$ASSETS/$id/arquivos/$n").use { it.readBytes() }
        }
        return Pacote(json, arquivos)
    }

    private fun lerImportado(id: String): Pacote {
        val pasta = File(pastaImportados, id)
        val arquivos = (File(pasta, "arquivos").listFiles { f -> f.isFile } ?: emptyArray()).associate { "arquivos/${it.name}" to it.readBytes() }
        return Pacote(File(pasta, "tema.json").readText(), arquivos)
    }

    private fun ler(id: String, embutido: Boolean) = if (embutido) lerEmbutido(id) else lerImportado(id)

    private fun famila(t: Tema, id: String, embutido: Boolean, p: Pacote): FontFamily {
        val arq = t.painel.fonte.app.arquivo
        if (arq != null) try {
            val bytes = p.arquivos[arq]!!
            // o Compose lê a fonte de um arquivo ou dos assets; a do tema vem dos bytes já lidos e validados
            val tmp = File(ctx.cacheDir, "fonte-$id.ttf").apply { writeBytes(bytes) }
            return FontFamily(Font(tmp))
        } catch (_: Exception) { /* fonte quebrada: cai na do sistema */ }
        return familiaDoSistema(t.painel.fonte.app.sistema)
    }

    private fun carregar(id: String, embutido: Boolean): TemaCarregado {
        val p = ler(id, embutido)
        val t = validador.validar(p.json, p.arquivos.keys)
        if (t.id != id) throw TemaInvalido("o id (${t.id}) é diferente do nome da pasta ($id)")
        return TemaCarregado(t, famila(t, id, embutido, p))
    }

    /** O tema ativo guardado; se faltar ou estiver inválido, o Padrão. */
    fun carregarAtivo(): TemaCarregado {
        val id = prefs.getString("tema", PADRAO) ?: PADRAO
        for (embutido in listOf(true, false)) {
            val existe = if (embutido) id in idsEmbutidos() else id in idsImportados()
            if (existe) try { return carregar(id, embutido) } catch (_: Exception) {}
        }
        return carregar(PADRAO, true)
    }

    val ativoId: String get() = prefs.getString("tema", PADRAO) ?: PADRAO

    /** Temas válidos instalados (os inválidos ficam de fora), embutidos primeiro. */
    fun listar(): List<ItemTema> {
        val out = mutableListOf<ItemTema>()
        for ((embutido, ids) in listOf(true to idsEmbutidos(), false to idsImportados())) for (id in ids) try {
            val p = ler(id, embutido)
            val t = validador.validar(p.json, p.arquivos.keys)
            if (t.id == id) out += ItemTema(id, t.nome, t.descricao.orEmpty(), t.autor.orEmpty(), embutido)
        } catch (_: Exception) {}
        return out
    }

    /** Ativa e guarda o tema; lança [TemaInvalido] (e mantém o anterior) se ele não puder ser carregado. */
    fun ativar(id: String): TemaCarregado {
        val c = try {
            carregar(id, id in idsEmbutidos())
        } catch (e: TemaInvalido) { throw e } catch (e: Exception) { throw TemaInvalido("não deu para ler o tema $id: ${e.message}") }
        prefs.edit().putString("tema", id).apply()
        return c
    }

    /** Lê o `.json` ou `.zip` escolhido pelo usuário, valida e instala em filesDir/temas/<id>/. Devolve o tema. */
    fun importar(uri: Uri): Tema {
        val limite = LimitesTema.TOTAL + LimitesTema.JSON + 1024
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { ent ->
            val b = ByteArray(limite + 1)
            var n = 0
            while (n <= limite) { val r = ent.read(b, n, b.size - n); if (r < 0) break; n += r }
            if (n > limite) throw TemaInvalido("arquivo grande demais para um tema")
            b.copyOf(n)
        } ?: throw TemaInvalido("não deu para abrir o arquivo")
        val pacote = if (ehZip(bytes)) lerPacoteZip(bytes.inputStream())
        else Pacote(try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw TemaInvalido("o arquivo não está em UTF-8") })
        val t = validador.validar(pacote.json, pacote.arquivos.keys)
        if (t.id in idsEmbutidos()) throw TemaInvalido("o id ${t.id} já é de um tema que vem com o app; troque o id")
        instalar(t.id, pacote)
        return t
    }

    private fun instalar(id: String, p: Pacote) {
        pastaImportados.mkdirs()
        val destino = File(pastaImportados, id)
        val tmp = File(pastaImportados, "$id.novo")
        tmp.deleteRecursively()
        try {
            File(tmp, "arquivos").mkdirs()
            File(tmp, "tema.json").writeText(p.json)
            for ((nome, b) in p.arquivos) {
                val f = File(tmp, nome)
                // defesa extra: o caminho final tem de ficar dentro da pasta do tema
                if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) throw TemaInvalido("caminho proibido: $nome")
                f.writeBytes(b)
            }
            destino.deleteRecursively()
            if (!tmp.renameTo(destino)) throw TemaInvalido("não deu para guardar o tema")
        } finally { tmp.deleteRecursively() }
    }

    /** Gera o .zip do tema e abre o menu de compartilhar do Android. */
    fun exportar(id: String) {
        val embutido = id in idsEmbutidos()
        val p = ler(id, embutido)
        val pasta = File(ctx.cacheDir, "temas").apply { mkdirs() }
        val zip = File(pasta, "$id.zip")
        zip.outputStream().use { escreverPacoteZip(it, p) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", zip)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Tema do painel do 306: $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(envio, "Exportar tema $id")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        const val ASSETS = "temas"
        const val PADRAO = "padrao"
    }
}
