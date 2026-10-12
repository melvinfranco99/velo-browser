#!/usr/bin/env bash
# Instalador de Velo para Arch Linux.
#   ./install.sh            instala para tu usuario en ~/.local (sin root)
#   ./install.sh --system   construye e instala un paquete de pacman (requiere sudo)
#   ./install.sh --no-blocklist   no descarga la lista ampliada de bloqueo
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPS=(python python-pyqt6 python-pyqt6-webengine tor)
BLOCKLIST_URL="https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
SYSTEM=0
BLOCKLIST=1
for arg in "$@"; do
  case "$arg" in
    --system) SYSTEM=1 ;;
    --no-blocklist) BLOCKLIST=0 ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "Opción desconocida: $arg" >&2; exit 1 ;;
  esac
done

say() { printf '\033[1;35m==>\033[0m %s\n' "$*"; }

command -v pacman >/dev/null || { echo "Este instalador es para Arch Linux (pacman)." >&2; exit 1; }

missing=()
for dep in "${DEPS[@]}"; do pacman -Qq "$dep" >/dev/null 2>&1 || missing+=("$dep"); done
if ((${#missing[@]})); then
  say "Instalando dependencias: ${missing[*]}"
  sudo pacman -S --needed --noconfirm "${missing[@]}"
fi

XDG_DATA="${XDG_DATA_HOME:-$HOME/.local/share}"

if ((SYSTEM)); then
  say "Construyendo paquete de pacman"
  build="$(mktemp -d)"
  trap 'rm -rf "$build"' EXIT
  cp "$SRC/packaging/PKGBUILD.local" "$build/PKGBUILD"
  tar -C "$SRC" --exclude=.git -czf "$build/velo-src.tar.gz" velo packaging LICENSE README.md
  (cd "$build" && makepkg -si --noconfirm)
else
  PREFIX="$XDG_DATA/velo/app"
  BIN="$HOME/.local/bin"
  say "Instalando Velo en $PREFIX"
  rm -rf "$PREFIX"
  mkdir -p "$PREFIX" "$BIN" "$XDG_DATA/applications" "$XDG_DATA/icons/hicolor/scalable/apps"
  cp -r "$SRC/velo" "$PREFIX/"
  find "$PREFIX" -name '__pycache__' -prune -exec rm -rf {} +
  sed "s|@LIBDIR@|$PREFIX|" "$SRC/packaging/velo.in" > "$BIN/velo"
  chmod 755 "$BIN/velo"
  sed "s|@BINDIR@|$BIN|g" "$SRC/packaging/velo.desktop.in" > "$XDG_DATA/applications/velo.desktop"
  cp "$SRC/velo/data/velo.svg" "$XDG_DATA/icons/hicolor/scalable/apps/velo.svg"
  command -v update-desktop-database >/dev/null && update-desktop-database "$XDG_DATA/applications" || true
  command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q "$XDG_DATA/icons/hicolor" 2>/dev/null || true
  case ":$PATH:" in *":$BIN:"*) ;; *) say "Aviso: añade $BIN a tu PATH para usar el comando 'velo'." ;; esac
fi

if ((BLOCKLIST)); then
  say "Descargando lista ampliada de bloqueo (StevenBlack/hosts)"
  mkdir -p "$XDG_DATA/velo"
  tmp="$(mktemp)"
  if curl -fsSL --max-time 60 "$BLOCKLIST_URL" -o "$tmp" && grep -q '^0\.0\.0\.0 ' "$tmp"; then
    mv "$tmp" "$XDG_DATA/velo/blocklist-extra.txt"
    say "Lista instalada: $(grep -c '^0\.0\.0\.0 ' "$XDG_DATA/velo/blocklist-extra.txt") dominios"
  else
    rm -f "$tmp"
    say "No se pudo descargar la lista ampliada; se usará la lista base."
  fi
fi

say "¡Listo! Abre «Velo» desde el menú de aplicaciones o ejecuta: velo"
