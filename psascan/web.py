"""Painel web local do psascan (só biblioteca padrão).

  python3 web.py [--porta AA:BB:CC:DD:EE:FF | /dev/rfcomm0] [--http 8096] [--sim]
  ->  http://127.0.0.1:8096          (painel; a página antiga continua em /simples)

Cada ação que fala com a central mostra antes os quadros exatos (/api/previa) e
só envia depois de "Enviar". Somente leitura ao iniciar: apagar falhas e atuadores só funcionam
depois de ligar a chave "Permitir comandos de escrita" (POST /api/escrita), que volta a ficar
desligada a cada início do servidor. A leitura contínua roda NO SERVIDOR (continua
gravando mesmo se o navegador/tela dormir) e reconecta sozinha se o Bluetooth
cair. Os valores vão ao navegador por SSE (/api/stream).
Só atende em 127.0.0.1; POST de outra origem e Host estranho são recusados (403).
"""
import argparse, collections, json, mimetypes, os, re, subprocess, sys, threading, time, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import central, cli, conexao, dongle, faixas, falhas_info, layouts as _layouts, perfil as _perfil, psa5, registro, sessoes, temas as _temas, ver_captura

AQUI = os.path.dirname(os.path.abspath(__file__))
ESTATICOS = os.path.join(AQUI, 'static')
CAPTURAS = os.path.join(AQUI, '..', 'capturas')
MAX_TEMA = _temas.MAX_ZIP   # corpo máximo de POST /api/tema/importar
MAX_LAYOUT = _layouts.MAX_JSON   # corpo máximo de POST /api/layout/importar e /salvar
BUFFER_S = 600.0         # leituras guardadas em memória (gráficos e "marcar evento" sem REC)
PREFIXO_S = 60.0         # quanto do buffer entra numa gravação iniciada por "marcar evento"
MIME = {'.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2', '.html': 'text/html',
        '.svg': 'image/svg+xml', '.json': 'application/json', '.md': 'text/plain'}


class EscritaDesabilitada(PermissionError):
    """Comando que muda o estado da central pedido com a escrita desligada (HTTP 403)."""


