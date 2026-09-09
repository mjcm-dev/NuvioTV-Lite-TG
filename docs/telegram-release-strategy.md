# Estrategia Releases Telegram — Doble Fork

Objetivo: mantener dos repos limpios con el mismo módulo TG (`docs/telegram-requirements.md`) y generar releases propias a partir de nuevas releases/tags en los repos origen, sin mezclar trabajo experimental.

## 1. Mapa de repos

| Repo propio | Origen upstream | Contenido propio |
|---|---|---|
| `repo-full-TG` | `https://github.com/NuvioMedia/NuvioTV` | `dev` sigue a upstream + capa TG (`feat/telegram`) |
| `repo-lite-TG` | `https://github.com/hackerslash/NuvioTV-Lite` | `dev` sigue a upstream-lite + capa TG (`feat/telegram`) |

Reglas:

- `dev` siempre refleja el origen + merges revisados. Nunca se desarrolla TG directo en `dev`.
- Todo TG vive en `feat/telegram` (o `feat/telegram-*` por iteración) y se integra a `dev` por PR.
- Los tags propios nunca reutilizan el nombre del tag upstream. Formato: `<upstream-tag>-tg.1`, p. ej. `v1.4.4-lite-tg.1`.
- `docs/telegram-requirements.md` es idéntico en ambos repos (única fuente de verdad funcional).

## 2. Remotos recomendados

```bash
# repo-full-TG
git remote add upstream https://github.com/NuvioMedia/NuvioTV.git
# repo-lite-TG
git remote add upstream https://github.com/hackerslash/NuvioTV-Lite.git
git fetch upstream --tags
```

`origin` = fork propio. `upstream` = solo lectura, nunca push directo.

## 3. Detección de nuevas releases origen

Fuentes a vigilar (orden de fiabilidad):

1. Tags Git: `git ls-remote --tags upstream`.
2. GitHub Releases: `https://github.com/<org>/<repo>/releases/latest` y feed de tags.
3. Commits en `upstream/dev`: `git log upstream/dev --oneline -20`.

Automatización sugerida: workflow programado `sync-upstream.yml` con `cron: 0 6 * * *` + `workflow_dispatch` que:

- hace `fetch upstream --tags`,
- compara `upstream/dev` vs `dev` local,
- si hay avance o tag nuevo, crea rama `sync/upstream-YYYYMMDD` y abre PR a `dev`,
- etiqueta el PR con el tag origen detectado.

## 4. Flujo de integración por cada release origen

1. Crear rama `sync/upstream-<fecha>` desde `dev`.
2. `git merge upstream/dev --no-edit` (preferir merge sobre rebase en `dev` para historial auditable).
3. Resolver conflictos: por defecto gana upstream, salvo bloques TG delimitados (`// TG-START` … `// TG-END`) que se re-aplican desde `feat/telegram`.
4. Actualizar `CHANGELOG.md` con sección `<versión>-TG`: qué tag origen porta + fixes TG incluidos.
5. PR → `dev` → CI verde (compilación + matriz mínima de pruebas del §8 de requisitos).
6. Hacer `rebase` de `feat/telegram` sobre el nuevo `dev` y resolver solo lo TG.

## 5. Generación de la release propia

Workflow manual `tg-release.yml` (`workflow_dispatch`, input `upstream_tag`, p. ej. `v1.4.4-lite`):

1. Parte de `dev` ya sincronizado.
2. Ajusta `versionName`/`versionCode` en `app/build.gradle.kts`:
   - `versionName = "<upstream>-tg"` sin espacios,
   - `versionCode` = siguiente entero (nunca reutilizar el de upstream).
3. Compila matrices:
   - full: `:app:assembleFullRelease` (y `universal` si aplica),
   - lite: `:app:assembleLiteRelease` por ABI (`armeabi-v7a`, `arm64-v8a`, `x86_64`, `x86`) + `universal`.
   - Comando debug equivalente para QA: `:app:assembleLiteDebug` / `:app:assembleFullDebug`.
4. Crea tag anotado `git tag -a <upstream>-tg.1 -m "TG port of <upstream>"` y `git push origin <tag>`.
5. Publica GitHub Release con: APKs por ABI, notas (origen + cambios TG), hash/SHA si se requiere, y aviso de credenciales necesarias (`TELEGRAM_API_ID/HASH`, `TMDB_API_KEY`).
6. Para Lite, verificar que `GITHUB_OWNER/GITHUB_REPO` apuntan al fork propio para que la OTA in-app busque las releases correctas.

## 6. Versionado y compatibilidad

- Un tag upstream → N tags propios (`-tg.1`, `-tg.2`…) para fixes sin cambiar base.
- Nunca mover un tag publicado. Un fix = nuevo sufijo.
- `versionCode` siempre creciente por package (`lite` y `full` llevan contadores independientes si usan distinto `applicationId`).
- Mantener `release_test_cases.csv` (si existe en origen) + matriz TG del §8 antes de publicar.

## 7. Limpieza inicial desde cero (checklist)

1. Fork limpio desde el tag/rama origen elegido (`upstream/dev`).
2. Copiar `docs/telegram-requirements.md` (este módulo) como primer commit de spec.
3. Implementar por bloques en este orden: AUTH → PLAY → STORE → RESUME → MOVIE → SERIES.
4. Configurar `local.example.properties` con claves vacías + docs de obtención.
5. Verificar ABIs: `armeabi-v7a` OK, resto degradan sin crash.
6. Pasar matriz mínima y solo entonces etiquetar primera `-tg.1`.

## 8. Convención de marcadores TG (para merges futuros)

Todo código Telegram va delimitado para re-aplicarlo mecánicamente tras cada merge de upstream:

- `// TG-START: <motivo> (re-apply on upstream merge)` … `// TG-END` en Kotlin/Java/Gradle
  (`<!-- TG-START … -->` en XML/Markdown, `# TG-START …` en properties).
- `// TG-ONLY-FILE: …` en la primera línea de ficheros 100 % Telegram
  (`core/telegram/*`, `TelegramRepository*`, `TelegramAuth*`, binding `org/drinkless/tdlib/*`,
  tests `core/telegram/*`, `TELEGRAM_STREAMING_PLAN.md`).

Regla de resolución: ante un conflicto, quedarse con la versión upstream y re-aplicar
encima todos los bloques `TG-START`/`TG-END` + ficheros `TG-ONLY-FILE`. Verificar con
(este doc se auto-excluye del conteo porque menciona los marcadores en prosa):

```bash
git grep -c "TG-START" -- 'app/*' 'local.example.properties' | awk -F: '{s+=$2} END {print s}'
git grep -c "TG-END" -- 'app/*' 'local.example.properties' | awk -F: '{s+=$2} END {print s}'
```

Ambos conteos deben coincidir entre sí y no bajar tras el merge. Después, pasar la
matriz TG del §8 de `telegram-requirements.md`.
