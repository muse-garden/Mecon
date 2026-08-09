package com.mecon.core.musicxml

import com.mecon.core.musicxml.model.*
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.serialization.*

/**
 * Parser for MusicXML documents.
 *
 * Converts XML string to intermediate MusicXmlScore model.
 */
internal class MusicXmlNoteParser {
    fun parseNote(reader: XmlReader): MusicXmlNote {
        var pitch: MusicXmlPitch? = null
        var rest: MusicXmlRest? = null
        var duration: Int? = null
        var isChord = false
        val ties = mutableListOf<MusicXmlTie>()
        var voice = 1
        var type: String? = null
        var dots = 0
        var accidental: MusicXmlAccidental? = null
        var timeModification: MusicXmlTimeModification? = null
        var stem: MusicXmlStem? = null
        var staff = 1
        val beams = mutableListOf<MusicXmlBeam>()
        var notations: MusicXmlNotations? = null
        var grace: MusicXmlGrace? = null
        val printObject = reader.getAttributeValue(null, "print-object") != "no"

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "pitch" -> pitch = parsePitch(reader)
                    "rest" -> rest = parseRest(reader)
                    "duration" -> duration = reader.elementText().toIntOrNull()
                    "chord" -> {
                        isChord = true
                        skipElement(reader)
                    }
                    "tie" -> {
                        val tieType = reader.getAttributeValue(null, "type") ?: "start"
                        ties.add(MusicXmlTie(tieType))
                        skipElement(reader)
                    }
                    "voice" -> voice = reader.elementText().toIntOrNull() ?: 1
                    "type" -> type = reader.elementText()
                    "dot" -> {
                        dots++
                        skipElement(reader)
                    }
                    "accidental" -> accidental = parseAccidental(reader)
                    "time-modification" -> timeModification = parseTimeModification(reader)
                    "stem" -> stem = MusicXmlStem(reader.elementText())
                    "staff" -> staff = reader.elementText().toIntOrNull() ?: 1
                    "beam" -> {
                        val beamNumber = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val beamValue = reader.elementText()
                        beams.add(MusicXmlBeam(beamNumber, beamValue))
                    }
                    "notations" -> notations = parseNotations(reader)
                    "grace" -> {
                        val slash = reader.getAttributeValue(null, "slash") == "yes"
                        grace = MusicXmlGrace(slash = slash)
                        skipElement(reader)
                    }
                    "lyric" -> skipElement(reader)  // Skip lyrics for now
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "note") {
                break
            }
        }

        return MusicXmlNote(
            pitch = pitch,
            rest = rest,
            duration = duration,
            isChord = isChord,
            tie = ties,
            voice = voice,
            type = type,
            dots = dots,
            accidental = accidental,
            timeModification = timeModification,
            stem = stem,
            staff = staff,
            beams = beams,
            notations = notations,
            grace = grace,
            printObject = printObject
        )
    }

    private fun parsePitch(reader: XmlReader): MusicXmlPitch {
        var step = "C"
        var alter = 0
        var octave = 4

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "step" -> step = reader.elementText()
                    "alter" -> alter = reader.elementText().toFloatOrNull()?.toInt() ?: 0
                    "octave" -> octave = reader.elementText().toIntOrNull() ?: 4
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "pitch") {
                break
            }
        }

        return MusicXmlPitch(step, alter, octave)
    }

    private fun parseRest(reader: XmlReader): MusicXmlRest {
        var displayStep: String? = null
        var displayOctave: Int? = null
        val measure = reader.getAttributeValue(null, "measure") == "yes"

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "display-step" -> displayStep = reader.elementText()
                    "display-octave" -> displayOctave = reader.elementText().toIntOrNull()
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "rest") {
                break
            }
        }

        return MusicXmlRest(displayStep, displayOctave, measure)
    }

    private fun parseAccidental(reader: XmlReader): MusicXmlAccidental {
        val cautionary = reader.getAttributeValue(null, "cautionary") == "yes"
        val editorial = reader.getAttributeValue(null, "editorial") == "yes"
        val parentheses = reader.getAttributeValue(null, "parentheses") == "yes"
        val bracket = reader.getAttributeValue(null, "bracket") == "yes"
        val value = reader.elementText()

        return MusicXmlAccidental(value, cautionary, editorial, parentheses, bracket)
    }

    private fun parseTimeModification(reader: XmlReader): MusicXmlTimeModification {
        var actualNotes = 3
        var normalNotes = 2
        var normalType: String? = null
        var normalDot = 0

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "actual-notes" -> actualNotes = reader.elementText().toIntOrNull() ?: 3
                    "normal-notes" -> normalNotes = reader.elementText().toIntOrNull() ?: 2
                    "normal-type" -> normalType = reader.elementText()
                    "normal-dot" -> {
                        normalDot++
                        skipElement(reader)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "time-modification") {
                break
            }
        }

        return MusicXmlTimeModification(actualNotes, normalNotes, normalType, normalDot)
    }

    private fun parseNotations(reader: XmlReader): MusicXmlNotations {
        val tied = mutableListOf<MusicXmlTied>()
        val slurs = mutableListOf<MusicXmlSlur>()
        val tuplets = mutableListOf<MusicXmlTuplet>()
        val articulations = mutableListOf<MusicXmlArticulation>()
        val ornaments = mutableListOf<MusicXmlOrnament>()
        val fermatas = mutableListOf<MusicXmlFermata>()
        var arpeggiate: MusicXmlArpeggiate? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "tied" -> {
                        val tiedType = reader.getAttributeValue(null, "type") ?: "start"
                        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val orientation = reader.getAttributeValue(null, "orientation")
                        val placement = parsePlacement(reader.getAttributeValue(null, "placement"))
                        tied.add(MusicXmlTied(
                            tiedType, number, orientation, placement, parseCurvePosition(reader),
                        ))
                        skipElement(reader)
                    }
                    "slur" -> {
                        val slurType = reader.getAttributeValue(null, "type") ?: "start"
                        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val placement = parsePlacement(reader.getAttributeValue(null, "placement"))
                        slurs.add(MusicXmlSlur(
                            slurType, number, placement, parseCurvePosition(reader),
                        ))
                        skipElement(reader)
                    }
                    "tuplet" -> {
                        val tupletType = reader.getAttributeValue(null, "type") ?: "start"
                        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        val bracket = when (reader.getAttributeValue(null, "bracket")) {
                            "yes" -> true
                            "no" -> false
                            else -> null
                        }
                        val showNumber = reader.getAttributeValue(null, "show-number")
                        val showType = reader.getAttributeValue(null, "show-type")
                        tuplets.add(MusicXmlTuplet(tupletType, number, bracket, showNumber, showType))
                        skipElement(reader)
                    }
                    "articulations" -> articulations.addAll(parseArticulations(reader))
                    "ornaments" -> ornaments.addAll(parseOrnaments(reader))
                    "fermata" -> {
                        val fermataType = reader.getAttributeValue(null, "type") ?: "upright"
                        fermatas.add(MusicXmlFermata(fermataType))
                        skipElement(reader)
                    }
                    "arpeggiate" -> {
                        val direction = reader.getAttributeValue(null, "direction")
                        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        arpeggiate = MusicXmlArpeggiate(direction, number)
                        skipElement(reader)
                    }
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "notations") {
                break
            }
        }

        return MusicXmlNotations(
            tied = tied,
            slurs = slurs,
            tuplets = tuplets,
            articulations = articulations,
            ornaments = ornaments,
            fermatas = fermatas,
            arpeggiate = arpeggiate
        )
    }

    private fun parsePlacement(value: String?): MusicXmlPlacement? = when (value) {
        "above" -> MusicXmlPlacement.ABOVE
        "below" -> MusicXmlPlacement.BELOW
        else -> null
    }

    private fun parseCurvePosition(reader: XmlReader): MusicXmlCurvePosition = MusicXmlCurvePosition(
        defaultX = reader.getAttributeValue(null, "default-x")?.toFloatOrNull(),
        defaultY = reader.getAttributeValue(null, "default-y")?.toFloatOrNull(),
        relativeX = reader.getAttributeValue(null, "relative-x")?.toFloatOrNull(),
        relativeY = reader.getAttributeValue(null, "relative-y")?.toFloatOrNull(),
        bezierX = reader.getAttributeValue(null, "bezier-x")?.toFloatOrNull(),
        bezierY = reader.getAttributeValue(null, "bezier-y")?.toFloatOrNull(),
    )

    private fun parseArticulations(reader: XmlReader): List<MusicXmlArticulation> {
        val articulations = mutableListOf<MusicXmlArticulation>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "accent" -> articulations.add(MusicXmlArticulation.Accent)
                    "strong-accent" -> articulations.add(MusicXmlArticulation.StrongAccent)
                    "staccato" -> articulations.add(MusicXmlArticulation.Staccato)
                    "staccatissimo" -> articulations.add(MusicXmlArticulation.Staccatissimo)
                    "tenuto" -> articulations.add(MusicXmlArticulation.Tenuto)
                    "detached-legato" -> articulations.add(MusicXmlArticulation.DetachedLegato)
                    "spiccato" -> articulations.add(MusicXmlArticulation.Spiccato)
                    "breath-mark" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        articulations.add(MusicXmlArticulation.Breath(placement))
                    }
                    "caesura" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        articulations.add(MusicXmlArticulation.Caesura(placement))
                    }
                    else -> articulations.add(MusicXmlArticulation.Other(reader.localName))
                }
                skipElement(reader)
            } else if (event == EventType.END_ELEMENT && reader.localName == "articulations") {
                break
            }
        }

        return articulations
    }

    private fun parseOrnaments(reader: XmlReader): List<MusicXmlOrnament> {
        val ornaments = mutableListOf<MusicXmlOrnament>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "trill-mark" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        ornaments.add(MusicXmlOrnament.Trill(placement))
                    }
                    "turn" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        ornaments.add(MusicXmlOrnament.Turn(placement))
                    }
                    "inverted-turn" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        ornaments.add(MusicXmlOrnament.InvertedTurn(placement))
                    }
                    "mordent" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        val long = reader.getAttributeValue(null, "long") == "yes"
                        ornaments.add(MusicXmlOrnament.Mordent(placement, long))
                    }
                    "inverted-mordent" -> {
                        val placement = when (reader.getAttributeValue(null, "placement")) {
                            "above" -> MusicXmlPlacement.ABOVE
                            "below" -> MusicXmlPlacement.BELOW
                            else -> null
                        }
                        val long = reader.getAttributeValue(null, "long") == "yes"
                        ornaments.add(MusicXmlOrnament.InvertedMordent(placement, long))
                    }
                    "tremolo" -> {
                        val tremoloType = reader.getAttributeValue(null, "type") ?: "single"
                        val marks = reader.elementText().toIntOrNull() ?: 3
                        ornaments.add(MusicXmlOrnament.Tremolo(tremoloType, marks))
                        continue  // Already consumed text
                    }
                    "wavy-line" -> {
                        val wavyType = reader.getAttributeValue(null, "type") ?: "start"
                        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
                        ornaments.add(MusicXmlOrnament.WavyLine(wavyType, number))
                    }
                    else -> ornaments.add(MusicXmlOrnament.Other(reader.localName))
                }
                skipElement(reader)
            } else if (event == EventType.END_ELEMENT && reader.localName == "ornaments") {
                break
            }
        }

        return ornaments
    }

    private fun XmlReader.elementText(): String {
        val builder = StringBuilder()
        while (hasNext()) {
            when (next()) {
                EventType.TEXT, EventType.CDSECT -> builder.append(text)
                EventType.END_ELEMENT -> break
                else -> {}
            }
        }
        return builder.toString().trim()
    }

    // Helper to skip an element and its children
    private fun skipElement(reader: XmlReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> {}
            }
        }
    }
}
