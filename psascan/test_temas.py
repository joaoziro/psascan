"""Temas (docs/TEMA.md):  python3 -m unittest test_temas   (dentro de psascan/)"""
import copy, http.client, io, json, os, re, shutil, tempfile, threading, unittest, zipfile

import perfil as P, temas, web

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.normpath(os.path.join(AQUI, '..'))
PERFIL = P.carregar()
BASE = {'formato': 1, 'id': 'teste', 'nome': 'Teste'}


def tema(**extra):
    t = dict(BASE)
    t.update(extra)
    return t


def js(t):
    return json.dumps(t).encode()


def zip_de(arquivos):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        for n, b in arquivos.items():
            z.writestr(n, b)
    return buf.getvalue()


class Validacao(unittest.TestCase):
    def recusa(self, dados, trecho):
        with self.assertRaises(temas.TemaInvalido) as c:
            temas.carregar(dados if isinstance(dados, bytes) else js(dados))
        self.assertIn(trecho, str(c.exception))

    def test_padrao_valido_e_completo(self):
        t = temas.carregar(temas.PADRAO)
        self.assertEqual(t['id'], 'padrao')
        self.assertEqual(temas.completar(t), t)      # o Padrão já traz todos os campos

    def test_parcial_vale_e_completa(self):
        t = tema(interface={'escuro': {'accent': '#ff0000'}}, painel={'ponteiro': {'estilo': 'barra'}})
        temas.carregar(js(t))
        c = temas.completar(t)
        self.assertEqual(c['id'], 'teste')
        self.assertEqual(c['interface']['escuro']['accent'], '#ff0000')
        self.assertEqual(c['interface']['escuro']['bg'], temas.padrao()['interface']['escuro']['bg'])
        self.assertEqual(c['painel']['ponteiro']['estilo'], 'barra')
        self.assertEqual(c['painel']['ponteiro']['raio_ponta'], 87)
        self.assertEqual(c['painel']['cores'], temas.padrao()['painel']['cores'])
        self.assertIsNone(c['mascote'])

    def test_mescla_nao_altera_o_padrao(self):
        temas.completar(tema(painel={'cores': {'fundo': '#111111'}}))
        self.assertEqual(temas.padrao()['painel']['cores']['fundo'], '#000000')

    def test_json_e_formato(self):
        self.recusa(b'{nao e json', 'JSON inválido')
        self.recusa(b'\xff\xfe', 'JSON inválido')
        self.recusa(b'{"formato": NaN}', 'JSON inválido')
        self.recusa(b'[]', 'objeto')
        self.recusa(dict(BASE, formato=2), 'formato')
        self.recusa(dict(BASE, formato=True), 'formato')

    def test_id_e_nome(self):
        for ruim in ('A', 'Ruim_ID', 'a' * 33, '', 'x y', None, 5):
            self.recusa(dict(BASE, id=ruim), 'id')
        self.recusa({'formato': 1, 'id': 'teste'}, 'nome')
        self.recusa(dict(BASE, nome='x' * 41), 'nome')
        self.recusa(dict(BASE, nome=''), 'nome')

    def test_campo_desconhecido_em_cada_nivel(self):
        self.recusa(tema(extra=1), 'extra: campo desconhecido')
        self.recusa(tema(interface={'novo': 1}), 'interface.novo')
        self.recusa(tema(interface={'escuro': {'nova-cor': '#000000'}}), 'interface.escuro.nova-cor')
        self.recusa(tema(painel={'cores': {'nova': '#000000'}}), 'painel.cores.nova')
        self.recusa(tema(painel={'espessuras': {'fina': 1}}), 'painel.espessuras.fina')
        self.recusa(tema(mascote={'x': 1}), 'mascote.x')

    def test_cores(self):
        for ruim in ('vermelho', '#fff', '#12345', '#1234567', 'ff0000', '#gggggg', 5, None, ['#000000']):
            self.recusa(tema(interface={'claro': {'accent': ruim}}), 'interface.claro.accent')
        temas.carregar(js(tema(interface={'claro': {'accent': '#ff000080'}})))
        # listas de degradê: tamanho fixo
        self.recusa(tema(painel={'cores': {'aro': ['#000000', '#111111']}}), 'painel.cores.aro')
        self.recusa(tema(painel={'cores': {'aro': '#000000'}}), 'painel.cores.aro')
        self.recusa(tema(painel={'cores': {'aro': ['#000000', '#111111', 'x']}}), 'painel.cores.aro[2]')
        self.recusa(tema(painel={'cores': {'texto': ['#ffffff']}}), 'painel.cores.texto')

    def test_numeros_e_faixas(self):
        self.recusa(tema(painel={'espessuras': {'aro': 99}}), 'painel.espessuras.aro: número fora da faixa')
        self.recusa(tema(painel={'espessuras': {'aro': 0.1}}), 'painel.espessuras.aro')
        self.recusa(tema(painel={'espessuras': {'aro': '3'}}), 'painel.espessuras.aro')
        self.recusa(tema(painel={'espessuras': {'aro': True}}), 'painel.espessuras.aro')
        self.recusa(tema(painel={'raios': {'miolo': 10}}), 'painel.raios.miolo')
        self.recusa(tema(painel={'raios': {'cartao': 25}}), 'painel.raios.cartao')
        self.recusa(tema(painel={'ponteiro': {'brilho': 1.5}}), 'painel.ponteiro.brilho')
        self.recusa(tema(painel={'ponteiro': {'raio_ponta': 96}}), 'painel.ponteiro.raio_ponta')
        self.recusa(tema(painel={'animacao': {'rapidez': 0}}), 'painel.animacao.rapidez')
        self.recusa(tema(interface={'raios': {'r': 30}}), 'interface.raios.r')
        temas.carregar(js(tema(painel={'espessuras': {'contorno_numeros': 0}, 'animacao': {'rapidez': 20}})))

    def test_enumeracoes_e_booleanos(self):
        self.recusa(tema(painel={'ponteiro': {'estilo': 'laser'}}), 'painel.ponteiro.estilo: valor fora da lista')
        self.recusa(tema(painel={'animacao': {'ponteiro': 'pula'}}), 'painel.animacao.ponteiro')
        self.recusa(tema(painel={'escala': {'niveis': 4}}), 'painel.escala.niveis')
        self.recusa(tema(painel={'escala': {'pintar_passou': 1}}), 'painel.escala.pintar_passou')
        self.recusa(tema(painel={'fonte': {'app': {'sistema': 'comic'}}}), 'painel.fonte.app.sistema')
        self.recusa(tema(mascote={'posicao': 'meio'}), 'mascote.posicao')
        for estilo in ('agulha', 'seta', 'barra'):
            temas.carregar(js(tema(painel={'ponteiro': {'estilo': estilo}})))

    def test_fontes(self):
        self.recusa(tema(interface={'fontes': {'num': 'Foo; } body{x'}}), 'interface.fontes.num')
        self.recusa(tema(interface={'fontes': {'ui': 'Foo", bar'}}), 'interface.fontes.ui')   # aspas sem par
        self.recusa(tema(interface={'fontes': {'mono': ''}}), 'interface.fontes.mono')
        self.recusa(tema(painel={'fonte': {'web': {'familia': 'A"B'}}}), 'painel.fonte.web.familia')
        temas.carregar(js(tema(interface={'fontes': {'num': '"Foo Bar", system-ui, sans-serif'}})))

    def test_contraste(self):
        self.recusa(tema(painel={'cores': {'texto': '#010101'}}), 'contraste insuficiente')
        self.recusa(tema(interface={'claro': {'ink': '#efeee8'}}), 'interface.claro.ink')
        self.recusa(tema(interface={'escuro': {'bg': '#eef0f1'}}), 'interface.escuro.bg')
        self.assertAlmostEqual(temas.contraste('#000000', '#ffffff'), 21.0, places=3)
        self.assertAlmostEqual(temas.contraste('#777777', '#ffffff'), 4.478, places=2)   # referência WCAG
        # no limite: #767676 sobre branco passa (4,54:1), #777777 não
        temas.carregar(js(tema(painel={'cores': {'texto': '#767676', 'fundo': '#ffffff'}})))
        self.recusa(tema(painel={'cores': {'texto': '#777777', 'fundo': '#ffffff'}}), 'contraste')

    def test_arquivos_citados(self):
        t = tema(painel={'fonte': {'web': {'familia': 'Foo', 'arquivos': {'500': 'arquivos/f.woff2'}}}})
        self.recusa(js(t), 'não existe no tema')                       # json solto não tem a pasta
        pacote = zip_de({'tema.json': js(t), 'arquivos/f.woff2': b'wOF2'})
        self.assertEqual(temas.carregar(pacote)['id'], 'teste')
        for ruim in ('f.woff2', '../f.woff2', 'arquivos/../f.woff2', '/arquivos/f.woff2', 'arquivos/sub/f.woff2', 'arquivos/f.ttf'):
            t2 = tema(painel={'fonte': {'web': {'familia': 'Foo', 'arquivos': {'500': ruim}}}})
            self.recusa(zip_de({'tema.json': js(t2), 'arquivos/f.woff2': b'x', 'arquivos/f.ttf': b'x'}), 'painel.fonte.web.arquivos.500')
        t3 = tema(painel={'fonte': {'web': {'familia': 'Foo', 'arquivos': {'550': 'arquivos/f.woff2'}}}})
        self.recusa(zip_de({'tema.json': js(t3), 'arquivos/f.woff2': b'x'}), 'peso')
        t4 = tema(mascote={'imagem': 'arquivos/m.png'})
        self.recusa(js(t4), 'mascote.imagem')
        temas.carregar(zip_de({'tema.json': js(t4), 'arquivos/m.png': b'x'}))


