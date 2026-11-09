package br.psascan.painel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import br.psascan.painel.ui.layout.Catalogo
import br.psascan.painel.ui.layout.GradeLayout
import br.psascan.painel.ui.layout.Layout
import br.psascan.painel.ui.layout.LayoutAusente
import br.psascan.painel.ui.layout.LayoutInvalido
import br.psascan.painel.ui.layout.LimitesLayout
import br.psascan.painel.ui.layout.ValidadorLayout
import br.psascan.painel.ui.layout.paraJson
import java.io.File

/** Um layout da lista (válido ou não), para o seletor. */
data class ItemLayout(
    val id: String, val nome: String, val descricao: String, val embutido: Boolean, val ativo: Boolean,
    val valido: Boolean, val erro: String?, val widgets: Int, val grade: GradeLayout?, val orientacao: String,
)

/** De onde vêm os layouts que acompanham o app (assets/layouts/<id>.json). */
interface ProntosLayout {
    fun ids(): List<String>
    fun ler(id: String): String
}

/** Onde se guarda o id do layout ativo (SharedPreferences no app). */
interface AtivoLayout {
    var id: String?
}

/**
 * Onde moram os layouts (docs/LAYOUT.md): os que vêm com o app em assets/layouts/, os do usuário em
 * filesDir/layouts/ e o id do ativo em SharedPreferences. Segue psascan/layouts.py (Repositorio). Ativo
 * ausente ou inválido → `painel`. O núcleo não depende do Android (dá para testar com uma pasta temporária);
 * só [importar] com Uri e [exportar] precisam do contexto.
 */
