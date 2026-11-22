package pe.bpm.features.training

import pe.bpm.features.training.domain.CaptionCue
import pe.bpm.features.training.domain.WebVtt
import kotlin.test.*

class WebVttTest {
    private val sample = "﻿WEBVTT\r\n\r\nNOTE ejemplo\r\n\r\n1\r\n00:00:01.000 --> 00:00:04.000\r\n<v Chef>Lávate las manos</v>\r\nantes de empezar.\r\n\r\n00:01:05.500 --> 00:01:08.000 align:start\r\nUsa guantes.\r\n"

    @Test fun parsesCuesWithHoursOptionalAndStripsTags() {
        val cues = WebVtt.parse(sample)
        assertEquals(listOf(CaptionCue(1000, 4000, "Lávate las manos\nantes de empezar."), CaptionCue(65_500, 68_000, "Usa guantes.")), cues)
        assertEquals(3_600_000 + 1000, WebVtt.parse("WEBVTT\n\n01:00:01.000 --> 01:00:02.000\nHola").single().startMs)
    }

    @Test fun activeCueFollowsThePlayerPosition() {
        val cues = WebVtt.parse(sample)
        assertNull(WebVtt.activeCue(cues, 500))
        assertEquals("Usa guantes.", WebVtt.activeCue(cues, 66_000)?.text)
        assertNull(WebVtt.activeCue(cues, 4000), "el fin es exclusivo")
    }

    @Test fun validationMatchesTheServer() {
        assertNull(WebVtt.validationIssue(""))
        assertNull(WebVtt.validationIssue(sample))
        assertNotNull(WebVtt.validationIssue("00:00:01.000 --> 00:00:02.000\nHola"))
        assertNotNull(WebVtt.validationIssue("WEBVTT\n\nHola"))
        assertNotNull(WebVtt.validationIssue("WEBVTT\n\n00:00:01,000 --> 00:00:02,000\nHola"), "formato SRT con coma")
        assertNotNull(WebVtt.validationIssue("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n" + "a".repeat(WebVtt.MAX_LENGTH)))
    }

    @Test fun transcriptJoinsCuesWithoutRepeats() {
        val cues = listOf(CaptionCue(0, 1, "Hola"), CaptionCue(1, 2, "Hola"), CaptionCue(2, 3, "Adiós"))
        assertEquals("Hola\nAdiós", WebVtt.transcript(cues))
    }
}
