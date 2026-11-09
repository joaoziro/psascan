"""Conexão resistente a quedas + monitor contínuo (usado pelo CLI e pela web).

- Conexao.executar(fn) roda fn(ecu); se o Bluetooth cair, o scanner travar ou a
  central parar de responder, fecha tudo, espera um pouco e refaz a sequência
  de conexão sozinha (versão, linha K, início) e tenta de novo.
- A confirmação é por AÇÃO: quem chama mostra quadros_da_acao() uma vez; as
  reconexões repetem só a sequência de conexão (os mesmos quadros já mostrados).
- Monitor lê os parâmetros sem parar numa thread, grava CSV e analisa a lambda.
"""
import collections, threading, time

import central, dongle, perfil as _perfil, registro

ERROS_DE_LINK = (OSError, ValueError)   # OSError inclui Timeout/ConnectionError; ValueError = quadro/checksum


def quadros_da_acao(perfil, acao, n=None, conectado=False):
    """Lista [{'desc','bytes'}] do que a ação envia, sem enviar nada.
    Ações: versao, conectar, falhas, apagar, ao-vivo|completo, lambda|rapido, atuador (com n = posição
    na lista de atuadores) ou o id de qualquer item de perfil.escrita."""
    fam = central.modulo(perfil)
    P = lambda desc, p: {'desc': desc, 'bytes': dongle.frame(p).hex(' ')}
    L = lambda itens: [P(d, p) for d, p in itens]
    con = [] if conectado else L(fam.quadros_conexao(perfil))
    if acao == 'versao':
        return [P('versão do scanner', [dongle.CMD_VERSAO])]
    if acao == 'conectar':
        return con + L([fam.quadro_manter(perfil)])
    if acao == 'falhas':
        return con + L([fam.quadro_falhas(perfil)])
    if acao in ('ao-vivo', 'completo'):
        return con + L(fam.quadros_blocos(perfil))
    if acao in ('lambda', 'rapido'):
        return con + L(fam.quadros_blocos(perfil, [perfil.lambda_['bloco']] if perfil.lambda_ else [perfil.blocos[0][0]]))
    e = None
    if acao == 'apagar':
        e = perfil.escrita_apagar()
    elif acao == 'atuador':
        e = perfil.atuadores()[int(n)]
    else:
        try:
            e = perfil.escrita_por_id(acao)
        except KeyError:
            pass
    if e is None:
        raise ValueError('ação desconhecida: %s' % acao)
    return con + L(fam.quadros_escrita(perfil, e))


class Conexao:
    def __init__(self, porta, perfil=None, captura=None, log=lambda s: None, escrita=False):
        self.porta = porta
        self.perfil = perfil or _perfil.carregar()
        self._escrita = escrita
        self.cap = dongle.Captura(captura) if isinstance(captura, str) else captura
        self.log = log
        self.d = self.ecu = None
        self.historico = collections.deque(maxlen=200)   # (hora, estado, detalhe) para a aba Avançado
        self._estado = None
        self.ultimo_erro = ''
        self.estado = 'desconectado'
        self.reconexoes = 0
        self.versao = None
        self.etapa = ''
        self.trava = threading.RLock()
        self.parar = threading.Event()

    @property
    def escrita(self):
        """Comandos de escrita habilitados? Desligado por padrão; vale para a conexão atual e as próximas."""
        return self._escrita

    @escrita.setter
    def escrita(self, ligada):
        self._escrita = bool(ligada)
        if self.ecu:
            self.ecu.escrita = self._escrita

    @property
    def estado(self):
        return self._estado

    @estado.setter
    def estado(self, novo):
        if novo != self._estado:
            self.historico.append((time.strftime('%H:%M:%S'), novo, self.ultimo_erro if novo == 'reconectando' else ''))
        self._estado = novo

    def _nota(self, texto):
        self.log(texto)
        if self.cap:
            self.cap('nota', texto=texto)

    def desconectar(self):
        with self.trava:
            if self.d:
                self.d.close()
            self.d = self.ecu = None
            self.estado = 'desconectado'

    def _conectar(self):
        self.estado = 'conectando'
        self.etapa = 'abrir Bluetooth'
        self.d = dongle.Dongle(self.porta, log=self.log, captura=self.cap)
        nota = self.d.nota
        def etapa(texto):                    # as notas da sequência viram a etapa mostrada na tela inicial
            self.etapa = texto
            nota(texto)
        self.d.nota = etapa
        self.ecu = central.abrir(self.d, self.perfil, escrita=self.escrita)
        self.etapa = 'versão do scanner'
        self.versao = self.ecu.conectar()[0]
        self.etapa = 'conectado'
        self.estado = 'conectado'

    def executar(self, fn, tentativas=5):
        """tentativas=None: insiste até self.parar ser acionado (modo monitor/estrada)."""
        espera, n = 0.5, 0
        with self.trava:
            while True:
                try:
                    if not self.ecu:
                        self._conectar()
                    return fn(self.ecu)
                except ERROS_DE_LINK as e:
                    n += 1
                    self.ultimo_erro = '%s: %s' % (type(e).__name__, e)
                    self._nota('falha de comunicação (%s); reconectando' % self.ultimo_erro)
                    self.desconectar()
                    self.estado = 'reconectando'
                    if (tentativas is not None and n >= tentativas) or self.parar.is_set():
                        self.estado = 'desconectado'
                        raise
                    self.reconexoes += 1
                    if self.parar.wait(espera):
                        self.estado = 'desconectado'   # parou no meio da espera: não deixar "reconectando"
                        raise
                    espera = min(espera * 2, 5.0)

    def close(self):
        self.parar.set()
        self.desconectar()
        if self.cap:
            self.cap.close()


