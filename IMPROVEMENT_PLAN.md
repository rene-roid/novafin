# Novafin: plan de mejoras

Propuesta de producto para que Novafin sea más fácil de configurar, más agradable de usar y mejor
para descubrir qué ver después. Complementa a [PLAN.md](PLAN.md), que sigue siendo el diseño técnico
de referencia (API, reproducción, descargas, direcciones, casting). Nada de aquí lo sustituye.

Alcance: películas, series, anime y episodios. Música, fotos y libros quedan fuera. Las ideas de
"radio", "listas de canciones" o "colas" se traducen a su equivalente en vídeo (siguiente episodio,
sugerencias al terminar, listas de reproducción de vídeo).

Servidor objetivo: Jellyfin 10.11. Cliente: Android 9+ (minSdk 28), Compose, Media3, sin SDK de Jellyfin.

---

## 1. Qué hay hoy

Inventario hecho leyendo el código (`app/src/main/java/xyz/yuuki/novafin`), no la lista de deseos.

| Área | Estado | Dónde |
|---|---|---|
| Reproducción directa, remux y HLS, reintento transcodificado si falla el decodificador | Completo | `Playback.kt`, `DeviceProfile.kt` |
| Perfiles de calidad Wi-Fi / datos, smart original, bajada automática al rebufferizar | Completo | `DeviceProfile.kt`, `App.streamQuality` |
| Memoria de pistas por serie o película, listas de idiomas preferidos | Completo y probado | `Tracks.kt`, `LogicTest.kt` |
| Descargas originales y HLS, temporadas, "mantener N episodios", límite, borrado de vistos | Completo | `Downloads.kt` |
| Sincronización offline (gana el más reciente) | Completo y probado | `Downloads.kt` |
| Varias direcciones del mismo servidor con carrera, comprobación de `Id` | Completo | `Api.kt`, `AddressesScreen` |
| Chromecast, PiP, audio en segundo plano | Completo | `Playback.kt`, `PlayerActivity.kt` |
| Saltar intro y recap, siguiente episodio con cuenta atrás | Completo, con un fallo (ver abajo) | `Player.kt` |
| Inicio: continuar, siguientes, novedades por biblioteca, bibliotecas, héroe en tablet | Completo, fijo | `Home.kt` |
| Biblioteca: selector de biblioteca, 6 ordenaciones, "No vistos" o "Favoritos" | A medias: filtros excluyentes, sin dirección de orden, sin géneros ni años | `Library.kt` |
| Búsqueda de películas, series y episodios | A medias: sin estado de carga, sin tipos, sin personas | `Library.kt` |
| Detalle: reparto, colecciones, pistas, descargas | A medias: reparto y géneros no navegables, sin "similares" | `Details.kt` |
| Ajustes | Completos pero en una sola lista de ~35 opciones | `Settings.kt` |
| Login: dirección, usuario/contraseña, Quick Connect | Funciona; errores genéricos, sin descubrimiento en la red local | `Login.kt` |
| Varios servidores, widgets, sesiones, visionado compartido | No existen | |

Carencias reales encontradas al leer el código:

- **Cancelar el siguiente episodio no cancelaba nada.** "Hide" ocultaba la tarjeta, pero al llegar
  `STATE_ENDED` se reproducía igualmente el siguiente episodio (`Playback.kt`, listener de estado).
- Los filtros de biblioteca eran excluyentes (un solo `String?`) y estaban mezclados con la ordenación en la misma fila.
- La búsqueda no mostraba nada mientras cargaba la primera vez.
- El reparto y los géneros eran texto muerto: no había forma de ir de una ficha a "más de esta actriz" o "más de este género".
- Al terminar una película no se proponía nada.
- El login convertía cualquier fallo de red en "No Jellyfin server answered at …".

## 2. Viabilidad por tipo de dependencia

