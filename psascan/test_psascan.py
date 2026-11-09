"""python3 -m unittest -v   (dentro de psascan/)"""
import copy, io, unittest

import time

import contextlib, http.client, json, os, shutil, tempfile, threading

import central, cli, conexao, dongle, faixas, falhas_info, perfil as P, psa5, registro, sessoes, sim, web

AQUI = os.path.dirname(os.path.abspath(__file__))
PERFIL = P.carregar()
with open(os.path.join(AQUI, 'dados_teste', 'referencia.json'), encoding='utf-8') as _f:
    REF = json.load(_f)


def vals_de_referencia(k=0):
    """Leitura ao vivo [(métrica, bruto, número, texto)] do k-ésimo par de blocos reais da referência."""
    out = []
    for b in REF['blocos'][2 * k:2 * k + 2]:
        d = bytes.fromhex(b['dados'])
        for m in PERFIL.metricas:
            if m['bloco'] == d[1]:
                out.append((m,) + P.converter(PERFIL, m, d))
    return sorted(out, key=lambda x: x[0]['ordem'])


def gravar_csv_de_referencia(pasta, nome='20261025-163040.csv', n=40):
    """CSV no formato atual, com as respostas reais da referência (lambda presa em rico, motor quente)."""
    os.makedirs(pasta, exist_ok=True)
    caminho = os.path.join(pasta, nome)
    r = registro.RegistroCSV(caminho, t0=0.0)
    for i in range(n):
        r.linha(vals_de_referencia(i % 3), i * 0.3, 1700000000 + i)
    r.close()
    return caminho


class Quadros(unittest.TestCase):
    def test_crc_x25(self):
        self.assertEqual(dongle.crc16_x25(b'123456789'), (0x906E).to_bytes(2, 'little'))  # valor de referência X-25

    def test_envelope_ida_volta(self):
        for n in (1, 255, 256, 300):
            p = bytes([1]) + bytes(n - 1)
            self.assertEqual(dongle.unframe(dongle.frame(p))[0], p)

    def test_quadro_da_central(self):
        self.assertEqual(psa5.quadro(PERFIL.conexao['inicio']), bytes.fromhex('820f6e'))
        self.assertEqual(psa5.desquadro(bytes.fromhex('820f6e'), base=0x80), b'\x0f')
        self.assertEqual(psa5.desquadro(bytes.fromhex('c2c37a')), b'\xc3')

    def test_falhas(self):
        d = bytes([0x73]) + ((0x22 << 3) | 6).to_bytes(2, 'big') + ((0x1A1 << 3) | 0x2000).to_bytes(2, 'big')
        f = psa5.decodificar_falhas(d)
        self.assertEqual([(x['codigo'], x['tipo'], x['presente']) for x in f], [(0x22, 6, True), (0x1A1, 0, False)])
        self.assertIsNone(psa5.decodificar_falhas(b'\x32'))


class NaoRegressao(unittest.TestCase):
    """O código novo reproduz o que o código anterior à Fase 3 calculava (dados_teste/referencia.json)."""

    def test_blocos(self):
        self.assertGreater(len(REF['blocos']), 100)
        for b in REF['blocos']:
            d = bytes.fromhex(b['dados'])
            for ordem, esperado in b['valores'].items():
                m = PERFIL.metrica(int(ordem))
                raw, num, txt = P.converter(PERFIL, m, d)
                self.assertAlmostEqual(num, esperado, delta=1e-9, msg='%s em %s' % (m['id'], b['dados']))

    def test_falhas(self):
        for f in REF['falhas']:
            lst = psa5.decodificar_falhas(bytes.fromhex(f['dados']))
            self.assertEqual([{k: x[k] for k in ('codigo', 'tipo', 'presente')} for x in lst], f['falhas'])

    def test_quadros_das_acoes(self):
        for acao, esperado in REF['quadros'].items():
            novo = [q['bytes'] for q in conexao.quadros_da_acao(PERFIL, acao)]
            self.assertEqual(novo, [q['bytes'] for q in esperado], acao)


