# Requisitos Módulo Telegram (TG) — NuvioTV

## 0. Propósito y alcance

Integrar la cuenta personal de Telegram del usuario como fuente de streams dentro de la app Android TV, con búsqueda de películas y series, reproducción directa, caché acotada y continuación de la última fuente vista.

Este documento es una especificación de **peticiones (qué debe hacer el sistema)**, no de soluciones. Otro LLM debe poder reconstruir el módulo desde cero, en limpio, a partir de este fichero en una sola petición. No prescribe clases, ficheros ni llamadas concretas.

**Fuera de alcance explícito:** cuenta Nuvio / Supabase self-hosted, sincronización de perfiles, addons, biblioteca, progreso, credenciales de proveedores, QR login de cuenta Nuvio, Edge Functions, torrent, plugins JS, trailers, launcher-sync. Nada de este documento requiere backend propio: solo cuenta Telegram + TMDB como enriquecimiento de títulos.

**Repos objetivo:** el mismo módulo debe construirse en dos repos limpios:

- `repo-full-TG`: fork desde `https://github.com/NuvioMedia/NuvioTV` (build completa).
- `repo-lite-TG`: fork desde `https://github.com/hackerslash/NuvioTV-Lite` (edición Lite para cajas de poca RAM).

El comportamiento TG es idéntico en ambos. En Lite, además, el módulo debe respetar el techo de memoria y no reactivar funcionalidades eliminadas por la edición Lite.

**Plataforma:** Android TV, `minSdk 24`. Dispositivo de referencia de gama baja: 1 GB RAM, 32-bit `armeabi-v7a`, Android 11. No debe romperse en `arm64-v8a` / `x86_64` / `x86`: si la librería nativa TDLib no existe para un ABI, Telegram queda deshabilitado con mensaje, sin crash.

**Idioma de referencia:** interfaz en español de España (`es-ES`). Las búsquedas deben contemplar siempre el título localizado **y** el título original (normalmente inglés), además del identificador IMDb cuando exista.

**Credenciales externas (inyectadas por configuración, nunca en el repo):**

- Telegram `API_ID + API_HASH` obtenidos en `my.telegram.org`.
- TMDB `API_KEY v3` (clave clásica `api_key=...`; no vale el token v4 `Read Access Token` tal como está diseñado el uso actual).

---

## 1. Vinculación cuenta TG

### 1.1 Objetivo

Permitir al usuario vincular y desvincular su cuenta personal de Telegram para habilitar las búsquedas TG.

### 1.2 Peticiones

- **TG-AUTH-1 — Estados visibles.** La pantalla Telegram debe representar inequívocamente cada situación: conectando, TDLib no disponible en este dispositivo, faltan credenciales en esta compilación, mostrar QR para vincular, pedir número de teléfono, pedir código de verificación (indicando longitud cuando se conozca), pedir contraseña de verificación en dos pasos, cuenta vinculada (mostrando el nombre cuando se conozca), error genérico con mensaje.
- **TG-AUTH-2 — Flujo principal QR.** El flujo por defecto es QR: mostrar un QR con instrucciones del tipo “Abre Telegram en tu móvil: Ajustes → Dispositivos → Vincular dispositivo, y escanea este código”. Mantener como alternativa el flujo teléfono → código → contraseña 2FA para cuando el QR no sea usable.
- **TG-AUTH-3 — Persistencia entre reinicios.** Una vez vinculada, la sesión debe sobrevivir al reinicio de la app sin pedir QR de nuevo. Si nunca hubo sesión, el arranque de la app no debe pagar coste de inicialización Telegram.
- **TG-AUTH-4 — Arranque con poco espacio.** Solo intentar resumir sesión si hay espacio libre mínimo. Si no lo hay, liberar primero descargas TG y, si sigue sin haberlo, no intentar resumir (pero sin borrar la sesión).
- **TG-AUTH-5 — Sin sesión no hay búsqueda.** Sin sesión válida, cualquier búsqueda TG devuelve lista vacía de forma silenciosa, sin error ni crash.
- **TG-AUTH-6 — Desvincular borra todo.** Desvincular debe cerrar sesión en Telegram y borrar todo rastro local (sesión + ficheros descargados), volviendo al estado inicial “no vinculada”.
- **TG-AUTH-7 — Errores terminales no reintentan en bucle.** Un fallo terminal de inicialización no debe reintentarse solo; requiere reentrar a la pantalla, desvincular o reiniciar la app.
- **TG-AUTH-8 — Ajustes de búsqueda siempre visibles.** La sección “Búsqueda TG” debe mostrarse haya o no sesión, porque afecta a búsquedas futuras. Estructura:
  - **Búsqueda avanzada para películas** (cabecera)
    - **Internacionalización** (interruptor, por defecto ACTIVADO)
    - **Descartar series** (interruptor, por defecto ACTIVADO): rechaza archivos con marcadores de temporada/episodio en búsquedas de películas (p. ej. el episodio `S06E03` cuando se busca la película del mismo título).
  - **Búsqueda avanzada para series** (cabecera)
    - **Internacionalización** (interruptor, por defecto ACTIVADO)
    - **Canal/Carpeta con solo episodios** (interruptor, por defecto ACTIVADO; ver sección 6)