| Idea | Solo app | Depende de Jellyfin / permisos | Servicio externo | No compensa |
|---|---|---|---|---|
| "Más como esto" en la ficha | | `GET /Items/{id}/Similar` | | |
| Reparto y géneros navegables | | `/Items?personIds=`, `/Items?genres=` | | |
| Filtros combinables, chips activos | x (con `/Items` y `/Genres`) | | | |
| Fila "mejor valorados sin ver" | | `/Items?filters=IsUnplayed&sortBy=CommunityRating` | | |
| "Sorpréndeme" (título aleatorio sin ver) | | `sortBy=Random` | | |
| Sugerencia al terminar una película | | `/Items/{id}/Similar` | | |
| "Porque viste X" | | `/Movies/Recommendations` (solo películas) | | |
| Secciones de inicio configurables | x | | | |
| Ajustes por categorías | x | | | |
| Descubrimiento de servidores en la LAN | x (UDP 7359, si el servidor tiene activado el descubrimiento) | | | |
| Errores de conexión comprensibles | x | | | |
| Widgets | x (con instantánea local) | token de la app | | |
| Ver tus otros dispositivos | | `GET /Sessions`, ver §3.7 | | |
| Visionado compartido entre usuarios del mismo servidor | | SyncPlay, permiso `SyncPlayAccess` | | |
| Sala compartida por enlace con gente sin cuenta | | | Relay o servidor propio | Probablemente |
| Recomendador propio con historial y embeddings | | | Servicio o cálculo local pesado | Sí, por ahora |
| Varios servidores guardados | x | | | |

## 3. Propuestas por área

### 3.1 Descubrimiento y reproducción

**Qué resuelve Jellyfin solo.**
- `GET /Items/{id}/Similar`: similares por géneros, etiquetas, personas y estudios. Funciona con películas y series; para un episodio se pide el de la serie.
- `GET /Movies/Recommendations`: categorías "Porque viste…", "Protagonizada por…", "Dirigida por…". Solo películas.
- `GET /Shows/NextUp` y `/UserItems/Resume`: ya usados.
- Filtros de `/Items` (`IsUnplayed`, `IsResumable`, `IsFavorite`, `genres`, `years`, `personIds`, `isHd`, `is4K`, `minCommunityRating`, `sortBy=Random`).

**Qué necesita lógica propia.** Mezclar historial, géneros y personas en un ranking, evitar repetir lo
mismo en varias filas y "contenido poco visto del servidor" (Jellyfin no expone popularidad entre
usuarios a un usuario normal). Propuesta: no construir un recomendador. Usar las piezas del servidor y
deduplicar en el cliente.

**Contenido difícil de descubrir sin ruido.**
1. Una sola fila "Mejor valorados sin ver" (nota ≥ 7, sin ver, orden por nota). Determinista y explicable; desactivable.
2. Botón "Sorpréndeme" en la biblioteca, que respeta los filtros activos. Una acción, no una fila más.
3. Navegación lateral: ficha → actor → su filmografía en tu servidor; ficha → género → lo mejor valorado del género.
4. Más adelante: "Porque viste X" como sección opcional, una sola fila, basada en la última película terminada.

**Al terminar algo.**
- Episodio: tarjeta con cuenta atrás de 10 s, "Reproducir ahora" y "Cancelar". Cancelar detiene también el autoplay del final. El autoplay se desactiva en Ajustes → Reproducción.
- Película: tarjeta "Watch next" con una película similar sin ver. Nunca se reproduce sola. Se desactiva en Ajustes → Reproducción. En el último episodio de una serie no aparece nada todavía: `/Similar` devuelve series y el reproductor no puede reproducir una serie sin elegir episodio.
- Más adelante: al terminar una serie, proponer la serie similar abriendo su ficha (desde el reproductor no se puede reproducir una serie sin elegir episodio).

### 3.2 Biblioteca, filtros y búsqueda

- Modelo de filtro inmutable (`LibraryFilter`) con enumerados, que produce los parámetros de `/Items` y la lista de chips activos. Una única fuente de verdad, probada.
- Filtros: estado de visionado (todos / sin ver / empezados / vistos), favoritos, géneros (varios, se combinan con O), décadas, resolución (HD / 4K).
- Fiabilidad de cada filtro en Jellyfin: visto/favorito/empezado son exactos; género y año dependen de los metadatos; HD/4K dependen del análisis de medios; la duración no tiene filtro en `/Items` (se descarta); clasificación por edades existe (`officialRatings`) y queda para una segunda fase con `/Items/Filters`.
- Resultados al instante, sin botón "Aplicar"; chips activos con su "x"; "Borrar todo"; estado vacío con "Quitar filtros".
- Ordenación con dirección explícita. Se guarda por biblioteca; los filtros duran la sesión para no esconder títulos sin que el usuario lo recuerde.
- Búsqueda: tipos (Todo, Películas, Series, Episodios, Personas), secciones con cabecera, carga visible, errores con reintento, búsquedas recientes.

