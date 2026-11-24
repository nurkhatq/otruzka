#!/usr/bin/env bash
# Сборка release-APK на vps3 (docker, образ tsd-android-build из /opt/tsd-build/Dockerfile)
# и выкладка на https://qoimams.asia/apk/sborka.apk. Ноут не собирает: Android SDK и
# gradle живут только на сервере.
#
# Ключ подписи — СВОЙ с v2.16 (владелец 2026-11-24): /opt/tsd-build/keys на vps3,
# в репозитории его нет и быть не должно. Подпись другая, чем у v2.15 (debug-ключ
# Android Studio Адилетхана), поэтому переход на 2.16 — один раз удалить старое
# приложение на ТСД и поставить заново; дальше обновления по баннеру, как раньше.
#
# Использование:  ./deploy_apk_vps3.sh ["текст заметки к версии"] [--no-version]
#   --no-version  выложить APK, но не трогать version.json (баннер на ТСД не покажется).
#                 Нужно, пока терминалы с 2.15 не переустановлены: их баннер скачал бы
#                 APK с другой подписью, и установщик отказал бы.
set -euo pipefail
cd "$(dirname "$0")"

NOTES="${1:-}"
NOVER=0
for a in "$@"; do [[ "$a" == "--no-version" ]] && NOVER=1; done
[[ "$NOTES" == "--no-version" ]] && NOTES=""
REMOTE="vps3"
REMOTE_DIR="/var/www/apk"
SRC="/opt/tsd-build/src-apk"

VCODE=$(grep -E '^\s*versionCode ' app/build.gradle | awk '{print $2}')
VNAME=$(grep -E '^\s*versionName ' app/build.gradle | awk '{print $2}' | tr -d '"')
[[ -n "$VCODE" && -n "$VNAME" ]] || { echo "!! versionCode/versionName не прочитались" >&2; exit 1; }

echo ">> Исходники на $REMOTE…"
rsync -a --delete --exclude .git --exclude 'app/build' --exclude '.gradle' --exclude '.idea' \
  --exclude local.properties ./ "$REMOTE:$SRC/"

echo ">> Сборка v$VNAME (code $VCODE) release на $REMOTE…"
ssh "$REMOTE" "bash /opt/tsd-build/build.sh $SRC release" | tail -5
APK="$SRC/app/build/outputs/apk/release/otgruzka-tsd.apk"
ssh "$REMOTE" "test -f $APK" || { echo "!! APK не собрался" >&2; exit 1; }

echo ">> Проверка подписи…"
ssh "$REMOTE" "docker run --rm -v $SRC:/src tsd-android-build \
  /opt/android-sdk/build-tools/36.0.0/apksigner verify --print-certs /src/app/build/outputs/apk/release/otgruzka-tsd.apk" \
  | grep -i "sha-256\|Signer" | head -2

ssh "$REMOTE" "cp $APK $REMOTE_DIR/sborka.apk && md5sum $REMOTE_DIR/sborka.apk | cut -d' ' -f1"
if [[ "$NOVER" == "1" ]]; then
  echo ">> version.json не тронут (--no-version): баннер на ТСД не покажется"
else
  ssh "$REMOTE" "cat > $REMOTE_DIR/version.json <<JSON
{
  \"version_code\": $VCODE,
  \"version_name\": \"$VNAME\",
  \"url\": \"https://qoimams.asia/apk/sborka.apk\",
  \"notes\": \"$NOTES\"
}
JSON"
  echo ">> version.json → $VNAME ($VCODE): ТСД покажут баннер обновления"
fi
echo ">> OK: https://qoimams.asia/apk/sborka.apk"