class Pacotes(unittest.TestCase):
    def recusa(self, dados, trecho):
        with self.assertRaises(temas.TemaInvalido) as c:
            temas.carregar(dados)
        self.assertIn(trecho, str(c.exception))

    def test_zip_basico(self):
        self.assertEqual(temas.carregar(zip_de({'tema.json': js(BASE)}))['nome'], 'Teste')

    def test_zip_invalido_ou_sem_tema(self):
        self.recusa(b'PK\x03\x04lixo', 'zip inválido')
        self.recusa(zip_de({'outro.json': b'{}'}), 'não permitido')
        self.recusa(zip_de({'arquivos/a.png': b'x'}), 'não tem tema.json')

    def test_arquivo_proibido(self):
        for nome in ('script.js', 'arquivos/a.svg', 'arquivos/a.html', 'arquivos/a.exe', 'LEIA.txt', 'arquivos/sub/a.png',
                     'dir/tema.json'):
            self.recusa(zip_de({'tema.json': js(BASE), nome: b'x'}), 'não permitido')

    def test_caminhos_proibidos(self):
        for nome in ('../tema.json', 'arquivos/../../a.png', '/etc/a.png', 'arquivos\\a.png'):
            self.recusa(zip_de({'tema.json': js(BASE), nome: b'x'}), 'proibido')

    def test_tamanhos(self):
        self.recusa(zip_de({'tema.json': js(BASE), 'arquivos/a.png': b'0' * (temas.MAX_ARQUIVO + 1)}), 'grande demais')
        n = temas.MAX_TOTAL // temas.MAX_ARQUIVO + 1
        cheio = {'tema.json': js(BASE)}
        cheio.update({'arquivos/a%d.png' % k: b'0' * temas.MAX_ARQUIVO for k in range(n)})
        self.recusa(zip_de(cheio), 'somam')
        self.recusa(zip_de({'tema.json': b'{"a": "%s"}' % (b'x' * temas.MAX_JSON)}), 'grande demais')
        ok = {'tema.json': js(BASE), 'arquivos/a.png': b'0' * temas.MAX_ARQUIVO}
        temas.carregar(zip_de(ok))

    def test_arquivos_demais(self):
        muitos = {'tema.json': js(BASE)}
        muitos.update({'arquivos/a%d.png' % k: b'x' for k in range(temas.MAX_ENTRADAS)})
        self.recusa(zip_de(muitos), 'demais')

    def test_zip_com_nome_repetido(self):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, 'w') as z:
            z.writestr('tema.json', js(BASE))
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter('ignore')
                z.writestr('tema.json', js(BASE))
        self.recusa(buf.getvalue(), 'repetido')

    def test_pasta_do_disco(self):
        d = tempfile.mkdtemp()
        try:
            t = tema(painel={'fonte': {'web': {'familia': 'Foo', 'arquivos': {'500': 'arquivos/f.woff2'}}}})
            os.makedirs(os.path.join(d, 'arquivos'))
            with open(os.path.join(d, 'tema.json'), 'wb') as f:
                f.write(js(t))
            with open(os.path.join(d, 'arquivos', 'f.woff2'), 'wb') as f:
                f.write(b'x')
            self.assertEqual(temas.carregar(d)['id'], 'teste')
            self.assertEqual(temas.carregar(os.path.join(d, 'tema.json'))['id'], 'teste')
            with open(os.path.join(d, 'leia.txt'), 'w') as f:
                f.write('x')
            self.recusa(d, 'não permitido')
        finally:
            shutil.rmtree(d)