### 3.3 Personalización y diseño

Personalizable, y nada más:
- Secciones de inicio: visibles y orden.
- Sugerencias al terminar: sí/no.

Descartado por ahora, porque añade ajustes sin un problema real detrás: tamaño de póster y densidad de
cuadrícula (la cuadrícula ya es adaptativa por ancho), temas alternativos (los tres intentos de rediseño
del historial se revirtieron), orden de las pestañas.

Revisión visual pendiente (en el emulador, ver §6):
- Contraste de textos `Sub` sobre la aurora en las zonas claras del cielo.
- Áreas táctiles de 48 dp en chips y botones de icono.
- Descripciones de contenido para TalkBack en tarjetas (hoy las imágenes tienen `null`; el título se lee por el texto).

### 3.4 Login y servidores

- Descubrimiento en la red local por UDP 7359 ("who is JellyfinServer?"). Tocar un servidor encontrado lleva a la identificación; la identidad sigue saliendo de `/System/Info/Public`.
- Errores por causa: nombre que no resuelve, puerto cerrado, tiempo agotado (sugiere Tailscale o la dirección pública), certificado no fiable, "responde pero no es Jellyfin".
- Validación en línea de la dirección antes de conectar.
- Paso de identificación con nombre, versión y dirección del servidor; "Siguiente" y "Hecho" en el teclado; mostrar/ocultar contraseña; autocompletado de credenciales del sistema.
- Aviso no bloqueante si el servidor es anterior a 10.10.
- Quick Connect con instrucciones de dónde introducir el código.
- No se guarda la contraseña. La comprobación de `Id` para direcciones adicionales no cambia.

**Varias direcciones frente a varios servidores.** En Direcciones, cabecera "<servidor> · N direcciones" y
una etiqueta LAN / Tailscale / Pública por dirección. Añadir una dirección de otro servidor ya se rechaza.

**Varios servidores guardados (fase 3).** Hoy `Prefs` guarda un único servidor. Diseño propuesto: una
lista de cuentas `{serverId, serverName, userId, userName, token, addresses}` con una activa; cambiar de
cuenta reinicia `Api`, la caché de UI y la carrera de direcciones; las descargas se indexan por
`serverId`. Riesgo medio: toca `Prefs`, `Api`, `Downloads` y el arranque. No se ha implementado.

### 3.5 Ajustes

Raíz con tarjeta de cuenta y categorías con un resumen de sus valores actuales:
Cuenta y servidores · Pantalla de inicio · Reproducción · Idiomas y subtítulos · Calidad · Descargas y
almacenamiento · Acerca de. En tablet ancha, dos paneles. Las opciones avanzadas de calidad quedan en un
grupo plegable. Ninguna opción cambia de comportamiento.

Fase 2: buscador de ajustes (filtrar por texto en títulos y descripciones).

### 3.6 Widgets Android

Encaja con la arquitectura si el widget **no** hace red por su cuenta en cada actualización.

- **Datos.** Cuando Home carga, se guarda una instantánea pequeña (`widget.json`: hasta 6 elementos de continuar y siguientes, con id, título, etiqueta de episodio, progreso y ruta de la miniatura ya descargada en caché). El widget pinta esa instantánea. Así funciona sin conexión y no gasta batería.
- **Actualización.** `updatePeriodMillis` no baja de 30 minutos (documentación de Android). Propuesta: actualizar al cerrar la app, al terminar o parar una reproducción y al sincronizar offline. Si se quiere refresco en segundo plano, un `WorkManager` periódico (dependencia nueva) que sigue sujeto a App Standby Buckets. Un `BroadcastReceiver` tiene 10 s antes de ANR.
- **Autenticación.** El token ya está en `Prefs`; el widget no lo necesita si solo lee la instantánea. Las acciones abren la app con un `PendingIntent` (detalle o reproducción); si la sesión ha caducado, la app muestra el login como hoy.
- **Tamaños.** Compacto (2×2): un elemento con miniatura, título y botón reanudar. Ampliado (4×2 / 4×3): fila de 3 a 4 tarjetas con progreso. Android 12+: `targetCellWidth/Height` y `maxResize*` con `RemoteViews(Map<SizeF, RemoteViews>)`. Android 15: vista previa generada con `setWidgetPreview` (limitada a unas dos llamadas por hora).
- **Tecnología.** `RemoteViews` sin dependencias nuevas, coherente con la política del proyecto (sin SDKs extra). Glance reduce código pero añade dependencia y no se mezcla con Compose normal.
- **Personalización razonable.** Al añadirlo: "Continuar viendo" o "Siguientes episodios", y opcionalmente una biblioteca. No elegir servidor mientras no haya varios servidores.
- **Limitaciones.** Miniaturas de bitmap: no usar `partiallyUpdateAppWidget` para imágenes (límite de memoria de bitmaps, avisado para Android 17). El progreso del widget puede ir hasta 30 min por detrás si la reproducción ocurrió en otro dispositivo.
- **Estado.** No implementado en esta iteración: pide un receptor, layouts XML, la instantánea y pruebas en varios launchers; mejor como entrega propia (fase 2).