- Todos los interruptores son por perfil.

### 1.3 Criterios de aceptación

- Sin librería nativa para el ABI → mensaje “no disponible”, app usable.
- Sin `API_ID/HASH` en la compilación → mensaje “faltan credenciales”, solo se ve el interruptor de búsqueda.
- QR escaneado en el móvil → pantalla muestra “Vinculada como \<nombre\>”.
- Reinicio de la app → sigue vinculada sin QR.
- Desvincular → vuelve a pedir vinculación y no quedan ficheros TG.

> **NOTA — por qué así:** el QR lo genera la propia librería Telegram contra la API oficial y exige `API_ID/HASH`; no existe QR “genérico” sin credenciales. La sesión vive en el almacenamiento nativo de la librería, no en preferencias de la app. En TV con mando el QR es mucho más usable que teclear el teléfono, de ahí el flujo QR-first.

---

## 2. Reproducción fuente TG

### 2.1 Objetivo

Reproducir un fichero de Telegram en el reproductor (ExoPlayer) como si fuera un stream progresivo con seek, sin servidor HTTP intermedio.

### 2.2 Peticiones

- **TG-PLAY-1 — URL interna opaca.** Cada resultado TG se representa con una URL interna de loopback que codifica la identidad `chat + mensaje + fichero`. El reproductor debe detectar ese host de loopback y rutearla a un DataSource TG dedicado, nunca al pipeline HTTP ni a la caché genérica de ExoPlayer.
- **TG-PLAY-2 — Lectura desde disco.** El DataSource debe resolver el tamaño del fichero vía TDLib, iniciar su descarga y leer del fichero temporal en disco. Cuando el reproductor pide una posición aún no descargada, debe esperar brevemente (no devolver fin de stream) hasta que el dato aparezca.
- **TG-PLAY-3 — Seek aleatorio real.** Debe soportar los saltos aleatorios que exige el contenedor MP4 (p. ej. tabla `moov` al final) sin devolver basura de huecos no descargados.
- **TG-PLAY-4 — Sin descargas concurrentes del mismo fichero.** No emitir una segunda orden de descarga del mismo fichero que cancele la anterior. Reutilizar/coalescar la descarga en curso ante lecturas y seeks cercanos en el tiempo.
- **TG-PLAY-5 — No forzar contenedor.** No asumir MP4; respetar el tipo detectado para no romper otros contenedores.
- **TG-PLAY-6 — Reintentos acotados y buffer conservador.** Política de reintentos finita (sin bucle infinito) y configuración de buffer específica TG, más conservadora que el streaming HTTP y pensada para 1 GB de RAM (decenas de MB de buffer objetivo, minutos de ventana máxima, back-buffer corto de ~2 s).

### 2.3 Criterios de aceptación

- Una película MP4 con índice al final reproduce de principio a fin y permite saltar adelante/atrás.
- Un seek lejano cambia la ventana de descarga sin corromper ni reiniciar en bucle.
- Sin dato todavía descargado se muestra buffering, no “sin fuentes” ni fin prematuro.

> **NOTA — por qué así:** la librería Telegram solo admite una descarga activa por fichero; una segunda orden cancela la primera. Versiones que pedían trozos de ~32 MB por cada lectura dejaban solo ese trozo en disco; el extractor MP4 saltaba a zonas vacías y leía basura → `Invalid NAL length` + crash del decodificador + reintento infinito. De ahí la regla “una sola descarga + lectura bloqueante”. El antiguo proxy HTTP intermedio se eliminó por este mismo motivo.

---

## 3. Gestión caché / almacenamiento fuente TG

### 3.1 Objetivo

No llenar un TV de 1 GB y no perder la sesión por falta de espacio.

### 3.2 Peticiones