class Repositorio(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.rep = temas.Repositorio(self.dir)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_listar_so_com_o_padrao(self):
        lst = self.rep.listar()
        self.assertIn('padrao', [t['id'] for t in lst])
        pad = next(t for t in lst if t['id'] == 'padrao')
        self.assertTrue(pad['ativo'] and pad['embutido'] and pad['valido'])
        self.assertEqual(self.rep.ativo()['id'], 'padrao')

    def test_instalar_listar_ativar_exportar(self):
        t = tema(id='noturno', nome='Noturno', autor='eu', interface={'escuro': {'accent': '#00ff88'}},
                 painel={'fonte': {'web': {'familia': 'Foo', 'arquivos': {'500': 'arquivos/f.woff2'}}}})
        v0 = self.rep.versao
        self.assertEqual(self.rep.instalar(zip_de({'tema.json': js(t), 'arquivos/f.woff2': b'wOF2'}))['id'], 'noturno')
        self.assertGreater(self.rep.versao, v0)
        self.assertTrue(os.path.isfile(os.path.join(self.dir, 'temas', 'noturno', 'arquivos', 'f.woff2')))
        self.assertEqual([x for x in os.listdir(os.path.join(self.dir, 'temas')) if x.startswith('.')], [])
        self.assertTrue({'noturno', 'padrao'} <= {x['id'] for x in self.rep.listar()})
        self.assertFalse([x for x in self.rep.listar() if x['id'] == 'noturno'][0]['embutido'])
        self.rep.ativar('noturno')
        self.assertEqual(self.rep.ativo_id(), 'noturno')
        self.assertEqual(self.rep.ativo()['interface']['escuro']['accent'], '#00ff88')
        self.assertEqual(self.rep.ativo()['interface']['escuro']['bg'], '#0c0e11')     # completado com o Padrão
        # exportar devolve um zip que volta a passar na validação, com os mesmos dados
        z = self.rep.exportar('noturno')
        self.assertEqual(sorted(zipfile.ZipFile(io.BytesIO(z)).namelist()), ['arquivos/f.woff2', 'tema.json'])
        outro = temas.Repositorio(tempfile.mkdtemp())
        try:
            self.assertEqual(outro.instalar(z), t)
        finally:
            shutil.rmtree(outro.config)
        self.assertEqual(self.rep.arquivo('noturno', 'f.woff2'), os.path.realpath(
            os.path.join(self.dir, 'temas', 'noturno', 'arquivos', 'f.woff2')))

    def test_reinstalar_substitui(self):
        self.rep.instalar(js(tema(id='aa', nome='Um')))
        self.rep.instalar(js(tema(id='aa', nome='Dois')))
        self.assertEqual([x['nome'] for x in self.rep.listar() if x['id'] == 'aa'], ['Dois'])

    def test_nao_sobrescreve_embutido(self):
        with self.assertRaises(temas.TemaInvalido) as c:
            self.rep.instalar(js(tema(id='padrao', nome='Falso')))
        self.assertIn('vem com o projeto', str(c.exception))
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'temas', 'padrao')))

    def test_recusado_nao_e_salvo(self):
        with self.assertRaises(temas.TemaInvalido):
            self.rep.instalar(js(tema(painel={'cores': {'texto': '#010101'}})))
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'temas', 'teste')))
        self.assertEqual(self.rep.ativo_id(), 'padrao')

    def test_ativo_invalido_ou_sumido_volta_ao_padrao(self):
        self.rep.instalar(js(tema(id='aa', nome='A')))
        self.rep.ativar('aa')
        with open(os.path.join(self.dir, 'temas', 'aa', 'tema.json'), 'w') as f:
            f.write('{quebrado')
        t, erro = self.rep.ativo_com_erro()
        self.assertEqual(t['id'], 'padrao')
        self.assertIn('inválido', erro)
        self.assertFalse([x for x in self.rep.listar() if x['id'] == 'aa'][0]['valido'])
        shutil.rmtree(os.path.join(self.dir, 'temas', 'aa'))
        t, erro = self.rep.ativo_com_erro()
        self.assertEqual(t['id'], 'padrao')
        self.assertIn('não existe', erro)

    def test_ativar_inexistente(self):
        with self.assertRaises(KeyError):
            self.rep.ativar('nao-existe')
        with self.assertRaises(KeyError):
            self.rep.ativar('../x')
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'tema')))

    def test_arquivo_sem_travessia(self):
        self.rep.instalar(zip_de({'tema.json': js(tema(id='aa', nome='A')), 'arquivos/m.png': b'x'}))
        self.assertIsNotNone(self.rep.arquivo('aa', 'm.png'))
        for ruim in ('../tema.json', '..', 'tema.json', 'a/b.png', 'x.png', '%2e%2e', 'm.png/'):
            self.assertIsNone(self.rep.arquivo('aa', ruim))
        self.assertIsNone(self.rep.arquivo('../aa', 'm.png'))

    def test_css(self):
        c = temas.css(self.rep.ativo())
        self.assertIn(':root{--bg:#0c0e11;', c)
        self.assertIn(':root[data-theme="light"]{--rail:#e4e0d5;', c)
        for v in ('--painel-fundo:#000000', '--painel-texto-apagado:#6d6c68', '--num:"Barlow Condensed"', '--r:12px', '--r-sm:8px',
                  '--estrada-fundo:#000000', '--scrim:#00000099'):
            self.assertIn(v, c)
        self.assertNotIn('--painel-aro:', c)      # só cores simples; degradês ficam no JSON
        self.assertIn('/static/vendor/barlow-condensed/barlow-condensed-latin-600-normal.woff2', c)


