"""Converte uma captura Bluetooth (btsnoop do `btmon -w`) no formato de captura da psascan.

  python3 btsnoop_para_jsonl.py entrada.btsnoop saida.jsonl

Guarda só o conteúdo do canal serial (RFCOMM) nos dois sentidos, com o tempo relativo:
nada de endereços Bluetooth, nomes de aparelho ou dados do computador. O resultado abre
com ver_captura.py como qualquer captura da própria ferramenta (tx = do computador para o
scanner, rx = do scanner para o computador).

Aceita o formato "monitor do Linux" (2001, o que o btmon grava) e o H4 (1002).
"""
import json, struct, sys


def pacotes_rfcomm(caminho):
    """Gera (t_segundos, 'tx'|'rx', bytes) com o conteúdo de cada quadro RFCOMM de dados."""
    with open(caminho, 'rb') as f:
        b = f.read()
    if b[:8] != b'btsnoop\0':
        raise ValueError('não é um arquivo btsnoop')
    enlace = struct.unpack('>I', b[12:16])[0]
    i, parcial, t0 = 16, {}, None
    while i + 24 <= len(b):
        _, tam, flags, _, ts = struct.unpack('>IIIIq', b[i:i + 24])
        reg, i = b[i + 24:i + 24 + tam], i + 24 + tam
        if enlace == 2001:              # monitor do Linux: opcode 4 = ACL enviado, 5 = ACL recebido
            op = flags & 0xFFFF
            if op not in (4, 5):
                continue
            sentido = 'rx' if op == 5 else 'tx'
        elif enlace == 1002:            # H4: 1º byte = tipo (2 = ACL); bit 0 do flags = recebido
            if not reg or reg[0] != 2:
                continue
            reg, sentido = reg[1:], 'rx' if flags & 1 else 'tx'
        else:
            raise ValueError('tipo de enlace %d não suportado' % enlace)
        if len(reg) < 4:
            continue
        cab, n = struct.unpack('<HH', reg[:4])
        acl = reg[4:4 + n]
        # pedaço de continuação (bits 12-13 = 01) se junta ao anterior do mesmo sentido
        parcial[sentido] = parcial.get(sentido, b'') + acl if (cab >> 12) & 3 == 1 else acl
        l2 = parcial[sentido]
        if len(l2) < 4:
            continue
        tam_l2, canal = struct.unpack('<HH', l2[:4])
        if len(l2) - 4 < tam_l2:
            continue                    # o pacote L2CAP ainda não chegou inteiro
        pdu, parcial[sentido] = l2[4:4 + tam_l2], b''
        if canal < 0x40 or len(pdu) < 4:
            continue                    # canais fixos (sinalização), não dados
        endereco, controle = pdu[0], pdu[1]
        if controle & 0xEF != 0xEF or endereco >> 2 == 0:
            continue                    # só UIH de dados (DLCI ≠ 0)
        if pdu[2] & 1:
            tam_d, k = pdu[2] >> 1, 3
        else:
            tam_d, k = (pdu[2] >> 1) | (pdu[3] << 7), 4
        if controle == 0xFF:            # UIH com crédito: 1 byte a mais antes dos dados
            k += 1
        dados = pdu[k:k + tam_d]
        if dados:
            t0 = ts if t0 is None else t0
            yield (ts - t0) / 1e6, sentido, bytes(dados)


def converter(entrada, saida):
    n = 0
    with open(saida, 'w', encoding='utf-8') as f:
        f.write(json.dumps({'t': 0.0, 'dir': 'nota', 'texto': 'convertido de captura Bluetooth: só o canal serial'},
                           ensure_ascii=False) + '\n')
        for t, sentido, dados in pacotes_rfcomm(entrada):
            f.write(json.dumps({'t': round(t, 4), 'dir': sentido, 'hex': dados.hex(' ')}) + '\n')
            n += 1
    return n


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print('%d pacotes' % converter(sys.argv[1], sys.argv[2]))
