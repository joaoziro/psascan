"""Mostra uma captura (.jsonl) com os quadros remontados e anotados.

  python3 ver_captura.py capturas/20261021-101500.jsonl
"""
import json, sys

import dongle, psa5

CMDS = {0x18: 'versão', 0x07: 'linha K', 0x04: 'central', 0x13: 'linha K no pino', 0x28: 'destravar'}


def anota(p, tx):
    c = p[0] & 0x7F if not tx else p[0]
    s = CMDS.get(c, 'cmd %02X' % c)
    if p[0] == 0x7F:
        if len(p) > 2 and p[2] == 0:      # 7F <cmd> 00 é o "ok" do dongle para 13 e 07 (visto nas capturas)
            return 'ok do dongle (%s)' % CMDS.get(p[1], 'cmd %02X' % p[1])
        return 'ERRO do dongle'
    if c == 0x04:
        try:
            q = p[3:] if tx else p[1:]
            d = psa5.desquadro(q, base=0x80 if tx else 0xC0)
            return '%s %s dados=[%s]' % (s, 'pedido' if tx else 'resposta', d.hex(' '))
        except (ValueError, IndexError) as e:
            return '%s (quadro da central inválido: %s)' % (s, e)
    return s


def quadros(caminho):
    """Captura remontada em itens {'t', 'tipo', ...}:
    nota (texto, pendente), descartado (hex), quadro (dir, hex, anot), invalido (dir, erro, hex), sobra (dir, hex)."""
    bufs = {'tx': b'', 'rx': b''}
    out = []
    with open(caminho, encoding='utf-8') as f:
        linhas = f.readlines()
    for linha in linhas:
        r = json.loads(linha)
        d = r['dir']
        if d in ('nota',):
            out.append({'t': r['t'], 'hora': r.get('hora'), 'tipo': 'nota', 'texto': r.get('texto', ''),
                        'pendente': r.get('pendente')})
            continue
        if d == 'descartado':
            out.append({'t': r['t'], 'hora': r.get('hora'), 'tipo': 'descartado', 'hex': r['hex']})
            continue
        bufs[d] += bytes.fromhex(r['hex'])
        while True:
            try:
                p, bufs[d] = dongle.unframe(bufs[d])
            except ValueError as e:
                out.append({'t': r['t'], 'hora': r.get('hora'), 'tipo': 'invalido', 'dir': d, 'erro': str(e),
                            'hex': bufs[d].hex(' ')})
                bufs[d] = b''
                break
            if p is None:
                break
            out.append({'t': r['t'], 'hora': r.get('hora'), 'tipo': 'quadro', 'dir': d,
                        'hex': dongle.frame(p, True).hex(' '), 'anot': anota(p, d == 'tx')})
    for d, b in bufs.items():
        if b:
            out.append({'t': None, 'tipo': 'sobra', 'dir': d, 'hex': b.hex(' ')})
    return out


def main(caminho):
    for q in quadros(caminho):
        k = q['tipo']
        if k == 'nota':
            print('%8.3f  --  %s%s' % (q['t'], q['texto'], ('  pendente=' + q['pendente']) if q['pendente'] else ''))
        elif k == 'descartado':
            print('%8.3f  ??  bytes sobrando: %s' % (q['t'], q['hex']))
        elif k == 'invalido':
            print('%8.3f  %s  INVÁLIDO %s: %s' % (q['t'], q['dir'].upper(), q['erro'], q['hex']))
        elif k == 'quadro':
            print('%8.3f  %s  %-40s %s' % (q['t'], q['dir'].upper(), q['hex'], q['anot']))
        else:
            print('sobrou em %s (quadro incompleto): %s' % (q['dir'], q['hex']))


if __name__ == '__main__':
    main(sys.argv[1])
