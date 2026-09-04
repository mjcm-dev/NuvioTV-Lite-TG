#!/user/bin

ADB="$HOME/Library/Android/sdk/platform-tools/adb"

$ADB logcat -c
$ADB logcat -v time | grep -E "TelegramRepo|StreamRepo|Context accept|accepted=|title=|se="