class Sessao:
    def __init__(self, porta, perfil, captura=None, pasta=sessoes.PASTA, registros=sessoes.REGISTROS,
                 ajustes=faixas.AJUSTES, capturas=CAPTURAS, temas=None, layouts=None):
        self.perfil = perfil
        self.temas = temas or _temas.Repositorio()    # ativo e importados (docs/TEMA.md)
        self.layouts = layouts or _layouts.Repositorio()  # prontos, do usuário e o ativo (docs/LAYOUT.md)
        self.capturas = capturas
        self.ps = {m['ordem']: m for m in perfil.metricas}
        self.log = []
        self.con = conexao.Conexao(porta, perfil, captura, log=self._log, escrita=False)   # escrita: desligada ao iniciar
        self.monitor = None
        self.trava = threading.Lock()
        self.fx = faixas.Faixas(perfil, ajustes)
        self.ind = sessoes.Indice(pasta, registros)
        self.hist_falhas = falhas_info.Historico(os.path.join(pasta, 'falhas.json'))
        self.t0 = time.monotonic()
        self.buffer = collections.deque()          # (t, hora, vals, item)
        self.seq = 0
        self.cond = threading.Condition()
        self.lambda_ = registro.AnaliseLambda()
        self.o_lambda = perfil.lambda_['ordem'] if perfil.lambda_ else None
        self.o_luz = perfil.luz_falha['ordem'] if perfil.luz_falha else None
        self.gravacao = None
        self.trava_rec = threading.Lock()
        self.atuador_ativo = None
        self.simulador = False
        self._manter = False
        self._luz = None                            # luz de injeção na leitura anterior

    def _log(self, s):
        self.log = (self.log + [time.strftime('%H:%M:%S ') + s])[-300:]

    # ---- leituras ----
    def ao_ler(self, t, vals):
        hora = time.time()
        num = {m['ordem']: n for m, _, n, _ in vals}
        if self.o_lambda is not None and num.get(self.o_lambda) is not None:
            self.lambda_.add(t, num[self.o_lambda])
        lam = self.lambda_.resumo()
        ctx = faixas.contexto(self.perfil, num)
        est = {}
        for o, v in num.items():
            a = self.fx.avaliar(o, v, ctx, lam['diagnostico'])
            est[o] = [a['estado'], a['msg'], a['lado']]
        item = {'t': round(t - self.t0, 2), 'hora': round(hora, 2),
                'v': {o: (None if v is None else round(v, 3)) for o, v in num.items()},
                'txt': {m['ordem']: txt for m, _, _, txt in vals if m['tipo'] in ('bit', 'enum')},
                'est': est, 'lambda': lam, 'ctx': sorted(ctx)}
        with self.trava_rec:
            g = self.gravacao
            if g:
                g.linha(vals, t, hora, self.con.reconexoes)
        luz = num.get(self.o_luz)
        if g and self._luz == 0 and luz == 1:      # a luz acendeu gravando: ler qual falha foi
            threading.Thread(target=self._falhas_auto, args=(g, 'luz acendeu'), daemon=True).start()
        if luz is not None:
            self._luz = luz
        with self.cond:
            self.seq += 1
            item['seq'] = self.seq
            self.buffer.append((t, hora, vals, item))
            while self.buffer and t - self.buffer[0][0] > BUFFER_S:
                self.buffer.popleft()
            self.cond.notify_all()

    def itens_desde(self, seq):
        with self.cond:
            out = []
            for x in reversed(self.buffer):
                if x[3]['seq'] <= seq:
                    break
                out.append(x[3])
        return out[::-1][-30:]

    def buffer_colunas(self, seg=BUFFER_S):
        with self.cond:
            itens = [x[3] for x in self.buffer]
        if itens:
            fim = itens[-1]['t']
            itens = [i for i in itens if fim - i['t'] <= seg]
        cod = {'neutro': 0, 'normal': 1, 'atencao': 2, 'fora': 3}
        return {'t': [i['t'] for i in itens], 'hora': [i['hora'] for i in itens],
                'v': {o: [i['v'].get(o) for i in itens] for o in self.ps},
                'est': {o: [cod[i['est'].get(o, ['neutro'])[0]] for i in itens] for o in self.ps},
                'lambda': [(i['lambda'] or {}).get('diagnostico') for i in itens]}

    # ---- estado ----
    def previa(self, acao, n=None):
        q = conexao.quadros_da_acao(self.perfil, acao, n, self.con.estado == 'conectado')
        if acao in ('rapido', 'completo'):     # gravando, a web também lê as falhas (só leitura)
            desc, p = central.modulo(self.perfil).quadro_falhas(self.perfil)
            q.append({'desc': desc + ' — só gravando: no início, no fim e quando a luz de falha acender',
                      'bytes': dongle.frame(p).hex(' ')})
        return q

    def estado(self):
        """Formato antigo (página /simples) + estado da conexão."""
        e = self.monitor.estado() if self.monitor else {'rodando': False}
        e.update(conexao=self.con.estado, reconexoes=self.con.reconexoes, ultimo_erro=self.con.ultimo_erro,
                 versao=self.con.versao, escrita=self.con.escrita)
        return e

    def resumo_estado(self):
        m, g = self.monitor, self.gravacao
        rec = None
        if g:
            rec = {'id': g.sid, 'nome': g.meta['nome'], 's': round(g.s(), 1), 'eventos': g.meta['eventos'][-20:],
                   'quedas': len(g.meta['quedas']), 'leituras': g.meta['leituras']}
        return {'conexao': self.con.estado, 'reconexoes': self.con.reconexoes, 'ultimo_erro': self.con.ultimo_erro,
                'versao': self.con.versao, 'rodando': bool(m and m.rodando), 'modo': m.modo if m else None,
                'taxa_hz': round(m.taxa, 1) if m else 0, 'leituras': m.leituras if m else 0,
                'rec': rec, 'atuador_ativo': self.atuador_ativo, 'escrita': self.con.escrita, 'etapa': self.con.etapa, 'porta': self.con.porta,
                'dica_erro': dica_erro(self.con.ultimo_erro) if self.con.estado != 'conectado' else ''}

    def parametros(self):
        p = self.perfil
        out = []
        for m in p.metricas:
            bit = m['tipo'] in ('bit', 'enum')
            v = m.get('valores') or {}
            esc = m.get('escala')
            out.append({'id': m['id'], 'ordem': m['ordem'], 'bloco': m['bloco'], 'curto': m['curto'], 'nome': m['nome'],
                        'unidade': m['unidade'], 'obs': m.get('obs', ''), 'nivel': m.get('nivel'),
                        'evidencia': m.get('evidencia', ''), 'pendente': p.pendente(m),
                        'tipo': 'bit' if bit else 'num', 'tipo_perfil': m['tipo'],
                        'textos': [v.get('0', 'não'), v.get('1', 'sim')] if m['tipo'] == 'bit' else None,
                        'mascaras': {int(k): t for k, t in v.items()} if m['tipo'] == 'enum' else None,
                        'valores_pendentes': m.get('valores_pendentes', []),
                        'decimais': p.decimais(m), 'min': esc[0] if esc else None, 'max': esc[1] if esc else None,
                        'analise': m.get('analise'), 'luz_falha': bool(m.get('luz_falha'))})
        return {'parametros': out, 'faixas': self.fx.todas(), 'lambda': self.o_lambda,
                'condicoes': {k: c['texto'] for k, c in p.condicoes.items()},
                'condicoes_rotulo': {k: c.get('rotulo', '') for k, c in p.condicoes.items()}}

    # ---- ações ----
    def _iniciar_rec(self, nome='', prefixo_s=0.0):
        if not (self.monitor and self.monitor.rodando):
            raise RuntimeError('inicie a leitura ao vivo antes de gravar')
        with self.cond:
            pre = [(t, h, v) for t, h, v, _ in self.buffer]
        if pre and prefixo_s:
            fim = pre[-1][0]
            pre = [x for x in pre if fim - x[0] <= prefixo_s]
        else:
            pre = []
        with self.trava_rec:
            if self.gravacao:
                return self.gravacao
            cap = self.con.cap.caminho if self.con.cap else None
            g = self.gravacao = sessoes.Gravacao(self.ind, nome, cap, pre, self.con.reconexoes)
            self.con._nota('REC: início da sessão %s %s' % (g.sid, nome))
        threading.Thread(target=self._falhas_auto, args=(g, 'início'), daemon=True).start()
        return g

    def _parar_rec(self, ler_falhas=True):
        with self.trava_rec:
            g, self.gravacao = self.gravacao, None
        if g:
            if ler_falhas:
                self._falhas_auto(g, 'fim')
            self.con._nota('REC: fim da sessão %s' % g.sid)
            return g.fechar()
        return None

    def _falhas_auto(self, g, motivo):
        """Lê as falhas (33, só leitura) durante a gravação: no início, no fim e quando a luz acende."""
        try:
            lst, _ = self.con.executar(lambda e: e.ler_falhas(), tentativas=1)
        except Exception as e:
            g.evento('falhas não lidas (%s): %s' % (motivo, e))
            return
        if lst is None:
            return
        self.hist_falhas.leitura(lst)
        g.falhas(motivo, [falhas_info.descrever(f, self.perfil) for f in lst])

    def executar(self, acao, corpo=None):
        corpo = corpo or {}
        n, frase = corpo.get('n'), corpo.get('frase')
        c = self.con
        if acao in ('rapido', 'completo'):
            with self.trava:
                if self.monitor and self.monitor.rodando:
                    self.monitor.parar()
                self.monitor = conexao.Monitor(c, acao, ao_ler=self.ao_ler)
                self.monitor.iniciar()
                self._iniciar_manter()
                return {'modo': acao}
        if acao == 'parar':
            meta = self._parar_rec()
            with self.trava:
                if self.monitor:
                    self.monitor.parar()
            return {'sessao': meta}
        if acao == 'rec':
            g = self._iniciar_rec(corpo.get('nome', ''))
            return {'id': g.sid}
        if acao == 'parar-rec':
            return {'sessao': self._parar_rec()}
        if acao == 'evento':
            auto = self.gravacao is None
            g = self._iniciar_rec('', PREFIXO_S) if auto else self.gravacao
            ev = g.evento(corpo.get('texto') or 'evento')
            c._nota('evento: %s (s=%.1f)' % (ev['texto'], ev['s']))
            return {'evento': ev, 'rec_automatico': auto, 'id': g.sid}
        if acao == 'sessao':
            return self.ind.atualizar(corpo['id'], nome=corpo.get('nome'), notas=corpo.get('notas'))
        if acao == 'escrita':
            if not isinstance(corpo.get('ativa'), bool):
                raise ValueError('informe {"ativa": true|false}')
            c.escrita = corpo['ativa']
            c._nota('escrita %s' % ('HABILITADA' if c.escrita else 'desabilitada'))
            return {'escrita': c.escrita}
        if acao == 'faixas':
            self.fx.ajustar(int(corpo['ordem']), corpo.get('regras'))
            return self.fx.todas()
        if acao == 'desconectar':
            self._manter = False
            if self.monitor:
                self.monitor.parar()
            c.desconectar()
            return 'ok'
        if acao == 'porta':
            return self.trocar_porta(corpo.get('endereco', ''))
        if acao == 'conectar':
            c.parar.clear()
            c.ultimo_erro = ''
            c.executar(lambda e: True, tentativas=1)
            self._iniciar_manter()
            return {'versao': c.versao}
        if acao == 'chave-desligada':        # o teste do atuador só para desligando a chave
            self.atuador_ativo = None
            self._manter = False
            self._parar_rec(ler_falhas=False)
            if self.monitor:
                self.monitor.parar()
            c.desconectar()
            c._nota('usuário desligou a chave para parar o atuador')
            return 'ok'
        c.parar.clear()
        if acao == 'versao':
            return c.executar(lambda e: e.d.versao())
        if acao == 'falhas':
            lst, bruto = c.executar(lambda e: e.ler_falhas())
            if lst is not None:
                self.hist_falhas.leitura(lst)
                lst = [falhas_info.descrever(f, self.perfil) for f in lst]
            return {'falhas': lst, 'bruto': bruto.hex(' ')}
        if acao == 'apagar':
            e = self.perfil.escrita_apagar()
            if not e:
                raise ValueError('este perfil não tem o comando de apagar falhas')
            self._exigir_escrita()
            r = c.executar(lambda ecu: ecu.escrever(e['id']))
            self.hist_falhas.apagou()
            return {'resposta': r.hex(' ')}
        if acao == 'atuador':
            a = self.perfil.escrita_por_id(corpo.get('id') or '')
            if a['tipo'] != 'atuador':
                raise ValueError('%s não é um atuador' % a['nome'])
            self._exigir_escrita()
            liberar = frase == psa5.FRASE_PERIGO
            if a.get('perigoso') and not liberar:
                raise PermissionError('%s bloqueado no modo de segurança: digite %s para confirmar' % (a['nome'], psa5.FRASE_PERIGO))
            r = c.executar(lambda ecu: ecu.escrever(a['id'], liberar))
            self.atuador_ativo = {'nome': a['nome'], 'id': a['id'], 'pedido': a['pedido'], 'desde': time.strftime('%H:%M:%S')}
            return {'resposta': r.hex(' ')}
        raise ValueError('ação desconhecida')

    def _exigir_escrita(self):
        if not self.con.escrita:
            raise EscritaDesabilitada('escrita desabilitada')

    def _iniciar_manter(self):
        if not self._manter:
            self._manter = True
            threading.Thread(target=self._manter_vivo, daemon=True).start()

    def _manter_vivo(self):
        """Conectado e parado: manda o pedido de manutenção do perfil (visto nas capturas), senão a central larga a conexão.
        (manter() só envia se ficou >1 s sem falar; com a leitura ao vivo rodando, não manda nada.)"""
        while self._manter:
            time.sleep(1.5)
            if (self.monitor and self.monitor.rodando) or self.con.estado != 'conectado':
                continue
            try:
                self.con.executar(lambda e: e.manter(), tentativas=1)
            except Exception:
                self._manter = False

    def trocar_porta(self, endereco):
        endereco = endereco.strip()
        if self.simulador:              # senão o "simulador" passaria a falar com o carro de verdade
            raise PermissionError('modo simulador: para usar um scanner de verdade, abra o psascan normal')
        if not (dongle.MAC.match(endereco) or endereco.startswith('/dev/')):
            raise ValueError('endereço inválido: use AA:BB:CC:DD:EE:FF ou /dev/rfcomm0')
        self._manter = False
        if self.monitor:
            self.monitor.parar()
        self.con.desconectar()
        self.con.porta = endereco
        if not self.simulador:          # no simulador a "porta" é o pty; não mexe na configuração do carro
            os.makedirs(os.path.dirname(cli.CONF), exist_ok=True)
            with open(cli.CONF, 'w') as f:
                f.write(endereco + '\n')
        return {'porta': endereco}

    def scanners(self):
        """Aparelhos Bluetooth pareados (bluetoothctl) + o salvo."""
        out = []
        if self.simulador:
            return {'atual': self.con.porta, 'aparelhos': [{'endereco': self.con.porta, 'nome': 'simulador'}], 'simulador': True}
        try:
            r = subprocess.run(['bluetoothctl', 'devices', 'Paired'], capture_output=True, text=True, timeout=5)
            for linha in r.stdout.splitlines():
                p = linha.split(' ', 2)
                if len(p) >= 2 and p[0] == 'Device' and dongle.MAC.match(p[1]):
                    out.append({'endereco': p[1], 'nome': p[2] if len(p) > 2 else ''})
        except (OSError, subprocess.SubprocessError):
            pass
        if self.con.porta and not any(x['endereco'] == self.con.porta for x in out):
            out.append({'endereco': self.con.porta, 'nome': 'simulador' if self.simulador else 'salvo'})
        return {'atual': self.con.porta, 'aparelhos': out, 'simulador': self.simulador}

    def close(self):
        self._manter = False
        self._parar_rec(ler_falhas=False)
        if self.monitor:
            self.monitor.parar()
        self.con.close()