class LayoutRepositorio(
    val catalogo: Catalogo,
    private val prontos: ProntosLayout,
    private val pastaUsuario: File,
    private val ativoGuardado: AtivoLayout,
    private val ctx: Context? = null,
) {
    constructor(ctx: Context) : this(
        Catalogo.de(ctx.assets.open("widgets/catalogo.json").bufferedReader().use { it.readText() }),
        object : ProntosLayout {
            override fun ids() = (ctx.assets.list(ASSETS) ?: emptyArray()).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
            override fun ler(id: String) = ctx.assets.open("$ASSETS/$id.json").bufferedReader().use { it.readText() }
        },
        File(ctx.filesDir, "layouts"),
        object : AtivoLayout {
            private val prefs = ctx.getSharedPreferences("painel", Context.MODE_PRIVATE)
            override var id: String?
                get() = prefs.getString("layout", null)
                set(v) { prefs.edit().apply { if (v == null) remove("layout") else putString("layout", v) }.apply() }
        },
        ctx,
    )

    val validador = ValidadorLayout(catalogo)

    private fun idValido(id: String) = LimitesLayout.RE_ID.matches(id)
    private fun arquivoUsuario(id: String) = File(pastaUsuario, "$id.json")

    fun ehPronto(id: String) = idValido(id) && id in prontos.ids()

    private fun idsUsuario(): List<String> =
        (pastaUsuario.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }?.map { it.name.removeSuffix(".json") }
            ?: emptyList()).filter(::idValido).sorted()

    /** O layout [id] validado. [LayoutAusente] se não existe, [LayoutInvalido] se não passa. */
    fun layout(id: String): Layout {
        if (!idValido(id)) throw LayoutAusente(id)
        return conferirId(when {
            id in prontos.ids() -> validador.carregar(prontos.ler(id))
            arquivoUsuario(id).isFile -> validador.carregar(arquivoUsuario(id).readBytes())
            else -> throw LayoutAusente(id)
        }, id)
    }

    private fun conferirId(l: Layout, id: String): Layout {
        if (l.id != id) throw LayoutInvalido("id: ${l.id} é diferente do nome do arquivo ($id)")
        return l
    }

    /** Todos os layouts (os prontos primeiro), inclusive os inválidos, com o motivo. */
    fun listar(): List<ItemLayout> {
        val ativo = ativoId
        val out = mutableListOf<ItemLayout>()
        val vistos = HashSet<String>()
        for ((embutido, ids) in listOf(true to prontos.ids().sorted(), false to idsUsuario())) for (id in ids) {
            if (!vistos.add(id)) continue
            val base = ItemLayout(id, id, "", embutido, id == ativo, true, null, 0, null, "qualquer")
            out += try {
                val l = layout(id)
                base.copy(nome = l.nome, descricao = l.descricao.orEmpty(), widgets = l.widgets.size, grade = l.grade,
                    orientacao = l.orientacao ?: "qualquer")
            } catch (e: LayoutInvalido) { base.copy(valido = false, erro = e.motivo) }
        }
        return out
    }

    /** O id do layout ativo guardado (ou `painel`). Pode apontar para um layout que sumiu: ver [ativoComErro]. */
    val ativoId: String get() = ativoGuardado.id?.takeIf(::idValido) ?: LimitesLayout.PADRAO

    /** O ativo e, se ele sumiu ou é inválido, o motivo (aí devolve o `painel`). */
    fun ativoComErro(): Pair<Layout, String?> {
        val id = ativoId
        val motivo = try { return layout(id) to null }
        catch (_: LayoutAusente) { "o layout \"$id\" não existe mais" }
        catch (e: LayoutInvalido) { "o layout \"$id\" é inválido: ${e.motivo}" }
        return layout(LimitesLayout.PADRAO) to motivo
    }

    /** Ativa e guarda; recusa (e mantém o anterior) layout que não existe ou não passa na validação. */
    fun ativar(id: String): Layout = layout(id).also { ativoGuardado.id = id }

    /**
     * Valida e grava a cópia do usuário. Salvar com o id de um layout que vem com o app é recusado
     * ("escolha outro id"): os prontos nunca mudam. Com o id de um layout do próprio usuário, substitui o arquivo
     * dele (é assim que se edita).
     */
    fun salvar(layout: Layout): Layout {
        validador.validar(layout)
        if (ehPronto(layout.id)) throw LayoutInvalido(
            "id: \"${layout.id}\" já é um layout que vem com o app; escolha outro id (ex. ${layout.id.take(28)}-meu)")
        gravar(arquivoUsuario(layout.id), layout.paraJson().toByteArray(Charsets.UTF_8))
        return layout
    }

    /** Valida os bytes de um `.json` e grava em filesDir/layouts/. Devolve o layout. */
    fun importar(dados: ByteArray): Layout = salvar(validador.carregar(dados))

    /** Lê o `.json` escolhido no seletor de arquivos do Android. */
    fun importar(uri: Uri): Layout {
        val c = ctx ?: throw IllegalStateException("sem contexto")
        val limite = LimitesLayout.MAX_JSON + 1
        val bytes = c.contentResolver.openInputStream(uri)?.use { ent ->
            val b = ByteArray(limite)
            var n = 0
            while (n < limite) { val r = ent.read(b, n, limite - n); if (r < 0) break; n += r }
            b.copyOf(n)
        } ?: throw LayoutInvalido("não deu para abrir o arquivo")
        return importar(bytes)
    }

    /** Os bytes do `.json` do layout (pronto ou do usuário). */
    fun exportar(id: String): ByteArray = layout(id).paraJson().toByteArray(Charsets.UTF_8)

    /** Gera o `.json` e abre o menu de compartilhar do Android. */
    fun compartilhar(id: String) {
        val c = ctx ?: throw IllegalStateException("sem contexto")
        val pasta = File(c.cacheDir, "layouts").apply { mkdirs() }
        val arq = File(pasta, "$id.json").apply { writeBytes(exportar(id)) }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.arquivos", arq)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Layout do painel do 306: $id")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(envio, "Exportar layout $id")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Apaga um layout do usuário (os que vêm com o app, não). Se era o ativo, volta ao `painel`. */
    fun apagar(id: String) {
        if (ehPronto(id)) throw LayoutInvalido("id: \"$id\" vem com o app e não pode ser apagado")
        if (!idValido(id) || !arquivoUsuario(id).isFile) throw LayoutAusente(id)
        arquivoUsuario(id).delete()
        if (ativoGuardado.id == id) ativoGuardado.id = null
    }

    private fun gravar(destino: File, dados: ByteArray) {
        pastaUsuario.mkdirs()
        val tmp = File(pastaUsuario, ".tmp-${destino.name}")
        try {
            tmp.writeBytes(dados)
            if (!tmp.renameTo(destino)) { destino.delete(); if (!tmp.renameTo(destino)) throw LayoutInvalido("não deu para guardar o layout") }
        } finally { tmp.delete() }
    }

    companion object {
        const val ASSETS = "layouts"
    }
}