class Perfil(unittest.TestCase):
    def base(self):
        return copy.deepcopy(PERFIL.dados)

    def invalido(self, dados, trecho):
        with self.assertRaises(P.PerfilInvalido) as cm:
            P.Perfil(dados)
        self.assertIn(trecho, str(cm.exception))

    def test_carrega_sl96(self):
        self.assertEqual((PERFIL.id, PERFIL.familia), ('sagem-sl96', 'psa5'))
        self.assertEqual([i for i, _ in PERFIL.blocos], [0, 1])
        self.assertEqual(PERFIL.blocos[0][1], b'\x11\x00')
        self.assertEqual(PERFIL.metrica('rpm')['ordem'], 1)
        self.assertEqual(PERFIL.metrica(15)['id'], 'lambda')
        self.assertEqual(PERFIL.lambda_['id'], 'lambda')
        self.assertEqual(PERFIL.escrita_por_id('apagar_falhas')['pedido'], 'C3')
        self.assertEqual(PERFIL.nome_falha(295), 'Regulação de riqueza')
        self.assertEqual(PERFIL.nome_falha(0x127 + 1000), 'código 0x50F (não identificado)')
        self.assertEqual(PERFIL.nome_tipo(5), 'tipo 5')
        self.assertTrue(PERFIL.falha_pendente(33))
        self.assertFalse(PERFIL.falha_pendente(295))
        self.assertTrue(PERFIL.pendente(PERFIL.metrica('agua')))      # curva pendente
        self.assertFalse(PERFIL.pendente(PERFIL.metrica('rpm')))

    def test_textos(self):
        d = bytes.fromhex('11 00 0c 2f 1a 80 20 10 3a 8a 73 ca 16 6d c0 56 30 54 28 00 c8 83 45 8c 2d 01 00')
        self.assertEqual(P.converter(PERFIL, PERFIL.metrica('rpm'), d)[2], '832 rpm')
        self.assertEqual(P.converter(PERFIL, PERFIL.metrica('bateria'), d)[2], '14.0 V')
        self.assertEqual(P.converter(PERFIL, PERFIL.metrica('pedal'), d)[2], 'marcha lenta')
        self.assertEqual(P.texto(PERFIL, PERFIL.metrica('pedal'), 33), '?')
        self.assertEqual(P.texto(PERFIL, PERFIL.metrica('ac_termistor'), 0), 'não')

    def test_curta(self):
        with self.assertRaises(IndexError):
            P.converter(PERFIL, PERFIL.metrica('rpm'), b'\x11\x00')

    def test_invalidos(self):
        d = self.base(); del d['nome']
        self.invalido(d, 'nome')
        d = self.base(); d['metricas'][0]['bloco'] = 9
        self.invalido(d, 'bloco 9')
        d = self.base(); d['conexao']['pino'] = '13 zz'
        self.invalido(d, 'conexao.pino')
        d = self.base(); d['metricas'][1]['ordem'] = d['metricas'][0]['ordem']
        self.invalido(d, 'ordem')
        d = self.base(); d['condicoes']['ligado']['regras'] = [{'condicao': 'quente'}]
        self.invalido(d, 'ciclo')
        d = self.base(); d['metricas'][8]['curva'] = 'nao_existe'
        self.invalido(d, 'curva')
        d = self.base(); d['metricas'][0]['faixas'][0]['quando'] = 'nunca'
        self.invalido(d, 'condição')
        d = self.base(); d['formato'] = 2
        self.invalido(d, 'formato')
        d = self.base(); d['escrita'][0]['pedido'] = 'C'
        self.invalido(d, 'pedido')

    def test_carregar_arquivo_ruim(self):
        with tempfile.TemporaryDirectory() as t:
            c = os.path.join(t, 'perfil.json')
            open(c, 'w').write('{ não é json')
            with self.assertRaises(P.PerfilInvalido):
                P.carregar(c)
            with self.assertRaises(P.PerfilInvalido):
                P.carregar(os.path.join(t, 'nao-existe.json'))


class Conversao(unittest.TestCase):
    def conv(self, id_, d):
        return P.converter(PERFIL, PERFIL.metrica(id_), d)

    def test_rpm_byte(self):
        d = bytearray(27); d[4] = 25
        self.assertEqual(self.conv('rpm', d), (25, 800, '800 rpm'))

    def test_word_big_endian(self):
        d = bytearray(27); d[8:10] = (13360).to_bytes(2, 'big')
        self.assertEqual(self.conv('map', d)[2], '400 mbar')

    def test_bit(self):
        d = bytearray(27); d[19] = 1 << 5
        self.assertEqual(self.conv('rele_principal', d), (1, 1, 'sim'))

    def test_inverso_e_montagem(self):
        raws = {m['ordem']: raw for m, raw, _, _ in vals_de_referencia(0) if m['bloco'] == 0}
        d = P.montar_bloco(PERFIL, 0, raws)
        for m in PERFIL.metricas:
            if m['bloco'] == 0:
                self.assertEqual(P.converter(PERFIL, m, d)[0], raws[m['ordem']], m['id'])


class Lambda(unittest.TestCase):
    def analisa(self, f):
        a = registro.AnaliseLambda()
        for i in range(100):
            a.add(i * 0.1, f(i * 0.1))
        return a.resumo()['diagnostico']

    def test_diagnosticos(self):
        self.assertIn('OSCILANDO', self.analisa(lambda t: 850 if int(t * 2) % 2 else 100))
        self.assertIn('POBRE', self.analisa(lambda t: 110))
        self.assertIn('RICO', self.analisa(lambda t: 860))
        self.assertIn('MEIO', self.analisa(lambda t: 450))
        self.assertIn('DEVAGAR', self.analisa(lambda t: 100 if t < 5 else 850))