def dica_erro(erro):
    """Explica o erro de conexão em português de oficina."""
    e = (erro or '').lower()
    if not e:
        return ''
    if any(x in e for x in ('host is down', 'no route', 'refused', 'errno 112', 'errno 113', 'errno 111')):
        return 'O Bluetooth não achou o scanner: ele está na tomada OBD, com a ignição ligada? É o aparelho certo?'
    if 'no such file' in e or 'errno 2' in e:
        return 'Porta não encontrada: escolha o scanner na lista.'
    if 'erro: 7f 07' in e or 'erro: 7f 13' in e:
        return 'O scanner recusou configurar a linha K. Desligue e ligue a ignição e tente de novo.'
    if 'sem resposta' in e or 'timeout' in e:
        return 'A central não respondeu: confira se a ignição está ligada.'
    return ''


NOME_CAPTURA = re.compile(r'^[\w.-]+\.jsonl$')
NOME_SESSAO = re.compile(r'^[\w-]+$')


def handler(sessao):
    class H(BaseHTTPRequestHandler):
        def _json(self, obj, code=200):
            b = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(b)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(b)

        def _arquivo(self, caminho, tipo=None, baixar=None):
            try:
                with open(caminho, 'rb') as f:
                    b = f.read()
            except OSError:
                return self.send_error(404)
            self.send_response(200)
            ext = os.path.splitext(caminho)[1]
            tipo = tipo or MIME.get(ext) or mimetypes.guess_type(caminho)[0] or 'application/octet-stream'
            self.send_header('Content-Type', tipo + ('; charset=utf-8' if tipo.startswith('text/') else ''))
            self.send_header('Content-Length', str(len(b)))
            self.send_header('Cache-Control', 'no-cache')
            if baixar:
                self.send_header('Content-Disposition', 'attachment; filename="%s"' % baixar)
            self.end_headers()
            self.wfile.write(b)

        def log_message(self, *a):
            pass

        def _host_ok(self):
            # contra DNS rebinding: só aceita os nomes locais (a checagem de Origin sozinha não basta)
            porta = self.server.server_address[1]
            return self.headers.get('Host') in ('127.0.0.1:%d' % porta, 'localhost:%d' % porta)

        def _stream(self):
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            ultimo, t_estado, t_tema, t_layout = max(0, sessao.seq - 1), 0.0, sessao.temas.versao, sessao.layouts.versao
            try:
                while True:
                    with sessao.cond:
                        sessao.cond.wait_for(lambda: sessao.seq != ultimo, timeout=1.0)
                    itens = sessao.itens_desde(ultimo)
                    if itens:
                        ultimo = itens[-1]['seq']
                    out = ''.join('event: leitura\ndata: %s\n\n' % json.dumps(i, ensure_ascii=False) for i in itens)
                    if time.monotonic() - t_estado >= 1.0:
                        t_estado = time.monotonic()
                        out += 'event: estado\ndata: %s\n\n' % json.dumps(sessao.resumo_estado(), ensure_ascii=False)
                    if sessao.temas.versao != t_tema:       # o tema mudou: as páginas abertas recarregam o CSS
                        t_tema = sessao.temas.versao
                        out += 'event: tema\ndata: %s\n\n' % json.dumps({'id': sessao.temas.ativo_id(), 'versao': t_tema})
                    if sessao.layouts.versao != t_layout:   # o layout ativo mudou: a tela personalizável remonta
                        t_layout = sessao.layouts.versao
                        out += 'event: layout\ndata: %s\n\n' % json.dumps({'id': sessao.layouts.ativo_id(), 'versao': t_layout})
                    if out:
                        self.wfile.write(out.encode())
                        self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                return

        def do_GET(self):
            if not self._host_ok():
                return self.send_error(403)
            path, _, q = self.path.partition('?')
            args = dict(x.split('=', 1) for x in q.split('&') if '=' in x)
            try:
                return self._get(path, args)
            except (KeyError, ValueError) as e:
                self._json({'erro': 'não encontrado: %s' % e}, 404)
            except Exception as e:
                traceback.print_exc()
                self._json({'erro': '%s: %s' % (type(e).__name__, e)}, 500)

        def _get_tema(self, path, args):
            rep = sessao.temas
            if path == '/api/tema.css':
                return self._bytes(_temas.css(rep.ativo()).encode(), 'text/css; charset=utf-8')
            if path == '/api/tema':
                return self._json(rep.ativo())
            if path == '/api/temas':
                _, erro = rep.ativo_com_erro()
                return self._json({'temas': rep.listar(), 'ativo': rep.ativo()['id'], 'erro': erro})
            if path == '/api/tema/exportar':
                tid = args.get('id') or rep.ativo()['id']
                try:
                    b = rep.exportar(tid)
                except KeyError:
                    return self._json({'erro': 'tema não encontrado: %s' % tid}, 404)
                except _temas.TemaInvalido as e:
                    return self._json({'erro': str(e)}, 400)
                return self._bytes(b, 'application/zip', 'tema-%s.zip' % tid)
            m = re.match(r'^/tema/([a-z0-9-]+)/arquivos/([\w.-]+)$', path)
            if m:
                alvo = rep.arquivo(m.group(1), m.group(2))
                if alvo:
                    with open(alvo, 'rb') as f:
                        return self._bytes(f.read(), _temas.TIPOS[os.path.splitext(alvo)[1].lower()])
            self.send_error(404)

        def _bytes(self, b, tipo, baixar=None):
            self.send_response(200)
            self.send_header('Content-Type', tipo)
            self.send_header('Content-Length', str(len(b)))
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('X-Content-Type-Options', 'nosniff')
            if baixar:
                self.send_header('Content-Disposition', 'attachment; filename="%s"' % baixar)
            self.end_headers()
            self.wfile.write(b)

        def _post_tema(self, path):
            rep = sessao.temas
            tam = int(self.headers.get('Content-Length') or 0)
            if tam > MAX_TEMA:
                if tam <= 4 * MAX_TEMA:      # descarta o corpo para o cliente receber a resposta em vez de um pipe quebrado
                    self.rfile.read(tam)
                self.close_connection = True
                return self._json({'erro': 'arquivo grande demais'}, 413)
            corpo = self.rfile.read(tam)
            try:
                if path == '/api/tema/importar':
                    t = rep.instalar(corpo)
                    return self._json({'ok': {'id': t['id'], 'nome': t['nome']}})
                if path == '/api/tema/ativar':
                    tid = json.loads(corpo or b'{}').get('id')
                    rep.ativar(tid)
                    return self._json({'ok': tid})
            except _temas.TemaInvalido as e:
                return self._json({'erro': str(e)}, 400)
            except KeyError:
                return self._json({'erro': 'tema não encontrado'}, 404)
            except (ValueError, AttributeError) as e:
                return self._json({'erro': 'pedido inválido: %s' % e}, 400)
            self.send_error(404)

        def _get_layout(self, path, args):
            rep = sessao.layouts
            if path == '/api/layouts':
                _, erro = rep.ativo_com_erro()
                return self._json({'layouts': rep.listar(), 'ativo': rep.ativo()['id'], 'erro': erro})
            if path == '/api/layout':
                if args.get('id'):
                    return self._json(rep.layout(args['id']))
                return self._json(rep.ativo())
            if path == '/api/layout/exportar':
                lid = args.get('id') or rep.ativo()['id']
                return self._bytes(rep.exportar(lid), 'application/json', 'layout-%s.json' % lid)
            self.send_error(404)

        def _post_layout(self, path):
            rep = sessao.layouts
            tam = int(self.headers.get('Content-Length') or 0)
            if tam > MAX_LAYOUT:
                if tam <= 4 * MAX_LAYOUT:
                    self.rfile.read(tam)
                self.close_connection = True
                return self._json({'erro': 'arquivo grande demais'}, 413)
            corpo = self.rfile.read(tam)
            try:
                if path == '/api/layout/importar':
                    L = rep.importar(corpo)
                    return self._json({'ok': {'id': L['id'], 'nome': L['nome']}})
                j = json.loads(corpo or b'{}')
                if path == '/api/layout/salvar':
                    L = rep.salvar(j.get('layout'))
                    return self._json({'ok': {'id': L['id'], 'nome': L['nome']}})
                if path == '/api/layout/ativar':
                    rep.ativar(j.get('id'))
                    return self._json({'ok': j.get('id')})
                if path == '/api/layout/apagar':
                    rep.apagar(j.get('id'))
                    return self._json({'ok': j.get('id')})
            except _layouts.LayoutInvalido as e:
                return self._json({'erro': str(e)}, 400)
            except KeyError:
                return self._json({'erro': 'layout não encontrado'}, 404)
            except (ValueError, AttributeError) as e:
                return self._json({'erro': 'pedido inválido: %s' % e}, 400)
            self.send_error(404)

        def _get(self, path, args):
            if path == '/':
                return self._arquivo(os.path.join(ESTATICOS, 'index.html'))
            if path == '/simples':
                return self._arquivo(os.path.join(AQUI, 'web.html'))
            if path.startswith('/static/'):
                alvo = os.path.realpath(os.path.join(ESTATICOS, path[len('/static/'):]))
                if not alvo.startswith(os.path.realpath(ESTATICOS) + os.sep):
                    return self.send_error(404)
                return self._arquivo(alvo)
            if path == '/api/stream':
                return self._stream()
            if path.startswith('/api/tema') or path.startswith('/tema/'):
                return self._get_tema(path, args)
            if path in ('/api/layouts', '/api/layout') or path.startswith('/api/layout/'):
                try:
                    return self._get_layout(path, args)
                except KeyError:
                    return self._json({'erro': 'layout não encontrado: %s' % args.get('id', '')}, 404)
                except _layouts.LayoutInvalido as e:
                    return self._json({'erro': str(e)}, 400)
            if path == '/api/info':
                cap = sessao.con.cap
                p = sessao.perfil
                return self._json({'nome': p.nome, 'perfil': p.id, 'compatibilidade': p.compatibilidade,
                                   'porta': sessao.con.porta,
                                   'captura': cap.caminho if cap else None, 'dicas_lambda': registro.DICAS_LAMBDA,
                                   'atuadores': [{'id': a['id'], 'nome': a['nome'], 'pedido': a['pedido'],
                                                  'perigoso': bool(a.get('perigoso')), 'aviso': a.get('aviso', ''),
                                                  'nivel': a.get('nivel')} for a in p.atuadores()],
                                   'apagar': bool(p.escrita_apagar()),
                                   'apagar_aviso': (p.escrita_apagar() or {}).get('aviso', ''),
                                   'frase_perigo': psa5.FRASE_PERIGO, 'simulador': sessao.simulador,
                                   'escrita': sessao.con.escrita})
            if path == '/api/scanners':
                return self._json(sessao.scanners())
            if path == '/api/parametros':
                return self._json(sessao.parametros())
            if path == '/api/previa':
                try:
                    return self._json(sessao.previa(args.get('acao'), args.get('n')))
                except Exception as e:
                    return self._json({'erro': str(e)}, 400)
            if path == '/api/estado':
                return self._json(sessao.estado())
            if path == '/api/resumo':
                return self._json(sessao.resumo_estado())
            if path == '/api/buffer':
                return self._json(sessao.buffer_colunas(float(args.get('seg', BUFFER_S))))
            if path == '/api/log':
                return self._json(sessao.log[-60:])
            if path == '/api/conexao':
                c = sessao.con
                return self._json({'estado': c.estado, 'reconexoes': c.reconexoes, 'ultimo_erro': c.ultimo_erro,
                                   'versao': c.versao, 'porta': c.porta, 'historico': list(c.historico),
                                   'log': sessao.log[-300:], 'captura': c.cap.caminho if c.cap else None})
            if path == '/api/sessoes':
                return self._json(sessao.ind.listar())
            m = re.match(r'^/api/sessoes/([\w-]+)(\.csv)?$', path)
            if m:
                sid = m.group(1)
                if m.group(2):
                    b = sessoes.exportar_csv(sessao.ind, sid).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/csv; charset=utf-8')
                    self.send_header('Content-Length', str(len(b)))
                    self.send_header('Content-Disposition', 'attachment; filename="psascan-%s.csv"' % sid)
                    self.end_headers()
                    return self.wfile.write(b)
                return self._json(sessoes.carregar(sessao.ind, sid, sessao.perfil, sessao.fx))
            if path == '/api/falhas/historico':
                return self._json(sessao.hist_falhas.resumo(sessao.perfil))
            if path == '/api/capturas':
                pasta = sessao.capturas
                try:
                    nomes = sorted((n for n in os.listdir(pasta) if NOME_CAPTURA.match(n)), reverse=True)
                except OSError:
                    nomes = []
                return self._json([{'nome': n, 'bytes': os.path.getsize(os.path.join(pasta, n))} for n in nomes])
            m = re.match(r'^/api/capturas/(.+)$', path)
            if m and NOME_CAPTURA.match(m.group(1)):
                return self._json(ver_captura.quadros(os.path.join(sessao.capturas, m.group(1))))
            self.send_error(404)

        def do_POST(self):
            if not self._host_ok() or self.headers.get('Origin') not in (None, 'http://%s' % self.headers.get('Host')):
                return self.send_error(403)      # só a própria página pode mandar comandos
            if self.path.split('?')[0] in ('/api/tema/importar', '/api/tema/ativar'):
                return self._post_tema(self.path.split('?')[0])
            if self.path.split('?')[0] in ('/api/layout/importar', '/api/layout/salvar', '/api/layout/ativar',
                                           '/api/layout/apagar'):
                return self._post_layout(self.path.split('?')[0])
            acao = self.path.split('?')[0][len('/api/'):] if self.path.startswith('/api/') else ''
            n = self.rfile.read(int(self.headers.get('Content-Length') or 0))
            try:
                corpo = json.loads(n or b'{}')
                self._json({'ok': sessao.executar(acao, corpo)})
            except EscritaDesabilitada:
                self._json({'erro': 'escrita desabilitada'}, 403)
            except Exception as e:
                if not isinstance(e, (PermissionError, RuntimeError, ValueError, KeyError)):
                    traceback.print_exc()
                self._json({'erro': '%s: %s' % (type(e).__name__, e)}, 500)
    return H


