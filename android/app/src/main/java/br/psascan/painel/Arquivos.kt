package br.psascan.painel

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Uma gravação já salva. tamanho pode vir 0 enquanto o Android não atualiza o índice. */
data class ArquivoGravado(val nome: String, val uri: Uri, val tamanho: Long, val modificadoMs: Long)

/** Arquivo aberto para gravar: o stream e como forçar a ida ao disco (fsync). */
class Aberto(val uri: Uri, val nome: String, val saida: FileOutputStream, private val pfd: ParcelFileDescriptor? = null) {
    fun sincronizar() = try { saida.fd.sync() } catch (_: Exception) {}

    /** O FileOutputStream sobre o descritor do MediaStore não fecha o descritor sozinho. */
    fun fechar() {
        try { saida.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}

/**
 * Onde ficam os CSV. Android 10+: Downloads/PSA pelo MediaStore (sem permissão, aparece no
 * gerenciador de arquivos e no computador pelo USB). Android 8 e 9: a pasta do app em
 * Android/data/br.psascan.painel/files/PSA, para não pedir a permissão de armazenamento.
 * Compartilhar abre o menu do Android (WhatsApp, Drive, e-mail…).
 */
class Arquivos(private val ctx: Context) {
    private val novo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    private val pastaAntiga get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, PASTA).apply { mkdirs() }

    val onde: String get() = if (novo) "Downloads/$PASTA" else pastaAntiga.absolutePath

    fun criar(inicioMs: Long): Aberto {
        val existentes = listar().map { it.nome }.toSet()
        val nome = Gravador.nomeArquivo(inicioMs) { it in existentes }
        if (!novo) {
            val f = File(pastaAntiga, nome)
            return Aberto(Uri.fromFile(f), nome, FileOutputStream(f))
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PASTA")
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("o Android não deixou criar o arquivo em Downloads")
        val pfd = r.openFileDescriptor(uri, "w") ?: throw IllegalStateException("não abriu $nome")
        // o nome pode ter mudado se já havia um igual que não é deste app
        val real = r.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: nome
        return Aberto(uri, real, FileOutputStream(pfd.fileDescriptor), pfd)
    }

    /** Só os arquivos deste app (sem permissão de leitura, o MediaStore só mostra os nossos). */
    fun listar(): List<ArquivoGravado> {
        if (!novo) return (pastaAntiga.listFiles { f -> f.name.endsWith(".csv") } ?: emptyArray())
            .map { ArquivoGravado(it.name, Uri.fromFile(it), it.length(), it.lastModified()) }
            .sortedByDescending { it.nome }
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        val out = mutableListOf<ArquivoGravado>()
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$PASTA/"),
            "${MediaStore.MediaColumns.DISPLAY_NAME} DESC")?.use { c ->
            while (c.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                out += ArquivoGravado(c.getString(1), uri, c.getLong(2), c.getLong(3) * 1000)
            }
        }
        return out
    }

    fun compartilhar(a: ArquivoGravado) {
        val uri = if (a.uri.scheme == "file")
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", File(a.uri.path!!)) else a.uri
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Gravação do 306: ${a.nome}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(envio, "Compartilhar ${a.nome}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    companion object {
        const val PASTA = "PSA"
    }
}