### 3.7 Clientes conectados y permisos

Verificado en el código de Jellyfin 10.11 (`SessionController`, `SessionManager.GetSessions`):

- `GET /Sessions` solo exige estar autenticado.
- **Un usuario normal ve únicamente sus propias sesiones** (y las que no tienen usuario). Un administrador ve todas. Para no administradores, la información de aceleración por hardware del transcode se oculta.
- Cada sesión trae dispositivo, cliente, última actividad, `NowPlayingItem` y `PlayState`.
- `POST /Sessions/{id}/Playing/{comando}` solo exige autenticación; con `controllableByUserId` la lista se limita a sesiones controlables (`SupportsRemoteControl`) según `EnableRemoteControlOfOtherUsers` y `EnableSharedDeviceControl`. Sin verificar del todo qué comprueba `SendPlaystateCommand`.

Propuesta, presentada como opcional y honesta:
1. **"Tus dispositivos"** (Ajustes → Cuenta): lista de tus sesiones activas en los últimos 10 min, con qué reproducen y desde dónde. Para todo usuario.
2. **"Continuar desde otro dispositivo"**: si otra sesión tuya está reproduciendo, ofrecer "Seguir aquí" (abrir el mismo elemento en la posición de su `PlayState`). Útil y sin permisos especiales.
3. **Mando a distancia** de otra sesión tuya (pausa, seek) si `SupportsRemoteControl`. Fase 3.
4. **Vista de administrador** (todas las sesiones): solo si `Policy.IsAdministrator`; si no, la entrada no aparece. Nunca un estado vacío que sugiera "no hay nadie" cuando en realidad no hay permiso.

### 3.8 Visionado compartido

Lo que Jellyfin 10.11 ya tiene (verificado en `SyncPlayController`, `SyncPlayAccessHandler`):

- **SyncPlay**: grupos en memoria del servidor, `New`, `Join`, `List`, `Pause`, `Unpause`, `Seek`, `Buffering`, `Ready`, cola (`SetNewQueue`, `Queue`, `MovePlaylistItem`, `NextItem`).
- Permiso por usuario `SyncPlayAccess`: `CreateAndJoinGroups`, `JoinGroups` o `None`.
- El estado del grupo llega por el WebSocket de la sesión (`SyncPlayGroupUpdate`, `SyncPlayCommand`). Novafin hoy no abre ese WebSocket.
- No hay enlaces de invitación: se une uno eligiendo el grupo de `List`, y solo usuarios del mismo servidor con acceso a las bibliotecas del contenido.

Niveles de alcance:

| Nivel | Qué es | Depende de | Coste |
|---|---|---|---|
| 0. Compartir una recomendación | Hoja de compartir de Android con título, año y enlace a la web de Jellyfin (`/web/#/details?id=`) | Que el receptor tenga cuenta en el servidor | Muy bajo, solo app |
| 1. "Veámoslo juntos" | Crear o unirse a un grupo SyncPlay desde la ficha o el reproductor; ver quién está y el estado | SyncPlay + permiso + WebSocket | Medio-alto: WebSocket, máquina de estados de grupo, buffering coordinado |
| 2. Cola compartida y votos | Proponer títulos a la cola del grupo, el anfitrión elige | Nivel 1 + `Queue`/`MovePlaylistItem` | Medio sobre el nivel 1 |
| 3. Sala por enlace para gente sin cuenta o de fuera | Enlace público que une a desconocidos | Servicio de relay propio, cuentas o tokens de invitado, conectividad entrante, moderación | Alto y con riesgo de privacidad; **no recomendado** |

