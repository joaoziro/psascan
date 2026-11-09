"""Layouts (docs/LAYOUT.md):  python3 -m unittest test_layouts   (dentro de psascan/)"""
import copy, http.client, json, os, shutil, tempfile, threading, unittest

import layouts, perfil as P, web

AQUI = os.path.dirname(os.path.abspath(__file__))
PERFIL = P.carregar()


def w(**extra):
    d = {'id': 'a', 'tipo': 'numero', 'metrica': 'rpm', 'x': 0, 'y': 0, 'largura': 2, 'altura': 2}
    d.update(extra)
    return d


def lay(*ws, **extra):
    d = {'formato': 1, 'id': 'teste', 'nome': 'Teste', 'grade': {'colunas': 12, 'linhas': 6}, 'widgets': list(ws)}
    d.update(extra)
    return d


def js(x):
    return json.dumps(x).encode()


class Validacao(unittest.TestCase):
    def recusa(self, L, contem):
        with self.assertRaises(layouts.LayoutInvalido) as c:
            layouts.validar(copy.deepcopy(L))
        self.assertIn(contem, str(c.exception))

    def test_valido(self):
        L = lay(w(), w(id='b', tipo='mostrador', x=2, largura=4, altura=4,
                       opcoes={'escala': {'min': 0, 'max': 7000, 'maior': 1000, 'menor': 5}, 'centro': {'conteudo': 'rolos'},
                               'arco_inferior': {'metrica': 'agua', 'min': 50, 'max': 130}}),
                orientacao='paisagem', descricao='x')
        self.assertIs(layouts.validar(L), L)

    def test_sem_widgets_vale(self):
        layouts.validar(lay())

    def test_cabecalho(self):
        self.recusa(lay(formato=2), 'formato')
        self.recusa(lay(formato=True), 'formato')
        self.recusa(lay(id='Ruim_ID'), 'id')
        self.recusa(lay(id='a'), 'id')
        self.recusa(lay(nome=''), 'nome')
        self.recusa({k: v for k, v in lay().items() if k != 'nome'}, 'nome')
        self.recusa(lay(descricao='x' * 301), 'descricao')
        self.recusa(lay(extra=1), 'extra')
        self.recusa(lay(orientacao='diagonal'), 'orientacao')
        self.recusa([], 'layout')

    def test_grade(self):
        for g, c in (({'colunas': 3, 'linhas': 6}, 'grade.colunas'), ({'colunas': 17, 'linhas': 6}, 'grade.colunas'),
                     ({'colunas': 12, 'linhas': 2}, 'grade.linhas'), ({'colunas': 12, 'linhas': 13}, 'grade.linhas'),
                     ({'colunas': 12.5, 'linhas': 6}, 'grade.colunas'), ({'colunas': 12}, 'grade.linhas'),
                     ({'colunas': 12, 'linhas': 6, 'x': 1}, 'grade.x')):
            self.recusa(lay(grade=g), c)
        layouts.validar(lay(grade={'colunas': 4, 'linhas': 3}))
        layouts.validar(lay(grade={'colunas': 16, 'linhas': 12}))

    def test_limite_de_widgets(self):
        ws = [w(id='w%d' % i, tipo='numero', x=i % 16, y=i // 16, largura=1, altura=1) for i in range(40)]
        layouts.validar(lay(*ws, grade={'colunas': 16, 'linhas': 12}))
        self.recusa(lay(*ws, w(id='mais', x=15, y=11, largura=1, altura=1), grade={'colunas': 16, 'linhas': 12}), 'widgets')

    def test_widget(self):
        self.recusa(lay(w(), w(x=4)), 'repetido')
        self.recusa(lay(w(id='Ruim')), 'widgets[0].id')
        self.recusa(lay(w(tipo='radar')), 'tipo')
        self.recusa(lay(w(tipo=['x'])), 'tipo')
        self.recusa(lay(w(extra=1)), 'extra')
        self.recusa(lay(w(metrica='RPM!')), 'metrica')
        self.recusa(lay(w(metrica=5)), 'metrica')
        sem = w()
        del sem['metrica']
        self.recusa(lay(sem), 'metrica')
        self.recusa(lay(w(tipo='falhas', largura=3, altura=2)), 'não usa métrica')
        self.recusa(lay(w(titulo='x' * 41)), 'titulo')
        self.recusa(lay(w(opcoes=[])), 'opcoes')
        d = w()
        del d['x']
        self.recusa(lay(d), 'widgets[0].x')

    def test_metrica_inexistente_no_perfil_nao_invalida(self):
        layouts.validar(lay(w(metrica='nao_existe_neste_perfil')))

    def test_tamanhos_do_catalogo(self):
        cat = layouts.catalogo()['tipos']
        for tipo, t in cat.items():
            (l0, a0), (l1, a1) = t['minimo'], t['maximo']
            sem = {'metrica': 'rpm'} if t['metricas'] else {}
            g = {'colunas': 16, 'linhas': 12}
            base = dict(id='a', tipo=tipo, x=0, y=0, **sem)
            layouts.validar(lay(dict(base, largura=l0, altura=a0), grade=g))
            layouts.validar(lay(dict(base, largura=l1, altura=a1), grade=g))
            if l0 > 1:
                self.recusa(lay(dict(base, largura=l0 - 1, altura=a0), grade=g), 'largura')
            if a0 > 1:
                self.recusa(lay(dict(base, largura=l0, altura=a0 - 1), grade=g), 'altura')
            self.recusa(lay(dict(base, largura=l1 + 1, altura=a1), grade=g), 'largura')
            self.recusa(lay(dict(base, largura=l0, altura=a1 + 1), grade=g), 'altura')
        self.recusa(lay(w(largura=0)), 'largura')
        self.recusa(lay(w(largura=1.5)), 'largura')
        self.recusa(lay(w(largura='2')), 'largura')

    def test_fora_da_grade(self):
        layouts.validar(lay(w(x=10)))
        self.recusa(lay(w(x=11)), 'widgets[0].x')
        layouts.validar(lay(w(y=4)))
        self.recusa(lay(w(y=5)), 'widgets[0].y')
        self.recusa(lay(w(x=-1)), 'widgets[0].x')
        self.recusa(lay(w(y=-1)), 'widgets[0].y')

    def test_sobreposicao(self):
        layouts.validar(lay(w(), w(id='b', x=2)))              # encostados
        layouts.validar(lay(w(), w(id='b', y=2)))
        self.recusa(lay(w(), w(id='b', x=1, y=1)), 'sobrepõe')
        self.recusa(lay(w(), w(id='b', x=0, y=0, largura=1, altura=1)), 'sobrepõe')
        self.recusa(lay(w(largura=4, altura=4), w(id='b', x=1, y=1, largura=1, altura=1)), 'sobrepõe')   # um dentro do outro

    def test_opcoes(self):
        self.recusa(lay(w(opcoes={'cor': 'azul'})), 'opcoes.cor')
        self.recusa(lay(w(opcoes={'mostrar_unidade': 'sim'})), 'mostrar_unidade')
        self.recusa(lay(w(opcoes={'mostrar_unidade': 1})), 'mostrar_unidade')
        layouts.validar(lay(w(opcoes={'decimais': 0})))
        layouts.validar(lay(w(opcoes={'decimais': 4})))
        self.recusa(lay(w(opcoes={'decimais': 5})), 'decimais')
        self.recusa(lay(w(opcoes={'decimais': -1})), 'decimais')
        self.recusa(lay(w(opcoes={'decimais': 1.5})), 'decimais')
        self.recusa(lay(w(opcoes={'decimais': True})), 'decimais')
        self.recusa(lay(w(tipo='barra', opcoes={'orientacao': 'diagonal'})), 'orientacao')
        layouts.validar(lay(w(tipo='barra', opcoes={'orientacao': 'vertical', 'min': -5, 'max': 1000})))
        self.recusa(lay(w(tipo='barra', opcoes={'min': 'zero'})), 'opcoes.min')
        self.recusa(lay(w(tipo='numero_historico', opcoes={'segundos': 9})), 'segundos')
        self.recusa(lay(w(tipo='numero_historico', opcoes={'segundos': 601})), 'segundos')
        self.recusa(lay(w(tipo='luz', largura=1, altura=1, metrica='luz_injecao', opcoes={'rotulo': 'x' * 21})), 'rotulo')
        self.recusa(lay(w(tipo='estado', opcoes={'qualquer': 1})), 'opcoes.qualquer')

    def test_opcoes_de_objeto(self):
        m = dict(tipo='mostrador', largura=4, altura=4)
        layouts.validar(lay(w(**m, opcoes={'escala': {'alerta': 6500}})))
        self.recusa(lay(w(**m, opcoes={'escala': {'maior': 0}})), 'escala.maior')
        self.recusa(lay(w(**m, opcoes={'escala': {'menor': 21}})), 'escala.menor')
        self.recusa(lay(w(**m, opcoes={'escala': {'min': float('nan')}})), 'escala.min')
        self.recusa(lay(w(**m, opcoes={'escala': {'fonte': 1}})), 'escala.fonte')
        self.recusa(lay(w(**m, opcoes={'escala': 3})), 'escala')
        self.recusa(lay(w(**m, opcoes={'centro': {'conteudo': 'logo'}})), 'centro.conteudo')
        self.recusa(lay(w(**m, opcoes={'centro': {'conteudo': 'metrica', 'metrica': 'A B'}})), 'centro.metrica')
        layouts.validar(lay(w(**m, opcoes={'centro': {'conteudo': 'metrica', 'metrica': 'velocidade'}})))
        self.recusa(lay(w(**m, opcoes={'arco_inferior': {'metrica': 'agua'}})), 'arco_inferior.min')
        self.recusa(lay(w(**m, opcoes={'arco_inferior': {'metrica': 'agua', 'min': 1}})), 'arco_inferior.max')
        layouts.validar(lay(w(**m, opcoes={'arco_inferior': {'metrica': 'agua', 'min': 50, 'max': 130}})))

    def test_luz_sem_metrica_so_com_fonte_falhas(self):
        d = dict(tipo='luz', largura=1, altura=1)
        sem = w(**d)
        del sem['metrica']
        self.recusa(lay(sem), 'metrica')
        sem['opcoes'] = {'fonte': 'falhas'}
        layouts.validar(lay(sem))
        layouts.validar(lay(w(**d, opcoes={'fonte': 'falhas'})))       # com métrica também passa

    def test_carregar_json(self):
        with self.assertRaises(layouts.LayoutInvalido) as c:
            layouts.carregar(b'{ nao e json')
        self.assertIn('JSON', str(c.exception))
        with self.assertRaises(layouts.LayoutInvalido):
            layouts.carregar(b'\xff\xfe')
        with self.assertRaises(layouts.LayoutInvalido) as c:
            layouts.carregar(b'x' * (layouts.MAX_JSON + 1))
        self.assertIn('grande', str(c.exception))
        self.assertEqual(layouts.carregar(js(lay()))['id'], 'teste')
        self.assertEqual(layouts.carregar(json.dumps(lay())).get('id'), 'teste')


class Prontos(unittest.TestCase):
    def test_os_prontos_sao_validos_e_usam_metricas_do_perfil(self):
        ids = sorted(n[:-5] for n in os.listdir(layouts.RAIZ_LAYOUTS) if n.endswith('.json'))
        self.assertEqual(ids, ['completo', 'giro-grande', 'marcha-lenta', 'mistura-lambda', 'painel', 'partida-quente'])
        metricas = {m['id']: m for m in PERFIL.metricas}
        cat = layouts.catalogo()['tipos']
        for lid in ids:
            with self.subTest(layout=lid):
                L = layouts.carregar(os.path.join(layouts.RAIZ_LAYOUTS, lid + '.json'))
                self.assertEqual(L['id'], lid)
                for x in L['widgets']:
                    citadas = [x['metrica']] if 'metrica' in x else []
                    for op in (x.get('opcoes') or {}).values():
                        if isinstance(op, dict) and 'metrica' in op:
                            citadas.append(op['metrica'])
                    for mid in citadas:
                        self.assertIn(mid, metricas, '%s: %s' % (x['id'], mid))
                    if 'metrica' in x:
                        self.assertTrue(layouts.aceita(x['tipo'], metricas[x['metrica']]['tipo']),
                                        '%s: %s não serve ao tipo %s' % (lid, x['metrica'], x['tipo']))
                    else:
                        self.assertEqual((x.get('opcoes') or {}).get('fonte'), 'falhas')
                    self.assertIn(x['tipo'], cat)

    def test_categorias(self):
        self.assertEqual([layouts.categoria(t) for t in ('u8', 'u16', 'curva', 'bit', 'enum')],
                         ['numerica', 'numerica', 'numerica', 'bit', 'discreta'])
        self.assertTrue(layouts.aceita('numero', 'bit'))        # número mostra o texto de um bit
        self.assertTrue(layouts.aceita('luz', 'bit'))
        self.assertFalse(layouts.aceita('luz', 'enum'))
        self.assertFalse(layouts.aceita('mostrador', 'enum'))
        self.assertFalse(layouts.aceita('falhas', 'u8'))


class Repositorio(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.rep = layouts.Repositorio(os.path.join(self.dir, 'config'))

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_listar_e_ativo_padrao(self):
        r = self.rep.listar()
        self.assertEqual(len(r), 6)
        self.assertTrue(all(x['embutido'] and x['valido'] for x in r))
        self.assertEqual([x['id'] for x in r if x['ativo']], ['painel'])
        self.assertEqual(self.rep.ativo()['id'], 'painel')
        self.assertIsNone(self.rep.ativo_com_erro()[1])

    def test_ativar(self):
        v = self.rep.versao
        self.rep.ativar('completo')
        self.assertEqual(self.rep.ativo()['id'], 'completo')
        self.assertGreater(self.rep.versao, v)
        self.assertEqual(layouts.Repositorio(os.path.join(self.dir, 'config')).ativo_id(), 'completo')   # guardado em arquivo
        with self.assertRaises(KeyError):
            self.rep.ativar('nao-existe')
        with self.assertRaises(KeyError):
            self.rep.ativar('../etc/passwd')
        self.assertEqual(self.rep.ativo()['id'], 'completo')

    def test_ativo_sumido_ou_invalido_volta_ao_padrao(self):
        os.makedirs(self.rep.config)
        with open(self.rep.arq_ativo, 'w') as f:
            f.write('sumiu\n')
        L, motivo = self.rep.ativo_com_erro()
        self.assertEqual(L['id'], 'painel')
        self.assertIn('sumiu', motivo)
        os.makedirs(self.rep.pasta_usuario)
        with open(os.path.join(self.rep.pasta_usuario, 'ruim.json'), 'w') as f:
            f.write(json.dumps(lay(id='ruim', formato=7)))
        # ativar recusa o inválido
        with self.assertRaises(layouts.LayoutInvalido):
            self.rep.ativar('ruim')
        with open(self.rep.arq_ativo, 'w') as f:
            f.write('ruim\n')
        L, motivo = self.rep.ativo_com_erro()
        self.assertEqual(L['id'], 'painel')
        self.assertIn('inválido', motivo)
        item = next(x for x in self.rep.listar() if x['id'] == 'ruim')
        self.assertFalse(item['valido'])
        self.assertIn('formato', item['erro'])

    def test_salvar_ler_e_listar(self):
        L = lay(w(), id='meu', nome='Meu')
        self.rep.salvar(L)
        self.assertTrue(os.path.isfile(os.path.join(self.dir, 'config', 'layouts', 'meu.json')))
        self.assertEqual(self.rep.layout('meu'), L)
        item = next(x for x in self.rep.listar() if x['id'] == 'meu')
        self.assertFalse(item['embutido'])
        self.assertEqual((item['nome'], item['widgets']), ('Meu', 1))
        L2 = lay(w(), w(id='b', x=4), id='meu', nome='Meu 2')       # editar o seu = salvar por cima
        self.rep.salvar(L2)
        self.assertEqual(self.rep.layout('meu')['nome'], 'Meu 2')
        self.assertEqual(len([x for x in self.rep.listar() if x['id'] == 'meu']), 1)

    def test_salvar_por_cima_de_pronto_e_recusado(self):
        with self.assertRaises(layouts.LayoutInvalido) as c:
            self.rep.salvar(lay(id='painel'))
        self.assertIn('vem com o projeto', str(c.exception))
        self.assertIn('painel-meu', str(c.exception))
        self.assertFalse(os.path.exists(self.rep.pasta_usuario))

    def test_salvar_invalido_nao_grava(self):
        with self.assertRaises(layouts.LayoutInvalido):
            self.rep.salvar(lay(w(), w(id='b'), id='ruim'))        # sobreposto
        self.assertFalse(os.path.exists(os.path.join(self.rep.pasta_usuario, 'ruim.json')))

    def test_salvar_o_ativo_sobe_a_versao(self):
        self.rep.salvar(lay(w(), id='meu'))
        self.rep.ativar('meu')
        v = self.rep.versao
        self.rep.salvar(lay(w(x=3), id='meu'))
        self.assertGreater(self.rep.versao, v)

    def test_importar_exportar(self):
        L = self.rep.importar(js(lay(w(), id='veio', nome='Veio')))
        self.assertEqual(L['id'], 'veio')
        saiu = json.loads(self.rep.exportar('veio'))
        self.assertEqual(saiu, L)
        pronto = json.loads(self.rep.exportar('painel'))
        self.assertEqual(pronto['id'], 'painel')
        self.assertEqual(layouts.carregar(self.rep.exportar('painel'))['id'], 'painel')
        for ruim in (b'nao e json', js(lay(formato=2)), js(lay(id='painel')), b'x' * (layouts.MAX_JSON + 1)):
            with self.assertRaises(layouts.LayoutInvalido):
                self.rep.importar(ruim)
        with self.assertRaises(KeyError):
            self.rep.exportar('nao-existe')

    def test_apagar(self):
        self.rep.salvar(lay(w(), id='meu'))
        self.rep.ativar('meu')
        v = self.rep.versao
        self.rep.apagar('meu')
        self.assertFalse(os.path.exists(os.path.join(self.rep.pasta_usuario, 'meu.json')))
        self.assertEqual(self.rep.ativo()['id'], 'painel')       # o ativo apagado volta ao padrão
        self.assertGreater(self.rep.versao, v)
        with self.assertRaises(KeyError):
            self.rep.apagar('meu')
        with self.assertRaises(layouts.LayoutInvalido):
            self.rep.apagar('painel')
        with self.assertRaises(KeyError):
            self.rep.apagar('../x')
        self.assertTrue(self.rep.pronto('painel'))

    def test_arquivo_do_usuario_com_id_diferente_do_nome(self):
        os.makedirs(self.rep.pasta_usuario)
        with open(os.path.join(self.rep.pasta_usuario, 'outro.json'), 'w') as f:
            f.write(json.dumps(lay(id='diferente')))
        with self.assertRaises(layouts.LayoutInvalido):
            self.rep.layout('outro')


class ApiWeb(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.rep = layouts.Repositorio(os.path.join(self.dir, 'config'))
        self.ses = web.Sessao('/dev/null', PERFIL, None, os.path.join(self.dir, 'sessoes'),
                              os.path.join(self.dir, 'registros'), os.path.join(self.dir, 'faixas.json'), layouts=self.rep)
        self.ses.simulador = True
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
        h = {'Host': '127.0.0.1:%d' % self.porta, 'Origin': 'http://127.0.0.1:%d' % self.porta}
        h.update(cab or {})
        c.request(metodo, caminho, corpo, h)
        r = c.getresponse()
        return r.status, r.read(), r

    def test_lista_e_ativo(self):
        st, b, _ = self.req('GET', '/api/layouts')
        self.assertEqual(st, 200)
        j = json.loads(b)
        self.assertEqual(j['ativo'], 'painel')
        self.assertIsNone(j['erro'])
        self.assertEqual(len(j['layouts']), 6)
        self.assertEqual(json.loads(self.req('GET', '/api/layout')[1])['id'], 'painel')
        self.assertEqual(json.loads(self.req('GET', '/api/layout?id=completo')[1])['id'], 'completo')
        self.assertEqual(self.req('GET', '/api/layout?id=nao-existe')[0], 404)

    def test_catalogo_e_pagina_estaticos(self):
        st, b, _ = self.req('GET', '/static/widgets/catalogo.json')
        self.assertEqual(st, 200)
        self.assertIn('mostrador', json.loads(b)['tipos'])
        st, b, _ = self.req('GET', '/static/widgets/host.js')
        self.assertEqual(st, 200)
        self.assertEqual(self.req('GET', '/static/js/tela.js')[0], 200)
        self.assertEqual(self.req('GET', '/static/widgets/teste.html')[0], 200)

    def test_ativar_salvar_importar_exportar_apagar(self):
        st, b, _ = self.req('POST', '/api/layout/salvar', js({'layout': lay(w(), id='meu', nome='Meu')}))
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(b)['ok'], {'id': 'meu', 'nome': 'Meu'})
        st, b, _ = self.req('POST', '/api/layout/ativar', js({'id': 'meu'}))
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(self.req('GET', '/api/layout')[1])['id'], 'meu')
        st, b, r = self.req('GET', '/api/layout/exportar?id=meu')
        self.assertEqual(st, 200)
        self.assertIn('layout-meu.json', r.getheader('Content-Disposition'))
        exportado = json.loads(b)
        exportado['id'] = 'copia'
        st, b, _ = self.req('POST', '/api/layout/importar', js(exportado))
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(b)['ok']['id'], 'copia')
        self.assertIn('copia', [x['id'] for x in json.loads(self.req('GET', '/api/layouts')[1])['layouts']])
        st, b, _ = self.req('POST', '/api/layout/apagar', js({'id': 'meu'}))
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(self.req('GET', '/api/layouts')[1])['ativo'], 'painel')

    def test_recusas(self):
        st, b, _ = self.req('POST', '/api/layout/salvar', js({'layout': lay(w(), w(id='b'), id='ruim')}))
        self.assertEqual(st, 400)
        self.assertIn('sobrepõe', json.loads(b)['erro'])
        st, b, _ = self.req('POST', '/api/layout/salvar', js({'layout': lay(id='painel')}))
        self.assertEqual(st, 400)
        self.assertIn('vem com o projeto', json.loads(b)['erro'])
        self.assertEqual(self.req('POST', '/api/layout/importar', b'nao e json')[0], 400)
        self.assertEqual(self.req('POST', '/api/layout/salvar', b'nao e json')[0], 400)
        self.assertEqual(self.req('POST', '/api/layout/salvar', js({}))[0], 400)
        self.assertEqual(self.req('POST', '/api/layout/ativar', js({'id': 'nao-existe'}))[0], 404)
        self.assertEqual(self.req('POST', '/api/layout/ativar', js({}))[0], 404)
        self.assertEqual(self.req('POST', '/api/layout/apagar', js({'id': 'painel'}))[0], 400)
        self.assertEqual(self.req('POST', '/api/layout/apagar', js({'id': 'nao-existe'}))[0], 404)
        self.assertEqual(self.req('GET', '/api/layout/exportar?id=nao-existe')[0], 404)
        self.assertEqual(self.req('GET', '/api/layout/outra-coisa')[0], 404)
        st, _, _ = self.req('POST', '/api/layout/importar', b'x' * (layouts.MAX_JSON + 10))
        self.assertEqual(st, 413)

    def test_origin_e_host(self):
        corpo = js({'id': 'completo'})
        self.assertEqual(self.req('POST', '/api/layout/ativar', corpo, {'Origin': 'http://evil.example'})[0], 403)
        self.assertEqual(self.req('POST', '/api/layout/ativar', corpo, {'Host': 'evil.example'})[0], 403)
        self.assertEqual(self.req('GET', '/api/layouts', cab={'Host': 'evil.example'})[0], 403)
        self.assertEqual(self.rep.ativo_id(), 'painel')

    def test_evento_sse_layout(self):
        c = http.client.HTTPConnection('127.0.0.1', self.porta, timeout=10)
        c.request('GET', '/api/stream', headers={'Host': '127.0.0.1:%d' % self.porta})
        r = c.getresponse()
        r.fp.raw._sock.settimeout(8) if hasattr(r.fp, 'raw') and hasattr(r.fp.raw, '_sock') else None
        self.assertEqual(r.status, 200)
        self.rep.ativar('completo')
        achou, buf = False, ''
        for _ in range(40):
            linha = r.fp.readline().decode()
            buf += linha
            if 'event: layout' in buf:
                achou = True
                break
        c.close()
        self.assertTrue(achou, buf)


if __name__ == '__main__':
    unittest.main()
