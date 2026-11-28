# Ubuntu Plasma (Android)

Application Android (ARM64) qui fait tourner un **Ubuntu avec le bureau KDE
Plasma** sur votre téléphone/tablette, via [PRoot](https://github.com/proot-me/proot)
(chroot sans root) et un serveur **VNC intégré**.

## Fonctionnement

1. **Installer Ubuntu** : l'application télécharge un rootfs Ubuntu 22.04 ARM64
   (~30 Mo), l'extrait dans son stockage privé, puis installe `kde-plasma-desktop`,
   `plasma-workspace`, `tigervnc-standalone-server` et `xterm` à l'intérieur
   (10 à 30 min selon la connexion).
2. **Démarrer Plasma** : PRoot lance `Xvnc` (bureau virtuel 1280x720, boucle
   locale uniquement) puis la session `startplasma-x11`. Le client VNC intégré
   au protocole RFB (écrit en Kotlin) affiche le bureau plein écran.
   - Touchez l'écran pour cliquer ;
   - Le bouton « Clavier » affiche une zone de saisie pour envoyer du texte.

Aucun accès root n'est nécessaire : tout est isolé dans les données de l'app,
et le serveur VNC n'écoute que sur `127.0.0.1`.

## Prérequis

- Appareil **ARM64** (aarch64) avec Android 8.0 (API 26) ou plus récent.
- ~2 Go d'espace libre et une connexion internet pour la première installation.

## Workflow de build (GitHub Actions)

Le workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) :

1. installe JDK 17 + Android SDK ;
2. récupère le binaire **PRoot arm64** (compilé pour Android/bionic) et le place
   dans `app/src/main/jniLibs/arm64-v8a/libproot.so` ;
3. compile `assembleDebug` et `assembleRelease` (l'APK release est signé avec la
   clef debug pour rester installable) ;
4. publie les APK en **artifact** `ubuntu-plasma-apks`.

Lancer manuellement : onglet *Actions* → *Build APK* → *Run workflow*.

## Build local

```bash
# Récupérer le binaire proot arm64 (voir étape du workflow CI), puis :
gradle assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

## Limitations connues

- Client VNC : encodage `Raw` uniquement (suffisant en boucle locale) ;
  connexion via un réseau distant non prévue.
- Pas de virtualisation : Plasma tourne en mode « proot » (traduction de
  chemins), les performances sont proches d'un Ubuntu natif ARM64.
- Clavier basique (texte, Entrée, Retour arrière, Tab) ; les raccourcis
  complexes ne sont pas encore mappés.