Restricciones que la interfaz debe decir claramente: todos deben poder llegar al servidor (LAN, Tailscale o pública); todos necesitan cuenta y permiso SyncPlay; con transcodificación cada cliente puede tardar distinto en arrancar; no funciona offline ni con descargas. El nivel 1 es el que recomiendo cuando llegue: aprovecha el servidor, no añade infraestructura y no promete lo que no puede cumplir.

### 3.9 Listas y orden

Cómo ordena Jellyfin 10.11 (verificado en `ItemSortBy`, `OrderMapper`, `BoxSet`, `PlaylistsController`):

- **Añadido recientemente**: `/Items/Latest` agrupa episodios nuevos bajo su serie. En `/Items`, `DateCreated` ordena por fecha de alta del elemento y `DateLastContentAdded` por la última vez que una serie recibió contenido (las series lo soportan); es el equivalente útil de "lo nuevo arriba" para series.
- **Favoritos**: no tienen orden propio; es cualquier `sortBy` sobre `filters=IsFavorite`. No existe "fecha en que lo marqué favorito".
- **Colecciones (BoxSet)**: orden según su `DisplayOrder` (por defecto `PremiereDate` ascendente; `Default` conserva el orden manual del administrador).
- **Listas de reproducción de vídeo**: soportadas; conservan el orden guardado; mover un elemento es `POST /Playlists/{id}/Items/{itemId}/Move/{newIndex}` y requiere ser propietario o tener permiso de edición.

Propuesta (equivalente en vídeo a "canciones nuevas al principio o al final"):
1. Ordenación "Contenido nuevo" (`DateLastContentAdded` descendente) en bibliotecas de series: la serie a la que acaba de llegar un episodio sube. Una línea en `sorts`.
2. Favoritos como filtro de biblioteca (hecho) y, en fase 2, una pantalla "Mis favoritos" con orden elegible y recordado.
3. Colecciones: respetar el orden del servidor por defecto y ofrecer "Estreno" / "Nombre" / "Añadido" como alternativa local, sin escribir en el servidor.
4. Listas de vídeo (fase 3): ver, reproducir en orden con autoplay al siguiente elemento, añadir desde la ficha ("Añadir a lista"), elegir si lo nuevo va al principio o al final usando `Move` justo después de añadir, y reordenar arrastrando solo cuando el servidor da permiso de edición.

---

## 4. Backlog priorizado

Valor (V) y esfuerzo (E) de 1 a 3. Riesgo: B bajo, M medio, A alto.