- **TG-STORE-1 — Ubicaciones separadas.** Guardar la sesión y las descargas TG en el almacenamiento interno de la app, separadas de la caché genérica del reproductor.
- **TG-STORE-2 — Techos.** Aplicar un techo total para descargas TG (valor inicial recomendado: entre ~900 MB y ~1200 MB) y un umbral de espacio libre mínimo para hacer streaming (recomendado ~700 MB) con un mínimo absoluto más bajo solo para resumir sesión (recomendado ~128 MB). Los valores exactos son ajustables; lo obligatorio es que existan y se respeten.
- **TG-STORE-3 — Limpieza por antigüedad de uso.** Cuando se supere el techo o falte espacio libre, borrar primero los ficheros menos recientemente modificados, protegiendo siempre el fichero en reproducción en curso. Disparar la limpieza al abrir un stream y al arrancar la app, con un intervalo mínimo anti-thrashing (recomendado ~15 s).
- **TG-STORE-4 — Acciones de usuario.** Ofrecer “Liberar almacenamiento (mantener cuenta)” que borra cachés sin cerrar sesión Telegram. “Desvincular cuenta” borra además la sesión.
- **TG-STORE-5 — Degradación con poco espacio.** Con poco espacio libre, descargar por ventanas pequeñas en lugar de intentar el fichero entero.
- **TG-STORE-6 — Sin caducidad por edad en v1.** No se exige TTL por antigüedad; basta la limpieza por presión de espacio. No se exige ajuste de tamaño expuesto al usuario en v1.

### 3.3 Criterios de aceptación

- Un maratón de visionado no supera el techo configurado; el fichero en curso nunca es borrado a mitad de reproducción.
- “Liberar almacenamiento” no desloguea Telegram; “Desvincular” sí lo hace y no deja ficheros.

> **NOTA — por qué así:** una película puede ocupar 4–15 GB y el flash del dispositivo (~8 GB) se comparte con la caché del reproductor e imágenes. Sin limpieza LRU + protección del fichero en curso, o bien se llena el disco (riesgo para la propia sesión) o bien se borra lo que se está viendo.

---

## 4. Última fuente reproducida

### 4.1 Objetivo

Al volver a una película/episodio, priorizar la misma fuente TG que se vio la última vez.

### 4.2 Peticiones

- **TG-RESUME-1 — Identidad estable.** La identidad para resume es `chat + mensaje`. El identificador interno de fichero de la librería es volátil entre sesiones/dispositivos y debe ignorarse para comparar.
- **TG-RESUME-2 — Origen preferente.** Determinar la fuente preferente así: si hay progreso visto a medias (recomendado: entre ~2 % y ~90 %) con URL base, usarlo; en caso contrario (caso habitual TG, donde el progreso no guarda proveedor), usar la última URL cacheada de ese contenido (TTL largo, recomendado ~30 días). Un flag “empezar desde el principio” anula la preferencia.
- **TG-RESUME-3 — Comparación y orden.** Coincide si la URL candidata es exactamente la preferida **o** comparte `chat + mensaje`. Las coincidentes suben al frente de la lista; el resto conserva su orden.
- **TG-RESUME-4 — Nombre de fichero.** El nombre del fichero es solo presentación/detección de tipo, nunca identidad para resume.
- **TG-RESUME-5 — Casos borde.** Si solo cambió el identificador volátil de fichero, sigue coincidiendo (pero la insignia “Resume” puede no mostrarse si esta exige igualdad exacta: documentarlo como comportamiento conocido). Si cambió el mensaje (resubida/borrado), se pierde el resume (correcto). Un mensaje con varios ficheros es ambiguo. Una caché muy vieja puede apuntar a una URL muerta: en ese caso caer al selector, nunca auto-reproducir en bucle.

> **NOTA — por qué así:** el identificador de fichero cambia aunque el vídeo sea el mismo; `chat + mensaje` es lo único estable entre sesiones. Por eso el resume es por “ámbito” y no por URL completa.

---

## 5. Búsquedas películas

### 5.1 Objetivo

Devolver ficheros de vídeo reproducibles que correspondan a la película pedida, evitando basura (ebooks, cómics, muestras, partes).

### 5.2 Peticiones

