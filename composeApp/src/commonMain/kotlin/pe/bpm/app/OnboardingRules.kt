package pe.bpm.app

/*
 * Reglas puras de «Primeros pasos» y de la ayuda contextual (sin Compose): qué se le dice a cada rol y qué pasos de
 * arranque tiene. Todo el texto es español simple, sin siglas que la persona no conozca.
 */

/** Rol con el que se muestran la introducción, los pasos y la ayuda. */
enum class GuideRole { Worker, Chef, Admin, Super }

/** Una tarjeta de la introducción: [image] es el nombre de la mascota (`ready`, `learning`, `fix`, `done`, `class`, `welcome`). */
data class IntroCard(val image: String, val title: String, val body: String)

/** Introducción de la primera vez: 4 o 5 tarjetas cortas por rol. */
fun introCards(role: GuideRole): List<IntroCard> = when (role) {
    GuideRole.Worker -> listOf(
        IntroCard("welcome", "¡Bienvenido a BPM!", "Aquí cuidas la cocina día a día y aprendes las buenas prácticas de manipulación, en pasos cortos."),
        IntroCard("ready", "Hoy: tus tareas", "En «Hoy» ves lo que toca en tu turno. Toca el círculo de una tarea cuando la termines y cuéntanos cómo quedó."),
        IntroCard("learning", "Aprender un poquito cada día", "Las lecciones duran pocos minutos. Si aprendes algo cada día, tu racha sube y ganas insignias."),
        IntroCard("fix", "¿Viste un problema?", "Pulsa «Reportar un problema», elige de qué se trata y cuéntalo con tus palabras. Tu jefe lo ve en sus avisos; si es urgente, le llega a su teléfono al instante."),
        IntroCard("done", "Ayuda siempre a mano", "El botón «?» de arriba explica cada pantalla. En «Ajustes» (la rueda) puedes agrandar el texto o pedir que la app te lea en voz alta."),
    )
    GuideRole.Chef -> listOf(
        IntroCard("welcome", "¡Bienvenido, jefe de cocina!", "BPM te ayuda a llevar el turno, revisar los controles y acompañar a tu equipo."),
        IntroCard("ready", "Turno: lo que requiere atención", "Arriba ves lo vencido o abierto. Debajo, los controles de hoy y las tareas del turno."),
        IntroCard("class", "Controles paso a paso", "Un control es una revisión con preguntas (temperaturas, limpieza, recepción). Respondes una pregunta por pantalla y, al final, ves el resultado."),
        IntroCard("fix", "Incidencias", "Si algo falla, repórtalo o atiende lo que reportó tu equipo: asignas quién lo corrige y validas el cierre."),
        IntroCard("done", "Ayuda siempre a mano", "El botón «?» explica cada pantalla y en «Ajustes» (la rueda) cambias el tamaño del texto o activas «Escuchar»."),
    )
    GuideRole.Admin -> listOf(
        IntroCard("welcome", "¡Bienvenido, administrador!", "Desde aquí organizas tu empresa: sedes, equipo, controles y capacitación."),
        IntroCard("ready", "Inicio: qué atender primero", "Verás los controles vencidos, las incidencias abiertas y cómo va cada sede. Toca un aviso para ir directo."),
        IntroCard("class", "Controles y Equipo", "En «Controles» programas revisiones por sede. En «Equipo» invitas personas y les asignas su sede y rol."),
        IntroCard("learning", "Academia", "Asigna cursos a tu equipo y mira quién avanza. Las constancias se emiten al completar."),
        IntroCard("done", "Ayuda siempre a mano", "El botón «?» explica cada pantalla, con palabras sencillas. En «Ajustes» (la rueda) están las opciones de accesibilidad."),
    )
    GuideRole.Super -> listOf(
        IntroCard("welcome", "¡Bienvenido a la administración general!", "Desde aquí ves todas las organizaciones y entras a la que necesites."),
        IntroCard("ready", "Organizaciones", "Lista de organizaciones con su cumplimiento. Toca una para abrirla y trabajar dentro de ella."),
        IntroCard("class", "Indicadores", "Mira los números de la organización abierta: cumplimiento, controles a tiempo, incidencias y capacitación."),
        IntroCard("done", "Ayuda siempre a mano", "El botón «?» explica cada pantalla y en «Ajustes» (la rueda) están las opciones de accesibilidad."),
    )
}

