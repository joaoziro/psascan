package br.psascan.painel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.psascan.painel.protocolo.Dongle
import br.psascan.painel.protocolo.Sagem
import br.psascan.painel.protocolo.bytesHex
import br.psascan.painel.protocolo.hex
import br.psascan.painel.ui.Aparelho
import br.psascan.painel.ui.EstadoTema
import br.psascan.painel.ui.TemaInvalido
import br.psascan.painel.ui.Temas
import br.psascan.painel.ui.Cor
import br.psascan.painel.ui.BarraModos
import br.psascan.painel.ui.DiagnosticoTela
import br.psascan.painel.ui.Topo
import br.psascan.painel.ui.EstadoFalhas
import br.psascan.painel.ui.FalhasTela
import br.psascan.painel.ui.DialogoMarcar
import br.psascan.painel.ui.DialogoParar
import br.psascan.painel.ui.GravacoesTela
import br.psascan.painel.ui.layout.EstadoEdicao
import br.psascan.painel.ui.layout.EstadoLayouts
import br.psascan.painel.ui.layout.FalhasLidas
import br.psascan.painel.ui.layout.FonteDados
import br.psascan.painel.ui.layout.LayoutAusente
import br.psascan.painel.ui.layout.LayoutInvalido
import br.psascan.painel.ui.layout.Layout
import br.psascan.painel.ui.layout.TelaPersonalizavel
import br.psascan.painel.ui.layout.usaFalhas
import br.psascan.painel.ui.SIM
import br.psascan.painel.ui.ScannerTela
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado das telas; a conexão e a gravação ficam no [Nucleo], que vive além da tela. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val nucleo = (app as App).nucleo
    val perfil get() = nucleo.perfil
    val falhas = MutableStateFlow(EstadoFalhas())
    val gravacoes = MutableStateFlow(listOf<ArquivoGravado>())
    private val bt = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val temas = (app as App).temas
    val tema = MutableStateFlow(EstadoTema())

    init {
        nucleo.abriuTela()
        atualizarTemas()
    }

    private fun atualizarTemas(mensagem: String = "", erro: Boolean = false) {
        tema.value = EstadoTema(temas.listar(), temas.ativoId, mensagem, erro)
    }

    /** Troca o tema do app inteiro; um tema que não carrega é recusado e o atual continua. */
    fun ativarTema(id: String) {
        try {
            Temas.ativo = temas.ativar(id)
            atualizarTemas()
        } catch (e: TemaInvalido) { atualizarTemas("Tema recusado: ${e.motivo}", true) }
    }

    fun importarTema(uri: Uri) {
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { temas.importar(uri) }
                atualizarTemas("Tema \"${t.nome}\" importado.")
            } catch (e: TemaInvalido) {
                atualizarTemas("Tema recusado: ${e.motivo}", true)
            } catch (e: Exception) {
                atualizarTemas("Não deu para importar: ${e.message}", true)
            }
        }
    }

    fun exportarTema() {
        try { temas.exportar(tema.value.ativoId) }
        catch (e: Exception) { atualizarTemas("Não deu para exportar: ${e.message}", true) }
    }

    // ---- layouts da tela personalizável (ver LayoutRepositorio e ui/layout/TelaLayout.kt)
    private val layouts = (app as App).layouts
    val catalogo get() = layouts.catalogo
    val layout = MutableStateFlow(EstadoLayouts())
    /** A edição em curso (null = fora do modo edição); fica aqui para sobreviver à troca de modo do app. */
    val edicao = MutableStateFlow<EstadoEdicao?>(null)
    private var limparMensagem: Job? = null

    init { atualizarLayouts() }

    private fun atualizarLayouts(mensagem: String = "", erro: Boolean = false) {
        val (ativo, motivo) = layouts.ativoComErro()
        layout.value = if (mensagem.isEmpty() && motivo != null)
            EstadoLayouts(layouts.listar(), ativo, "Layout ativo não pôde ser usado: $motivo. O Painel padrão está no lugar.", true)
        else EstadoLayouts(layouts.listar(), ativo, mensagem, erro)
        agendarLimpeza()
    }

    private fun agendarLimpeza() {
        limparMensagem?.cancel()
        if (layout.value.mensagem.isEmpty()) return
        limparMensagem = viewModelScope.launch { delay(7000); layout.update { it.copy(mensagem = "", erro = false) } }
    }

    /** Um aviso curto na barra da tela (uma recusa, por exemplo). */
    fun avisarLayout(texto: String, erro: Boolean = true) {
        layout.update { it.copy(mensagem = texto, erro = erro) }
        agendarLimpeza()
    }

    /** Troca o layout ativo; um layout que não carrega é recusado e o atual continua. */
    fun ativarLayout(id: String) {
        try { layouts.ativar(id); atualizarLayouts() }
        catch (e: LayoutInvalido) { avisarLayout("Layout recusado: ${e.motivo}") }
        catch (e: LayoutAusente) { avisarLayout("Layout recusado: ${e.message}") }
    }

    fun importarLayout(uri: Uri) {
        viewModelScope.launch {
            try {
                val l = withContext(Dispatchers.IO) { layouts.importar(uri) }
                layouts.ativar(l.id)
                atualizarLayouts("Layout \"${l.nome}\" importado e ativado.")
            } catch (e: LayoutInvalido) { avisarLayout("Layout recusado: ${e.motivo}") }
            catch (e: Exception) { avisarLayout("Não deu para importar: ${e.message}") }
        }
    }

    fun exportarLayout(id: String) {
        try { layouts.compartilhar(id) } catch (e: Exception) { avisarLayout("Não deu para exportar: ${e.message}") }
    }

    fun apagarLayout(id: String) {
        try { layouts.apagar(id); atualizarLayouts("Layout apagado.", false) }
        catch (e: LayoutInvalido) { avisarLayout(e.motivo) }
        catch (e: LayoutAusente) { avisarLayout(e.message.orEmpty()) }
    }

    /** Salva a edição como layout do usuário e o ativa. Devolve o motivo da recusa, ou null se salvou. */
    fun salvarLayout(l: Layout): String? = try {
        layouts.salvar(l)
        layouts.ativar(l.id)
        edicao.value = null
        atualizarLayouts("Layout \"${l.nome}\" salvo e ativado.", false)
        null
    } catch (e: LayoutInvalido) { e.motivo }

    /** Lê as falhas uma vez para os widgets que as mostram (a leitura inclui só o que a central tem agora). */
    fun lerFalhasParaPainel() {
        val f = falhas.value
        if (f.lista == null && f.ocupado.isEmpty() && nucleo.conexao.value != null) lerFalhas()
    }

    fun escolher(endereco: String) {
        falhas.value = EstadoFalhas()
        nucleo.escolher(endereco)
    }

    @SuppressLint("MissingPermission")
    fun pareados(): List<Aparelho> = bt?.bondedDevices?.map { Aparelho(it.name ?: "sem nome", it.address) }
        ?.sortedByDescending { a -> NOMES_SCANNER.any { a.nome.contains(it, true) } } ?: emptyList()

    private val hora get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    fun lerFalhas(apagou: Boolean = false) = acao("lendo…") { c ->
        val l = c.lerFalhas()
        nucleo.gravacao.falhasDaTela("falhas", l)
        falhas.update { it.copy(lista = l, hora = hora, apagadas = apagou, erro = "") }
    }

    /** A chave da tela de Falhas: vale na tela e na Conexao, e volta a desligada ao reabrir o app. */
    fun permitirEscrita(sim: Boolean) {
        falhas.update { it.copy(escrita = sim) }
        nucleo.conexao.value?.escrita = sim
    }

    fun apagarFalhas() = acao("apagando…") { c ->
        c.escrita = falhas.value.escrita
        c.apagarFalhas()
        nucleo.gravacao.falhasDaTela("falhas_apagadas", null)
        falhas.update { it.copy(ocupado = "conferindo…") }
        val l = c.lerFalhas()
        nucleo.gravacao.falhasDaTela("falhas", l)
        falhas.update { it.copy(lista = l, hora = hora, apagadas = true, erro = "") }
    }

    fun atualizarGravacoes() = viewModelScope.launch {
        gravacoes.value = withContext(Dispatchers.IO) { try { nucleo.arquivos.listar() } catch (_: Exception) { emptyList() } }
    }

    private fun acao(texto: String, fn: suspend CoroutineScope.(Conexao) -> Unit) {
        val c = nucleo.conexao.value ?: run { falhas.update { it.copy(erro = "Escolha o scanner primeiro (botão SCANNER).") }; return }
        falhas.update { it.copy(ocupado = texto, erro = "") }
        viewModelScope.launch {
            try { fn(c) } catch (e: Exception) {
                falhas.update { it.copy(erro = "Não deu certo: ${e.message}. Confira a ignição e o scanner.") }
            } finally { falhas.update { it.copy(ocupado = "") } }
        }
    }

    override fun onCleared() { nucleo.fechouTela() }

    companion object {
        // o PSA COM se anuncia como "autoscan&programming" (visto num S23); os outros por garantia
        val NOMES_SCANNER = listOf("autoscan", "PSA", "COM")
    }
}

