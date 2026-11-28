#!/bin/bash
# Démarre Xvnc (bureau virtuel VNC) puis la session KDE Plasma.
# Exécuté dans Ubuntu via PRoot. Arguments : LARGEUR HAUTEUR.
set -e

export HOME=/root
export USER=root
export LANG=C.UTF-8

WIDTH="${1:-1280}"
HEIGHT="${2:-720}"
DISPLAY_NUM=1
export DISPLAY=":${DISPLAY_NUM}"

export XDG_RUNTIME_DIR=/tmp/xdg
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
mkdir -p /tmp/.X11-unix
rm -f "/tmp/.X${DISPLAY_NUM}-lock" "/tmp/.X11-unix/X${DISPLAY_NUM}" 2>/dev/null || true

echo "[plasma] Lancement de Xvnc :${DISPLAY_NUM} (${WIDTH}x${HEIGHT})..."
Xtigervnc ":${DISPLAY_NUM}" \
    -geometry "${WIDTH}x${HEIGHT}" \
    -depth 24 \
    -rfbport 5901 \
    -localhost yes \
    -SecurityTypes None \
    -NeverShared \
    &
XVNC_PID=$!

for i in $(seq 1 30); do
    if kill -0 "$XVNC_PID" 2>/dev/null && xdpyinfo -display ":${DISPLAY_NUM}" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$XVNC_PID" 2>/dev/null; then
        echo "[plasma] Xvnc s'est arrêté prématurément."
        exit 1
    fi
    sleep 1
done

xdpyinfo -display ":${DISPLAY_NUM}" >/dev/null 2>&1 || { echo "[plasma] Xvnc ne répond pas."; exit 1; }

echo "[plasma] Lancement de la session KDE Plasma..."
export XDG_CURRENT_DESKTOP=KDE
exec dbus-launch --exit-with-session startplasma-x11
