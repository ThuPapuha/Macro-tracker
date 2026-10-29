# Macro Tracker para iPhone

App nativa en SwiftUI (iOS 17 o más nuevo) con lo mismo que la de Android: comidas con IA, historial y meta semanal, peso y composición corporal, rutinas y entrenamientos, grupos con feed, comentarios y ranking, y perfil con objetivos y respaldos. Usa el **mismo servidor** (Supabase y la función de Gemini), así que amigos con Android y con iPhone pueden estar en los mismos grupos. Los respaldos JSON sirven entre las dos apps.

Cada cambio en `ios/` se compila y se prueba en GitHub Actions ([`.github/workflows/ios.yml`](../.github/workflows/ios.yml)) con un simulador de iPhone. Así, si la acción está en verde, el proyecto compila.

---

## Guía para publicarla (TestFlight y App Store)

### Lo que necesitas

- Una **Mac** con **Xcode 16 o más nuevo** (gratis en la Mac App Store).
- Una cuenta del **Apple Developer Program** (99 USD al año): <https://developer.apple.com/programs/>. Sin ella se puede probar en tu propio iPhone desde Xcode, pero no subir a TestFlight ni a la App Store.
- [Homebrew](https://brew.sh) para instalar XcodeGen.

### 1. Generar el proyecto de Xcode

El proyecto de Xcode no está en git: se genera a partir de [`project.yml`](project.yml).

```bash
git clone https://github.com/ThuPapuha/Macro-tracker.git
cd Macro-tracker/ios
brew install xcodegen
xcodegen generate
open MacroTracker.xcodeproj
```

Si más adelante cambian archivos (por ejemplo, después de un `git pull`), vuelve a correr `xcodegen generate`.

### 2. Poner tu cuenta y tu identificador

Crea el archivo `ios/Config/Local.xcconfig`. Git lo ignora, así que tus datos no se suben. Ponle esto:

```
APP_BUNDLE_ID = com.tunombre.macrotracker
APP_TEAM_ID = ABCDE12345
```

- `APP_BUNDLE_ID`: un identificador único en toda la App Store; usa tu dominio o tu nombre al revés.
- `APP_TEAM_ID`: tu *Team ID* de 10 caracteres. Está en <https://developer.apple.com/account> › Membership details.

Vuelve a correr `xcodegen generate`. El proyecto usa **firma automática**: Xcode crea solo los certificados y perfiles la primera vez. En Xcode › Settings › Accounts tienes que haber iniciado sesión con tu Apple ID de desarrollador.

Para probarla en tu iPhone, conéctalo, elígelo arriba en Xcode y pulsa ▶︎. La primera vez, en el iPhone, activa el modo desarrollador en Ajustes › Privacidad y seguridad.

### 3. Crear la app en App Store Connect

1. Entra a <https://appstoreconnect.apple.com> › Apps › **+** › Nueva app.
2. Plataforma iOS. Nombre: *Macro Tracker*; si ya está tomado, elige otro, y el nombre bajo el ícono puede seguir siendo el mismo. Idioma principal: Español. Bundle ID: el mismo de `APP_BUNDLE_ID`. SKU: cualquier texto, por ejemplo `macrotracker`.

### 4. Subir una versión

1. En Xcode, arriba, elige el destino **Any iOS Device (arm64)**.
2. Menú **Product › Archive**.
3. Al terminar se abre el Organizer: **Distribute App › App Store Connect › Upload** y sigue con las opciones por defecto.
4. En 10 a 30 minutos aparece en App Store Connect › TestFlight.

**Cada subida necesita un número de build mayor.** Súbelo en `Local.xcconfig` con `CURRENT_PROJECT_VERSION = 2`, `3`… (por defecto vale 1, en [`Config/App.xcconfig`](Config/App.xcconfig)). Para una versión nueva en la tienda, cambia también `MARKETING_VERSION`, por ejemplo `1.1.0`.

### 5. TestFlight (probar con amigos)

- **Probadores internos** (hasta 100 personas de tu equipo de App Store Connect): los agregas y les llega al instante, sin revisión.
- **Probadores externos** (hasta 10 000, con un enlace público): la primera build pasa una revisión corta de Apple (1 a 2 días).

Cada build de TestFlight dura 90 días.

### 6. Publicar en la App Store

En la ficha de la app en App Store Connect completa lo siguiente.

**Capturas de pantalla.** Se piden las de iPhone de 6,9" (1320 × 2868), y salen del simulador del iPhone 16/17 Pro Max con ⌘S.

**Textos.** Descripción, palabras clave y URL de soporte. Para la URL de soporte sirve la del repositorio o la de *issues*.

**URL de política de privacidad:** <https://github.com/ThuPapuha/Macro-tracker/blob/main/PRIVACY.md>

**Categoría:** Salud y forma física.

**Clasificación por edad:** responde el cuestionario. No tiene contenido sensible. Hay comentarios entre amigos, pero solo dentro de grupos privados por código.

**Privacidad de la app** (*App Privacy*). Respuestas sugeridas, todas con fin de *Funcionalidad de la app*, sin rastreo:

| Dato | ¿Vinculado al usuario? | Por qué |
|---|---|---|
| Salud (peso, composición corporal, comidas y calorías) | Sí | Se comparte con tus grupos |
| Estado físico (entrenamientos) | Sí | Se comparte con tus grupos |
| Nombre (el apodo de los grupos) | Sí | Así te ven tus amigos |
| ID de usuario (cuenta anónima del servidor) | Sí | Para los grupos |
| Otro contenido del usuario (comentarios y reacciones) | Sí | Se ve en los grupos |
| Fotos (fotos de comidas que se mandan a la IA) | No | Solo para estimar los macros; no se guardan |

Quien no se une a ningún grupo solo usa el servidor para la IA. Esa IA crea una cuenta anónima para limitar el uso diario, y no guarda lo que se analiza. Estas respuestas coinciden con el manifiesto [`PrivacyInfo.xcprivacy`](MacroTracker/PrivacyInfo.xcprivacy) que va dentro de la app.

**Notas para la revisión de Apple.** No hace falta usuario ni contraseña. Para probar los grupos: pestaña Grupos › elegir un nombre › Crear grupo. Para borrar la cuenta del servidor: Perfil › Borrar mi cuenta de grupos.

**Cifrado.** La app ya declara que solo usa el cifrado estándar (`ITSAppUsesNonExemptEncryption = NO`), así que no se piden documentos de exportación.

Después pulsa **Enviar a revisión**. Suele tardar de 1 a 3 días.

### Cosas que Apple suele revisar (ya están resueltas)

- **Borrar la cuenta desde la app** (regla 5.1.1(v)): Perfil › *Borrar mi cuenta de grupos* borra el usuario anónimo y todo lo compartido en el servidor.
- **Permiso de cámara** con su explicación. Las fotos de la galería se eligen con el selector del sistema, que no pide permiso.
- **Notificaciones** de la actividad de los grupos: se piden al entrar a un grupo y se revisan en segundo plano cuando iOS lo permite (*Background fetch*).
- **Manifiesto de privacidad** (`PrivacyInfo.xcprivacy`) con los datos y las API usadas.

---

## Cómo está hecha

```
ios/
├── project.yml              # XcodeGen: targets, firma, Info.plist
├── Config/App.xcconfig      # bundle id, equipo, versión, servidor
├── MacroTracker/
│   ├── App/                 # arranque, pestañas, AppModel (dependencias)
│   ├── Core/                # lógica pura: nutrición, gym, peso, meta semanal, respaldos
│   ├── Net/                 # Supabase (sesión anónima + RPC), grupos, Gemini, documentos
│   ├── Data/                # guardado local (JSON + Keychain), ajustes, sync, avisos, respaldos
│   └── UI/                  # pantallas SwiftUI: Hoy, Agregar, Historial, Progreso, Grupos, Perfil
└── MacroTrackerTests/       # pruebas de la lógica y de los formatos compartidos con Android
```

- Los datos viven en el teléfono: comidas, rutinas, entrenamientos y peso, en `Application Support`. La sesión del servidor va en el Llavero. Cada vez que la app pasa a segundo plano se guarda una copia en *Archivos › En mi iPhone › Macro Tracker* con el mismo formato que la app de Android.
- El servidor solo se usa si estás en un grupo, y para la IA. La clave de Gemini está en el servidor, como en Android. En Perfil se puede poner una clave propia.
- La lógica de `Core/` replica la del módulo `core` de Android con los mismos números. Las pruebas de `MacroTrackerTests` comprueban, entre otras cosas, que se lee un respaldo de Android y el feed del servidor tal cual.

**Al agregar una función nueva**, hay que hacerla en las dos apps: Android en `app/` y `core/`, iPhone aquí. El servidor (`supabase/`) es uno solo para las dos.

### Usar otro servidor

`Config/App.xcconfig` apunta al mismo Supabase que la app de Android. La clave *publishable* está pensada para ser pública: la base de datos solo deja usar las funciones de `supabase/schema.sql`. Si quieres tu propio servidor, sigue los pasos del [README principal](../README.md) y cambia `SUPABASE_PROJECT_REF` y `SUPABASE_ANON_KEY` en `Local.xcconfig`.