- **TG-MOVIE-0 — Interruptor de internacionalización.** Si está desactivado, la semilla es mínima (“como NuvioTV”): solo el título de la interfaz (metadatos del addon) + año + `imdbId`, sin expansión TMDB. El resto de esta sección describe el modo activado (por defecto).
- **TG-MOVIE-1 — Semillas de títulos.** Construir la lista de títulos candidatos en este orden de preferencia: datos TMDB (detalles + títulos alternativos + IDs externos), metadatos cacheados del addon, metadatos del addon primario. Si el idioma preferido no es inglés, añadir además los títulos en inglés. Incluir siempre `títulos + año + imdbId` cuando se conozcan. Normalizar IDs tipo `tt...:temporada:episodio` a `tt...` antes de cualquier lookup.
- **TG-MOVIE-1b — Variante regional España vs Latinoamérica.** Cuando la interfaz esté en español, resolver el bloque (España o Latinoamérica) por esta cadena: idioma fijado en la app → país del locale del sistema → si el locale no trae país (`es` a secas), desempate por timezone (zonas `Europe` → España, zonas `America` → LatAm; `es-US` → LatAm). Pedir a TMDB `es-ES` para España y `es-MX` para LatAm (son las únicas traducciones al español que TMDB ofrece), y priorizar alternativos del bloque propio. **La query lleva siempre ambas variantes; la región solo ordena el ranking** (ver TG-MOVIE-5).
- **TG-MOVIE-2 — Términos de consulta.** Consultar hasta ~6 títulos candidatos con un máximo de ~14 términos. Variantes por título: base, acrónimo (iniciales, 2–8 letras; p. ej. título multi-palabra → sigla), sin año, sin marcador de secuela al final (`2`, `II`, `Part 2`), tokens normalizados sin palabras vacías ES/EN más los últimos 2 tokens. Si hay `imdbId` válido (`tt` + 7–9 dígitos), intercalar `variante + imdb` y añadir el `imdb` solo al final. Buscar tanto en mensajes con documento como con vídeo, con límite alto por consulta y corte en el top ~40 global.
- **TG-MOVIE-3 — Filtros duros.** Solo mensajes de documento/vídeo con nombre de fichero no vacío y tamaño > 0. Rechazar no-vídeo (ebooks, cómics, documentos: `cbz/cbr/pdf/epub/mobi/azw…`). Aceptar extensiones de vídeo (`mkv/mp4/avi/mov/wmv/flv/webm/m4v/mpg/mpeg/m2ts/ts/3gp`) o `mime video/*`. Rechazar archivos partidos (`*.7z.001`, `*.002`…) como no reproducibles. Tamaño mínimo recomendado: 50 MB para películas.
- **TG-MOVIE-4 — Aceptación por título.** Rechazar si los años conocidos difieren en más de 1. Aceptar si la puntuación de título ≥ 0.70, o el fichero menciona el `imdbId`, o la consulta era solo `imdbId`. Bonificar cuando el fichero menciona el `imdbId`. La puntuación combina solape de tokens (con sinónimos ES/EN como hombre/man, etc.) y similitud de texto, penalizando tokens extra.
- **TG-MOVIE-5 — Orden.** Puntuación descendente, preferencia de idioma según bloque: España (castellano de España > español genérico > latam > dual ES/EN > dual > inglés), Latinoamérica (latino > español genérico > castellano > dual ES/EN > dual > inglés), resto como antes; calidad (`4K > 1080 > 720 > …`), tamaño.
- **TG-MOVIE-6 — Sin temporada/episodio.** Las películas no usan términos S/E, ni contexto de canal, ni fallback por canal.

### 5.3 Criterios de aceptación

- Buscar una película de una sola palabra encuentra variantes pese a ruido (p. ej. devuelve vídeos y descarta ebooks/pdf/partes).
- Con título localizado + original + sigla + `imdbId`, los canales que usan cualquiera de esas formas son alcanzados.
- Si TMDB falla, la búsqueda sigue funcionando con metadatos locales del addon.

> **NOTA — por qué así:** muchos canales nombran en inglés/original o con sigla aunque la interfaz esté en español. Sin título original + `imdbId` + acrónimo, títulos de 1 palabra se pierden o traen ruido. En pruebas TMDB devolvió 401; por eso el fallback a metadatos locales es obligatorio y TMDB caído nunca debe bloquear la búsqueda.

---

## 6. Búsquedas series

Incluye todo lo de películas, más lo siguiente.

### 6.1 Peticiones