class Monitor:
    """Leitura contínua numa thread. modo 'rapido' = só bloco 0 (lambda, RPM,
    temperaturas, MAP, borboleta); 'completo' = blocos 0 e 1."""

    def __init__(self, con, modo='rapido', csv=None, janela=60.0, ao_ler=None):
        """ao_ler(t, vals): chamado na thread do monitor a cada leitura (a web usa para o SSE e o REC)."""
        self.con, self.modo = con, modo
        self.ao_ler = ao_ler
        p = con.perfil
        # leitura rápida: só o bloco da lambda (o primeiro bloco, se o perfil não tiver lambda)
        self.blocos = [p.lambda_['bloco'] if p.lambda_ else p.blocos[0][0]] if modo == 'rapido' else None
        self.csv = registro.RegistroCSV(csv) if csv else None
        self.lambda_ = registro.AnaliseLambda()
        self.historico = collections.deque()      # (t, mV) para o gráfico
        self.janela = janela
        self.ultimo = []                           # [(métrica, bruto, número, texto)]
        self.leituras = 0
        self.taxa = 0.0
        self.erro = ''
        self.rodando = False
        self._parar = threading.Event()
        self._t = None

    def iniciar(self):
        self.con.parar.clear()
        self._parar.clear()
        self.rodando = True
        self._t = threading.Thread(target=self._loop, daemon=True)
        self._t.start()

    def parar(self):
        self._parar.set()
        self.con.parar.set()
        if self._t:
            self._t.join(10)
        self.rodando = False
        if self.csv:
            self.csv.close()

    def _loop(self):
        t_taxa, n_taxa = time.monotonic(), 0
        try:
            while not self._parar.is_set():
                try:
                    vals = self.con.executar(lambda e: e.ler_parametros(self.blocos), tentativas=None)
                except ERROS_DE_LINK as e:
                    self.erro = str(e)
                    break
                t = time.monotonic()
                self.ultimo, self.leituras = vals, self.leituras + 1
                if self.csv:
                    self.csv.linha(vals, t)
                for m, raw, num, txt in vals:
                    if registro.eh_lambda(m) and num is not None:
                        self.lambda_.add(t, num)
                        self.historico.append((t, num))
                while self.historico and t - self.historico[0][0] > self.janela:
                    self.historico.popleft()
                if self.ao_ler:
                    try:
                        self.ao_ler(t, vals)
                    except Exception as e:       # problema na gravação não pode parar a leitura
                        self.con.log('erro ao processar leitura: %s' % e)
                n_taxa += 1
                if t - t_taxa >= 2:
                    self.taxa, t_taxa, n_taxa = n_taxa / (t - t_taxa), t, 0
        finally:
            self.rodando = False

    def estado(self):
        agora = time.monotonic()
        return {
            'rodando': self.rodando, 'modo': self.modo, 'leituras': self.leituras,
            'taxa_hz': round(self.taxa, 1), 'conexao': self.con.estado,
            'reconexoes': self.con.reconexoes, 'ultimo_erro': self.con.ultimo_erro or self.erro,
            'csv': self.csv.caminho if self.csv else None,
            'valores': [{'id': m['id'], 'nome': m['nome'], 'valor': txt, 'raw': raw, 'obs': m.get('obs', '')}
                        for m, raw, num, txt in self.ultimo],
            'lambda': {'serie': [(round(t - agora, 2), round(v)) for t, v in list(self.historico)],
                       'analise': self.lambda_.resumo()},
        }