class ApiWeb(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.rep = temas.Repositorio(os.path.join(self.dir, 'config'))
        self.ses = web.Sessao('/dev/null', PERFIL, None, os.path.join(self.dir, 'sessoes'),
                              os.path.join(self.dir, 'registros'), os.path.join(self.dir, 'faixas.json'), temas=self.rep)
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

    def test_tema_css_tem_as_variaveis(self):
        st, b, r = self.req('GET', '/api/tema.css')
        self.assertEqual(st, 200)
        self.assertIn('text/css', r.getheader('Content-Type'))
        for v in (b'--bg:#0c0e11', b'--accent:#f2a33a', b':root[data-theme="light"]', b'--ui:', b'--painel-ambar:#f2c94c',
                  b'@font-face'):
            self.assertIn(v, b)

    def test_tema_ativo_e_lista(self):
        t = json.loads(self.req('GET', '/api/tema')[1])
        self.assertEqual(t['id'], 'padrao')
        self.assertEqual(t['painel']['ponteiro']['estilo'], 'agulha')
        j = json.loads(self.req('GET', '/api/temas')[1])
        self.assertEqual(j['ativo'], 'padrao')
        self.assertIsNone(j['erro'])
        self.assertIn('padrao', [x['id'] for x in j['temas']])

    def test_importar_ativar_exportar(self):
        t = tema(id='verde', nome='Verde', interface={'escuro': {'accent': '#00ff88'}})
        st, b, _ = self.req('POST', '/api/tema/importar', js(t))
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(b)['ok'], {'id': 'verde', 'nome': 'Verde'})
        st, b, _ = self.req('POST', '/api/tema/ativar', json.dumps({'id': 'verde'}).encode())
        self.assertEqual(st, 200, b)
        self.assertEqual(json.loads(self.req('GET', '/api/tema')[1])['interface']['escuro']['accent'], '#00ff88')
        self.assertIn(b'--accent:#00ff88', self.req('GET', '/api/tema.css')[1])
        st, b, r = self.req('GET', '/api/tema/exportar?id=verde')
        self.assertEqual(st, 200)
        self.assertEqual(r.getheader('Content-Type'), 'application/zip')
        self.assertIn('tema-verde.zip', r.getheader('Content-Disposition'))
        self.assertEqual(json.loads(zipfile.ZipFile(io.BytesIO(b)).read('tema.json')), t)
        # sem id: exporta o ativo
        self.assertEqual(self.req('GET', '/api/tema/exportar')[0], 200)
        self.assertEqual(self.req('GET', '/api/tema/exportar?id=nao-existe')[0], 404)

    def test_importar_recusado_devolve_motivo(self):
        st, b, _ = self.req('POST', '/api/tema/importar', js(tema(painel={'ponteiro': {'estilo': 'laser'}})))
        self.assertEqual(st, 400)
        self.assertIn('painel.ponteiro.estilo', json.loads(b)['erro'])
        st, b, _ = self.req('POST', '/api/tema/importar', js(dict(BASE, id='padrao')))
        self.assertEqual(st, 400)
        self.assertIn('vem com o projeto', json.loads(b)['erro'])
        self.assertEqual(self.req('POST', '/api/tema/importar', b'')[0], 400)
        self.assertEqual(json.loads(self.req('GET', '/api/tema')[1])['id'], 'padrao')

    def test_ativar_invalido(self):
        self.assertEqual(self.req('POST', '/api/tema/ativar', b'{"id": "nao-existe"}')[0], 404)
        self.assertEqual(self.req('POST', '/api/tema/ativar', b'nao json')[0], 400)
        self.assertEqual(self.req('POST', '/api/tema/ativar', b'{}')[0], 404)

    def test_protecao_de_origem_e_host(self):
        for caminho in ('/api/tema/importar', '/api/tema/ativar'):
            self.assertEqual(self.req('POST', caminho, js(BASE), {'Origin': 'http://mal.com'})[0], 403)
            self.assertEqual(self.req('POST', caminho, js(BASE), {'Host': 'mal.com:%d' % self.porta})[0], 403)
        self.assertEqual(self.req('GET', '/api/tema.css', cab={'Host': 'mal.com:%d' % self.porta})[0], 403)
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'config', 'temas')))

    def test_corpo_grande_recusado(self):
        st, _, _ = self.req('POST', '/api/tema/importar', b'0' * (web.MAX_TEMA + 1))
        self.assertEqual(st, 413)

    def test_arquivos_do_tema(self):
        pacote = zip_de({'tema.json': js(tema(id='aa', nome='A')), 'arquivos/m.png': b'\x89PNG'})
        self.assertEqual(self.req('POST', '/api/tema/importar', pacote)[0], 200)
        st, b, r = self.req('GET', '/tema/aa/arquivos/m.png')
        self.assertEqual((st, b, r.getheader('Content-Type')), (200, b'\x89PNG', 'image/png'))
        self.assertEqual(r.getheader('X-Content-Type-Options'), 'nosniff')
        for ruim in ('/tema/aa/arquivos/..%2ftema.json', '/tema/aa/arquivos/../tema.json', '/tema/aa/arquivos/tema.json',
                     '/tema/../aa/arquivos/m.png', '/tema/aa/arquivos/nao.png', '/tema/%2e%2e/arquivos/m.png'):
            self.assertEqual(self.req('GET', ruim)[0], 404, ruim)

    def test_sse_avisa_a_troca_de_tema(self):
        c = http.client.HTTPConnection('127.0.0.1', self.porta, timeout=10)
        c.request('GET', '/api/stream', headers={'Host': '127.0.0.1:%d' % self.porta})
        r = c.getresponse()
        self.rep.instalar(js(tema(id='aa', nome='A')))
        self.rep.ativar('aa')
        achou = False
        for _ in range(20):
            linha = r.fp.readline().decode()
            if linha.startswith('event: tema'):
                achou = True
                break
        c.close()
        self.assertTrue(achou)

    def test_paginas_referenciam_o_tema(self):
        _, b, _ = self.req('GET', '/')
        self.assertIn(b'/api/tema.css', b)
        self.assertIn(b'/api/tema.css', self.req('GET', '/simples')[1])
        self.assertIn(b'/api/tema.css', self.req('GET', '/static/painel-carro.html')[1])