| # | Idea | V | E | Riesgo | Depende de | Estado |
|---|---|---|---|---|---|---|
| 1 | Cancelar de verdad el autoplay del siguiente episodio | 3 | 1 | B | | **Hecho** |
| 2 | Reparto y géneros navegables (páginas de persona y género) | 3 | 1 | B | `/Items?personIds`, `genres` | **Hecho** |
| 3 | "Más como esto" en la ficha | 3 | 1 | B | `/Items/{id}/Similar` | **Hecho** |
| 4 | Filtros combinables con chips, orden con dirección | 3 | 2 | B | `/Items`, `/Genres` | **Hecho** |
| 5 | Búsqueda con tipos, personas, carga y recientes | 3 | 2 | B | `/Persons` | **Hecho** |
| 6 | Ajustes por categorías con resúmenes, dos paneles en tablet | 3 | 2 | B | | **Hecho** |
| 7 | Secciones de inicio visibles y ordenables | 2 | 1 | B | | **Hecho** |
| 8 | Fila "Mejor valorados sin ver" y "Sorpréndeme" | 2 | 1 | B | | **Hecho** |
| 9 | Sugerencia de película al terminar (sin autoplay) | 2 | 1 | B | `/Similar` | **Hecho**, sin probar en reproducción real |
| 10 | Login: descubrimiento LAN, errores por causa, validación | 3 | 2 | B | UDP 7359 en el servidor | **Hecho**; descubrimiento sin servidor que responda |
| 11 | "Play" de una serie nueva empezaba por un especial (S0) | 2 | 1 | B | | **Hecho** |
| 12 | Orden "New episodes" (`DateLastContentAdded`) en series | 2 | 1 | B | | **Hecho** (fase 2) |
| 13 | Widgets "Continue watching" y "Next up", compacto y ampliado | 3 | 2 | M | | **Hecho** (fase 2) |
| 14 | "Your devices", "Continue here" y tarjeta en Inicio | 2 | 2 | B | `/Sessions` | **Hecho** (fase 2) |
| 15 | Filtro por clasificación por edades | 2 | 1 | B | `/Items/Filters` | **Hecho** (fase 2); años exactos descartados, las décadas bastan |
| 16 | "Because you watched X" en Inicio, sin repetir títulos ya visibles | 2 | 1 | B | `/Movies/Recommendations` | **Hecho** (fase 2) |
| 17 | Buscador dentro de Ajustes | 1 | 1 | B | | Descartado: siete categorías con resumen ya se recorren de un vistazo, y un índice aparte duplicaría los títulos de cada opción |
| 18 | Accesibilidad: estado de visionado en tarjetas y episodios, avatar etiquetado | 2 | 2 | B | | **Hecho en parte** (fase 2). Los controles del reproductor ya tenían descripciones (comprobado en el árbol de accesibilidad). Pendiente: medir el contraste del texto secundario sobre las zonas claras de la aurora |
| 19 | Varias cuentas guardadas (servidores o usuarios) y aprobar códigos de Quick Connect | 2 | 3 | M | | **Hecho** (fase 3) |
| 20 | Listas de reproducción de vídeo, "Mis favoritos", orden de colecciones y compartir | 2 | 3 | M | permisos de lista | **Hecho** (fase 3) |
| 21 | Ver juntos con SyncPlay (nivel 1) y cola de reproducción | 2 | 3 | A | SyncPlay, WebSocket | **Hecho** (fase 3). El nivel 2 (proponer y votar) no existe en Jellyfin: la cola del grupo sí, los votos no |
| 22 | Mando a distancia de otra sesión (pausa, reanudar, parar) | 1 | 2 | M | `/Sessions/{id}/Playing` | **Hecho** (fase 2), solo si la sesión admite control remoto |
| 23 | Sala pública por enlace | 1 | 3 | A | relay externo | Descartado |
| 24 | Recomendador propio | 1 | 3 | M | | Descartado |
| 25 | Tamaño de póster / densidad configurables | 1 | 1 | B | | Descartado (la cuadrícula ya es adaptativa) |

## 5. Plan por fases

### Fase 1: descubrir y encontrar (implementada en esta rama)

Entregables y criterios de aceptación:

1. **Fichas navegables.** Tocar un género abre "<género>" con títulos de todas las bibliotecas ordenados por nota; tocar una persona abre su página con foto, biografía y filmografía en el servidor. "Más como esto" aparece en películas, series y episodios y se oculta si está vacío.
2. **Biblioteca.** Los filtros se combinan; cada filtro activo aparece como chip con "x"; "Clear all" vacía todo; el recuento cambia al instante; sin resultados se ofrece quitar filtros. La ordenación muestra su dirección y se recuerda por biblioteca. "Surprise me" abre un título sin ver que respeta los demás filtros.
3. **Búsqueda.** Tipos Todo / Películas / Series / Episodios / Personas; secciones con cabecera; indicador de carga; error con reintento; recientes antes de escribir.
4. **Inicio.** Las filas siguen el orden y la visibilidad de Ajustes → Pantalla de inicio; la fila "Top rated, not watched yet" no muestra series ya vistas.
5. **Reproductor.** La tarjeta del siguiente episodio ofrece "Play now" y "Cancel"; cancelar impide el autoplay al terminar. Tras una película aparece "Watch next" con una película similar sin ver, que nunca arranca sola.
6. **Ajustes.** Raíz con cuenta y siete categorías con resumen; páginas con descripción y valor actual; opciones avanzadas de calidad plegadas; dos paneles en tablet; Direcciones explica que son entradas al mismo servidor.
7. **Login.** Servidores de la red local listados si responden; validación en línea; errores que dicen la causa; "Siguiente"/"Hecho" en el teclado; mostrar contraseña; autocompletado; aviso para servidores < 10.10; Quick Connect explicado.

