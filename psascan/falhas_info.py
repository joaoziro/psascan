"""Códigos de falha: nomes e causas vêm do perfil da central; aqui ficam a descrição para a tela
e o histórico das leituras (sessoes/falhas.json).

Os códigos são decimais no perfil; a tela mostra em hexadecimal (ex. 295 = 127h).
Falha com nível "pendente" tem só um nome provável, ainda não visto em captura.
"""
import json, os, threading, time


def descrever(f, perfil):
    """f: falha de Central.ler_falhas() (ou {codigo, tipo, presente}) -> acrescenta nome, tipo_nome,
    causas, hex e pendente (nome só provável, ainda não confirmado em captura)."""
    info = perfil.info_falha(f['codigo'])
    return dict(f, nome=info['nome'], tipo_nome=perfil.nome_tipo(f['tipo']), causas=info['causas'],
                hex='%03X' % f['codigo'], pendente=info['pendente'], conhecido=info['conhecido'])


class Historico:
    """sessoes/falhas.json: {'leituras': [{'hora', 'falhas': [{codigo, tipo, presente}]}],
    'apagamentos': [hora]}"""
    MAX = 500

    def __init__(self, caminho):
        self.caminho = caminho
        self.trava = threading.Lock()
        try:
            with open(caminho, encoding='utf-8') as f:
                self.dados = json.load(f)
        except (OSError, ValueError):
            self.dados = {'leituras': [], 'apagamentos': []}

    def _salvar(self):
        os.makedirs(os.path.dirname(self.caminho), exist_ok=True)
        tmp = self.caminho + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(self.dados, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.caminho)

    def leitura(self, falhas, hora=None):
        with self.trava:
            self.dados['leituras'].append({
                'hora': hora or time.strftime('%Y-%m-%d %H:%M:%S'),
                'falhas': [{'codigo': f['codigo'], 'tipo': f['tipo'], 'presente': f['presente']} for f in falhas]})
            self.dados['leituras'] = self.dados['leituras'][-self.MAX:]
            self._salvar()

    def apagou(self, hora=None):
        with self.trava:
            self.dados['apagamentos'].append(hora or time.strftime('%Y-%m-%d %H:%M:%S'))
            self._salvar()

    def resumo(self, perfil):
        """Por (código, tipo): primeira/última vez vista, nº de leituras em que apareceu,
        se estava presente na última, e se apareceu na leitura mais recente."""
        with self.trava:
            leit = list(self.dados['leituras'])
            apag = list(self.dados['apagamentos'])
        out = {}
        for L in leit:
            for f in L['falhas']:
                k = (f['codigo'], f['tipo'])
                r = out.setdefault(k, descrever(dict(f), perfil) | {'primeira': L['hora'], 'vezes': 0,
                                                                        'vezes_presente': 0})
                r['ultima'] = L['hora']
                r['vezes'] += 1
                r['vezes_presente'] += bool(f['presente'])
                r['presente'] = f['presente']
        atuais = {(f['codigo'], f['tipo']) for f in leit[-1]['falhas']} if leit else set()
        for k, r in out.items():
            r['na_ultima_leitura'] = k in atuais
            r['apagada_em'] = next((a for a in reversed(apag) if a > r['ultima']), None)
        return {'falhas': sorted(out.values(), key=lambda r: r['ultima'], reverse=True),
                'leituras': len(leit), 'ultima_leitura': leit[-1]['hora'] if leit else None,
                'apagamentos': apag}
