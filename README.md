# Macro Tracker

App para Android y iPhone para registrar los macros de tus comidas día a día, con grupos para seguir el progreso junto a tus amigos y objetivos automáticos a partir de un documento (plan de tu nutricionista, InBody, análisis, etc.). La IA usa **Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`), cuyo plan gratis permite muchas más consultas diarias que Gemini Flash.

## Qué hace

- **Hoy**: anillo de calorías y barras de proteína, carbohidratos y grasas frente a tus objetivos. Las comidas se separan en desayuno, almuerzo, cena y snacks, y puedes navegar entre días.
- **Agregar comida**:
  - **Con IA**: escribe lo que comiste ("2 huevos y una tostada") o toma una foto del plato o de la etiqueta nutricional. Gemini estima cada alimento y tú revisas los valores antes de guardar.
  - **Manual**: si dejas las kcal vacías, se calculan con los macros.
  - **Recientes**: repite algo que ya comiste con un toque.
- **Meta semanal**: en *Hoy* ves la semana (de lunes a domingo) frente a tu meta diaria × 7, en calorías y macros, con una barra por día. Si un día comes menos de lo que te toca, esas calorías quedan como **margen** para los días siguientes (y si te pasas, se descuentan): la app te dice hasta cuánto puedes comer hoy si quieres usarlo, o cuánto al día si lo repartes. Los días sin registrar, o con muy pocas calorías (probablemente un registro incompleto), no cuentan para el margen.
- **Historial**: gráfico de calorías de 7, 14 o 30 días, promedios, días que cumpliste tu objetivo y el resumen de las últimas 4 semanas frente a la meta semanal.
- **Progreso**:
  - **Peso y composición corporal**: registra tu peso y, si lo mides (báscula de bioimpedancia, InBody…), tu porcentaje de grasa, porcentaje de músculo y masa ósea en kg, todo a mano. Ponle una meta de peso (inicial y meta), mira cuánto te falta, cómo cambia cada medida (en verde si va bien: menos grasa, más músculo), tu masa grasa y magra, y el gráfico de los últimos 180 días de cada medida. Puedes editar o borrar cualquier registro.
  - **Gym**: ordena tus rutinas en secciones que creas tú (por ejemplo *Hipertrofia* y *Fuerza*; puedes renombrarlas, plegarlas o borrarlas). Cada rutina tiene los ejercicios que quieras y, para cada uno, sus series con repeticiones y carga, en **kg o en número de discos** (útil en máquinas). Al entrenar, cambias las repes y la carga de cada serie y las marcas al terminarlas; la rutina recuerda lo último que levantaste para la próxima vez. El historial se puede filtrar por sección. La app detecta tus récords (más peso que nunca, o más repes con tu mayor peso; los ejercicios en discos se comparan solo con discos) y guarda el historial y el progreso de cada ejercicio. Si cierras la app a mitad de entrenamiento, al volver sigue donde lo dejaste.
- **Grupos** (tipo red social): crea un grupo y comparte el código de 6 letras.
  - Arriba ves a cada amigo como una "historia" cuyo anillo se llena con las calorías del día (y se pinta de colores cuando cumple su objetivo). Toca a alguien para ver su perfil: su peso, composición corporal y meta (si los comparte), sus entrenamientos recientes y sus mejores marcas.
  - **Feed**: las comidas del día, los entrenamientos (con sus récords 🏆) y los registros de peso de todos. Puedes reaccionar (👍🔥💪👏😂) y comentar cada publicación o un alimento concreto.
  - **Hoy**: el progreso diario de cada uno frente a sus objetivos de calorías y macros.
  - **Ranking**: días en objetivo de alimentación, entrenos y récords de la semana, y avance hacia las metas de peso.
- **Notificaciones**: te avisa cuando un amigo registra comida o un entrenamiento, y cuando comentan o reaccionan a lo tuyo. No hay servidor de notificaciones push: la app revisa cada ~15 minutos en segundo plano (puedes apagarlo en Perfil).
- **Perfil**: tu nombre, tus objetivos (sube un PDF, una imagen, un Word o un texto y la IA fija tus calorías y macros; o usa la calculadora Mifflin-St Jeor o edítalos a mano) y qué compartes con tus grupos.

Tus datos se guardan en el teléfono. Con los grupos se comparten los totales del día y, si lo permites, los nombres de los alimentos; tus entrenamientos; y tu peso, composición corporal y meta, salvo que lo desactives en *Perfil → Compartir mi peso y composición corporal*.

## Cómo funciona la clave de Gemini

El repositorio es público y cualquiera puede descargar el APK, así que la clave **no se mete dentro de la app**. GitHub Actions toma `GEMINI_API_KEY` de tu environment y la guarda como secreto de una función de Supabase (`supabase/functions/gemini`). La app le habla a esa función, que comprueba la sesión, aplica un límite diario por usuario (150 consultas por defecto) y reenvía la petición a Gemini.

```
App Android ──► Supabase (base de datos de grupos + función "gemini") ──► Gemini 3.5 Flash-Lite
                         ▲
        GitHub Actions despliega todo con los secretos del environment "production"
