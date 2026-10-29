# Política de privacidad de Macro Tracker

*Última actualización: 29 de octubre de 2026*

Macro Tracker es una app gratuita y de código abierto ([código fuente](https://github.com/ThuPapuha/Macro-tracker)) para registrar comidas, entrenamientos y peso, y compartir el progreso con grupos de amigos. Esta política vale para las apps de Android y de iPhone.

## Lo que se queda en tu teléfono

Tus comidas, rutinas, entrenamientos, registros de peso y composición corporal, objetivos y ajustes se guardan **solo en tu teléfono**. También quedan en las copias de respaldo que tú exportas o que la app guarda en el almacenamiento de tu teléfono. No tenemos acceso a ellos.

## Lo que se manda al servidor

La app usa un servidor ([Supabase](https://supabase.com)) solo en estos casos.

**Grupos.** Si te unes a un grupo o creas uno, se crea una cuenta **anónima**, sin correo, teléfono ni contraseña. El servidor guarda lo que ven los miembros de tus grupos:

- el nombre o apodo que elijas;
- el resumen de cada día: calorías y macros, tus objetivos y, si no lo desactivas en Perfil, los nombres de lo que comiste;
- tus entrenamientos: ejercicios, series, repeticiones, carga y récords;
- tu peso, tu composición corporal y tu meta de peso, si no lo desactivas en Perfil;
- los comentarios y reacciones que hagas.

Solo los miembros de tus grupos pueden ver estos datos.

**Inteligencia artificial.** Cuando pides estimar una comida (con texto o con una foto) o calcular tus objetivos a partir de un documento, eso se manda a través de nuestro servidor a **Google Gemini** para analizarlo. Nuestro servidor no guarda el contenido; solo cuenta cuántas veces usas la IA al día, para limitar el abuso. Google trata esos datos según los [términos de la API de Gemini](https://ai.google.dev/gemini-api/terms). Si pones tu propia clave de Gemini en Perfil, la app habla directamente con Google.

## Lo que no hacemos

- No vendemos ni compartimos tus datos con anunciantes.
- No hay publicidad, analítica ni rastreo entre apps.
- No pedimos tu nombre real, tu correo ni tu ubicación.

## Permisos

- **Cámara**: solo para fotografiar comidas o etiquetas cuando tú lo pides.
- **Notificaciones**: para avisarte de la actividad de tus grupos. Puedes apagarlas en Perfil o en los ajustes del sistema.

## Borrar tus datos

- **Del servidor**: en la app, Perfil › *Borrar mi cuenta de grupos*. Se borran tu cuenta anónima y todo lo que compartiste: resúmenes, entrenamientos, peso, comentarios y reacciones. Los grupos que creaste pasan a otro miembro, o se borran si no queda nadie.
- **Salir de un grupo**: en el grupo, menú › *Salir del grupo*.
- **Del teléfono**: desinstalar la app borra sus datos. Las copias de respaldo (las que exportaste y la copia automática que la app de Android deja en Descargas) quedan donde están.

## Menores

La app no está dirigida a menores de 13 años.

## Contacto y cambios

Para preguntas o pedidos sobre tus datos, abre un *issue* en <https://github.com/ThuPapuha/Macro-tracker/issues>. Si esta política cambia, se actualiza en este archivo con su fecha.
