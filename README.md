# Velo: navegador privado y anónimo para Arch Linux y Android

**Web de descarga:** https://melvinfranco99.github.io/velo-browser/

Velo es un navegador ligero (unas 900 líneas de Python) pensado para la
privacidad y el anonimato. Usa el motor Chromium que trae **QtWebEngine** y le
añade varias capas de protección:

- **Tor integrado y obligatorio.** Velo arranca su propia instancia de Tor y
  todo el tráfico sale por ella. Si Tor no está disponible, no se carga nada.
- **Sesión efímera.** No escribe nada en disco: historial, cookies, caché y
  almacenamiento local viven en RAM y desaparecen al cerrar.
- **Antirrastreo.** Bloquea unos 72.000 dominios de rastreo, publicidad y
  malware, las cookies de terceros y los parámetros de seguimiento de las URL.
- **Antihuella (anti-fingerprinting).** Uniforma los valores que más
  identifican a un equipo y añade ruido por sitio a canvas y audio.

> Escribir un motor de renderizado desde cero (lo que hacen Blink, Gecko o
> WebKit) supone millones de líneas de código y sería inseguro. Velo, como hacen
> Brave o Tor Browser, se apoya en un motor maduro y se centra en el
> endurecimiento, que es donde se gana o se pierde la privacidad.

## Android

