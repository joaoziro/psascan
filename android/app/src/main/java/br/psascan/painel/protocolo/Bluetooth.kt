package br.psascan.painel.protocolo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RFCOMM até o PSA COM (pareado antes nas configurações do Android, PIN 1234).
 * No Linux a conexão é direto no canal 1; aqui tenta primeiro o perfil serial padrão (SPP)
 * e, se o scanner não anunciar, cai para o canal 1 como no psascan.
 */
@SuppressLint("MissingPermission")   // quem chama já pediu BLUETOOTH_CONNECT
class BluetoothTransporte(adaptador: BluetoothAdapter, endereco: String, log: (String) -> Unit) : Transporte {
    private val socket: BluetoothSocket
    private val fila = LinkedBlockingQueue<ByteArray>()
    @Volatile private var fechado = false

    init {
        // Sem cancelDiscovery(): o app nunca procura aparelhos, e no Android 12+ essa chamada exige
        // BLUETOOTH_SCAN (que não declaramos) e lança SecurityException.
        if (!adaptador.isEnabled) throw ErroLink("Bluetooth do celular desligado")
        val dev = adaptador.getRemoteDevice(endereco)
        socket = conectar(dev, log)
        Thread({
            val buf = ByteArray(4096)
            try {
                val ent = socket.inputStream
                while (true) {
                    val n = ent.read(buf)
                    if (n < 0) break
                    if (n > 0) fila.put(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            fechado = true
            fila.put(FIM)
        }, "bt-leitura").apply { isDaemon = true }.start()
    }

    private fun conectar(dev: BluetoothDevice, log: (String) -> Unit): BluetoothSocket {
        val tentativas = listOf<Pair<String, () -> BluetoothSocket>>(
            "SPP" to { dev.createRfcommSocketToServiceRecord(SPP) },
            "SPP sem criptografia" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "canal 1" to {
                dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            },
        )
        var ultimo: Exception? = null
        for ((nome, criar) in tentativas) {
            var s: BluetoothSocket? = null
            try {
                s = criar()
                s.connect()
                log("Bluetooth conectado ($nome)")
                return s
            } catch (e: SecurityException) {
                throw ErroLink("sem permissão de dispositivos próximos; libere nas configurações do app")
            } catch (e: Exception) {
                try { s?.close() } catch (_: IOException) {}   // tentativa falha não pode deixar socket aberto
                log("Bluetooth ($nome) falhou: ${e.message}")
                ultimo = e
            }
        }
        throw ErroLink("não conectou ao scanner: ${ultimo?.message}")
    }

    override fun escrever(dados: ByteArray) {
        try {
            socket.outputStream.write(dados)
            socket.outputStream.flush()
        } catch (e: IOException) {
            throw ErroLink("conexão fechada pelo scanner: ${e.message}")
        }
    }

    override fun ler(timeoutMs: Long): ByteArray {
        val primeiro = (if (timeoutMs <= 0) fila.poll() else fila.poll(timeoutMs, TimeUnit.MILLISECONDS))
            ?: return ByteArray(0)
        var out = primeiro
        while (true) {
            if (out === FIM) { fila.put(FIM); throw ErroLink("conexão fechada pelo scanner") }
            val mais = fila.poll() ?: break
            if (mais === FIM) { fila.put(FIM); break }
            out += mais
        }
        return out
    }

    override fun fechar() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val FIM = ByteArray(0)
    }
}