```

## Puesta en marcha (una sola vez, unos 5 minutos)

### 1. Crea una cuenta en Supabase y un token

1. Entra a <https://supabase.com> y crea una cuenta gratis (puedes entrar con GitHub). No hace falta crear ningún proyecto: el workflow lo crea solo.
2. Crea un **Access token** en <https://supabase.com/dashboard/account/tokens> y cópialo.

### 2. Agrega los secretos en GitHub

En este repositorio ve a **Settings → Environments → `production`** (créalo si no existe) y agrega estos *Environment secrets*:

| Nombre | Valor |
| --- | --- |
| `GEMINI_API_KEY` | Tu clave de Gemini (<https://aistudio.google.com/apikey>) |
| `SUPABASE_ACCESS_TOKEN` | El access token del paso 1.2 |

Opcional, como *Environment variables*:
- `GEMINI_MODEL`: por defecto `gemini-3.5-flash-lite`.
- `AI_DAILY_LIMIT`: por defecto `150`.
- `SUPABASE_REGION`: por ejemplo `sa-east-1` para Sudamérica. Por defecto se usa una región de América.
- `SUPABASE_PROJECT_REF`: solo si quieres usar un proyecto que ya tengas, en vez de que se cree uno llamado `macro-tracker`.

### 3. Genera la app

Con el código ya en `main`, ve a **Actions → Build & deploy → Run workflow** (o simplemente haz push a `main`). El workflow (`supabase/provision.sh`):

1. crea el proyecto `macro-tracker` en tu cuenta de Supabase si no existe, o lo reactiva si estaba pausado,
2. aplica `supabase/schema.sql` (tablas y funciones con seguridad por usuario),
3. activa el inicio de sesión anónimo,
4. guarda tu clave de Gemini como secreto y despliega la función `gemini`,
5. compila el APK, corre las pruebas y lo publica en **Releases**.

Cada push a `main` repite el proceso y publica una versión nueva.

### 4. Instálala en tu Android

1. Desde el teléfono abre <https://github.com/ThuPapuha/Macro-tracker/releases/latest>.
2. Descarga `MacroTracker-XX.apk` y ábrelo. Si Android lo pide, permite **instalar apps desconocidas** para tu navegador.
3. Pásales el mismo enlace a tus amigos. Uno crea el grupo en la pestaña **Grupos** y comparte el código.

Las versiones nuevas se instalan encima de la anterior sin perder datos, porque todas se firman con la misma llave (`app/signing/macrotracker.jks`).

### Respaldo de tus datos

Tus comidas se guardan en el teléfono. Para no perderlas:

- **Nunca desinstales para actualizar.** Instalar una versión nueva encima de la anterior conserva los datos, porque todas las versiones están firmadas con la misma llave. Desinstalar sí los borra.
- Cada vez que sales de la app (y antes de instalar una actualización) se guarda una copia en **Descargas › MacroTracker › macrotracker-respaldo.json** (Android 10 o superior). Ese archivo se conserva aunque desinstales la app.
- Si reinstalas, ve a **Perfil › Respaldo › Importar** y elige ese archivo. Se recuperan tus comidas, tus objetivos, tus rutinas, entrenamientos y registros de peso y, si estabas en grupos, tu mismo usuario de grupos. No compartas ese archivo: permite entrar a tus grupos como tú.
- También puedes exportar un respaldo a mano desde el mismo lugar.

### Actualizaciones

La app revisa las Releases de este repositorio al abrirse (como máximo cada 6 horas), usando el archivo `version.json` que se publica con cada versión. Si hay una versión nueva, muestra un aviso con el botón **Actualizar**: la descarga y abre el instalador de Android, que siempre pide confirmar. La primera vez, Android te pide permitir que Macro Tracker instale apps. También puedes buscar a mano en *Perfil → Buscar actualización*.

## iPhone

Hay una app nativa para iPhone en [`ios/`](ios/README.md), con las mismas funciones. Usa el mismo servidor, así que los grupos mezclan amigos con Android y con iPhone, y los respaldos sirven en las dos. La guía para compilarla y subirla a TestFlight o a la App Store desde una Mac está en [`ios/README.md`](ios/README.md). Política de privacidad: [`PRIVACY.md`](PRIVACY.md).

## Notas

- **Sin Supabase** la app igual funciona para registrar comidas. Para la IA puedes poner tu propia clave en *Perfil → Usar mi propia clave*; queda guardada solo en ese teléfono.
- **Llave de firma privada (opcional)**: la llave del repo es pública. Si prefieres una propia, agrega los secretos `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`. Cambiar de llave obliga a desinstalar la versión anterior una vez.
- **Identidad**: cada instalación es un usuario anónimo. Si desinstalas la app, vuelve a unirte a tus grupos con el código. Para borrar tu usuario y todo lo que compartiste: *Perfil → Borrar mi cuenta de grupos*.
- Si el login anónimo no se activó solo, actívalo en Supabase: *Authentication → Sign In / Providers → Allow anonymous sign-ins*.
- Los proyectos gratis de Supabase se pausan tras una semana sin uso. Con el uso diario no pasa; si ocurre, vuelve a correr el workflow y lo reactiva.

## Desarrollo

- `core/`: lógica en Kotlin puro (cálculos de nutrición y de gym, cliente de Gemini y de Supabase) con pruebas: `./gradlew :core:test`
- `app/`: interfaz en Jetpack Compose, bases locales Room (`macro-tracker.db` para comidas y `progress.db` para rutinas, entrenamientos y peso). Compilar: `./gradlew :app:assembleRelease`. Para apuntar a tu Supabase en local, define `SUPABASE_URL` y `SUPABASE_ANON_KEY` en `local.properties`.
- `supabase/`: esquema SQL y la función que llama a Gemini.
- `ios/`: la app de iPhone en SwiftUI (ver [`ios/README.md`](ios/README.md)). Las funciones nuevas hay que hacerlas en las dos apps.
