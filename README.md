# Android TV easy

Sauvegarde de la **Philips 2021/22 UHD Android TV** (PH1M_EA_9970A, TPV, Android 11,
firmware RTT2.211108.001). Le lanceur et les outils viennent de
[tv-vibehack](https://github.com/Gathin23/tv-vibehack) (Gathin, licence MIT). Rien n'est
désinstallé : les paquets système sont seulement désactivés pour l'utilisateur, et chaque
étape s'annule.

L'image ci-dessous est celle du projet d'origine (une Xiaomi), pas une photo de cette Philips.

![Avant : accueil Google TV avec une pub. Après : le lanceur tv-vibehack](docs/stock-vs-new-side.jpg)

---

## Cette télé

| | |
|---|---|
| Accueil | `com.gathin.tvhome`, textes en français (Sources, Réglages, Bonsoir, TV en direct) |
| Dock, de gauche à droite | OQEE / Free, Crunchyroll, ADN, Netflix |
| Aussi installées, hors du dock | Prime Video, Apple TV, YouTube, Disney+, France TV |
| TV en direct | `org.droidtv.playtv` (masquée des tuiles, proposée dans Sources) |
| Laissé actif | Ambilight, HDMI, tuner, télécommande, Play Store, services Google, Chromecast |

Le lanceur Google d'origine (`com.google.android.tvlauncher`) est désactivé. Tant qu'il
reste actif, le bouton Maison l'ouvre encore, même si le nouveau lanceur est l'accueil par
défaut.

L'écran de veille du lanceur (horloge) est sélectionné, mais le délai d'extinction Philips
est resté sur « jamais » (7 jours). L'horloge ne s'affiche donc pas, et ce délai n'a pas été
raccourci : sur une OLED, il concerne toute la télé.

## Ce qui a été désactivé

Liste complète, commentée :
[`examples/philips-2021-uhd/debloat.txt`](examples/philips-2021-uhd/debloat.txt).

- rangées sponsorisées et recommandations
- télémétrie Philips
- mode démo magasin
- mises à jour du firmware par les airs (à réactiver avant une mise à jour système)
- Alexa, Play-Fi, DLNA, Mimi, Philips Fitness

Chromecast (`com.google.android.apps.mediashell`) a été réactivé ensuite. Il ne figure pas
dans la liste, pour qu'un prochain passage du script ne le recoupe pas.

## Problème connu : Play Store et Free qui plantent

Ce n'est pas le lanceur, ni des données corrompues du Play Store. Le décodeur vidéo
MediaTek (Codec2, service `android.hardware.media.c2@1.0-mediatek`) peut mourir en boucle,
quelques millisecondes après son démarrage. Dès qu'une application lance une vidéo, elle
plante avec elle :

- le Play Store, au moment de la bande-annonce « Recommandé pour vous »
- OQEE / Free, au moment où le flux de la chaîne démarre

Le plantage est un `SIGSEGV` dans `libcodec2_client.so`, pendant la création du décodeur.
Depuis l'ordinateur, on ne peut pas redémarrer les services média : Android refuse
(`Operation not permitted`). Le repli vers l'ancien décodeur
(`setprop debug.stagefright.ccodec 0`) ne survit pas à un redémarrage et ne change rien
tant que `media.codec` tourne déjà.

**Ce qui a marché : redémarrer la télé.** Au démarrage suivant, le service Codec2 est
resté actif et le Play Store s'est ouvert. Si Free ou le Play Store replantent au début
d'une vidéo, redémarrer la télé. Ne pas vider les données du Play Store, et ne pas
désactiver d'autres paquets pour ça.

## Refaire ou annuler

Ordinateur sur le même Wi-Fi que la télé. Sur macOS récent, le binaire `adb` est souvent
bloqué par la confidentialité « Réseau local » : ces scripts passent par
[`tools/tv.py`](tools/tv.py), un client ADB en Python.

Sur la télé : Réglages → Préférences de l'appareil → À propos → OK sept fois sur
**Build** → Options pour les développeurs → **Débogage USB**. Accepter l'ordinateur
quand la télé le demande.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=<ip de la télé>

tools/venv/bin/python -I tools/tv.py --probe
TV_HOST=$TV_HOST tools/disable.sh examples/philips-2021-uhd/debloat.txt
```

Pour revenir en arrière (paquets réactivés, lanceur Google remis en accueil) :

```bash
TV_HOST=<ip de la télé> tools/restore.sh examples/philips-2021-uhd/debloat.txt
```

Le dock et les textes français sont dans
[`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml)
et
[`launcher/app/src/main/res/values-fr/strings.xml`](launcher/app/src/main/res/values-fr/strings.xml).
Recompiler demande le JDK 17 et le SDK Android (platform 34, build-tools 34.0.0).

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
TV_HOST=<ip de la télé> launcher/build-and-install.sh
```

Le script désactive le lanceur Google à la fin. Ne le lancer que si l'APK s'est bien
installé : sinon le bouton Maison n'a plus d'accueil.

Les dumps de cette télé (numéro de série, réglages, captures) restent dans `backup/` en
local. Ce dossier est ignoré par git.

## Licence

MIT, voir [`LICENSE`](LICENSE). La police Outfit est sous SIL Open Font License, voir
`launcher/FONT-LICENSE-OFL.txt`.