class MainActivity : ComponentActivity() {
    private var permitido by mutableStateOf(false)
    private var retomadas by mutableStateOf(0)   // volta das configurações: relê os pareados
    private val pedir = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permitido = it }
    private val modelo: AppModel by viewModels()
    /** O seletor de arquivos do Android para "Importar tema" (.json ou .zip). */
    private val escolherTema = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { modelo.importarTema(it) }
    }
    /** O seletor de arquivos do Android para "Importar layout" (.json). */
    private val escolherLayout = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { modelo.importarLayout(it) }
    }
    /** A notificação da gravação (Android 13+); gravando sem ela também funciona, só não aparece. */
    private val pedirNotificacao = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        (application as App).nucleo.iniciarGravacao()
    }

    private fun gravar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("painel", MODE_PRIVATE).getBoolean("pediu_notif", false)) {
            getSharedPreferences("painel", MODE_PRIVATE).edit().putBoolean("pediu_notif", true).apply()
            pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else (application as App).nucleo.iniciarGravacao()
    }

    private fun temPermissao() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Depois de duas recusas o Android não mostra mais o pedido e launch() volta "negado" na hora;
     * aí o único caminho é a tela do app nas configurações.
     */
    private fun pedirPermissao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val prefs = getSharedPreferences("painel", MODE_PRIVATE)
        if (prefs.getBoolean("pediu_bt", false) && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        prefs.edit().putBoolean("pediu_bt", true).apply()
        pedir.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun onResume() {
        super.onResume()
        permitido = temPermissao()   // pode ter voltado das configurações com a permissão liberada
        retomadas++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // painel não apaga dirigindo
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        permitido = temPermissao()
        // os bytes que o APAGAR manda ao scanner, vindos do perfil (null = o perfil não tem o comando)
        val perfil = (application as App).nucleo.perfil
        val bytesApagar = perfil.apagarFalhas?.let { e ->
            (e.antes + e.pedido).joinToString("\n") { Dongle.frame(Sagem.paraDongle(it.bytesHex())).hex() }
        }

        setContent {
            val m: AppModel = viewModel()
            val tema by m.tema.collectAsStateWithLifecycle()
            var tela by androidx.compose.runtime.remember { mutableStateOf(if (m.nucleo.escolhido.value == null) "scanner" else "painel") }
            val escolhido by m.nucleo.escolhido.collectAsStateWithLifecycle()
            val c by m.nucleo.conexao.collectAsStateWithLifecycle()
            val grav by m.nucleo.gravacao.estado.collectAsStateWithLifecycle()
            val gravacoes by m.gravacoes.collectAsStateWithLifecycle()
            var marca by androidx.compose.runtime.remember { mutableStateOf<Instante?>(null) }
            var confirmarParar by androidx.compose.runtime.remember { mutableStateOf(false) }
            val status = c?.status?.collectAsStateWithLifecycle()?.value ?: Status()
            val log = c?.log?.collectAsStateWithLifecycle()?.value ?: emptyList()
            val falhas by m.falhas.collectAsStateWithLifecycle()
            val diag = c?.diag?.collectAsStateWithLifecycle()?.value ?: Diagnostico()
            val lay by m.layout.collectAsStateWithLifecycle()
            val edicao by m.edicao.collectAsStateWithLifecycle()
            val falhasLidas = androidx.compose.runtime.remember(falhas.lista, falhas.hora) {
                falhas.lista?.let { l -> FalhasLidas(l.filter { it.presente }, l.filter { !it.presente }, falhas.hora) }
            }
            val usaFalhas = (edicao?.atual ?: lay.ativo)?.usaFalhas == true
            androidx.compose.runtime.LaunchedEffect(tela, status.link, usaFalhas) {
                if (tela == "painel" && usaFalhas && status.link == EstadoLink.CONECTADO) m.lerFalhasParaPainel()
            }
            c?.completo = tela == "diagnostico"
            BackHandler(tela != "painel") { tela = "painel" }
            Box(Modifier.fillMaxSize().background(Cor.fundo)) {
                if (tela == "scanner") {
                    // lista lida ao abrir a tela, não a cada recomposição (o log redesenha várias vezes por segundo)
                    val pareados = androidx.compose.runtime.remember(permitido, retomadas) { if (permitido) m.pareados() else null }
                    ScannerTela(pareados, escolhido, status, log, tema,
                        aoAtivarTema = m::ativarTema,
                        aoImportarTema = { escolherTema.launch(arrayOf("application/json", "application/zip",
                            "application/x-zip-compressed", "application/octet-stream", "text/plain")) },
                        aoExportarTema = m::exportarTema,
                        aoEscolher = { e ->
                            if (e != SIM && !permitido) pedirPermissao()
                            else { m.escolher(e); tela = "painel" }
                        }, aoPermitir = ::pedirPermissao) { tela = "painel" }
                } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Topo(status, escolhido == SIM, grav)
                    val miolo = Modifier.weight(1f).padding(vertical = 8.dp)
                    when (tela) {
                        "diagnostico" -> DiagnosticoTela(diag, m.perfil, miolo)
                        "falhas" -> FalhasTela(falhas, bytesApagar, { m.lerFalhas() }, m::apagarFalhas, m::permitirEscrita, miolo)
                        "gravacoes" -> GravacoesTela(grav, gravacoes, m.nucleo.arquivos.onde, m.nucleo::trocarModo,
                            { a -> m.nucleo.arquivos.compartilhar(a) }, { m.atualizarGravacoes() }, miolo)
                        else -> TelaPersonalizavel(lay, FonteDados(m.perfil, m.catalogo, diag, falhasLidas) { o, seg ->
                            c?.historico(o, seg) ?: emptyList()
                        }, edicao,
                            aoAtivar = m::ativarLayout,
                            aoImportar = { escolherLayout.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                            aoExportar = m::exportarLayout, aoApagar = m::apagarLayout,
                            aoEdicao = { m.edicao.value = it }, aoSalvar = m::salvarLayout, aoAviso = { m.avisarLayout(it) },
                            modifier = miolo)
                    }
                    BarraModos(tela, escolhido == SIM, grav, aoModo = { novo ->
                        tela = novo
                        if (novo == "falhas" && falhas.lista == null) m.lerFalhas()
                    }, aoScanner = { tela = "scanner" }, aoGravar = ::gravar,
                        aoMarcar = { marca = Instante.agora() }, aoParar = { confirmarParar = true })
                }
                marca?.let { q ->
                    DialogoMarcar(grav.marcas + 1, aoMarcar = { nota -> m.nucleo.gravacao.marcar(nota, q); marca = null },
                        aoCancelar = { marca = null })
                }
                if (confirmarParar) DialogoParar(grav, aoParar = { confirmarParar = false; m.nucleo.pararGravacao() },
                    aoCancelar = { confirmarParar = false })
            }
        }
    }
}