# ---------------------------------------------------------------- varredura: nenhuma cor nem fonte fixa no código
RE_HEX = re.compile(r'#[0-9a-fA-F]{3,8}\b')
RE_FUNCAO_COR = re.compile(r'\b(?:rgba?|hsla?)\(')
RE_NOME_COR = re.compile(r'(?:[:=]\s*|["\'`])(?:black|white|red|green|blue|gray|grey|yellow|orange|purple|silver|cyan|magenta)(?![-\w])')
RE_FONTE_CSS = re.compile(r'(?<![-\w])font-family\s*:\s*(?=\S)(?!var\()[^;}]+'
                          r'|(?<![-\w])font\s*:\s*(?=\S)(?!inherit\b)(?![^;}]*var\()[^;}]+')
RE_FONTE_ATTR = re.compile(r'\bpx\s+(?:system-ui|sans-serif|serif|monospace|Arial|Roboto|Helvetica|["\'][A-Z])|fontFamily\s*=\s*["\']|font-family["\']?\s*[:=]\s*["\'](?!var\()')
RE_KT_COR = re.compile(r'Color\(0x|Color\.(?:Black|White|Red|Green|Blue|Gray|Yellow|Cyan|Magenta|DarkGray|LightGray)\b')
RE_KT_FONTE = re.compile(r'FontFamily\.(?:Default|SansSerif|Serif|Monospace|Cursive)\b')

