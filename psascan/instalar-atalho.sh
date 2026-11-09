#!/bin/sh
# Cria o ícone "psascan" no menu de aplicativos e na área de trabalho.
#   sh psascan/instalar-atalho.sh
# Clique: abre o painel do CARRO. Clique direito: simulador ou encerrar.
set -e
AQUI="$(cd "$(dirname "$0")" && pwd)"
APPS="$HOME/.local/share/applications"
mkdir -p "$APPS"
cat > "$APPS/psascan.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=psascan
GenericName=Diagnóstico Peugeot 306
Comment=Painel de diagnóstico da Sagem SL96 pelo scanner PSA COM
Exec=python3 "$AQUI/abrir.py"
Icon=$AQUI/static/icone.svg
Terminal=false
Categories=Utility;
StartupWMClass=psascan
Actions=simulador;encerrar;

[Desktop Action simulador]
Name=Abrir o simulador (não é o carro)
Exec=python3 "$AQUI/abrir.py" --sim

[Desktop Action encerrar]
Name=Encerrar psascan
Exec=python3 "$AQUI/abrir.py" --parar
EOF
chmod +x "$APPS/psascan.desktop"
update-desktop-database "$APPS" 2>/dev/null || true

MESA="$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")"
if [ -d "$MESA" ]; then
  cp "$APPS/psascan.desktop" "$MESA/psascan.desktop"
  chmod +x "$MESA/psascan.desktop"
  gio set "$MESA/psascan.desktop" metadata::trusted true 2>/dev/null || true
fi
echo "Pronto: procure \"psascan\" no menu de aplicativos${MESA:+ ou na área de trabalho ($MESA)}."
echo "Se a área de trabalho mostrar o ícone apagado: clique direito nele → \"Permitir execução\"."