class Numero(unittest.TestCase):
    def test_numero_igual_ao_texto(self):
        d = bytearray(27); d[12] = 22; d[14] = 192; d[17] = 84
        for m in PERFIL.metricas:
            if m['bloco'] == 0 and m['tipo'] not in ('bit', 'enum'):
                raw, num, txt = P.converter(PERFIL, m, d)
                self.assertAlmostEqual(P.numero(PERFIL, m, raw), float(txt.split()[0]), delta=0.51)
        self.assertAlmostEqual(P.numero(PERFIL, PERFIL.metrica('agua'), 22), 96, delta=1)     # carro quente: bruto 22 = 96 °C


class Faixas(unittest.TestCase):
    QUENTE = {1: 832, 9: 96, 5: 16, 17: 0}

    def f(self):
        return faixas.Faixas(PERFIL, os.path.join(tempfile.mkdtemp(), 'faixas.json'))

    def ctx(self, vals):
        return faixas.contexto(PERFIL, vals)

    def test_contexto(self):
        self.assertIn('marcha_lenta_quente', self.ctx(self.QUENTE))
        frio = self.ctx({1: 900, 9: 40, 5: 16, 17: 0})
        self.assertNotIn('quente', frio)
        self.assertIn('desligado', self.ctx({1: 0, 9: 20}))
        self.assertIn('sempre', self.ctx({}))
        self.assertNotIn('ligado', self.ctx({}))          # sem leitura, a regra é falsa

    def test_estados_com_dados_do_carro(self):
        f, c = self.f(), self.ctx(self.QUENTE)
        self.assertEqual(f.avaliar(1, 832, c)['estado'], 'normal')
        self.assertEqual(f.avaliar(2, 14.0, c)['estado'], 'normal')
        self.assertEqual(f.avaliar(6, 16, c)['estado'], 'atencao')        # borboleta 16° x 11–12° da central
        self.assertEqual(f.avaliar(2, 12.0, c)['estado'], 'fora')
        self.assertEqual(f.avaliar(9, 60, c)['msg'], 'ainda frio')
        self.assertEqual(f.avaliar(20, 1, c)['estado'], 'atencao')        # luz de injeção acesa
        self.assertEqual(f.avaliar(16, 7, c)['estado'], 'neutro')         # sem regra

    def test_lado_da_faixa(self):
        """O cabeçalho mostra ALTO/BAIXO: o lado vem junto da avaliação."""
        f, c = self.f(), self.ctx(self.QUENTE)
        self.assertEqual(f.avaliar(6, 16, c)['lado'], 'acima')            # borboleta acima de 11–12°
        self.assertEqual(f.avaliar(2, 12.0, c)['lado'], 'abaixo')         # bateria baixa com motor ligado
        self.assertIsNone(f.avaliar(1, 832, c)['lado'])                   # normal: sem lado
        self.assertIsNone(f.avaliar(20, 1, c)['lado'])                    # bit: atenção sem lado
        self.assertEqual(f.avaliar(3, 30, c)['estado'], 'fora')           # avanço 30° em marcha lenta
        self.assertEqual(f.avaliar(3, 30, c)['lado'], 'acima')

    def test_condicoes_de_carga(self):
        """Carga parcial e plena existem como condição (pedal 0 e 64), ainda sem faixas."""
        f = self.f()
        self.assertIn('carga_parcial', self.ctx({1: 2500, 9: 90, 5: 0, 17: 80}))
        self.assertIn('plena_carga', self.ctx({1: 4500, 9: 90, 5: 64, 17: 120}))

    def test_condicao_fora_e_neutro(self):
        f = self.f()
        andando = self.ctx({1: 2500, 9: 90, 5: 0, 17: 80})
        self.assertEqual(f.avaliar(1, 2500, andando)['estado'], 'normal')     # cai na regra "sempre"
        self.assertEqual(f.avaliar(13, 6.0, andando)['estado'], 'neutro')     # injeção só tem faixa em marcha lenta

    def test_lambda_pela_analise(self):
        f, c = self.f(), self.ctx(self.QUENTE)
        self.assertEqual(f.avaliar(15, 823, c, 'PARADA EM RICO (~820 mV)')['estado'], 'fora')
        self.assertEqual(f.avaliar(15, 500, c, 'OSCILANDO (~1.0 Hz)')['estado'], 'normal')
        self.assertEqual(f.avaliar(15, 500, self.ctx({1: 900, 9: 30}), 'OSCILANDO')['estado'], 'neutro')

    def test_ajuste_salvo_e_padrao(self):
        f = self.f()
        f.ajustar(6, [{'quando': 'pedal_solto', 'normal': [11, 17], 'atencao': [9, 20]}])
        g = faixas.Faixas(PERFIL, f.caminho)
        self.assertEqual(g.avaliar(6, 16, {'pedal_solto'})['estado'], 'normal')
        self.assertTrue(g.todas()[6]['ajustado'])
        with self.assertRaises(ValueError):
            g.ajustar(6, [{'quando': 'nunca', 'normal': [1, 2]}])
        g.ajustar(6, None)
        self.assertEqual(faixas.Faixas(PERFIL, f.caminho).avaliar(6, 16, {'pedal_solto'})['estado'], 'atencao')