# fora da varredura: bibliotecas de terceiros (vendor/), o ícone/favicon (imagem, não interface) e Tema.kt (conversão/reserva)
EXCECOES = {os.path.join('android', 'app', 'src', 'main', 'java', 'br', 'psascan', 'painel', 'ui', 'Tema.kt')}


def arquivos_varridos(kotlin):
    out = []
    if not kotlin:
        for raiz, dirs, nomes in os.walk(os.path.join(AQUI, 'static')):
            dirs[:] = [d for d in dirs if d != 'vendor']
            out += [os.path.join(raiz, n) for n in nomes if n.endswith(('.js', '.css', '.html'))]
        out += [os.path.join(AQUI, 'web.html'), os.path.join(AQUI, 'web.py')]
    else:
        for raiz, _, nomes in os.walk(os.path.join(RAIZ, 'android', 'app', 'src', 'main', 'java')):
            out += [os.path.join(raiz, n) for n in nomes if n.endswith('.kt')]
    return [p for p in out if os.path.relpath(p, RAIZ) not in EXCECOES]


def achados(arquivos, regexes, so_css=()):
    out = []
    for p in sorted(arquivos):
        with open(p, encoding='utf-8') as f:
            for n, linha in enumerate(f, 1):
                for rx in regexes + (list(so_css) if p.endswith(('.css', '.html')) else []):
                    for m in rx.finditer(linha):
                        out.append('%s:%d: %s' % (os.path.relpath(p, RAIZ), n, m.group(0).strip()))
    return out


