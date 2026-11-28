#!/bin/bash
# Première exécution : installation de KDE Plasma + serveur VNC dans le rootfs.
# Exécuté dans Ubuntu via PRoot (voir ProotSession.bootstrapArgs()).
set -e

export HOME=/root
export USER=root
export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8

echo "[bootstrap] Mise à jour des listes de paquets..."
apt-get update

echo "[bootstrap] Installation de KDE Plasma, Xvnc et dbus (10 à 30 min)..."
apt-get install -y --no-install-recommends \
    dbus dbus-x11 \
    tigervnc-standalone-server \
    kde-plasma-desktop \
    plasma-workspace \
    x11-utils \
    xterm

echo "[bootstrap] Nettoyage..."
apt-get clean
rm -rf /var/lib/apt/lists/*

mkdir -p /root/.vnc /tmp/xdg
chmod 700 /tmp/xdg

echo "[bootstrap] Terminé. Vous pouvez démarrer Plasma."
