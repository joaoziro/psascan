package br.psascan.painel.ui.layout

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import br.psascan.painel.ui.FONTE_PAINEL
import br.psascan.painel.ui.caminhoDeArquivoValido
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imagens e fontes das peles (docs/TEMA.md, painel.peles): arquivos da pasta `arquivos/` do tema ativo, lidos de
 * assets/temas/<id>/arquivos/ (embutidos) ou filesDir/temas/<id>/arquivos/ (importados), com cache.
 * Os caminhos vêm do tema já validado ("arquivos/<nome>.<ext>"); aqui se confere de novo, por segurança.
 *
 * Nos widgets, use [rememberImagemTema] (imagem, lida fora da thread da tela e guardada em cache) e
 * [rememberFontePele] (fonte `.ttf` da pele, ou a do tema se a pele não trouxer).
 */
object ArquivosTema {
    private val imagens = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(chave: String, v: ImageBitmap) = v.asAndroidBitmap().byteCount
    }
    private val fontes = HashMap<String, FontFamily?>()

    private fun importado(ctx: Context, temaId: String, caminho: String) = File(ctx.filesDir, "temas/$temaId/$caminho")

    /** Chave do cache; muda se o tema importado for substituído (o arquivo ganha outra data). */
    private fun chave(ctx: Context, temaId: String, caminho: String): String {
        val f = importado(ctx, temaId, caminho)
        return if (f.isFile) "$temaId/$caminho@${f.lastModified()}" else "$temaId/$caminho"
    }

    /** Os bytes do arquivo do tema, ou null se não existe (ou o caminho não é permitido). Bloqueia: não chamar na thread da tela. */
    fun bytes(ctx: Context, temaId: String, caminho: String): ByteArray? {
        if (!caminhoDeArquivoValido(caminho)) return null
        val f = importado(ctx, temaId, caminho)
        return try {
            if (f.isFile) f.readBytes() else ctx.assets.open("temas/$temaId/$caminho").use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    /** A imagem já em cache, sem ler nada. */
    fun imagemEmCache(ctx: Context, temaId: String, caminho: String): ImageBitmap? =
        synchronized(imagens) { imagens.get(chave(ctx, temaId, caminho)) }

    /** Decodifica a imagem (e guarda no cache). Bloqueia: chamar fora da thread da tela. */
    fun imagem(ctx: Context, temaId: String, caminho: String): ImageBitmap? {
        val k = chave(ctx, temaId, caminho)
        synchronized(imagens) { imagens.get(k) }?.let { return it }
        val b = bytes(ctx, temaId, caminho) ?: return null
        val bmp = try { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } catch (_: Exception) { null } ?: return null
        synchronized(imagens) { imagens.put(k, bmp) }
        return bmp
    }

    /** A fonte `.ttf` do tema como família do Compose (null se faltar ou estiver quebrada). Pequena: pode ser chamada na composição. */
    fun fonte(ctx: Context, temaId: String, caminho: String): FontFamily? {
        val k = chave(ctx, temaId, caminho)
        synchronized(fontes) {
            if (k in fontes) return fontes[k]
            val f = try {
                val b = bytes(ctx, temaId, caminho)
                if (b == null) null else {
                    // o Compose lê a fonte de um arquivo; os bytes (já validados) vão para a pasta de cache
                    val tmp = File(ctx.cacheDir, "pele-${k.hashCode().toUInt()}-${caminho.substringAfterLast('/')}")
                    if (!tmp.isFile || tmp.length() != b.size.toLong()) tmp.writeBytes(b)
                    FontFamily(Font(tmp))
                }
            } catch (_: Exception) { null }
            fontes[k] = f
            return f
        }
    }
}

/** A imagem da pele (`caminho` = "arquivos/x.png"); null enquanto carrega, ou se [caminho] é null ou o arquivo não existe. */
@Composable
fun rememberImagemTema(temaId: String, caminho: String?): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val v by produceState(caminho?.let { ArquivosTema.imagemEmCache(ctx, temaId, it) }, temaId, caminho) {
        value = if (caminho == null) null else withContext(Dispatchers.IO) { ArquivosTema.imagem(ctx, temaId, caminho) }
    }
    return v
}

/** A fonte `.ttf` da pele; sem arquivo (ou se ele falhar) vale a fonte do tema. */
@Composable
fun rememberFontePele(temaId: String, caminho: String?): FontFamily {
    val ctx = LocalContext.current.applicationContext
    val f = remember(temaId, caminho) { caminho?.let { ArquivosTema.fonte(ctx, temaId, it) } }
    return f ?: FONTE_PAINEL
}
