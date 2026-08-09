package com.mecon.core.musicxml

import com.mecon.core.musicxml.model.*

/**
 * Writer for MusicXML documents.
 *
 * Converts intermediate MusicXmlScore model to XML string.
 */
class MusicXmlWriter {

    /**
     * Write MusicXML score to XML string.
     */
    fun write(score: MusicXmlScore): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">""")
            appendLine("""<score-partwise version="4.0">""")

            // Work
            score.workTitle?.let { title ->
                appendLine("  <work>")
                appendLine("    <work-title>${escapeXml(title)}</work-title>")
                appendLine("  </work>")
            }

            // Movement title (if different from work title)
            score.movementTitle?.takeIf { it != score.workTitle }?.let { title ->
                appendLine("  <movement-title>${escapeXml(title)}</movement-title>")
            }

            // Identification
            score.identification?.let { writeIdentification(it) }

            // Defaults
            score.defaults?.let { writeDefaults(it) }

            // Credits
            for (credit in score.credits) {
                writeCredit(credit)
            }

            // Part list
            appendLine("  <part-list>")
            for (partInfo in score.partList) {
                writePartInfo(partInfo)
            }
            appendLine("  </part-list>")

            // Parts
            for (part in score.parts) {
                writePart(part)
            }

            appendLine("</score-partwise>")
        }
    }

    private fun StringBuilder.writeIdentification(id: MusicXmlIdentification) {
        appendLine("  <identification>")

        for (creator in id.creators) {
            appendLine("""    <creator type="${creator.type}">${escapeXml(creator.name)}</creator>""")
        }

        id.rights?.let {
            appendLine("    <rights>${escapeXml(it)}</rights>")
        }

        id.encoding?.let { enc ->
            appendLine("    <encoding>")
            enc.software?.let {
                appendLine("      <software>${escapeXml(it)}</software>")
            }
            enc.encodingDate?.let {
                appendLine("      <encoding-date>$it</encoding-date>")
            }
            for (support in enc.supports) {
                appendLine("""      <supports element="$support" type="yes"/>""")
            }
            appendLine("    </encoding>")
        }

        id.source?.let {
            appendLine("    <source>${escapeXml(it)}</source>")
        }

        appendLine("  </identification>")
    }

    private fun StringBuilder.writeDefaults(defaults: MusicXmlDefaults) {
        if (defaults.scaling == null && defaults.pageLayout == null) return

        appendLine("  <defaults>")

        defaults.scaling?.let { scaling ->
            appendLine("    <scaling>")
            appendLine("      <millimeters>${formatFloat(scaling.millimeters)}</millimeters>")
            appendLine("      <tenths>${formatFloat(scaling.tenths)}</tenths>")
            appendLine("    </scaling>")
        }

        defaults.pageLayout?.let { layout ->
            appendLine("    <page-layout>")
            layout.pageHeight?.let { appendLine("      <page-height>${formatFloat(it)}</page-height>") }
            layout.pageWidth?.let { appendLine("      <page-width>${formatFloat(it)}</page-width>") }
            layout.margins?.let { margins ->
                val typeAttr = margins.type?.let { """ type="$it"""" } ?: ""
                appendLine("      <page-margins$typeAttr>")
                margins.leftMargin?.let { appendLine("        <left-margin>${formatFloat(it)}</left-margin>") }
                margins.rightMargin?.let { appendLine("        <right-margin>${formatFloat(it)}</right-margin>") }
                margins.topMargin?.let { appendLine("        <top-margin>${formatFloat(it)}</top-margin>") }
                margins.bottomMargin?.let { appendLine("        <bottom-margin>${formatFloat(it)}</bottom-margin>") }
                appendLine("      </page-margins>")
            }
            appendLine("    </page-layout>")
        }

        appendLine("  </defaults>")
    }

    private fun StringBuilder.writeCredit(credit: MusicXmlCredit) {
        val pageAttr = credit.page?.let { """ page="$it"""" } ?: ""
        appendLine("  <credit$pageAttr>")
        for (type in credit.creditTypes) {
            appendLine("    <credit-type>${escapeXml(type)}</credit-type>")
        }
        for (words in credit.words) {
            val attrs = buildString {
                words.defaultX?.let { append(""" default-x="${formatFloat(it)}"""") }
                words.defaultY?.let { append(""" default-y="${formatFloat(it)}"""") }
                words.justify?.let { append(""" justify="${escapeXml(it)}"""") }
                words.halign?.let { append(""" halign="${escapeXml(it)}"""") }
                words.valign?.let { append(""" valign="${escapeXml(it)}"""") }
                words.fontFamily?.let { append(""" font-family="${escapeXml(it)}"""") }
                words.fontSize?.let { append(""" font-size="${formatFloat(it)}"""") }
            }
            appendLine("    <credit-words$attrs>${escapeXml(words.text)}</credit-words>")
        }
        appendLine("  </credit>")
    }

    private fun StringBuilder.writePartInfo(partInfo: MusicXmlPartInfo) {
        appendLine("""    <score-part id="${partInfo.id}">""")
        appendLine("      <part-name>${escapeXml(partInfo.partName)}</part-name>")

        partInfo.partAbbreviation?.let {
            appendLine("      <part-abbreviation>${escapeXml(it)}</part-abbreviation>")
        }

        partInfo.scoreInstrument?.let { inst ->
            appendLine("""      <score-instrument id="${inst.id}">""")
            appendLine("        <instrument-name>${escapeXml(inst.instrumentName)}</instrument-name>")
            inst.instrumentAbbreviation?.let {
                appendLine("        <instrument-abbreviation>${escapeXml(it)}</instrument-abbreviation>")
            }
            appendLine("      </score-instrument>")
        }

        partInfo.midiInstrument?.let { midi ->
            appendLine("""      <midi-instrument id="${midi.id}">""")
            midi.midiChannel?.let { appendLine("        <midi-channel>$it</midi-channel>") }
            midi.midiProgram?.let { appendLine("        <midi-program>$it</midi-program>") }
            midi.volume?.let { appendLine("        <volume>$it</volume>") }
            midi.pan?.let { appendLine("        <pan>$it</pan>") }
            appendLine("      </midi-instrument>")
        }

        appendLine("    </score-part>")
    }

    private fun StringBuilder.writePart(part: MusicXmlPart) {
        appendLine("""  <part id="${part.id}">""")

        for (measure in part.measures) {
            writeMeasure(measure)
        }

        appendLine("  </part>")
    }

    private fun StringBuilder.writeMeasure(measure: MusicXmlMeasure) {
        val implicitAttr = if (measure.implicit) """ implicit="yes"""" else ""
        val widthAttr = measure.width?.let { """ width="$it"""" } ?: ""
        appendLine("""    <measure number="${measure.number}"$implicitAttr$widthAttr>""")

        // Print
        measure.print?.let { writePrint(it) }

        // Attributes
        measure.attributes?.let { writeAttributes(it) }

        for (element in measure.elements) {
            when (element) {
                is MusicXmlAttributes -> if (element !== measure.attributes) writeAttributes(element)
                is MusicXmlTimeMovement.Forward -> writeForward(element.duration)
                is MusicXmlTimeMovement.Backup -> writeBackup(element.duration)
                is MusicXmlDirection -> writeDirection(element)
                is MusicXmlNote -> writeNote(element)
            }
        }

        // Directions
        if (measure.elements.isEmpty() || measure.directions.any { it !in measure.elements }) {
            for (direction in measure.directions) {
                if (direction !in measure.elements) writeDirection(direction)
            }
        }

        // Notes, grouped by voice. Between voice groups we rewind the measure cursor
        // with <backup> so each voice is read from the start of the measure. The backup
        // duration equals the filled length of the just-finished voice group (chord
        // members and grace notes contribute no duration).
        var currentVoice: Int? = null
        var groupFilled = 0
        val notesFromElements = measure.elements.filterIsInstance<MusicXmlNote>().toSet()
        for (note in measure.notes) {
            if (note in notesFromElements) continue
            if (currentVoice != null && note.voice != currentVoice) {
                if (groupFilled > 0) writeBackup(groupFilled)
                groupFilled = 0
            }
            currentVoice = note.voice
            writeNote(note)
            if (note.grace == null && !note.isChord) {
                groupFilled += note.duration ?: 0
            }
        }

        // Barline
        measure.barline?.let { writeBarline(it) }

        appendLine("    </measure>")
    }

    private fun StringBuilder.writePrint(print: MusicXmlPrint) {
        val attrs = buildString {
            if (print.newSystem) append(""" new-system="yes"""")
            if (print.newPage) append(""" new-page="yes"""")
            print.blankPage?.let { append(""" blank-page="$it"""") }
            print.pageNumber?.let { append(""" page-number="$it"""") }
        }
        if (attrs.isNotEmpty()) {
            appendLine("      <print$attrs/>")
        }
    }

    private fun StringBuilder.writeAttributes(attrs: MusicXmlAttributes) {
        appendLine("      <attributes>")

        attrs.divisions?.let {
            appendLine("        <divisions>$it</divisions>")
        }

        for (key in attrs.keys) {
            writeKey(key)
        }

        for (time in attrs.times) {
            writeTime(time)
        }

        if (attrs.staves > 1) {
            appendLine("        <staves>${attrs.staves}</staves>")
        }

        for (clef in attrs.clefs) {
            writeClef(clef)
        }

        for (details in attrs.staffDetails) {
            writeStaffDetails(details)
        }

        attrs.transpose?.let { writeTranspose(it) }

        appendLine("      </attributes>")
    }

    private fun StringBuilder.writeStaffDetails(details: MusicXmlStaffDetails) {
        val staffAttr = details.staff?.let { """ number="$it"""" } ?: ""
        // We emit staff-details to carry per-staff visibility (print-object). Always state it explicitly
        // so a re-show ("yes") cancels an earlier hide ("no").
        val printAttr = if (details.printObject) """ print-object="yes"""" else """ print-object="no""""
        if (details.staffLines != 5) {
            appendLine("        <staff-details$staffAttr$printAttr>")
            appendLine("          <staff-lines>${details.staffLines}</staff-lines>")
            appendLine("        </staff-details>")
        } else {
            appendLine("        <staff-details$staffAttr$printAttr/>")
        }
    }

    private fun StringBuilder.writeKey(key: MusicXmlKey) {
        val staffAttr = key.staff?.let { """ number="$it"""" } ?: ""
        val printAttr = if (!key.printObject) """ print-object="no"""" else ""

        if (key.fifths != null) {
            appendLine("        <key$staffAttr$printAttr>")
            appendLine("          <fifths>${key.fifths}</fifths>")
            key.mode?.let { appendLine("          <mode>$it</mode>") }
            appendLine("        </key>")
        }
    }

    private fun StringBuilder.writeTime(time: MusicXmlTime) {
        val staffAttr = time.staff?.let { """ number="$it"""" } ?: ""
        val symbolAttr = time.symbol?.let { """ symbol="$it"""" } ?: ""
        val printAttr = if (!time.printObject) """ print-object="no"""" else ""

        if (time.senzaMisura) {
            appendLine("        <time$staffAttr$symbolAttr$printAttr>")
            appendLine("          <senza-misura/>")
            appendLine("        </time>")
        } else {
            appendLine("        <time$staffAttr$symbolAttr$printAttr>")
            for (beats in time.beats) {
                appendLine("          <beats>$beats</beats>")
            }
            for (beatType in time.beatType) {
                appendLine("          <beat-type>$beatType</beat-type>")
            }
            appendLine("        </time>")
        }
    }

    private fun StringBuilder.writeClef(clef: MusicXmlClef) {
        val staffAttr = clef.staff?.let { """ number="$it"""" } ?: ""
        val printAttr = if (!clef.printObject) """ print-object="no"""" else ""

        appendLine("        <clef$staffAttr$printAttr>")
        appendLine("          <sign>${clef.sign}</sign>")
        clef.line?.let { appendLine("          <line>$it</line>") }
        if (clef.clefOctaveChange != 0) {
            appendLine("          <clef-octave-change>${clef.clefOctaveChange}</clef-octave-change>")
        }
        appendLine("        </clef>")
    }

    private fun StringBuilder.writeTranspose(transpose: MusicXmlTranspose) {
        appendLine("        <transpose>")
        transpose.diatonic?.let { appendLine("          <diatonic>$it</diatonic>") }
        appendLine("          <chromatic>${transpose.chromatic}</chromatic>")
        if (transpose.octaveChange != 0) {
            appendLine("          <octave-change>${transpose.octaveChange}</octave-change>")
        }
        appendLine("        </transpose>")
    }

    private fun StringBuilder.writeDirection(direction: MusicXmlDirection) {
        val placementAttr = direction.placement?.let {
            """ placement="${it.name.lowercase()}""""
        } ?: ""

        appendLine("      <direction$placementAttr>")

        for (dirType in direction.types) {
            appendLine("        <direction-type>")
            writeDirectionType(dirType)
            appendLine("        </direction-type>")
        }

        direction.offset?.let {
            appendLine("        <offset>$it</offset>")
        }

        direction.staff?.let {
            appendLine("        <staff>$it</staff>")
        }

        direction.sound?.let { writeSound(it) }

        appendLine("      </direction>")
    }

    private fun StringBuilder.writeDirectionType(dirType: MusicXmlDirectionType) {
        when (dirType) {
            is MusicXmlDirectionType.Words -> {
                appendLine("          <words>${escapeXml(dirType.text)}</words>")
            }
            is MusicXmlDirectionType.Dynamics -> {
                appendLine("          <dynamics>")
                appendLine("            <${dirType.type}/>")
                appendLine("          </dynamics>")
            }
            is MusicXmlDirectionType.Metronome -> {
                appendLine("          <metronome>")
                appendLine("            <beat-unit>${dirType.beatUnit}</beat-unit>")
                repeat(dirType.beatUnitDots) {
                    appendLine("            <beat-unit-dot/>")
                }
                appendLine("            <per-minute>${dirType.perMinute.toInt()}</per-minute>")
                appendLine("          </metronome>")
            }
            is MusicXmlDirectionType.Wedge -> {
                val spreadAttr = dirType.spread?.let { """ spread="$it"""" } ?: ""
                appendLine("""          <wedge type="${dirType.type}"$spreadAttr/>""")
            }
            is MusicXmlDirectionType.Pedal -> {
                appendLine("""          <pedal type="${dirType.type}"/>""")
            }
            is MusicXmlDirectionType.OctaveShift -> {
                appendLine("""          <octave-shift type="${dirType.type}" size="${dirType.size}"/>""")
            }
            is MusicXmlDirectionType.Other -> {
                dirType.content?.let {
                    appendLine("          <${dirType.name}>${escapeXml(it)}</${dirType.name}>")
                } ?: appendLine("          <${dirType.name}/>")
            }
        }
    }

    private fun StringBuilder.writeSound(sound: MusicXmlSound) {
        val attrs = buildString {
            sound.tempo?.let { append(""" tempo="${it.toInt()}"""") }
            sound.dynamics?.let { append(""" dynamics="$it"""") }
            if (sound.dacapo) append(""" dacapo="yes"""")
            if (sound.fine) append(""" fine="yes"""")
            sound.segno?.let { append(""" segno="$it"""") }
            sound.dalsegno?.let { append(""" dalsegno="$it"""") }
            sound.coda?.let { append(""" coda="$it"""") }
            sound.tocoda?.let { append(""" tocoda="$it"""") }
        }
        if (attrs.isNotEmpty()) {
            appendLine("        <sound$attrs/>")
        }
    }

    private fun StringBuilder.writeNote(note: MusicXmlNote) {
        val printAttr = if (!note.printObject) """ print-object="no"""" else ""
        appendLine("      <note$printAttr>")

        // Grace note
        note.grace?.let {
            val slashAttr = if (it.slash) """ slash="yes"""" else ""
            appendLine("        <grace$slashAttr/>")
        }

        // Chord marker
        if (note.isChord) {
            appendLine("        <chord/>")
        }

        // Pitch or rest
        note.pitch?.let { writePitch(it) }
        note.rest?.let { writeRest(it) }
        note.unpitched?.let { writeUnpitched(it) }

        // Duration (not for grace notes)
        if (note.grace == null) {
            note.duration?.let { appendLine("        <duration>$it</duration>") }
        }

        // Tie
        for (tie in note.tie) {
            appendLine("""        <tie type="${tie.type}"/>""")
        }

        // Voice
        appendLine("        <voice>${note.voice}</voice>")

        // Type
        note.type?.let { appendLine("        <type>$it</type>") }

        // Dots
        repeat(note.dots) {
            appendLine("        <dot/>")
        }

        // Accidental
        note.accidental?.let { writeAccidental(it) }

        // Time modification
        note.timeModification?.let { writeTimeModification(it) }

        // Stem
        note.stem?.let { appendLine("        <stem>${it.value}</stem>") }

        // Notehead
        note.notehead?.let { writeNotehead(it) }

        // Staff
        if (note.staff > 1) {
            appendLine("        <staff>${note.staff}</staff>")
        }

        // Beams
        for (beam in note.beams) {
            appendLine("""        <beam number="${beam.number}">${beam.value}</beam>""")
        }

        // Notations
        note.notations?.let { writeNotations(it) }

        appendLine("      </note>")
    }

    private fun StringBuilder.writePitch(pitch: MusicXmlPitch) {
        appendLine("        <pitch>")
        appendLine("          <step>${pitch.step}</step>")
        if (pitch.alter != 0) {
            appendLine("          <alter>${pitch.alter}</alter>")
        }
        appendLine("          <octave>${pitch.octave}</octave>")
        appendLine("        </pitch>")
    }

    private fun StringBuilder.writeRest(rest: MusicXmlRest) {
        val measureAttr = if (rest.measure) """ measure="yes"""" else ""
        if (rest.displayStep != null && rest.displayOctave != null) {
            appendLine("        <rest$measureAttr>")
            appendLine("          <display-step>${rest.displayStep}</display-step>")
            appendLine("          <display-octave>${rest.displayOctave}</display-octave>")
            appendLine("        </rest>")
        } else {
            appendLine("        <rest$measureAttr/>")
        }
    }

    private fun StringBuilder.writeUnpitched(unpitched: MusicXmlUnpitched) {
        appendLine("        <unpitched>")
        appendLine("          <display-step>${unpitched.displayStep}</display-step>")
        appendLine("          <display-octave>${unpitched.displayOctave}</display-octave>")
        appendLine("        </unpitched>")
    }

    private fun StringBuilder.writeAccidental(accidental: MusicXmlAccidental) {
        val attrs = buildString {
            if (accidental.cautionary) append(""" cautionary="yes"""")
            if (accidental.editorial) append(""" editorial="yes"""")
            if (accidental.parentheses) append(""" parentheses="yes"""")
            if (accidental.bracket) append(""" bracket="yes"""")
        }
        appendLine("        <accidental$attrs>${accidental.value}</accidental>")
    }

    private fun StringBuilder.writeTimeModification(timeMod: MusicXmlTimeModification) {
        appendLine("        <time-modification>")
        appendLine("          <actual-notes>${timeMod.actualNotes}</actual-notes>")
        appendLine("          <normal-notes>${timeMod.normalNotes}</normal-notes>")
        timeMod.normalType?.let {
            appendLine("          <normal-type>$it</normal-type>")
        }
        repeat(timeMod.normalDot) {
            appendLine("          <normal-dot/>")
        }
        appendLine("        </time-modification>")
    }

    private fun StringBuilder.writeNotehead(notehead: MusicXmlNotehead) {
        val attrs = buildString {
            notehead.filled?.let { append(if (it) """ filled="yes"""" else """ filled="no"""") }
            if (notehead.parentheses) append(""" parentheses="yes"""")
        }
        appendLine("        <notehead$attrs>${notehead.value}</notehead>")
    }

    private fun StringBuilder.writeNotations(notations: MusicXmlNotations) {
        appendLine("        <notations>")

        for (tied in notations.tied) {
            val orientAttr = tied.orientation?.let { """ orientation="$it"""" } ?: ""
            val placementAttr = tied.placement?.let { """ placement="${it.name.lowercase()}"""" } ?: ""
            appendLine(
                """          <tied type="${tied.type}" number="${tied.number}"$orientAttr$placementAttr${curveAttributes(tied.curve)}/>"""
            )
        }

        for (slur in notations.slurs) {
            val placementAttr = slur.placement?.let { """ placement="${it.name.lowercase()}"""" } ?: ""
            appendLine(
                """          <slur type="${slur.type}" number="${slur.number}"$placementAttr${curveAttributes(slur.curve)}/>"""
            )
        }

        for (tuplet in notations.tuplets) {
            val bracketAttr = tuplet.bracket?.let { if (it) """ bracket="yes"""" else """ bracket="no"""" } ?: ""
            val showNumberAttr = tuplet.showNumber?.let { """ show-number="$it"""" } ?: ""
            appendLine("""          <tuplet type="${tuplet.type}" number="${tuplet.number}"$bracketAttr$showNumberAttr/>""")
        }

        if (notations.articulations.isNotEmpty()) {
            appendLine("          <articulations>")
            for (art in notations.articulations) {
                val artName = when (art) {
                    is MusicXmlArticulation.Accent -> "accent"
                    is MusicXmlArticulation.StrongAccent -> "strong-accent"
                    is MusicXmlArticulation.Staccato -> "staccato"
                    is MusicXmlArticulation.Staccatissimo -> "staccatissimo"
                    is MusicXmlArticulation.Tenuto -> "tenuto"
                    is MusicXmlArticulation.DetachedLegato -> "detached-legato"
                    is MusicXmlArticulation.Spiccato -> "spiccato"
                    is MusicXmlArticulation.Breath -> "breath-mark"
                    is MusicXmlArticulation.Caesura -> "caesura"
                    is MusicXmlArticulation.Other -> art.name
                }
                appendLine("            <$artName/>")
            }
            appendLine("          </articulations>")
        }

        if (notations.ornaments.isNotEmpty()) {
            appendLine("          <ornaments>")
            for (orn in notations.ornaments) {
                when (orn) {
                    is MusicXmlOrnament.Trill -> appendLine("            <trill-mark/>")
                    is MusicXmlOrnament.TrillMark -> appendLine("            <trill-mark/>")
                    is MusicXmlOrnament.Turn -> appendLine("            <turn/>")
                    is MusicXmlOrnament.InvertedTurn -> appendLine("            <inverted-turn/>")
                    is MusicXmlOrnament.Mordent -> appendLine("            <mordent/>")
                    is MusicXmlOrnament.InvertedMordent -> appendLine("            <inverted-mordent/>")
                    is MusicXmlOrnament.Tremolo -> appendLine("""            <tremolo type="${orn.type}">${orn.marks}</tremolo>""")
                    is MusicXmlOrnament.WavyLine -> appendLine("""            <wavy-line type="${orn.type}" number="${orn.number}"/>""")
                    is MusicXmlOrnament.Other -> appendLine("            <${orn.name}/>")
                }
            }
            appendLine("          </ornaments>")
        }

        for (fermata in notations.fermatas) {
            appendLine("""          <fermata type="${fermata.type}">${fermata.shape}</fermata>""")
        }

        notations.arpeggiate?.let {
            val dirAttr = it.direction?.let { d -> """ direction="$d"""" } ?: ""
            appendLine("""          <arpeggiate$dirAttr/>""")
        }

        for (gliss in notations.glissando) {
            val lineTypeAttr = gliss.lineType?.let { """ line-type="$it"""" } ?: ""
            appendLine("""          <glissando type="${gliss.type}" number="${gliss.number}"$lineTypeAttr/>""")
        }

        for (slide in notations.slide) {
            val lineTypeAttr = slide.lineType?.let { """ line-type="$it"""" } ?: ""
            appendLine("""          <slide type="${slide.type}" number="${slide.number}"$lineTypeAttr/>""")
        }

        appendLine("        </notations>")
    }

    private fun curveAttributes(curve: MusicXmlCurvePosition): String = buildString {
        curve.defaultX?.let { append(""" default-x="${formatFloat(it)}"""") }
        curve.defaultY?.let { append(""" default-y="${formatFloat(it)}"""") }
        curve.relativeX?.let { append(""" relative-x="${formatFloat(it)}"""") }
        curve.relativeY?.let { append(""" relative-y="${formatFloat(it)}"""") }
        curve.bezierX?.let { append(""" bezier-x="${formatFloat(it)}"""") }
        curve.bezierY?.let { append(""" bezier-y="${formatFloat(it)}"""") }
    }

    private fun StringBuilder.writeBackup(duration: Int) {
        appendLine("      <backup>")
        appendLine("        <duration>$duration</duration>")
        appendLine("      </backup>")
    }

    private fun StringBuilder.writeForward(duration: Int) {
        appendLine("      <forward>")
        appendLine("        <duration>$duration</duration>")
        appendLine("      </forward>")
    }

    private fun StringBuilder.writeBarline(barline: MusicXmlBarline) {
        appendLine("""      <barline location="${barline.location}">""")

        barline.barStyle?.let {
            appendLine("        <bar-style>$it</bar-style>")
        }

        barline.repeat?.let {
            val timesAttr = it.times?.let { t -> """ times="$t"""" } ?: ""
            appendLine("""        <repeat direction="${it.direction}"$timesAttr/>""")
        }

        barline.ending?.let {
            appendLine("""        <ending number="${it.number}" type="${it.type}"/>""")
        }

        appendLine("      </barline>")
    }

    /**
     * Escape special XML characters.
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatFloat(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else value.toString()
}
