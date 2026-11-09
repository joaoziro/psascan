"""Cliente do scanner Bluetooth "PSA COM" (protocolo visto nas capturas: ver docs/PROTOCOLO-SL96.md).

Envelope de cada quadro, nos dois sentidos:
    [0x80 | 0xA0+len_hi] [len_lo] payload... [crc_lo] [crc_hi]
CRC-16/X-25 (polinômio refletido 0x8408, init 0xFFFF, complemento final)
calculado sobre cabeçalho + payload. payload[0] (< 0x80) é o comando do dongle;
na resposta payload[0] == 0x7F indica erro.
"""
import json, os, re, select, socket, termios, time

CMD_VERSAO = 0x18   # versão/estado do dongle; não fala com a central
CMD_LINHA = 0x07    # configura a linha K
CMD_ECU = 0x04      # 04 <modo> <len> <bytes>: envia à central e devolve a resposta


def caminho_captura(pasta=None):
    pasta = pasta or os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'capturas')
    os.makedirs(pasta, exist_ok=True)
    return os.path.join(pasta, time.strftime('%Y%m%d-%H%M%S') + '.jsonl')


def crc16_x25(data):
    c = 0xFFFF
    for b in data:
        for i in range(8):
            if (b >> i ^ c) & 1:
                c ^= 0x10810
            c >>= 1
    c = ~c & 0xFFFF
    return bytes([c & 0xFF, c >> 8])


def frame(payload, resposta=False):
    payload = bytes(payload)
    n = len(payload)
    if not 0 < n <= 0x1FFF or (payload[0] >= 0x80 and not resposta):
        raise ValueError('payload inválido')
    hdr = bytes([0x80, n]) if n <= 0xFF else bytes([0xA0 | (n >> 8), n & 0xFF])
    return hdr + payload + crc16_x25(hdr + payload)


def unframe(buf):
    """Devolve (payload, resto) ou (None, buf) se o quadro ainda não chegou inteiro."""
    if len(buf) < 2:
        return None, buf
    if buf[0] != 0x80 and buf[0] & 0xF0 != 0xA0:
        raise ValueError('cabeçalho inválido: %s' % buf[:4].hex(' '))
    n = (buf[0] & 0x1F) << 8 | buf[1]
    if len(buf) < n + 4:
        return None, buf
    q = buf[:n + 4]
    if crc16_x25(q[:n + 2]) != q[n + 2:]:
        raise ValueError('CRC inválido: %s' % q.hex(' '))
    return q[2:n + 2], buf[n + 4:]


MAC = re.compile(r'^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$')


class Captura:
    """Arquivo .jsonl com cada write/read cru (t = segundos desde o início,
    dir = tx/rx/descartado/nota). Sobrevive às reconexões."""

    def __init__(self, caminho):
        self.caminho = caminho
        self.f = open(caminho, 'a', buffering=1)
        self.t0 = time.monotonic()

    def __call__(self, dir, dados=b'', **extra):
        r = {'t': round(time.monotonic() - self.t0, 4), 'hora': time.strftime('%H:%M:%S'), 'dir': dir}
        if dados:
            r['hex'] = bytes(dados).hex(' ')
        r.update(extra)
        self.f.write(json.dumps(r, ensure_ascii=False) + '\n')

    def close(self):
        self.f.close()


class Dongle:
    def __init__(self, port='/dev/rfcomm0', log=print, captura=None, canal=1, timeout_conexao=10):
        """port: endereço Bluetooth (AA:BB:CC:DD:EE:FF) — conecta direto por RFCOMM,
        sem `rfcomm bind` nem sudo — ou um caminho de tty (/dev/rfcomm0, pty do sim).
        captura: objeto Captura, caminho de .jsonl, ou None."""
        self.log = log
        self._cap_propria = isinstance(captura, str)
        self.cap = Captura(captura) if self._cap_propria else captura
        self.sock = None
        if MAC.match(port):
            self.sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            self.sock.settimeout(timeout_conexao)
            try:
                self.sock.connect((port, canal))
            except OSError:
                self.sock.close()
                raise
            self.sock.setblocking(True)
            self.fd = self.sock.fileno()
        else:
            self.fd = os.open(port, os.O_RDWR | os.O_NOCTTY)
            a = termios.tcgetattr(self.fd)
            a[0] = a[1] = a[3] = 0                      # raw
            a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
            a[4] = a[5] = termios.B115200
            termios.tcsetattr(self.fd, termios.TCSANOW, a)
        self._cap('nota', texto='aberto %s' % port)
        self._descartar_pendente()
        self.buf = b''

    def _cap(self, dir, dados=b'', **extra):
        if self.cap:
            self.cap(dir, dados, **extra)

    def nota(self, texto):
        self._cap('nota', texto=texto)

    def _descartar_pendente(self):
        # em vez de tcflush: lê e grava o que estiver sobrando no buffer
        while select.select([self.fd], [], [], 0)[0]:
            b = os.read(self.fd, 4096)
            if not b:
                break
            self._cap('descartado', b)

    def close(self):
        self._cap('nota', texto='fechado')
        try:
            if self.sock:
                self.sock.close()
            else:
                os.close(self.fd)
        except OSError:
            pass
        if self.cap and self._cap_propria:
            self.cap.close()

    def transact(self, payload, timeout=2.0, ok_7f00=False):
        """ok_7f00: aceita `7F <cmd> 00` como sucesso (assim o dongle confirma 13/07; visto nas capturas)."""
        payload = bytes(payload)
        q = frame(payload)
        self._descartar_pendente()
        self.buf = b''
        self.log('TX: ' + q.hex(' '))
        self._cap('tx', q)
        os.write(self.fd, q)
        end = time.monotonic() + timeout
        while True:
            try:
                p, self.buf = unframe(self.buf)
            except ValueError as e:
                self._cap('nota', texto='quadro inválido: %s' % e)
                raise
            if p is not None:
                self.log('RX: ' + frame(p, True).hex(' '))
                if ok_7f00 and len(p) > 2 and p[0] == 0x7F and p[1] == payload[0] and p[2] == 0:
                    return p
                if p[0] == 0x7F or p[0] != payload[0] + 0x80:
                    raise IOError('dongle respondeu erro: %s' % p.hex(' '))
                return p
            left = end - time.monotonic()
            if left <= 0:
                self._cap('nota', texto='timeout', pendente=self.buf.hex(' '))
                raise TimeoutError('sem resposta (recebido: %s)' % self.buf.hex(' '))
            if select.select([self.fd], [], [], left)[0]:
                b = os.read(self.fd, 4096)
                if not b:
                    self._cap('nota', texto='conexão fechada pelo scanner')
                    raise ConnectionError('conexão fechada pelo scanner')
                self._cap('rx', b)
                self.buf += b

    def versao(self):
        p = self.transact([CMD_VERSAO], 1.0)
        return {'hw': p[1] if len(p) > 1 else None,
                'estado': p[2] if len(p) > 2 else None,
                'firmware': '%d.%d' % (p[3], p[4]) if len(p) > 4 else None,
                'bruto': p.hex(' ')}


if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == '--enviar':
        d = Dongle(sys.argv[2] if len(sys.argv) > 2 else '/dev/rfcomm0')
        try:
            print(d.versao())
        finally:
            d.close()
    else:
        print('quadro de versão (só para o dongle):', frame([CMD_VERSAO]).hex(' '))
        print('use --enviar [/dev/rfcomm0] para mandar de verdade')
