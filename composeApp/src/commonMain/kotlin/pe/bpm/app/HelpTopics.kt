package pe.bpm.app

/*
 * Ayuda contextual: qué es la pantalla actual, cómo usarla en pocos pasos y un glosario. Texto en español simple, pensado
 * para personal de cocina sin formación técnica. Reglas puras (sin Compose) para poder probarlas.
 */

/** Tema de ayuda: la pantalla desde la que se abrió «?». */
enum class HelpTopic { Home, Learn, Course, Tasks, Controls, Team, Company, Profile, Settings, Report, Survey, Alerts, Reports, Normative, Generic }

data class HelpEntry(val title: String, val purpose: String, val steps: List<String>)

data class GlossaryTerm(val term: String, val meaning: String)

/** Tema que corresponde a una ruta de la app (la primera parte decide; las rutas de detalle heredan el de su sección). */
fun helpTopicForRoute(route: String?): HelpTopic {
    val r = route.orEmpty().substringBefore('?').trim('/')
    return when {
        r == "ajustes" -> HelpTopic.Settings
        r == "reportar" || r == "tareas/incidencias" || r.endsWith("incidencias") -> HelpTopic.Report
        r == "estudio" -> HelpTopic.Survey
        r == "avisos" || r == "empresa/avisos" -> HelpTopic.Alerts
        r == "reportes" -> HelpTopic.Reports
        r == "normativa" -> HelpTopic.Normative
        r.startsWith("aprender/") -> HelpTopic.Course
        r.startsWith("aprender") || r == "empresa/academia" -> HelpTopic.Learn
        r.startsWith("tareas") -> HelpTopic.Tasks
        r.startsWith("control") || r.startsWith("seguimiento") || r == "empresa/controles-programados" || r.startsWith("empresa/plantillas") -> HelpTopic.Controls
        r == "equipo" || r == "empresa/cuentas" -> HelpTopic.Team
        r.startsWith("empresa") || r == "indicadores" || r == "organizaciones" -> HelpTopic.Company
        r.startsWith("cuenta") -> HelpTopic.Profile
        r == "inicio" || r.isEmpty() -> HelpTopic.Home
        else -> HelpTopic.Generic
    }
}