- **TG-SERIES-0 — Interruptor de internacionalización.** Si está desactivado: semilla mínima como en TG-MOVIE-0 y patrones solo numéricos (`S06E03`, `6x03`, `E05`); quedan fuera los patrones con palabras (`Temporada/Capítulo`, `Season/Episode`, `T02E05`, `Ep 5`, `Cap 5`). El resto de esta sección describe el modo activado (por defecto).
- **TG-SERIES-1 — Términos con episodio.** Hasta ~18 términos cuando hay temporada/episodio. Términos S/E: `S02E05`, `S2E5`, `2x05`, `02x05`, `E05`, `Ep 5`, `Cap 5` (más `T02E05` en fallback por canal). Reforzar con combinaciones `3 títulos × 3 términos [+imdb]` y variantes `“título + término S/E”`.
- **TG-SERIES-2 — Formatos reconocidos.** Reconocer `S06E03`/`S6E3`, `6x03`/`12x09` **sin** confundir `720p`/`1080p` con episodio, `Temporada X Capítulo/Episodio Y`, `T02E05`, y `E/Ep/Cap N` suelto. Límites razonables (temporada ≤ 100, episodio ≤ 2000). Extraer además calidad (`480/720/1080/2160p` + `4k`) y año (último `19xx/20xx`).
- **TG-SERIES-3 — Tamaño mínimo recomendado:** 20 MB para series (menor que películas porque un episodio pesa menos).
- **TG-SERIES-4 — Temporada/episodio obligatorios.** La temporada/episodio efectivos (parseados del nombre, o número suelto 1–200, o token compacto de 3–4 dígitos solo en fallback) deben igualar lo pedido. Si falta o no coincide → descartar con motivo `missing/mismatch season/episode`. En fallback por canal se permite relajar la temporada solo si hay token de temporada o token exacto + episodio correcto.
- **TG-SERIES-5 — Canal/Carpeta con solo episodios (interruptor, por defecto ACTIVADO por perfil).** Si el nombre de archivo no trae el título pero sí marcador S/E, aceptar usando el título del canal o carpeta cuando su puntuación ≥ 0.70 (con bonificación acotada). El fallback por canal solo se ejecuta si hay 0 resultados **y** el interruptor está activado **y** hay S/E pedido: buscar chats candidatos (límite ~24), escanear historial paginado (~80 mensajes × ~80 páginas), umbral estricto inicial ~0.72 con reintento relajado ~0.58. Excepción: token exacto pedido + chat ≥ 0.58 → aceptar con puntuación base 0.70.
- **TG-SERIES-6 — Diagnóstico acotado.** Guardar una muestra acotada de descartes S/E (motivo, término, puntuación de canal, fichero, pedido vs parseado) y registrarla acotada en log. No guardar todo.

### 6.2 Criterios de aceptación

- `S06E03` acepta `6x03`, `S6E3` y estilos `T..E..`; `720p` nunca se interpreta como episodio.
- Un archivo `6x03.mkv` en un canal con el título de la serie acepta por contexto aunque el fichero no traiga el título.
- Si solo hay `.zip`/partes → 0 resultados (correcto, no es vídeo).
- Con el interruptor desactivado no hay contexto ni fallback por canal.

> **NOTA — por qué así:** gran parte del contenido de series circula como `6xNN` o solo `E05`, con el título únicamente en el canal. Sin contexto + fallback, las series pierden la mayoría de resultados. Los umbrales y límites existen para no barrer cientos de chats en un dispositivo de 1 GB.

---

## 7. Requisitos no funcionales

- **RNF-1 Arranque:** sin sesión TG previa, el arranque no debe encarecerse por Telegram.
- **RNF-2 Memoria:** verbosidad de la librería al mínimo (solo errores) en gama baja; buffers TG acotados; sin consultas masivas sin límite.
- **RNF-3 Secretos:** ninguna clave en el repo; fichero de ejemplo con vacíos y documentación de obtención.
- **RNF-4 ABIs:** soportar al menos `armeabi-v7a`; degradar a “no disponible” donde falte la librería nativa.
- **RNF-5 Privacidad/logs:** no registrar tokens, contraseñas ni rutas sensibles; muestra de descartes acotada.

## 8. Matriz mínima de pruebas

- Vinculación: sin nativa, sin claves, QR correcto, resume tras reinicio, desvincular borra todo.
- Reproducción: MP4 con índice al final + seek, seek lejano, pausa/reanuda.
- Almacenamiento: respeta techo, protege fichero en curso, “liberar” vs “desvincular”.
- Resume: mismo chat/mensaje con fichero distinto → prioriza; mensaje distinto → no.
- Películas: título de 1 palabra con ruido, título localizado + original + sigla + `imdbId`.
- Series: `S01E01`, `S03E10`, `S06E03`; patrón `NxNN` al inicio del nombre; `720p` no es episodio; solo comprimidos → 0.
- Región ES/LatAm: con interfaz `es-ES` prioriza variante peninsular; con `es-MX`/`es-AR`/`es`+timezone americano prioriza latina; la query contiene ambas en los dos casos.
- Toggles: i18n OFF en pelis → solo título UI; i18n OFF en series → sin `Temporada/Capítulo` ni `T02E05`; canal/carpeta OFF → sin contexto ni fallback.