class Varredura(unittest.TestCase):
    """O tema é a única fonte das cores e fontes: o código não pode ter literal de cor nem família de fonte."""

    def test_web_sem_cor_nem_fonte_fixa(self):
        ach = achados(arquivos_varridos(False), [RE_HEX, RE_FUNCAO_COR, RE_NOME_COR, RE_FONTE_ATTR], so_css=[RE_FONTE_CSS])   # 'font:' em .js é chave de objeto
        self.assertEqual(ach, [], '\n'.join(ach))

    def test_kotlin_sem_cor_nem_fonte_fixa(self):
        ach = achados(arquivos_varridos(True), [RE_KT_COR, RE_KT_FONTE])
        self.assertEqual(ach, [], '\n'.join(ach))

    def test_a_varredura_enxerga(self):
        for ruim in ('color:#fff', 'a { color: #1a1a1a }', 'rgba(0,0,0,.5)', 'x = "white"', 'background:black',
                     'font-family: Arial', 'p{font:15px system-ui}'):
            self.assertTrue(any(rx.search(ruim) for rx in (RE_HEX, RE_FUNCAO_COR, RE_NOME_COR, RE_FONTE_CSS)), ruim)
        for bom in ('white-space:nowrap', 'font-family:var(--num)', 'font:15px/1.45 var(--ui)', 'font:inherit;', '#btn-ler',
                    'color:var(--ink)'):
            self.assertFalse(any(rx.search(bom) for rx in (RE_HEX, RE_FUNCAO_COR, RE_NOME_COR, RE_FONTE_CSS)), bom)



class TemasDoProjeto(unittest.TestCase):
    def test_todos_os_temas_embutidos_sao_validos(self):
        """Cada temas/<id>/tema.json do projeto passa na validação e tem o id da pasta."""
        raiz = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'temas')
        ids = sorted(os.listdir(raiz))
        self.assertIn('padrao', ids)
        for i in ids:
            with self.subTest(tema=i):
                t = temas.carregar(os.path.join(raiz, i, 'tema.json'))
                self.assertEqual(t['id'], i)


if __name__ == '__main__':
    unittest.main()
