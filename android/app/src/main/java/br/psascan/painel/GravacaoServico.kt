package br.psascan.painel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Serviço em primeiro plano só enquanto grava: segura o processo com a tela apagada ou o app em
 * segundo plano, e um wake lock parcial para a CPU não dormir entre uma leitura e outra. A leitura
 * em si continua na Conexao (thread do scanner); o serviço não faz nada além de existir.
 */
class GravacaoServico : Service() {
    private var trava: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = (application as App).nucleo
        val est = n.gravacao.estado.value
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Gravação", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mostrador)
            .setContentTitle("Gravando o 306")
            .setContentText(est.arquivo)
            .setWhen(est.inicioMs).setUsesChronometer(true).setShowWhen(true)
            .setOngoing(true).setContentIntent(abrir)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (e: Exception) {
            // Android 14+: o tipo "dispositivo conectado" exige a permissão do Bluetooth (no simulador pode faltar)
            n.gravacao.avisar("Sem gravação em segundo plano (${e.javaClass.simpleName}): mantenha o app aberto.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (trava == null) trava = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "painel306:gravacao").apply { acquire(12 * 3600_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        trava?.let { if (it.isHeld) it.release() }
        trava = null
        super.onDestroy()
    }

    companion object {
        private const val CANAL = "gravacao"
        private const val ID = 306
    }
}