class Servidor(ThreadingHTTPServer):
    daemon_threads = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--perfil', help='perfil da central (padrão: perfis/sagem-sl96/perfil.json)')
    ap.add_argument('--porta', default=None, help='MAC do scanner ou tty (padrão: o salvo com "cli.py porta")')
    ap.add_argument('--http', type=int, default=8096)
    ap.add_argument('--sim', action='store_true', help='usar o simulador em vez do scanner')
    ap.add_argument('--sim-queda', type=float, help='simulador: cair o Bluetooth a cada N s')
    ap.add_argument('--sim-lambda', default='oscila', choices=['oscila', 'pobre', 'rico', 'meio'])
    ap.add_argument('--sim-cenario', default='estrada', choices=['fixo', 'ralenti', 'estrada'])
    ap.add_argument('--sim-csv', help='simulador: reproduzir uma gravação (registros/*.csv)')
    ap.add_argument('--sem-captura', action='store_true')
    ap.add_argument('--dados', help='pasta para capturas/, registros/ e sessoes/ (padrão: a raiz do projeto;'
                                    ' com --sim: sim-dados/, para não misturar com os dados do carro)')
    o = ap.parse_args()
    try:
        perfil = _perfil.carregar(o.perfil)
    except _perfil.PerfilInvalido as e:
        sys.exit('perfil inválido: %s' % e)
    porta = o.porta or cli.porta_padrao()
    if o.sim:
        import sim
        porta = sim.Simulador(lambda_=o.sim_lambda, queda_a_cada=o.sim_queda, cenario=o.sim_cenario,
                              csv=o.sim_csv, imitar_0f=True, perfil=perfil).caminho
    raiz = o.dados or (os.path.join(AQUI, '..', 'sim-dados') if o.sim else os.path.join(AQUI, '..'))
    pastas = {k: os.path.join(raiz, k) for k in ('capturas', 'registros', 'sessoes')}
    cap = None if o.sem_captura else dongle.caminho_captura(pastas['capturas'])
    # no simulador, o tema ativo e os importados ficam em sim-dados/config (não mexe em ~/.config/psascan)
    rep = _temas.Repositorio(os.path.join(raiz, 'config')) if o.sim else _temas.Repositorio()
    s = Sessao(porta, perfil, cap, pastas['sessoes'], pastas['registros'], capturas=pastas['capturas'], temas=rep,
               layouts=_layouts.Repositorio(os.path.join(raiz, 'config')) if o.sim else _layouts.Repositorio())
    s.simulador = o.sim
    try:
        srv = Servidor(('127.0.0.1', o.http), handler(s))
    except OSError as e:
        if e.errno != 98:
            raise
        s.close()
        sys.exit('porta %d já em uso: outro psascan está aberto? Use o que já está em http://127.0.0.1:%d,'
                 ' pare o outro com Ctrl+C ou use --http com outra porta.' % (o.http, o.http))
    print('psascan web em http://127.0.0.1:%d  (porta %s)' % (o.http, porta), flush=True)
    if cap:
        print('captura em', cap, flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        s.close()


if __name__ == '__main__':
    main()