class Simulado(unittest.TestCase):
    def test_reconecta_sozinho(self):
        s = sim.Simulador(queda_a_cada=6)      # fica 3 s mudo a cada 6 s
        con = conexao.Conexao(s.caminho)
        m = conexao.Monitor(con, 'rapido')
        m.iniciar()
        try:
            fim = time.monotonic() + 15
            while con.reconexoes == 0 and time.monotonic() < fim:
                time.sleep(0.1)
            self.assertGreaterEqual(con.reconexoes, 1)
            n = m.leituras
            while m.leituras <= n + 10 and time.monotonic() < fim:
                time.sleep(0.1)
            self.assertGreater(m.leituras, n + 10)   # voltou a ler depois da queda
            self.assertTrue(m.rodando)
        finally:
            m.parar()

    def test_inicio_uma_vez_so(self):
        s = sim.Simulador()
        d = dongle.Dongle(s.caminho, log=lambda x: None)
        try:
            central.abrir(d, PERFIL).conectar()
        finally:
            d.close()
        n0f = [p for p in s.log if p[0] == 0x04 and p[4] == 0x0F]
        self.assertEqual(len(n0f), 1)     # o 1º respondeu: não repetir (no carro o 2º dava erro)

    def test_estado_depois_de_parar(self):
        s = sim.Simulador(queda_a_cada=4)
        con = conexao.Conexao(s.caminho)
        m = conexao.Monitor(con, 'rapido')
        m.iniciar()
        fim = time.monotonic() + 10
        while con.estado != 'reconectando' and time.monotonic() < fim:
            time.sleep(0.05)
        m.parar()
        self.assertEqual(con.estado, 'desconectado')
        self.assertIn('reconectando', [e for _, e, _ in con.historico])

    def test_csv_ida_e_volta(self):
        tmp = tempfile.mkdtemp()
        csv = gravar_csv_de_referencia(tmp)
        linhas = registro.ler_csv(csv, PERFIL)
        self.assertEqual(len(linhas), 40)
        s_, hora, raws = linhas[0]
        blocos = {n: P.montar_bloco(PERFIL, n, raws) for n in (0, 1)}
        for m in PERFIL.metricas:
            self.assertEqual(P.converter(PERFIL, m, blocos[m['bloco']])[0], raws[m['ordem']], m['nome'])
        shutil.rmtree(tmp)

    def test_reproduz_csv_pelo_caminho_real(self):
        tmp = tempfile.mkdtemp()
        s = sim.Simulador(csv=gravar_csv_de_referencia(tmp), imitar_0f=True)
        d = dongle.Dongle(s.caminho, log=lambda x: None)
        try:
            e = central.abrir(d, PERFIL)
            e.conectar()
            vals = {m['ordem']: t for m, r, n, t in e.ler_parametros()}
            self.assertEqual(vals[1], '832 rpm')
            self.assertEqual(vals[15].split()[1], 'mV')
            self.assertGreater(float(vals[15].split()[0]), 700)     # lambda presa em rico, como no carro
        finally:
            d.close()
            shutil.rmtree(tmp)

    def test_csv_usa_falhas_do_carro(self):
        tmp = tempfile.mkdtemp()
        self.assertEqual(sim.Simulador(csv=gravar_csv_de_referencia(tmp)).falhas[0], (0x127, 2, True))
        shutil.rmtree(tmp)
        self.assertEqual(sim.Simulador().falhas, list(sim.FALHAS_TESTE))

    def test_cenario_estrada(self):
        c = sim.Cenario(PERFIL, 'estrada')
        self.assertGreater(c.valores(50)['velocidade'], 60)             # cruzeiro
        self.assertEqual(c.valores(205)['injecao'], 0.0)               # corte de injeção no freio-motor
        self.assertLess(c.valores(0)['agua'], 80)                      # começa frio

    def test_fluxo_completo_e_escrita_bloqueada(self):
        s = sim.Simulador()
        d = dongle.Dongle(s.caminho, log=lambda x: None)
        try:
            e = central.abrir(d, PERFIL)            # escrita=False por padrão
            e.conectar()
            f, _ = e.ler_falhas()
            self.assertEqual(len(f), 2)
            vals = {m['ordem']: t for m, r, n, t in e.ler_parametros()}
            self.assertEqual(vals[2], '13.9 V')
            with self.assertRaises(PermissionError):
                e.escrever('apagar_falhas')
            with self.assertRaises(PermissionError):
                e.escrever('atuador_luz_injecao')
            self.assertNotIn(0xC3, [p[4] for p in s.log if p[0] == 0x04])      # nada foi enviado
            self.assertEqual(e.ler_falhas()[0] and len(e.ler_falhas()[0]), 2)
            e.escrita = True
            self.assertEqual(e.escrever('apagar_falhas'), b'\xc3')
            self.assertEqual(e.ler_falhas()[0], [])
            self.assertEqual(e.escrever('atuador_luz_injecao'), b'\x66')
        finally:
            d.close()

    def test_perigoso_pede_liberacao(self):
        dados = copy.deepcopy(PERFIL.dados)
        dados['escrita'][1]['perigoso'] = True
        p = P.Perfil(dados)
        s = sim.Simulador(perfil=p)
        d = dongle.Dongle(s.caminho, log=lambda x: None)
        try:
            e = central.abrir(d, p, escrita=True)
            e.conectar()
            with self.assertRaises(PermissionError):
                e.escrever('atuador_luz_injecao')
            with self.assertRaises(PermissionError):
                e.escrever('atuador_luz_injecao', liberar_perigo='sim')
            self.assertEqual(e.escrever('atuador_luz_injecao', liberar_perigo=True), b'\x66')
        finally:
            d.close()

    def test_comando_fora_do_perfil_sem_resposta(self):
        s = sim.Simulador()
        d = dongle.Dongle(s.caminho, log=lambda x: None)
        try:
            e = central.abrir(d, PERFIL)
            e.conectar()
            with self.assertRaises(TimeoutError):
                e._ecu('fora do perfil', bytes([0x77, 0x01]), timeout=0.5)
        finally:
            d.close()