/** Ayuda de [topic] para [role]. */
fun helpEntry(topic: HelpTopic, role: GuideRole): HelpEntry = when (topic) {
    HelpTopic.Home -> when (role) {
        GuideRole.Worker -> HelpEntry("Hoy", "Es tu pantalla de inicio: lo que te toca hacer hoy y cuánto llevas.", listOf(
            "Mira la barra «Meta de hoy»: cuenta tu lección y las tareas del turno.",
            "Toca el círculo de una tarea cuando la termines y cuenta cómo quedó.",
            "Si algo está mal, usa «Reportar un problema».",
            "Toca «Continuar» para seguir tu lección."))
        GuideRole.Chef -> HelpEntry("Turno", "Resume tu turno: lo que requiere atención, los controles de hoy y tu equipo.", listOf(
            "Lee la franja roja «Requiere atención» y toca lo que quieras resolver.",
            "En «Controles de hoy» pulsa «Ver» o «Empezar» para hacer una revisión.",
            "Mira «Incidencias» para atender lo que reportó tu equipo."))
        GuideRole.Admin -> HelpEntry("Inicio", "Te dice qué sedes o personas necesitan atención y cómo va el cumplimiento.", listOf(
            "Empieza por «Requiere atención»: cada aviso lleva a la pantalla que lo resuelve.",
            "Revisa los cuatro indicadores y la lista de sedes.",
            "Usa «Acciones rápidas» para programar un control, asignar un curso o invitar a alguien."))
        GuideRole.Super -> HelpEntry("Organizaciones", "Todas las organizaciones con su cumplimiento.", listOf(
            "Busca una organización por su nombre.",
            "Tócala para abrirla y trabajar dentro de ella.",
            "Crea una nueva con «Nueva organización»."))
    }
    HelpTopic.Learn -> HelpEntry("Aprender", "Tus cursos y lecciones, en el orden recomendado.", listOf(
        "Toca el círculo que dice «Empezar» para abrir tu siguiente lección.",
        "Cada día que aprendes algo, tu racha sube.",
        "Cuando termines un curso, haz la evaluación final para ganar tu constancia."))
    HelpTopic.Course -> HelpEntry("Curso y lección", "Una lección corta con texto, video y preguntas de práctica.", listOf(
        "Lee o mira la lección. Con «Escuchar» la app te la lee en voz alta.",
        "Responde las preguntas de práctica: no califican, sirven para aprender.",
        "Al final, la evaluación del curso sí cuenta y suma puntos."))
    HelpTopic.Tasks -> HelpEntry("Tareas", "Las tareas de limpieza y control de tu sede, separadas por «Ahora», «Más tarde» y «Hechas».", listOf(
        "Toca una tarea para registrarla.",
        "Elige cómo quedó: bien, con observación o no se pudo.",
        "Agrega una foto si te la piden."))
    HelpTopic.Controls -> HelpEntry(if (role == GuideRole.Admin || role == GuideRole.Super) "Controles" else "Controles", "Revisiones con preguntas (temperatura, limpieza, recepción…) que se hacen en cada sede.", when (role) {
        GuideRole.Admin, GuideRole.Super -> listOf(
            "Pulsa «Programar control», elige una plantilla y la sede.",
            "En «Controles programados» ves quién debe hacerlos y cuándo.",
            "En «Revisiones del equipo» miras lo que ya se hizo.")
        else -> listOf(
            "Toca «Empezar» en un control de hoy.",
            "Responde una pregunta por pantalla; puedes volver atrás.",
            "Revisa tus respuestas y finaliza para ver el resultado.")
    })
    HelpTopic.Team -> HelpEntry("Equipo", "Las personas de tu empresa, con su rol y sede.", when (role) {
        GuideRole.Admin, GuideRole.Super -> listOf(
            "Toca una persona para ver sus datos.",
            "«Invitar persona» le envía un acceso por correo.",
            "«Cambiar rol o sede» ajusta lo que puede hacer.")
        else -> listOf("Mira quiénes trabajan en tus sedes.", "Toca a una persona para ver su rol y sede.")
    })
    HelpTopic.Company -> HelpEntry("Empresa", "Los datos de tu empresa, sus sedes y las herramientas de configuración.", listOf(
        "Revisa el nombre y los datos de contacto.",
        "Agrega o edita tus sedes.",
        "Entra a plantillas, avisos y cuentas desde las tarjetas."))
    HelpTopic.Profile -> HelpEntry("Perfil", "Quién eres en BPM y cómo vas.", listOf(
        "Mira tu racha, tus insignias y tus constancias.",
        "Entra a «Ajustes» para cambiar el texto, el sonido o cerrar sesión."))
    HelpTopic.Settings -> HelpEntry("Ajustes", "Opciones para ver, escuchar y usar mejor la app.", listOf(
        "Elige el tamaño del texto y activa «Más contraste» si lo necesitas.",
        "Activa «Escuchar» para que la app lea las pantallas en voz alta.",
        "Aquí también cierras sesión."))
    HelpTopic.Report -> HelpEntry("Reportar un problema", "Avisa a tu jefe de algo que está mal, aunque nadie haya configurado categorías.", listOf(
        "Elige el tipo de problema (o «No sé qué tipo es»).",
        "Cuéntalo con tus palabras y, si quieres, agrega una foto.",
        "Envía el reporte: tu jefe lo recibe y asigna quién lo corrige."))
    HelpTopic.Survey -> HelpEntry("Encuesta del estudio", "Una encuesta voluntaria y anónima sobre cómo trabajas.", listOf(
        "Lee el consentimiento y decide si participas.",
        "Responde una pregunta por pantalla; puedes dejarla y seguir después.",
        "Tus respuestas no muestran tu nombre."))
    HelpTopic.Alerts -> HelpEntry("Avisos", "Los avisos que te manda BPM: controles vencidos, incidencias, etc.", listOf(
        "Toca un aviso para ver el detalle.",
        "Márcalo como atendido cuando lo resuelvas."))
    HelpTopic.Reports -> HelpEntry("Reportes", "Cumplimiento y constancias por período y sede.", listOf(
        "Elige el período y la sede.",
        "Revisa los números y descarga el archivo si lo necesitas."))
    HelpTopic.Normative -> HelpEntry("Buscar en la normativa", "Busca qué dice la norma sanitaria sobre un tema.", listOf(
        "Escribe una palabra (por ejemplo «temperatura»).",
        "Toca un resultado para leer el párrafo completo."))
    HelpTopic.Generic -> HelpEntry("Ayuda", "Esta pantalla forma parte de tu trabajo diario en BPM.", listOf(
        "Usa el menú de abajo o de la izquierda para moverte.",
        "El botón «Volver» o la flecha te regresa a la pantalla anterior."))
}

/** Palabras que se verán en la app, explicadas sin tecnicismos. */
val helpGlossary: List<GlossaryTerm> = listOf(
    GlossaryTerm("BPM", "Buenas prácticas de manipulación: las reglas para trabajar los alimentos de forma segura y limpia."),
    GlossaryTerm("Sede", "Cada local o cocina de tu empresa."),
    GlossaryTerm("Control", "Una revisión con preguntas que se hace en una sede (por ejemplo, temperaturas o limpieza)."),
    GlossaryTerm("Turno", "El horario de trabajo de una sede: mañana, tarde o noche."),
    GlossaryTerm("Tarea", "Una limpieza o revisión puntual que te toca en tu turno."),
    GlossaryTerm("Incidencia", "Un problema que alguien reportó y que hay que corregir."),
    GlossaryTerm("Reportar", "Avisar de un problema para que lo corrijan."),
    GlossaryTerm("Plantilla", "El modelo de preguntas de un control; se programa en una sede."),
    GlossaryTerm("Evidencia", "Una foto que prueba cómo quedó algo."),
    GlossaryTerm("Racha", "Los días seguidos en que aprendiste algo."),
    GlossaryTerm("Constancia", "El certificado que recibes al completar un curso."),
    GlossaryTerm("Normativa", "Las normas sanitarias que BPM aplica."),
)

/** Glosario del rol: las palabras que ese rol realmente verá. */
fun glossaryFor(role: GuideRole): List<GlossaryTerm> {
    val skip = when (role) {
        GuideRole.Worker -> setOf("Plantilla")
        GuideRole.Chef -> setOf("Plantilla")
        GuideRole.Admin, GuideRole.Super -> setOf("Racha")
    }
    return helpGlossary.filter { it.term !in skip }
}