Descarga el APK desde la [web de Velo](https://melvinfranco99.github.io/velo-browser/)
o desde [Releases](https://github.com/melvinfranco99/velo-browser/releases/latest).
Funciona en Android 10 o superior y no necesita Orbot.

La app Android (`android/`, en Kotlin) aplica el mismo enfoque que la versión
de escritorio:

- **Tor embebido** (tor-android 0.4.8 de The Guardian Project, autores de
  Orbot). El WebView se fuerza por Tor con `ProxyController` y sin regla
  DIRECT: si Tor cae, no sale nada. Las descargas también van por Tor.
- **Perfiles efímeros** (en WebView 121 o posterior): «Nueva identidad» borra
  el perfil entero. En versiones antiguas se borran cookies, almacenamiento y
  caché.
- **WebRTC eliminado**, porque en Android el proxy del WebView no cubre UDP.
- **Sin rastros en el sistema:** sin copias de seguridad, sin Safe Browsing ni
  métricas de Google, sin capturas de pantalla (`FLAG_SECURE`) y con el teclado
  en modo incógnito.
- **Huella:** User-Agent y Client Hints genéricos (sin modelo de móvil),
  cabecera `X-Requested-With` desactivada (requiere WebView actualizado; la app
  avisa si no lo está), `Accept-Language: en-US`, zona horaria UTC y ruido por
  sitio en canvas y audio.
- Bloqueo de unos 72.000 dominios, solo HTTPS y limpieza de URL.

Para compilarlo: `cd android && ./gradlew assembleRelease` (JDK 17 y SDK 34).

## Instalación en Arch Linux

```bash
git clone https://github.com/melvinfranco99/velo-browser.git
cd velo-browser
./install.sh            # instala en ~/.local, sin root
# o bien
./install.sh --system   # crea e instala un paquete de pacman (velo-browser)
```

Dependencias (el instalador las instala si faltan): `python`,
`python-pyqt6`, `python-pyqt6-webengine` y `tor`. **No hace falta tener
activado el servicio de Tor del sistema**, porque Velo lanza el suyo.

Para desinstalar: `./uninstall.sh` (añade `--purge` para borrar también el
estado de Tor y la configuración), o `sudo pacman -R velo-browser` si lo
instalaste con `--system`.

## Uso

```bash
velo                    # normal: todo por Tor
velo --sin-js           # arranca con JavaScript desactivado (más seguro)
velo --sin-tor          # sin Tor (los sitios verán tu IP; desaconsejado)
velo https://ejemplo.org
```

| Atajo | Acción |
|---|---|
| `Ctrl+Shift+U` | **Nueva identidad**: cierra todo, borra la sesión, pide circuitos nuevos a Tor y regenera la semilla antihuella |
| `Ctrl+Shift+J` | Activar o desactivar JavaScript |
| `Ctrl+T` / `Ctrl+W` | Nueva pestaña / cerrar pestaña |
| `Ctrl+L` | Barra de direcciones |
| `Ctrl+F` | Buscar en la página |
| `Alt+←` / `Alt+→` / `F5` | Atrás / adelante / recargar |

### Configuración opcional

- `~/.config/velo/torrc-extra`: líneas que se añaden a la configuración de Tor.
  Sirve, por ejemplo, para usar **puentes** si tu red bloquea Tor:
  ```
  UseBridges 1
  ClientTransportPlugin obfs4 exec /usr/bin/lyrebird
  Bridge obfs4 …
  ```
- `~/.config/velo/blocklist.txt`: tus propios dominios a bloquear, uno por
  línea.
- Para actualizar la lista ampliada de bloqueo, vuelve a ejecutar `./install.sh`.

## Cómo protege tu privacidad

### 1. Red: Tor sin fugas

| Medida | Qué evita |
|---|---|
| Instancia privada de Tor en puertos aleatorios de 127.0.0.1, con autenticación por cookie | Depender de root o del servicio del sistema, y que otros programas usen o controlen tu Tor |
| `--proxy-server=socks5://…` con DNS remoto y `--host-resolver-rules=MAP * ~NOTFOUND` | **Fugas DNS**: tu proveedor de Internet no ve qué dominios visitas |
| Proxy de aplicación de Qt apuntando también a Tor | Que, si una de las dos barreras fallara, el tráfico saliera por tu conexión normal |
| `--proxy-bypass-list=<-loopback>` | Que una web escanee servicios de tu red local o de tu propio equipo |
| WebRTC con `disable_non_proxied_udp` y solo interfaces públicas | La **fuga de IP real o local por WebRTC** (probado: 0 candidatos ICE) |
| `IsolateDestAddr` en Tor | Que todos los sitios compartan circuito y nodo de salida |
| Fallo cerrado | Si Tor muere, las páginas dan `ERR_PROXY_CONNECTION_FAILED` y nada sale sin cifrar |
| `__OwningControllerProcess` | Que quede un Tor huérfano si Velo se cierra de golpe |
| El estado de Tor se guarda entre sesiones (`~/.local/share/velo/tor`) | Rotar de nodo de entrada (*guard*) en cada arranque, lo que aumenta el riesgo de caer en uno malicioso |

### 2. Almacenamiento: nada en disco

El perfil de Chromium es *off-the-record*: la caché HTTP está en memoria y no
hay cookies persistentes, historial, autocompletado ni permisos recordados. La
**nueva identidad** destruye el perfil entero y crea uno nuevo.

### 3. Rastreo

- Interceptor propio que bloquea las peticiones de terceros a unos 72.000
  dominios (lista base incluida más StevenBlack/hosts).
- Cookies de terceros bloqueadas.
- Se eliminan `utm_*`, `fbclid`, `gclid`, `msclkid`, `mc_eid`, `igshid` y unos
  30 parámetros de seguimiento más.
- **Solo HTTPS**: las peticiones `http://` se reescriben a `https://` (salvo
  `.onion`, donde Tor ya cifra de extremo a extremo).
- Cabecera **Global Privacy Control** (`Sec-GPC: 1`), que en algunas
  jurisdicciones obliga legalmente a no vender tus datos.
- Sin `<a ping>`, sin prefetch DNS, sin tráfico de fondo de Chromium
  (componentes, Safe Browsing remoto, traducción, Privacy Sandbox/Topics,
  FLEDGE, Attribution Reporting…).

### 4. Huella digital

La huella digital es el principal método para seguirte sin cookies. Velo:

- **Quita `QtWebEngine/6.x` del User-Agent.** Por defecto QtWebEngine lo
  incluye, y eso te identificaría entre muy pocos usuarios. Velo envía un
  User-Agent de Chrome para Linux genérico, coherente con el motor real para
  no crear contradicciones detectables.
- **Uniforma** idioma (`en-US`), zona horaria (UTC), núcleos de CPU (4),
  memoria (8 GB), pantalla (1920×1080), posición de ventana y puntos táctiles.
- **Desactiva** WebGL (revela tu GPU exacta), la API de batería, la de
  información de red, MIDI, Bluetooth, USB, HID, Serial, mandos y la lista de
  voces del sistema.
- **Aleatoriza por sitio y sesión** la lectura de canvas y audio: un ruido
  imperceptible distinto para cada dominio y para cada nueva identidad. Un
  mismo sitio ve una huella estable dentro de la sesión, pero dos sitios no
  pueden cruzarla (probado: example.com y example.org obtienen huellas de canvas
  distintas). Las funciones modificadas se presentan como `[native code]`.
- **Deniega siempre** cámara, micrófono, ubicación, notificaciones, captura de
  pantalla, portapapeles y apertura de ventanas emergentes.
- Mantiene el **sandbox de Chromium** y el aislamiento estricto por sitio
  (`--site-per-process`) contra ataques tipo Spectre.

## ¿Por qué es mejor que otros navegadores en privacidad?

| | Chrome / Edge | Firefox (por defecto) | Brave | **Velo** | Tor Browser |
|---|---|---|---|---|---|
| Oculta tu IP | ❌ | ❌ | Solo en ventanas Tor | ✅ siempre | ✅ siempre |
| Fallo cerrado si cae Tor | — | — | ❌ | ✅ | ✅ |
| Nada se guarda en disco | ❌ | ❌ | ❌ | ✅ | ✅ |
| Envía datos a su fabricante | Muchos (telemetría, cuenta, Safe Browsing) | Telemetría (desactivable) | Algunos (desactivables) | ❌ ninguno | ❌ |
| Bloqueo de rastreadores | ❌ | Básico | ✅ | ✅ | Por aislamiento |
| Protección antihuella | ❌ | Parcial | ✅ (aleatoriza) | ✅ (uniforma y aleatoriza) | ✅✅ (uniformidad masiva) |
| Fugas WebRTC bloqueadas | ❌ | ❌ | Parcial | ✅ | ✅ |
| Código auditable | Parcial | ✅ | ✅ | ✅ ~900 líneas | ✅ |

**Frente a Chrome, Edge, Firefox, Opera o Vivaldi**, Velo es claramente
superior en anonimato: esos navegadores no ocultan tu IP, guardan historial y
cookies en disco, y varios envían telemetría a su fabricante. Ninguno usa Tor
por defecto ni falla cerrado.

**Frente a Brave**, Velo enruta *todo* por Tor siempre (Brave solo lo hace en
ventanas privadas con Tor, con menos protecciones), no tiene cuentas,
recompensas ni servicios propios, y su código es lo bastante pequeño como para
que puedas leerlo entero.

### Honestidad: dónde NO es mejor

Para un anonimato frente a adversarios serios (gobiernos, periodismo de
riesgo), **Tor Browser sigue siendo la referencia** y no debes sustituirlo por
Velo:

1. **Tamaño de la multitud.** El anonimato de Tor Browser se basa en que
   millones de usuarios tienen exactamente la misma huella. Velo tiene muy
   pocos usuarios, así que, aunque oculta tu IP y uniforma muchos valores, un
   sitio avanzado podría distinguir "alguien que usa Velo".
2. **Actualizaciones del motor.** El Chromium de QtWebEngine se actualiza con
   el paquete `qt6-webengine` de Arch, que suele ir algo por detrás de Chrome
   en parches de seguridad. Mantén el sistema al día (`sudo pacman -Syu`).
3. **Limitaciones técnicas.** Velo no hace *letterboxing* (redondear el tamaño
   de la ventana; no la maximices), no protege la enumeración de fuentes, y
   sus protecciones de JavaScript no llegan a los *Web Workers*. El modo
   `--sin-js` elimina casi todos estos vectores.
4. **Auditoría.** Tor Browser lo revisan expertos desde hace años; Velo es un
   proyecto nuevo y personal.

En resumen: Velo es mucho más privado que cualquier navegador convencional para
el día a día, con Tor activo por defecto. Para necesidades críticas de
anonimato, usa Tor Browser o Tails.

### Consejos para mantener el anonimato

- No inicies sesión en cuentas personales mientras quieras ser anónimo.
- Usa **Nueva identidad** entre actividades que no deban relacionarse.
- No maximices la ventana: su tamaño exacto forma parte de tu huella.
- Los archivos descargados (PDF, DOCX…) pueden conectarse a Internet fuera de
  Tor al abrirlos.
- Activa `--sin-js` en sitios donde no lo necesites.

## Estructura del código

`android/` contiene la app Android y `docs/` la web de descarga
(GitHub Pages). La versión de escritorio está en:

```
velo/
├── __main__.py     arranque y parámetros de Chromium (proxy, WebRTC, flags)
├── tor.py          instancia privada de Tor, arranque y nueva identidad (NEWNYM)
├── interceptor.py  bloqueo, HTTPS estricto, limpieza de URL, GPC
├── blocklist.py    carga de listas de dominios
├── browser.py      ventana, pestañas, perfil efímero y permisos
└── data/
    ├── hardening.js    protección antihuella inyectada en cada página
    ├── start.html      página de inicio
    ├── blocklist.txt   lista base de rastreadores
    └── velo.svg        icono
```

Licencia MIT.