class Cli(unittest.TestCase):
    def roda(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = cli.main(list(args))
        return rc, out.getvalue()

    def test_escrita_exige_flag(self):
        rc, txt = self.roda('--sem-captura', '--sim-confirmar', 'apagar')
        self.assertEqual(rc, 1)
        self.assertIn('--escrita', txt)
        rc, txt = self.roda('--sem-captura', '--sim-confirmar', 'atuador', '0')
        self.assertEqual(rc, 1)
        self.assertIn('--escrita', txt)

    def test_escrita_com_flag_apaga(self):
        s = sim.Simulador()
        rc, txt = self.roda('--porta', s.caminho, '--sem-captura', '--sim-confirmar', '--escrita', 'apagar')
        self.assertEqual(rc, 0, txt)
        self.assertEqual(s.falhas, [])

    def test_falhas_e_atuadores(self):
        s = sim.Simulador()
        rc, txt = self.roda('--porta', s.caminho, '--sem-captura', '--sim-confirmar', 'falhas')
        self.assertEqual(rc, 0, txt)
        self.assertIn('Sensor de temperatura da água', txt)
        rc, txt = self.roda('atuadores')
        self.assertIn('atuador_luz_injecao', txt)


class Sessoes(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.csv = gravar_csv_de_referencia(os.path.join(self.dir, 'registros'))
        self.ind = sessoes.Indice(os.path.join(self.dir, 'sessoes'), os.path.join(self.dir, 'registros'))
        self.vals = vals_de_referencia(0)
        self.fx = faixas.Faixas(PERFIL, os.path.join(self.dir, 'f.json'))

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_gravacao_com_queda_e_evento(self):
        g = sessoes.Gravacao(self.ind, 'teste', agora=100.0)
        for k in range(10):
            g.linha(self.vals, 100.0 + k * 0.3)
        for k in range(10):                     # 5 s sem leitura = queda do Bluetooth
            g.linha(self.vals, 107.7 + k * 0.3)
        ev = g.evento('tec tec agora', t=108.0)
        meta = g.fechar()
        self.assertEqual(meta['leituras'], 20)
        self.assertEqual(len(meta['quedas']), 1)
        self.assertAlmostEqual(meta['quedas'][0][0], 2.7, places=1)
        self.assertEqual(ev['s'], 8.0)
        m2 = sessoes.Indice(self.ind.pasta, self.ind.registros).get(g.sid)    # persistiu
        self.assertEqual(m2['eventos'][0]['texto'], 'tec tec agora')
        d = sessoes.carregar(self.ind, g.sid, PERFIL, self.fx)
        self.assertEqual(len(d['t']), 20)
        self.assertEqual(d['series'][1][0], 832)
        self.assertIn('evento', sessoes.exportar_csv(self.ind, g.sid).splitlines()[0])
        self.assertIn('tec tec agora', sessoes.exportar_csv(self.ind, g.sid))

    def test_csv_usa_nome_do_perfil(self):
        cab = open(self.csv, encoding='utf-8').readline().strip().split(',')
        self.assertEqual(cab[:4], ['hora', 's', 'Rotação do motor', 'Rotação do motor (bruto)'])
        self.assertIn('832 rpm', open(self.csv, encoding='utf-8').read())

    def test_prefixo_do_buffer(self):
        pre = [(50.0 + k, None, self.vals) for k in range(5)]
        g = sessoes.Gravacao(self.ind, '', prefixo=pre, agora=55.0)
        g.linha(self.vals, 55.0)
        self.assertEqual(g.fechar()['leituras'], 6)
        d = sessoes.carregar(self.ind, g.sid, PERFIL, self.fx)
        self.assertEqual(d['t'][0], 0.0)
        self.assertEqual(d['t'][-1], 5.0)

    def test_csv_antigo_e_edicao(self):
        open(os.path.join(self.ind.registros, '20261025-161546.csv'), 'w').close()   # vazio: ignorado
        lst = self.ind.listar()
        self.assertEqual([m['id'] for m in lst], ['20261025-163040'])
        self.assertTrue(lst[0]['antiga'])
        self.assertEqual(lst[0]['leituras'], 40)
        self.ind.atualizar('20261025-163040', nome='motor ligado, quente', notas='lambda presa')
        self.assertEqual(self.ind.listar()[0]['nome'], 'motor ligado, quente')
        d = sessoes.carregar(self.ind, '20261025-163040', PERFIL, self.fx)
        self.assertIn('PARADA EM RICO', d['lambda']['resumo']['diagnostico'])
        self.assertEqual(d['estados'][15][-1], 3)          # lambda "fora" com motor quente
        with self.assertRaises(ValueError):
            self.ind.csv('../x')

    def test_falhas_durante_gravacao(self):
        F = lambda c, tp, pr: falhas_info.descrever({'codigo': c, 'tipo': tp, 'presente': pr}, PERFIL)
        g = sessoes.Gravacao(self.ind, '', agora=10.0)
        for k in range(20):
            g.linha(self.vals, 10.0 + k * 0.3)
        ev = g.falhas('início', [F(42, 1, False)], t=10.0)
        self.assertIn('02A Sonda lambda (memorizada)', ev['texto'])
        self.assertIsNone(g.falhas('fim', [F(42, 1, False)], t=12.0))          # nada novo: sem evento
        ev = g.falhas('luz acendeu', [F(295, 2, True), F(42, 1, False)], t=15.0)
        self.assertEqual(ev['texto'], 'falha (luz acendeu): 127 Regulação de riqueza (presente)')
        self.assertEqual(ev['s'], 5.0)
        meta = g.fechar()
        self.assertEqual([x['motivo'] for x in meta['falhas']], ['início', 'fim', 'luz acendeu'])
        self.assertIn('Regulação de riqueza', sessoes.exportar_csv(self.ind, g.sid))

    def test_falha_pendente_mostra_nome_provavel(self):
        f = falhas_info.descrever({'codigo': 33, 'tipo': 1, 'presente': True}, PERFIL)
        self.assertTrue(f['pendente'])
        g = sessoes.Gravacao(self.ind, '', agora=10.0)
        ev = g.falhas('início', [f], t=10.0)
        self.assertIn('[nome provável]', ev['texto'])
        g.fechar()

    def test_historico_falhas(self):
        h = falhas_info.Historico(os.path.join(self.dir, 'falhas.json'))
        h.leitura([{'codigo': 42, 'tipo': 1, 'presente': False}], '2026-10-25 15:40:00')
        h.leitura([{'codigo': 295, 'tipo': 2, 'presente': True}, {'codigo': 42, 'tipo': 1, 'presente': False}],
                  '2026-10-25 16:30:00')
        h.apagou('2026-10-25 16:35:00')
        h.leitura([{'codigo': 295, 'tipo': 2, 'presente': True}], '2026-10-25 16:36:00')
        r = {f['codigo']: f for f in falhas_info.Historico(h.caminho).resumo(PERFIL)['falhas']}
        self.assertEqual(r[42]['primeira'], '2026-10-25 15:40:00')
        self.assertEqual(r[42]['apagada_em'], '2026-10-25 16:35:00')
        self.assertFalse(r[42]['na_ultima_leitura'])
        self.assertTrue(r[295]['na_ultima_leitura'])
        self.assertEqual(r[295]['nome'], 'Regulação de riqueza')
        self.assertEqual(r[295]['hex'], '127')


class LambdaSerie(unittest.TestCase):
    def test_serie(self):
        ts = [k * 0.3 for k in range(200)]
        mvs = [850 if int(t) % 2 else 100 for t in ts]
        r = registro.analisar_serie(ts, mvs)
        self.assertIn('OSCILANDO', r['diag'][-1])
        self.assertGreater(r['resumo']['trocas_s'], 0.5)
        self.assertEqual(r['tempo'].get('OSCILANDO', 0) + r['tempo'].get('aguardando dados', 0) +
                         r['tempo'].get('VARIA, MAS DEVAGAR', 0), 100)


class Servidor(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.s = sim.Simulador(cenario='ralenti', imitar_0f=True)
        self.ses = web.Sessao(self.s.caminho, PERFIL, None, os.path.join(self.dir, 'sessoes'),
                              os.path.join(self.dir, 'registros'), os.path.join(self.dir, 'faixas.json'))
        self.ses.simulador = True          # não grava a porta em ~/.config/psascan
        self.srv = web.Servidor(('127.0.0.1', 0), web.handler(self.ses))
        self.porta = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.ses.close()
        shutil.rmtree(self.dir)

    def req(self, metodo, caminho, corpo=None, cab=None):
        c = http.client.HTTPConnection('127.0.0.1', self.porta, timeout=10)
        h = {'Host': '127.0.0.1:%d' % self.porta}
        h.update(cab or {})
        c.request(metodo, caminho, json.dumps(corpo) if corpo is not None else None, h)
        r = c.getresponse()
        return r.status, r.read()

    def test_estaticos_e_travessia(self):
        self.assertEqual(self.req('GET', '/static/../web.py')[0], 404)
        self.assertEqual(self.req('GET', '/static/%2e%2e/web.py')[0], 404)
        self.assertEqual(self.req('GET', '/simples')[0], 200)
        self.assertEqual(self.req('GET', '/api/capturas/..%2fweb.py')[0], 404)

    def test_origem_e_host(self):
        self.assertEqual(self.req('POST', '/api/versao', {}, {'Origin': 'http://mal.com'})[0], 403)
        self.assertEqual(self.req('GET', '/api/info', cab={'Host': 'mal.com:%d' % self.porta})[0], 403)
        self.assertEqual(self.req('GET', '/api/info')[0], 200)

    def test_parametros_vem_do_perfil(self):
        j = json.loads(self.req('GET', '/api/parametros')[1])
        ps = {p['id']: p for p in j['parametros']}
        self.assertEqual(len(ps), 21)
        self.assertEqual(j['lambda'], 15)
        self.assertTrue(ps['agua']['pendente'])               # curva pendente: "não confirmado"
        self.assertFalse(ps['rpm']['pendente'])
        self.assertEqual(ps['borboleta']['nivel'], 'V1')
        self.assertIn('evidencia', ps['rpm'])
        self.assertNotIn('dica', ps['rpm'])
        self.assertNotIn('nome_banco', ps['rpm'])
        self.assertEqual(ps['pedal']['mascaras']['16'], 'marcha lenta')
        self.assertEqual(j['condicoes']['quente'], 'motor ligado e quente (água ≥ 80 °C)')

    def test_conectar_etapas_e_scanner(self):
        st, b = self.req('POST', '/api/porta', {'endereco': '00:06:66:00:00:01'})
        self.assertIn('modo simulador', json.loads(b)['erro'])        # simulador nunca troca para o carro
        self.ses.simulador = False
        with self.assertRaises(ValueError):
            self.ses.trocar_porta('nao-e-mac')
        self.ses.simulador = True
        sc = json.loads(self.req('GET', '/api/scanners')[1])
        self.assertEqual(sc['atual'], self.s.caminho)
        st, b = self.req('POST', '/api/conectar', {})
        self.assertEqual(st, 200, b)
        r = json.loads(self.req('GET', '/api/resumo')[1])
        self.assertEqual((r['conexao'], r['etapa']), ('conectado', 'conectado'))
        time.sleep(2.2)                       # parado: manda o pedido de manter para a conexão não cair
        self.assertTrue(any(p[0] == 0x04 and p[4] == 0x00 for p in self.s.log))
        self.req('POST', '/api/desconectar', {})
        self.assertEqual(json.loads(self.req('GET', '/api/resumo')[1])['conexao'], 'desconectado')

    def test_dica_de_erro(self):
        self.assertIn('ignição', web.dica_erro('OSError: [Errno 112] Host is down'))
        self.assertIn('central não respondeu', web.dica_erro('TimeoutError: sem resposta (recebido: )'))

    def test_escrita_desligada_por_padrao(self):
        self.assertFalse(json.loads(self.req('GET', '/api/info')[1])['escrita'])
        self.assertFalse(json.loads(self.req('GET', '/api/estado')[1])['escrita'])
        self.assertFalse(json.loads(self.req('GET', '/api/resumo')[1])['escrita'])
        for caminho, corpo in (('/api/apagar', {}), ('/api/atuador', {'id': 'atuador_luz_injecao'})):
            st, b = self.req('POST', caminho, corpo)
            self.assertEqual(st, 403, b)
            self.assertEqual(json.loads(b), {'erro': 'escrita desabilitada'})
        self.assertFalse([p for p in self.s.log if p[0] == 0x04 and p[4] in (0xC3, 0xB4)])   # nada chegou à central
        self.assertEqual(self.req('POST', '/api/escrita', {'ativa': 'sim'})[0], 500)       # só booleano

    def test_escrita_liga_e_apaga(self):
        st, b = self.req('POST', '/api/escrita', {'ativa': True})
        self.assertEqual((st, json.loads(b)['ok']), (200, {'escrita': True}))
        self.assertTrue(json.loads(self.req('GET', '/api/info')[1])['escrita'])
        st, b = self.req('POST', '/api/apagar', {})
        self.assertEqual(st, 200, b)
        self.assertEqual(self.s.falhas, [])
        st, b = self.req('POST', '/api/atuador', {'id': 'atuador_luz_injecao'})
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(self.req('GET', '/api/resumo')[1])['atuador_ativo']['id'], 'atuador_luz_injecao')
        self.req('POST', '/api/escrita', {'ativa': False})
        self.assertEqual(self.req('POST', '/api/apagar', {})[0], 403)

    def test_previa_de_escrita_e_sem_riqueza(self):
        q = json.loads(self.req('GET', '/api/previa?acao=atuador_luz_injecao')[1])
        self.assertEqual(q[-1]['bytes'], '80 07 04 01 04 83 b4 06 c2 3d ff')
        self.assertEqual(self.req('POST', '/api/riqueza', {})[0], 500)

    def test_ao_vivo_sse_rec_e_evento(self):
        st, b = self.req('POST', '/api/evento', {'texto': 'x'})
        self.assertIn('inicie a leitura', json.loads(b)['erro'])
        self.req('POST', '/api/rapido', {})
        fim = time.monotonic() + 10
        while self.ses.seq < 8 and time.monotonic() < fim:
            time.sleep(0.1)
        c = http.client.HTTPConnection('127.0.0.1', self.porta, timeout=10)
        c.request('GET', '/api/stream', headers={'Host': '127.0.0.1:%d' % self.porta})
        r = c.getresponse()
        self.assertEqual(r.getheader('Content-Type'), 'text/event-stream; charset=utf-8')
        linhas = [r.fp.readline() for _ in range(12)]
        c.close()
        dados = [json.loads(l[6:]) for l in linhas if l.startswith(b'data: ')]
        leit = [d for d in dados if 'v' in d]
        self.assertTrue(leit)
        self.assertEqual(leit[0]['est']['1'][0] if '1' in leit[0]['est'] else leit[0]['est'][1][0], 'normal')
        st, b = self.req('POST', '/api/evento', {'texto': 'tec tec agora'})   # sem REC: grava sozinho com o buffer
        r = json.loads(b)['ok']
        self.assertTrue(r['rec_automatico'])
        self.assertGreater(r['evento']['s'], 0.1)       # o buffer anterior entrou na gravação
        time.sleep(1)
        st, b = self.req('POST', '/api/parar', {})
        meta = json.loads(b)['ok']['sessao']
        self.assertIn('tec tec agora', [e['texto'] for e in meta['eventos']])
        self.assertEqual([x['motivo'] for x in meta['falhas']], ['início', 'fim'])   # leu as falhas sozinho
        self.assertGreater(meta['leituras'], 8)
        lst = json.loads(self.req('GET', '/api/sessoes')[1])
        self.assertEqual(lst[0]['id'], r['id'])
        d = json.loads(self.req('GET', '/api/sessoes/%s' % r['id'])[1])
        self.assertEqual(len(d['t']), meta['leituras'])
        self.assertEqual(self.ses.con.estado, 'desconectado' if not self.ses.con.ecu else 'conectado')

    def test_falhas_com_nome_do_perfil(self):
        st, b = self.req('POST', '/api/falhas', {})
        lst = json.loads(b)['ok']['falhas']
        self.assertEqual(lst[0]['nome'], 'Sensor de temperatura da água')     # código 0x22 = 34
        self.assertTrue(lst[0]['pendente'])
        self.assertEqual(lst[1]['nome'], PERFIL.nome_falha(417))
        hist = json.loads(self.req('GET', '/api/falhas/historico')[1])
        self.assertEqual(hist['leituras'], 1)


if __name__ == '__main__':
    unittest.main()
