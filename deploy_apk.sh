#!/usr/bin/env bash
# Выкладка APK + version.json на прод одной командой.
#
# Приложение сверяет свою версию с version.json и показывает баннер
# «Доступно обновление» — поэтому version.json ВСЕГДА генерится отсюда,
# из app/build.gradle (руками не редактировать: рассинхрон = вечный баннер
# или молчащее обновление). Перед релизом бампни versionCode/versionName.
#
# Использование:  ./deploy_apk.sh ["текст заметки к версии"]
# Требует: JAVA_HOME Android Studio, gradle рядом (../gradle-8.6) или в PATH,
#          ssh-хост vps3 (см. ~/.ssh/config).
set -euo pipefail
cd "$(dirname "$0")"

NOTES="${1:-}"
REMOTE="vps3"
REMOTE_DIR="/var/www/apk"
APK="app/build/outputs/apk/debug/otgruzka-tsd.apk"

VCODE=$(grep -E '^\s*versionCode ' app/build.gradle | awk '{print $2}')
VNAME=$(grep -E '^\s*versionName ' app/build.gradle | awk '{print $2}' | tr -d '"')
if [[ -z "$VCODE" || -z "$VNAME" ]]; then
  echo "!! Не смог прочитать versionCode/versionName из app/build.gradle" >&2
  exit 1
fi

echo ">> Сборка v$VNAME (code $VCODE)…"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
GRADLE="../gradle-8.6/bin/gradle"
[[ -x "$GRADLE" ]] || GRADLE="gradle"
"$GRADLE" assembleDebug --no-daemon -q

[[ -f "$APK" ]] || { echo "!! APK не собрался: $APK" >&2; exit 1; }

cat > /tmp/version.json <<JSON
{
  "version_code": $VCODE,
  "version_name": "$VNAME",
  "url": "https://qoimams.asia/apk/sborka.apk",
  "notes": "$NOTES"
}
JSON

echo ">> Заливка на $REMOTE…"
# Сначала APK, потом version.json — пока оба не на месте, баннер не сработает
scp "$APK" "$REMOTE:$REMOTE_DIR/sborka.apk"
scp /tmp/version.json "$REMOTE:$REMOTE_DIR/version.json"

LOCAL_MD5=$(md5 -q "$APK" 2>/dev/null || md5sum "$APK" | cut -d' ' -f1)
REMOTE_MD5=$(ssh "$REMOTE" "md5sum $REMOTE_DIR/sborka.apk | cut -d' ' -f1")
if [[ "$LOCAL_MD5" != "$REMOTE_MD5" ]]; then
  echo "!! MD5 не совпал (обрыв заливки?): $LOCAL_MD5 != $REMOTE_MD5" >&2
  exit 1
fi

echo ">> OK: v$VNAME (code $VCODE) выложена, md5 $LOCAL_MD5"
echo ">> ТСД покажут баннер обновления в течение часа (или при перезаходе)."
