#!/usr/bin/env bash
# Desinstala la instalación de usuario de Velo (la de ./install.sh sin --system).
# Si lo instalaste con --system, usa:  sudo pacman -R velo-browser
set -euo pipefail
XDG_DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
rm -rf "$XDG_DATA/velo/app"
rm -f "$HOME/.local/bin/velo" "$XDG_DATA/applications/velo.desktop" \
      "$XDG_DATA/icons/hicolor/scalable/apps/velo.svg"
if [[ "${1:-}" == "--purge" ]]; then
  # Borra también el estado de Tor, la lista ampliada y tu configuración.
  rm -rf "$XDG_DATA/velo" "${XDG_CONFIG_HOME:-$HOME/.config}/velo"
fi
echo "Velo desinstalado."