### Fase 2: volver a la app sin abrirla

1. Orden "Contenido nuevo" en bibliotecas de series. Aceptación: una serie con episodio añadido hoy aparece primera.
2. Widgets compacto y ampliado (§3.6). Aceptación: muestran continuar/siguientes sin red, abren el elemento correcto, se actualizan al parar una reproducción, funcionan en Pixel Launcher y en un launcher de terceros, Android 9 y 15.
3. "Tus dispositivos" y "Seguir aquí" (§3.7). Aceptación: un usuario normal ve solo sus sesiones; sin sesiones activas, la sección explica que no hay otras sesiones tuyas activas.
4. Filtros por clasificación y año desde `/Items/Filters`; "Porque viste X"; buscador de ajustes.
5. Pase de accesibilidad con TalkBack en las pantallas principales y el reproductor.

### Fase 3: compartir y organizar

1. Varios servidores guardados (§3.4), con migración del servidor actual como primera cuenta.
2. Listas de reproducción de vídeo con orden y "añadir al principio/al final".
3. Ver juntos con SyncPlay, empezando por el nivel 1 (§3.8), tras un prototipo que confirme el WebSocket y la coordinación de buffering con dos emuladores.

## 6. Verificación

Lo comprobado en esta iteración, y cómo:

- `./gradlew testDebugUnitTest`: 24 pruebas, 0 fallos (`LogicTest` 13, `FilterTest` 6 nuevas, `LoginTest` 5 nuevas). `assembleDebug` compila.
- Emulador Pixel (1080×2400, Android, sesión iniciada contra el servidor real 10.11.11), con capturas:
  - Inicio con la fila nueva; reordenar secciones en Ajustes cambia el orden en Inicio (y se restauró el orden por defecto después).
  - Biblioteca: Unwatched + Action → 12 títulos con dos chips; quitar Action → 33; "Surprise me" abre un título sin ver.
  - Búsqueda "spider": sección Películas con 5 resultados y chips de tipo.
  - Ficha de película: chips de género, reparto, "More like this" con resultados; persona (2 títulos) y género Animation (11 títulos).
  - Ajustes: raíz con resúmenes, página Pantalla de inicio y Reproducción.
  - Tamaño tablet (2560×1600, 320 dpi, temporal y restaurado): Ajustes en dos paneles y hoja de filtros.
- Descubrimiento LAN: el protocolo se probó desde el PC contra el servidor real y **no responde** en UDP 7359 (ni broadcast ni unicast). Probablemente Docker sin `7359/udp` expuesto o con el descubrimiento desactivado; con red bridge de Docker los broadcast no suelen llegar al contenedor (inferido). La app muestra "None found" sin error.

Sin verificar:
- Pantalla de login en el emulador (cerrar sesión habría requerido credenciales que no tengo).
- Tarjeta de fin de episodio/película en reproducción real: llegar a los últimos segundos marca el elemento como visto en el servidor real.
- Pantalla dividida y móvil apaisado (solo se comprobó por anchos: compacto y expandido).

### Fase 2 (rama `improve/phase2`)

Comprobado:
- `./gradlew testDebugUnitTest`: 31 pruebas, 0 fallos (`ModelsTest` y `WidgetLayoutTest` nuevas; `FilterTest` ampliada).
- Inicio: "Because you watched The Angry Birds Movie" con Akira y The Angry Birds Movie 2. La primera versión repetía las mismas películas en dos filas e incluía la película base y otras ya vistas; ahora cada título sale una vez, sin ver y nunca la película base.
- Una sección nueva (la de recomendaciones) aparece aunque el usuario ya hubiera personalizado Inicio: se guardan las filas ocultas y el orden, no la lista de visibles.
- Biblioteca de series: orden "New episodes" disponible y aplicado; filtro de clasificación con TV-14, TV-MA y TV-PG del servidor (TV-MA → 1 título).
- "Your devices" como administrador: "No other sessions are active." El estado de administrador ahora se refresca al abrir la app, no solo al iniciar sesión.
- Widgets: el selector mostraba "Can't load widget" porque la vista previa usaba `<View>`, que `RemoteViews` no permite. Corregido y protegido por `WidgetLayoutTest`, que falla con cualquier etiqueta fuera de la lista permitida (comprobado en rojo y en verde). Widget compacto con Monster S1:E35 y progreso; ampliado con 4 elementos; tocar una tarjeta abre la ficha del episodio.

