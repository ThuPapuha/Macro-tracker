# Macro Tracker

App Android para registrar los macros de tus comidas día a día, con grupos para seguir el progreso junto a tus amigos y objetivos automáticos a partir de un documento (plan de tu nutricionista, InBody, análisis, etc.). La IA usa **Gemini 3.6 Flash** (`gemini-3.6-flash`).

## Qué hace

- **Hoy**: anillo de calorías y barras de proteína, carbohidratos y grasas frente a tus objetivos. Las comidas se separan en desayuno, almuerzo, cena y snacks, y puedes navegar entre días.
- **Agregar comida**:
  - **Con IA**: escribe lo que comiste ("2 huevos y una tostada") o toma una foto del plato o de la etiqueta nutricional. Gemini estima cada alimento y tú revisas los valores antes de guardar.
  - **Manual**: si dejas las kcal vacías, se calculan con los macros.
  - **Recientes**: repite algo que ya comiste con un toque.
- **Historial**: gráfico de calorías de 7, 14 o 30 días, promedios y días que cumpliste tu objetivo.
- **Grupos**: crea un grupo, comparte el código de 6 letras y ve el progreso diario de tus amigos, lo que comieron (si lo comparten) y un ranking semanal.
- **Perfil**: sube un PDF, una imagen, un Word o un texto y la IA fija tus calorías y macros objetivo. También hay una calculadora (Mifflin-St Jeor) y edición manual.

Tus comidas se guardan en el teléfono. Con los grupos solo se comparten los totales del día y, si lo permites, los nombres de los alimentos.

## Cómo funciona la clave de Gemini

El repositorio es público y cualquiera puede descargar el APK, así que la clave **no se mete dentro de la app**. GitHub Actions toma `GEMINI_API_KEY` de tu environment y la guarda como secreto de una función de Supabase (`supabase/functions/gemini`). La app le habla a esa función, que comprueba la sesión, aplica un límite diario por usuario (150 consultas por defecto) y reenvía la petición a Gemini.

```
App Android ──► Supabase (base de datos de grupos + función "gemini") ──► Gemini 3.6 Flash
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
- `GEMINI_MODEL`: por defecto `gemini-3.6-flash`.
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

## Notas

- **Sin Supabase** la app igual funciona para registrar comidas. Para la IA puedes poner tu propia clave en *Perfil → Usar mi propia clave*; queda guardada solo en ese teléfono.
- **Llave de firma privada (opcional)**: la llave del repo es pública. Si prefieres una propia, agrega los secretos `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`. Cambiar de llave obliga a desinstalar la versión anterior una vez.
- **Identidad**: cada instalación es un usuario anónimo. Si desinstalas la app, vuelve a unirte a tus grupos con el código.
- Si el login anónimo no se activó solo, actívalo en Supabase: *Authentication → Sign In / Providers → Allow anonymous sign-ins*.
- Los proyectos gratis de Supabase se pausan tras una semana sin uso. Con el uso diario no pasa; si ocurre, vuelve a correr el workflow y lo reactiva.

## Desarrollo

- `core/`: lógica en Kotlin puro (cálculos, cliente de Gemini y de Supabase) con pruebas: `./gradlew :core:test`
- `app/`: interfaz en Jetpack Compose, base local Room. Compilar: `./gradlew :app:assembleRelease`. Para apuntar a tu Supabase en local, define `SUPABASE_URL` y `SUPABASE_ANON_KEY` en `local.properties`.
- `supabase/`: esquema SQL y la función que llama a Gemini.
