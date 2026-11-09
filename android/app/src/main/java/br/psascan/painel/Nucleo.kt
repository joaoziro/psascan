package br.psascan.painel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import br.psascan.painel.protocolo.Perfil
import br.psascan.painel.protocolo.BluetoothTransporte
import br.psascan.painel.protocolo.ErroLink
import br.psascan.painel.protocolo.SimTransporte
import br.psascan.painel.ui.SIM
import br.psascan.painel.ui.Temas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class App : Application() {
    val nucleo by lazy { Nucleo(this) }
    val temas by lazy { TemaRepositorio(this) }
    val layouts by lazy { LayoutRepositorio(this) }

    override fun onCreate() {
        super.onCreate()
        Temas.ativo = temas.carregarAtivo()   // antes de qualquer tela: toda a UI lê o tema ativo
    }
}

/**
 * O que vive no processo, fora da tela: o scanner escolhido, a conexão e a gravação. A tela pode
 * fechar no meio de uma gravação (o serviço em primeiro plano segura o processo); a conexão só é
 * desligada quando não há tela aberta nem gravação em curso.
 */
class Nucleo(private val app: Application) {
    private val prefs = app.getSharedPreferences("painel", Context.MODE_PRIVATE)
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val perfil = Perfil.de(app.assets.open("perfil.json").bufferedReader().use { it.readText() })
    val arquivos = Arquivos(app)
    private val bt = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _escolhido = MutableStateFlow(prefs.getString("scanner", null))
    val escolhido: StateFlow<String?> = _escolhido.asStateFlow()
    private val _conexao = MutableStateFlow<Conexao?>(null)
    val conexao: StateFlow<Conexao?> = _conexao.asStateFlow()

    val gravacao = Gravacao(perfil, escopo, abrir = { ms ->
        val a = arquivos.criar(ms)
        Destino(a.nome, a.saida, a::sincronizar, a::fechar)
    }, aoMudar = ::servico).also { g ->
        prefs.getString("modo", null)?.let { m -> ModoLeitura.entries.firstOrNull { it.name == m } }?.let(g::trocarModo)
    }

    private var telas = 0

    fun abriuTela() {
        telas++
        if (_conexao.value == null) _conexao.value = criar(_escolhido.value)
    }

    fun fechouTela() {
        telas--
        soltarSeSobrando()
    }

    private fun soltarSeSobrando() {
        if (telas > 0 || gravacao.ativa) return
        _conexao.value?.desligar()
        _conexao.value = null
    }

    private fun criar(endereco: String?): Conexao? = endereco?.let { e ->
        Conexao(perfil, escopo) { log ->
            if (e == SIM) SimTransporte(perfil)
            else BluetoothTransporte(bt ?: throw ErroLink("este aparelho não tem Bluetooth"), e, log)
        }.also { it.ligar() }
    }

    /** Trocar de scanner encerra a gravação em curso (ela é de um carro/scanner só). */
    fun escolher(endereco: String) = escopo.launch {
        if (gravacao.ativa) gravacao.parar("troca de scanner")
        prefs.edit().putString("scanner", endereco).apply()
        _conexao.value?.desligar()
        _escolhido.value = endereco
        _conexao.value = criar(endereco)
    }

    fun iniciarGravacao() {
        val c = _conexao.value ?: run { gravacao.avisar("Escolha o scanner primeiro (botão SCANNER)."); return }
        val versao = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
        gravacao.iniciar(c, "scanner ${if (_escolhido.value == SIM) "simulador" else _escolhido.value}; app $versao")
    }

    fun pararGravacao() = escopo.launch {
        gravacao.parar()
        soltarSeSobrando()
    }

    fun trocarModo(m: ModoLeitura) {
        prefs.edit().putString("modo", m.name).apply()
        gravacao.trocarModo(m)
    }

    private fun servico(ativa: Boolean) {
        val i = Intent(app, GravacaoServico::class.java)
        if (!ativa) { app.stopService(i); return }
        // Android 14+: o tipo "dispositivo conectado" só vale com a permissão do Bluetooth (no simulador pode faltar)
        if (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            gravacao.avisar("Sem a permissão do Bluetooth não dá para gravar em segundo plano: mantenha o app aberto.")
            return
        }
        try {
            ContextCompat.startForegroundService(app, i)
        } catch (e: Exception) {   // Android 12+ recusa se o app não estiver na frente; aqui sempre está
            gravacao.avisar("Sem gravação em segundo plano (${e.message}): mantenha o app aberto.")
        }
    }
}