Sin verificar:
- Lista de "Your devices" con sesiones reales y los botones de pausa/parar: no había otras sesiones activas.
- Tarjeta "Playing on <dispositivo>" en Inicio, por el mismo motivo.
- Botón de reproducir del widget (habría cambiado el progreso en el servidor real) y widgets en Android 9–11 y otros launchers.
- TalkBack de extremo a extremo: solo se añadió el estado de visionado a tarjetas y episodios y la etiqueta del avatar.

### Fase 3 (rama `improve/phase3`)

Comprobado en el emulador contra el servidor real (Jellyfin 10.11.11), con un segundo cliente simulado por `tools/syncplay_peer.py`:
- `./gradlew testDebugUnitTest`: 50 pruebas, 0 fallos (`AccountsTest` y `SyncPlayTest` nuevas).
- **Ver juntos.** Crear un grupo desde la ficha abre el reproductor. Al unirse otro cliente el grupo pasa a Waiting y la app se pausa sola hasta que todos están listos. Desde el otro cliente, pausa (la app queda en 232,799 s, la posición exacta del comando del servidor), salto a 600,000 s y reanudar (600 → 603 → 606 s). La pausa desde la app pausa el grupo. "Leave" desde la hoja del reproductor vacía el grupo. Una lección de la prueba: el segundo cliente necesita su propio token, porque Jellyfin identifica la sesión por el dispositivo del token.
- **Quick Connect.** "Approve a Quick Connect code" en Ajustes aprobó el código de otro dispositivo, que obtuvo su token. Iniciar sesión con Quick Connect desde "Add account" funciona y vuelve a Inicio; con el mismo servidor y usuario no se duplica la cuenta, solo se renueva el token.
- **Login.** Mensajes reales: "Addresses can't contain spaces.", "Couldn't find nonexistent.invalid.", "Nothing answered at 192.168.68.63:1…", "10.0.2.2 answered, but it isn't a Jellyfin server." (con un servidor HTTP local que responde 404) y el paso de usuario con "Jellyfin 10.11.11 · dirección".
- **Listas.** Crear "Novafin test" desde una ficha, añadir otra película "al principio" (queda primera), subir un elemento y quitar otro: el orden del servidor coincide en cada paso. "Play all" pone la cola (aparece "Next" en una película). Se borró la lista después.
- **Favoritos** vacío coincide con el servidor (0); con Akira marcada aparece. **Compartir** abre la hoja con título, enlace y aviso de que hace falta cuenta.
- **Your devices y tarjeta de Inicio** con una sesión real ("Living room TV" reproduciendo Shrek): fila con progreso y "Continue here", sin botones remotos porque esa sesión no admite control remoto; en Inicio, "Playing on Living room TV". "Continue here" abre Shrek a partir de esa posición.
- **Siguiente episodio** (pendiente de la fase 1): con Breaking Bad S1:E7, la tarjeta muestra "Next episode in N · S2:E1"; al agotarse la cuenta empieza S2:E1; con "Cancel", S2:E1 termina y S2:E2 no empieza. Tras una película aparece "Watch next · The Wild Robot", y la tarjeta en apaisado ocupaba todo el ancho por el orden de los modificadores (corregido).

Sin verificar:
- Cambiar entre dos cuentas distintas (no hay un segundo servidor ni otro usuario con el que probar); la lógica está cubierta por `AccountsTest`.
- Descargas separadas por servidor (no hay descargas en el emulador).
- Orden de colecciones (el servidor no tiene colecciones).
- Sugerencia de una serie similar al terminar el último episodio de una serie: implementada después de perder la sesión del emulador.
- Reproducir en grupo con dos personas distintas y transcodificación lenta; corrección de deriva entre comandos (no implementada, como en jellyfin-web solo se sincroniza en cada comando).

Restos en el servidor por las pruebas: Akira, Shrek, The Angry Birds Movie y Breaking Bad S1:E7, S2:E1 y S2:E2 tienen más reproducciones y una fecha de última reproducción de hoy. Akira y Shrek siguen sin ver y con la posición a 0; los otros ya estaban vistos. Los tokens de prueba se revocaron.