/** Un paso de la lista «Primeros pasos»: [id] estable (se guarda), texto simple, si ya está hecho y a dónde lleva. */
data class FirstStep(val id: String, val title: String, val detail: String, val done: Boolean, val target: StepTarget)

/** A dónde lleva un paso; la raíz lo traduce a una ruta. */
enum class StepTarget { Learn, Tasks, Controls, Team, Company, Academy, Report, Home, Organizations, Indicators }

/** Señales reales con las que se marcan solos los pasos (lo que ya existe en la empresa) y los marcados a mano ([manual]). */
data class StepSignals(
    val manual: Set<String> = emptySet(),
    val locations: Int = 0,
    val members: Int = 0,
    val scheduledControls: Boolean = false,
    val controlsDone: Int = 0,
    val tasksDone: Int = 0,
    val lessonsStarted: Boolean = false,
    val organizations: Int = 0,
)

/** Pasos de arranque del rol. Los que se pueden saber con datos reales se marcan solos; el resto, cuando la persona los abre. */
fun firstSteps(role: GuideRole, s: StepSignals): List<FirstStep> = when (role) {
    GuideRole.Worker -> listOf(
        FirstStep("w-lesson", "Haz tu primera lección", "Son pocos minutos y suma a tu racha.", s.lessonsStarted || "w-lesson" in s.manual, StepTarget.Learn),
        FirstStep("w-task", "Registra tu primera tarea", "Toca el círculo de una tarea de tu turno.", s.tasksDone > 0 || "w-task" in s.manual, StepTarget.Home),
        FirstStep("w-report", "Conoce cómo reportar un problema", "Así avisas en dos pasos si ves algo mal.", "w-report" in s.manual, StepTarget.Report),
    )
    GuideRole.Chef -> listOf(
        FirstStep("c-turn", "Revisa tu turno", "Mira lo que requiere atención hoy.", "c-turn" in s.manual, StepTarget.Home),
        FirstStep("c-control", "Haz tu primer control", "Una revisión paso a paso, una pregunta por pantalla.", s.controlsDone > 0 || "c-control" in s.manual, StepTarget.Controls),
        FirstStep("c-team", "Mira a tu equipo", "Quiénes trabajan en tus sedes.", "c-team" in s.manual, StepTarget.Team),
        FirstStep("c-learn", "Aprende algo nuevo", "Tu capacitación pendiente.", "c-learn" in s.manual, StepTarget.Learn),
    )
    GuideRole.Admin -> listOf(
        FirstStep("a-site", "Crea tu sede", "Es el local donde trabaja tu equipo.", s.locations > 0, StepTarget.Company),
        FirstStep("a-team", "Invita a tu equipo", "Agrega personas y asígnales su sede y rol.", s.members > 1 || "a-team" in s.manual, StepTarget.Team),
        FirstStep("a-control", "Programa tu primer control", "Elige una plantilla y la sede donde se hará.", s.scheduledControls || "a-control" in s.manual, StepTarget.Controls),
        FirstStep("a-course", "Asigna un curso", "Elige un curso y a quién va dirigido.", "a-course" in s.manual, StepTarget.Academy),
    )
    GuideRole.Super -> listOf(
        FirstStep("s-org", "Crea o abre una organización", "Elige con cuál vas a trabajar.", s.organizations > 0 || "s-org" in s.manual, StepTarget.Organizations),
        FirstStep("s-kpi", "Mira sus indicadores", "Cumplimiento, controles e incidencias.", "s-kpi" in s.manual, StepTarget.Indicators),
    )
}

/** Cuántos pasos hay hechos, para la ficha «2 de 4». */
fun List<FirstStep>.doneCount(): Int = count { it.done }
