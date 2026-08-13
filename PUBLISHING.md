# Публикация версий

Официальная версия публикуется в [GitHub Releases](https://github.com/alexaistudio/FamilyTasks/releases) как файл `FTasks-<версия>-release.apk`.

Release APK должен быть собран из соответствующего тега и подписан постоянным production-сертификатом. Debug APK имеет другой package ID и сертификат, поэтому официальной версией не считается и не публикуется.

## Приватность Git email

Все коммиты и аннотированные теги должны использовать GitHub noreply-адрес. Для этого репозитория:

```bash
git config user.name alexaistudio
git config user.email 304850078+alexaistudio@users.noreply.github.com
git config core.hooksPath .githooks
```

Hooks блокируют коммит или push с обычным email, а GitHub Actions повторно проверяет всю доступную историю.
