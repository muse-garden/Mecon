package com.mecon.core.musicxml

import com.mecon.core.musicxml.model.*
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.serialization.*

/**
 * Parser for MusicXML documents.
 *
 * Converts XML string to intermediate MusicXmlScore model.
 */
class MusicXmlParser {
    private val noteParser = MusicXmlNoteParser()

    /**
     * Parse a MusicXML string to intermediate model.
     */
    fun parse(xml: String): Result<MusicXmlScore> = runCatching {
        val reader = xmlStreaming.newReader(stripDoctype(xml))
        parseScorePartwise(reader)
    }

    /**
     * Remove a `<!DOCTYPE …>` declaration from the prolog.
     *
     * MusicXML files carry an external DOCTYPE pointing at musicxml.org's DTD. The
     * underlying StAX parser would try to resolve that URL (failing offline / when
     * external entity resolution is disabled), so we drop the declaration. MusicXML
     * never uses an internal subset, so removing up to the first `>` is safe.
     */
    private fun stripDoctype(xml: String): String {
        val start = xml.indexOf("<!DOCTYPE")
        if (start < 0) return xml
        val end = xml.indexOf('>', start)
        if (end < 0) return xml
        return xml.removeRange(start, end + 1)
    }

    private fun parseScorePartwise(reader: XmlReader): MusicXmlScore {
        var workTitle: String? = null
        var movementTitle: String? = null
        var identification: MusicXmlIdentification? = null
        var defaults: MusicXmlDefaults? = null
        val credits = mutableListOf<MusicXmlCredit>()
        val partList = mutableListOf<MusicXmlPartInfo>()
        val parts = mutableListOf<MusicXmlPart>()

        reader.nextTag()  // Move to root element

        // Expect score-partwise root
        require(reader.localName == "score-partwise") {
            "Expected score-partwise root element, got ${reader.localName}"
        }

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "work" -> workTitle = parseWork(reader)
                    "movement-title" -> movementTitle = reader.elementText()
                    "identification" -> identification = parseIdentification(reader)
                    "defaults" -> defaults = parseDefaults(reader)
                    "credit" -> credits.add(parseCredit(reader))
                    "part-list" -> partList.addAll(parsePartList(reader))
                    "part" -> parts.add(parsePart(reader))
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "score-partwise") {
                break
            }
        }

        return MusicXmlScore(
            workTitle = workTitle,
            movementTitle = movementTitle,
            identification = identification,
            defaults = defaults,
            credits = credits,
            partList = partList,
            parts = parts
        )
    }

    private fun parseWork(reader: XmlReader): String? {
        var workTitle: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "work-title" -> workTitle = reader.elementText()
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "work") {
                break
            }
        }
        return workTitle
    }

    private fun parseIdentification(reader: XmlReader): MusicXmlIdentification {
        val creators = mutableListOf<MusicXmlCreator>()
        var rights: String? = null
        var encoding: MusicXmlEncoding? = null
        var source: String? = null
        val miscellaneous = mutableListOf<String>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "creator" -> {
                        val type = reader.getAttributeValue(null, "type") ?: "unknown"
                        val name = reader.elementText()
                        creators.add(MusicXmlCreator(type, name))
                    }
                    "rights" -> rights = reader.elementText()
                    "encoding" -> encoding = parseEncoding(reader)
                    "source" -> source = reader.elementText()
                    "miscellaneous" -> {
                        // Skip miscellaneous for now
                        skipElement(reader)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "identification") {
                break
            }
        }

        return MusicXmlIdentification(
            creators = creators,
            rights = rights,
            encoding = encoding,
            source = source,
            miscellaneous = miscellaneous
        )
    }

    private fun parseEncoding(reader: XmlReader): MusicXmlEncoding {
        var software: String? = null
        var encodingDate: String? = null
        val supports = mutableListOf<String>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "software" -> software = reader.elementText()
                    "encoding-date" -> encodingDate = reader.elementText()
                    "supports" -> {
                        val element = reader.getAttributeValue(null, "element")
                        if (element != null) supports.add(element)
                        skipElement(reader)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "encoding") {
                break
            }
        }

        return MusicXmlEncoding(software, encodingDate, supports)
    }

    private fun parseDefaults(reader: XmlReader): MusicXmlDefaults {
        var scaling: MusicXmlScaling? = null
        var pageLayout: MusicXmlPageLayout? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "scaling" -> scaling = parseScaling(reader)
                    "page-layout" -> pageLayout = parsePageLayout(reader)
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "defaults") {
                break
            }
        }

        return MusicXmlDefaults(scaling = scaling, pageLayout = pageLayout)
    }

    private fun parseCredit(reader: XmlReader): MusicXmlCredit {
        val page = reader.getAttributeValue(null, "page")?.toIntOrNull()
        val creditTypes = mutableListOf<String>()
        val words = mutableListOf<MusicXmlCreditWords>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "credit-type" -> creditTypes.add(reader.elementText())
                    "credit-words" -> words.add(parseCreditWords(reader))
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "credit") {
                break
            }
        }

        return MusicXmlCredit(
            page = page,
            creditTypes = creditTypes.filter { it.isNotBlank() },
            words = words.filter { it.text.isNotBlank() }
        )
    }

    private fun parseCreditWords(reader: XmlReader): MusicXmlCreditWords {
        val defaultX = reader.getAttributeValue(null, "default-x")?.toFloatOrNull()
        val defaultY = reader.getAttributeValue(null, "default-y")?.toFloatOrNull()
        val justify = reader.getAttributeValue(null, "justify")
        val halign = reader.getAttributeValue(null, "halign")
        val valign = reader.getAttributeValue(null, "valign")
        val fontFamily = reader.getAttributeValue(null, "font-family")
        val fontSize = reader.getAttributeValue(null, "font-size")?.toFloatOrNull()
        val text = reader.elementText()

        return MusicXmlCreditWords(
            text = text,
            defaultX = defaultX,
            defaultY = defaultY,
            justify = justify,
            halign = halign,
            valign = valign,
            fontFamily = fontFamily,
            fontSize = fontSize
        )
    }

    private fun parseScaling(reader: XmlReader): MusicXmlScaling {
        var millimeters = 7f
        var tenths = 40f

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "millimeters" -> millimeters = reader.elementText().toFloatOrNull() ?: millimeters
                    "tenths" -> tenths = reader.elementText().toFloatOrNull() ?: tenths
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "scaling") {
                break
            }
        }

        return MusicXmlScaling(millimeters = millimeters, tenths = tenths)
    }

    private fun parsePageLayout(reader: XmlReader): MusicXmlPageLayout {
        var pageHeight: Float? = null
        var pageWidth: Float? = null
        val margins = mutableListOf<MusicXmlPageMargins>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "page-height" -> pageHeight = reader.elementText().toFloatOrNull()
                    "page-width" -> pageWidth = reader.elementText().toFloatOrNull()
                    "page-margins" -> margins.add(parsePageMargins(reader))
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "page-layout") {
                break
            }
        }

        return MusicXmlPageLayout(
            pageHeight = pageHeight,
            pageWidth = pageWidth,
            margins = margins.find { it.type == "both" } ?: margins.firstOrNull()
        )
    }

    private fun parsePageMargins(reader: XmlReader): MusicXmlPageMargins {
        val type = reader.getAttributeValue(null, "type")
        var leftMargin: Float? = null
        var rightMargin: Float? = null
        var topMargin: Float? = null
        var bottomMargin: Float? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "left-margin" -> leftMargin = reader.elementText().toFloatOrNull()
                    "right-margin" -> rightMargin = reader.elementText().toFloatOrNull()
                    "top-margin" -> topMargin = reader.elementText().toFloatOrNull()
                    "bottom-margin" -> bottomMargin = reader.elementText().toFloatOrNull()
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "page-margins") {
                break
            }
        }

        return MusicXmlPageMargins(
            type = type,
            leftMargin = leftMargin,
            rightMargin = rightMargin,
            topMargin = topMargin,
            bottomMargin = bottomMargin
        )
    }

    private fun parsePartList(reader: XmlReader): List<MusicXmlPartInfo> {
        val parts = mutableListOf<MusicXmlPartInfo>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "score-part" -> parts.add(parseScorePart(reader))
                    "part-group" -> skipElement(reader)  // Handle grouping later
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "part-list") {
                break
            }
        }

        return parts
    }

    private fun parseScorePart(reader: XmlReader): MusicXmlPartInfo {
        val id = reader.getAttributeValue(null, "id") ?: ""
        var partName = ""
        var partAbbreviation: String? = null
        var scoreInstrument: MusicXmlScoreInstrument? = null
        var midiInstrument: MusicXmlMidiInstrument? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "part-name" -> partName = reader.elementText()
                    "part-abbreviation" -> partAbbreviation = reader.elementText()
                    "score-instrument" -> scoreInstrument = parseScoreInstrument(reader)
                    "midi-instrument" -> midiInstrument = parseMidiInstrument(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "score-part") {
                break
            }
        }

        return MusicXmlPartInfo(
            id = id,
            partName = partName,
            partAbbreviation = partAbbreviation,
            scoreInstrument = scoreInstrument,
            midiInstrument = midiInstrument
        )
    }

    private fun parseScoreInstrument(reader: XmlReader): MusicXmlScoreInstrument {
        val id = reader.getAttributeValue(null, "id") ?: ""
        var instrumentName = ""
        var instrumentAbbreviation: String? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "instrument-name" -> instrumentName = reader.elementText()
                    "instrument-abbreviation" -> instrumentAbbreviation = reader.elementText()
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "score-instrument") {
                break
            }
        }

        return MusicXmlScoreInstrument(id, instrumentName, instrumentAbbreviation)
    }

    private fun parseMidiInstrument(reader: XmlReader): MusicXmlMidiInstrument {
        val id = reader.getAttributeValue(null, "id") ?: ""
        var midiChannel: Int? = null
        var midiProgram: Int? = null
        var volume: Float? = null
        var pan: Float? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "midi-channel" -> midiChannel = reader.elementText().toIntOrNull()
                    "midi-program" -> midiProgram = reader.elementText().toIntOrNull()
                    "volume" -> volume = reader.elementText().toFloatOrNull()
                    "pan" -> pan = reader.elementText().toFloatOrNull()
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "midi-instrument") {
                break
            }
        }

        return MusicXmlMidiInstrument(id, midiChannel, midiProgram, null, volume, pan)
    }

    private fun parsePart(reader: XmlReader): MusicXmlPart {
        val id = reader.getAttributeValue(null, "id") ?: ""
        val measures = mutableListOf<MusicXmlMeasure>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "measure" -> measures.add(parseMeasure(reader))
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "part") {
                break
            }
        }

        return MusicXmlPart(id, measures)
    }

    private fun parseMeasure(reader: XmlReader): MusicXmlMeasure {
        val number = reader.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
        val width = reader.getAttributeValue(null, "width")?.toFloatOrNull()
        val implicit = reader.getAttributeValue(null, "implicit") == "yes"

        var attributes: MusicXmlAttributes? = null
        val directions = mutableListOf<MusicXmlDirection>()
        val notes = mutableListOf<MusicXmlNote>()
        val timeMovements = mutableListOf<MusicXmlTimeMovement>()
        val elements = mutableListOf<MusicXmlMeasureElement>()
        var barline: MusicXmlBarline? = null
        var print: MusicXmlPrint? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "attributes" -> {
                        val attrs = parseAttributes(reader)
                        if (attributes == null) attributes = attrs
                        elements.add(attrs)
                    }
                    "direction" -> {
                        val direction = parseDirection(reader)
                        directions.add(direction)
                        elements.add(direction)
                    }
                    "note" -> {
                        val note = noteParser.parseNote(reader)
                        notes.add(note)
                        elements.add(note)
                    }
                    "forward" -> {
                        val movement = MusicXmlTimeMovement.Forward(parseDurationOnly(reader, "forward"))
                        timeMovements.add(movement)
                        elements.add(movement)
                    }
                    "backup" -> {
                        val movement = MusicXmlTimeMovement.Backup(parseDurationOnly(reader, "backup"))
                        timeMovements.add(movement)
                        elements.add(movement)
                    }
                    "barline" -> barline = parseBarline(reader)
                    "print" -> print = parsePrint(reader)
                    "harmony" -> skipElement(reader)  // Skip harmony for now
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "measure") {
                break
            }
        }

        return MusicXmlMeasure(
            number = number,
            width = width,
            implicit = implicit,
            attributes = attributes,
            directions = directions,
            notes = notes,
            timeMovements = timeMovements,
            elements = elements,
            barline = barline,
            print = print
        )
    }

    /** Parse a `<forward>` / `<backup>` element, extracting only its `<duration>`. */
    private fun parseDurationOnly(reader: XmlReader, tag: String): Int {
        var duration = 0
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "duration" -> duration = reader.elementText().toIntOrNull() ?: 0
                    else -> skipElement(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == tag) {
                break
            }
        }
        return duration
    }

    private fun parseAttributes(reader: XmlReader): MusicXmlAttributes {
        var divisions: Int? = null
        val keys = mutableListOf<MusicXmlKey>()
        val times = mutableListOf<MusicXmlTime>()
        var staves = 1
        val clefs = mutableListOf<MusicXmlClef>()
        val staffDetails = mutableListOf<MusicXmlStaffDetails>()
        var transpose: MusicXmlTranspose? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "divisions" -> divisions = reader.elementText().toIntOrNull()
                    "key" -> keys.add(parseKey(reader))
                    "time" -> times.add(parseTime(reader))
                    "staves" -> staves = reader.elementText().toIntOrNull() ?: 1
                    "clef" -> clefs.add(parseClef(reader))
                    "staff-details" -> staffDetails.add(parseStaffDetails(reader))
                    "transpose" -> transpose = parseTranspose(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "attributes") {
                break
            }
        }

        return MusicXmlAttributes(
            divisions = divisions,
            keys = keys,
            times = times,
            staves = staves,
            clefs = clefs,
            staffDetails = staffDetails,
            transpose = transpose
        )
    }

    private fun parseStaffDetails(reader: XmlReader): MusicXmlStaffDetails {
        val staff = reader.getAttributeValue(null, "number")?.toIntOrNull()
        val printObject = reader.getAttributeValue(null, "print-object") != "no"
        var staffLines = 5
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "staff-lines" -> staffLines = reader.elementText().toIntOrNull() ?: 5
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "staff-details") {
                break
            }
        }
        return MusicXmlStaffDetails(staff = staff, staffLines = staffLines, printObject = printObject)
    }

    private fun parseKey(reader: XmlReader): MusicXmlKey {
        var fifths: Int? = null
        var mode: String? = null
        val staff = reader.getAttributeValue(null, "number")?.toIntOrNull()
        val printObject = reader.getAttributeValue(null, "print-object") != "no"

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "fifths" -> fifths = reader.elementText().toIntOrNull()
                    "mode" -> mode = reader.elementText()
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "key") {
                break
            }
        }

        return MusicXmlKey(
            fifths = fifths,
            mode = mode,
            staff = staff,
            printObject = printObject
        )
    }

    private fun parseTime(reader: XmlReader): MusicXmlTime {
        val beats = mutableListOf<String>()
        val beatType = mutableListOf<Int>()
        val symbol = reader.getAttributeValue(null, "symbol")
        val staff = reader.getAttributeValue(null, "number")?.toIntOrNull()
        val printObject = reader.getAttributeValue(null, "print-object") != "no"

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "beats" -> beats.add(reader.elementText())
                    "beat-type" -> beatType.add(reader.elementText().toIntOrNull() ?: 4)
                    "senza-misura" -> {
                        skipElement(reader)
                        return MusicXmlTime(senzaMisura = true, staff = staff, printObject = printObject)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "time") {
                break
            }
        }

        return MusicXmlTime(
            beats = beats,
            beatType = beatType,
            symbol = symbol,
            staff = staff,
            printObject = printObject
        )
    }

    private fun parseClef(reader: XmlReader): MusicXmlClef {
        var sign = "G"
        var line: Int? = null
        var clefOctaveChange = 0
        val staff = reader.getAttributeValue(null, "number")?.toIntOrNull()
        val printObject = reader.getAttributeValue(null, "print-object") != "no"

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "sign" -> sign = reader.elementText()
                    "line" -> line = reader.elementText().toIntOrNull()
                    "clef-octave-change" -> clefOctaveChange = reader.elementText().toIntOrNull() ?: 0
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "clef") {
                break
            }
        }

        return MusicXmlClef(
            sign = sign,
            line = line,
            clefOctaveChange = clefOctaveChange,
            staff = staff,
            printObject = printObject
        )
    }

    private fun parseTranspose(reader: XmlReader): MusicXmlTranspose {
        var chromatic = 0
        var diatonic: Int? = null
        var octaveChange = 0

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "chromatic" -> chromatic = reader.elementText().toIntOrNull() ?: 0
                    "diatonic" -> diatonic = reader.elementText().toIntOrNull()
                    "octave-change" -> octaveChange = reader.elementText().toIntOrNull() ?: 0
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "transpose") {
                break
            }
        }

        return MusicXmlTranspose(chromatic, diatonic, octaveChange)
    }

    private fun parseDirection(reader: XmlReader): MusicXmlDirection {
        val types = mutableListOf<MusicXmlDirectionType>()
        var offset: Int? = null
        val placement = when (reader.getAttributeValue(null, "placement")) {
            "above" -> MusicXmlPlacement.ABOVE
            "below" -> MusicXmlPlacement.BELOW
            else -> null
        }
        var staff: Int? = null
        var sound: MusicXmlSound? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "direction-type" -> types.addAll(parseDirectionType(reader))
                    "offset" -> offset = reader.elementText().toIntOrNull()
                    "staff" -> staff = reader.elementText().toIntOrNull()
                    "sound" -> sound = parseSound(reader)
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "direction") {
                break
            }
        }

        return MusicXmlDirection(types, offset, placement, staff, sound)
    }

    private fun parseDirectionType(reader: XmlReader): List<MusicXmlDirectionType> {
        val types = mutableListOf<MusicXmlDirectionType>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "words" -> types.add(MusicXmlDirectionType.Words(reader.elementText()))
                    "dynamics" -> {
                        val dynamicType = parseDynamics(reader)
                        if (dynamicType != null) {
                            types.add(MusicXmlDirectionType.Dynamics(dynamicType))
                        }
                    }
                    "metronome" -> types.add(parseMetronome(reader))
                    "wedge" -> {
                        val wedgeType = reader.getAttributeValue(null, "type") ?: "crescendo"
                        val spread = reader.getAttributeValue(null, "spread")?.toIntOrNull()
                        types.add(MusicXmlDirectionType.Wedge(wedgeType, spread))
                        skipElement(reader)
                    }
                    "pedal" -> {
                        val pedalType = reader.getAttributeValue(null, "type") ?: "start"
                        types.add(MusicXmlDirectionType.Pedal(pedalType))
                        skipElement(reader)
                    }
                    "octave-shift" -> {
                        val shiftType = reader.getAttributeValue(null, "type") ?: "up"
                        val size = reader.getAttributeValue(null, "size")?.toIntOrNull() ?: 8
                        types.add(MusicXmlDirectionType.OctaveShift(shiftType, size))
                        skipElement(reader)
                    }
                    else -> {
                        types.add(MusicXmlDirectionType.Other(reader.localName))
                        skipElement(reader)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "direction-type") {
                break
            }
        }

        return types
    }

    private fun parseDynamics(reader: XmlReader): String? {
        var dynamicType: String? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                dynamicType = reader.localName  // p, f, mf, etc.
                skipElement(reader)
            } else if (event == EventType.END_ELEMENT && reader.localName == "dynamics") {
                break
            }
        }

        return dynamicType
    }

    private fun parseMetronome(reader: XmlReader): MusicXmlDirectionType.Metronome {
        var beatUnit = "quarter"
        var beatUnitDots = 0
        var perMinute = 120f

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "beat-unit" -> beatUnit = reader.elementText()
                    "beat-unit-dot" -> {
                        beatUnitDots++
                        skipElement(reader)
                    }
                    "per-minute" -> perMinute = reader.elementText().toFloatOrNull() ?: 120f
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "metronome") {
                break
            }
        }

        return MusicXmlDirectionType.Metronome(beatUnit, beatUnitDots, perMinute)
    }

    private fun parseSound(reader: XmlReader): MusicXmlSound {
        val tempo = reader.getAttributeValue(null, "tempo")?.toFloatOrNull()
        val dynamics = reader.getAttributeValue(null, "dynamics")?.toFloatOrNull()
        val dacapo = reader.getAttributeValue(null, "dacapo") == "yes"
        val fine = reader.getAttributeValue(null, "fine") == "yes"
        val segno = reader.getAttributeValue(null, "segno")
        val dalsegno = reader.getAttributeValue(null, "dalsegno")
        val coda = reader.getAttributeValue(null, "coda")
        val tocoda = reader.getAttributeValue(null, "tocoda")

        skipElement(reader)

        return MusicXmlSound(
            tempo = tempo,
            dynamics = dynamics,
            dacapo = dacapo,
            segno = segno,
            dalsegno = dalsegno,
            coda = coda,
            tocoda = tocoda,
            fine = fine
        )
    }

    private fun parseBarline(reader: XmlReader): MusicXmlBarline {
        val location = reader.getAttributeValue(null, "location") ?: "right"
        var barStyle: String? = null
        var repeat: MusicXmlRepeat? = null
        var ending: MusicXmlEnding? = null

        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                when (reader.localName) {
                    "bar-style" -> barStyle = reader.elementText()
                    "repeat" -> {
                        val direction = reader.getAttributeValue(null, "direction") ?: "backward"
                        val times = reader.getAttributeValue(null, "times")?.toIntOrNull()
                        repeat = MusicXmlRepeat(direction, times)
                        skipElement(reader)
                    }
                    "ending" -> {
                        val endingNumber = reader.getAttributeValue(null, "number") ?: "1"
                        val endingType = reader.getAttributeValue(null, "type") ?: "start"
                        ending = MusicXmlEnding(endingNumber, endingType)
                        skipElement(reader)
                    }
                }
            } else if (event == EventType.END_ELEMENT && reader.localName == "barline") {
                break
            }
        }

        return MusicXmlBarline(location, barStyle, repeat, ending)
    }

    private fun parsePrint(reader: XmlReader): MusicXmlPrint {
        val newSystem = reader.getAttributeValue(null, "new-system") == "yes"
        val newPage = reader.getAttributeValue(null, "new-page") == "yes"
        val blankPage = reader.getAttributeValue(null, "blank-page")?.toIntOrNull()
        val pageNumber = reader.getAttributeValue(null, "page-number")
        skipElement(reader)
        return MusicXmlPrint(newSystem, newPage, blankPage, pageNumber)
    }

    // Helper to read element text content
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
