"""Confere a documentação de protocolo (docs/*.md) contra as capturas do carro.

- Todo quadro do scanner citado inteiro (`80 nn … crc crc`) tem CRC-16/X-25 válido e aparece,
  byte a byte, numa captura do carro (capturas/*.jsonl ou capturas/ref/*.jsonl).
- Todo quadro da central citado inteiro (`8x …` pedido, `Cx …` resposta) fecha o checksum.
- Toda citação `capturas/<arquivo>:<linha>` aponta para um arquivo e uma linha que existem.
- Nenhum termo do material de engenharia reversa aparece na documentação.

O CRC é reimplementado aqui a partir da definição (e não importado de dongle.py), para que a
doc seja conferida de forma independente do código.
"""
import glob, os, re, unittest

RAIZ = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
DOCS = sorted(glob.glob(os.path.join(RAIZ, 'docs', '*.md')))
SIMULADOR = {'20261025-163818.jsonl'}      # captura do simulador que ficou junto das do carro
PROIBIDOS = re.compile(r'superscan|asm-?x|deccom|\bocx\b|ghidra|FUN_[0-9a-f]|decompil|descompil|'
                       r'smartassembly|db004|zdia|xdia|\bluce\b|ferrari', re.I)
HEX = re.compile(r'`((?:[0-9A-Fa-f]{2} )+[0-9A-Fa-f]{2})`')
CITACAO = re.compile(r'(capturas/[\w./-]+\.jsonl):(\d+)')
CURTA = re.compile(r'`(\d{6}):(\d+)`')    # forma curta da doc: `161237:7` = capturas/20261025-161237.jsonl, linha 7
REF = re.compile(r'(capturas/ref/[\w.-]+\.jsonl)')


def crc_x25(dados):
    """CRC-16/X-25: refletido, polinômio 0x1021 (0x8408 refletido), início 0xFFFF, complemento."""
    crc = 0xFFFF
    for b in dados:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8408 if crc & 1 else crc >> 1
    crc ^= 0xFFFF
    return bytes([crc & 0xFF, crc >> 8])


def texto_docs():
    out = {}
    for c in DOCS:
        with open(c, encoding='utf-8') as f:
            out[os.path.basename(c)] = f.read()
    return out


def bytes_capturas():
    """Fluxo de bytes de cada sentido de cada captura do carro, para procurar quadros citados."""
    fluxos = []
    import json
    for c in glob.glob(os.path.join(RAIZ, 'capturas', '*.jsonl')) + glob.glob(os.path.join(RAIZ, 'capturas', 'ref', '*.jsonl')):
        if os.path.basename(c) in SIMULADOR:
            continue
        tx, rx = bytearray(), bytearray()
        with open(c, encoding='utf-8') as f:
            linhas = f.readlines()
        for linha in linhas:
            r = json.loads(linha)
            if r['dir'] == 'tx':
                tx += bytes.fromhex(r['hex'])
            elif r['dir'] == 'rx':
                rx += bytes.fromhex(r['hex'])
        fluxos += [bytes(tx), bytes(rx)]
    return fluxos


def citados():
    for nome, txt in texto_docs().items():
        for m in HEX.finditer(txt):
            yield nome, bytes.fromhex(m.group(1))


@unittest.skipUnless(DOCS, 'sem docs/*.md')
class Documentacao(unittest.TestCase):
    def test_crc_da_definicao(self):
        self.assertEqual(crc_x25(b'\x80\x01\x18'), b'\x31\x4f')   # versão do scanner

    def test_quadros_do_scanner(self):
        fluxos = bytes_capturas()
        vistos = 0
        for nome, q in citados():
            if q[0] != 0x80 or len(q) < 5 or q[1] != len(q) - 4:
                continue
            vistos += 1
            with self.subTest(doc=nome, quadro=q.hex(' ')):
                self.assertEqual(crc_x25(q[:-2]), q[-2:], 'CRC errado')
                self.assertTrue(any(q in f for f in fluxos), 'quadro não aparece em nenhuma captura')
        self.assertGreater(vistos, 5, 'a doc deveria citar quadros completos do scanner')

    def test_quadros_da_central(self):
        vistos = 0
        for nome, q in citados():
            base = 0x80 if 0x81 < q[0] < 0xA0 else 0xC0 if 0xC1 < q[0] < 0xE0 else None
            if base is None or len(q) != q[0] - base + 1:
                continue
            vistos += 1
            with self.subTest(doc=nome, quadro=q.hex(' ')):
                self.assertEqual(sum(q) & 0xFF, 0xFF, 'checksum da central não fecha')
        self.assertGreater(vistos, 3, 'a doc deveria citar quadros completos da central')

    def test_citacoes_de_captura(self):
        for nome, txt in texto_docs().items():
            cits = CITACAO.findall(txt) + [('capturas/20261025-%s.jsonl' % h, n) for h, n in CURTA.findall(txt)]
            if nome in ('METODO.md', 'PROTOCOLO-SL96.md'):
                self.assertGreater(len(cits), 10, 'a doc deveria citar as capturas')
            for arq in REF.findall(txt):
                self.assertTrue(os.path.exists(os.path.join(RAIZ, arq)), '%s não existe' % arq)
            for arq, linha in cits:
                with self.subTest(doc=nome, cita='%s:%s' % (arq, linha)):
                    self.assertNotIn(os.path.basename(arq), SIMULADOR, 'cita a captura do simulador')
                    caminho = os.path.join(RAIZ, arq)
                    self.assertTrue(os.path.exists(caminho), 'arquivo não existe')
                    with open(caminho, encoding='utf-8') as f:
                        self.assertLessEqual(int(linha), sum(1 for _ in f), 'linha além do fim')

    def test_sem_material_de_engenharia_reversa(self):
        for nome, txt in texto_docs().items():
            achados = sorted({m.group(0) for m in PROIBIDOS.finditer(txt)})
            self.assertEqual(achados, [], '%s cita %s' % (nome, achados))


if __name__ == '__main__':
    unittest.main()
