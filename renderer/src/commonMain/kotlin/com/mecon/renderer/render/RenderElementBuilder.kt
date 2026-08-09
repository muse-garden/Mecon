package com.mecon.renderer.render

import com.mecon.api.primitive.EventId
import com.mecon.api.primitive.TrackId
import com.mecon.renderer.geometry.AbsolutePoint
import com.mecon.renderer.geometry.AbsoluteRect
import com.mecon.renderer.geometry.Pixels
import com.mecon.renderer.geometry.RelativeRect

/**
 * Builder for constructing RenderElement.
 */
class RenderElementBuilder(
    private val id: RenderElementId,
    private val type: RenderElementType
) {
    private val commands = mutableListOf<RenderCommand>()
    private var hitBox: AbsoluteRect? = null
    private var eventId: EventId? = null
    private var trackId: TrackId? = null
    private var measureNumber: Int? = null
    private var systemIndex: Int? = null
    private var staffIndex: Int? = null
    private var selected: Boolean = false
    private var highlighted: Boolean = false
    private val metadata = mutableMapOf<String, String>()
    private var relativeHitBox: RelativeRect? = null

    fun addCommand(command: RenderCommand): RenderElementBuilder {
        commands.add(command)
        return this
    }

    fun addCommands(newCommands: List<RenderCommand>): RenderElementBuilder {
        commands.addAll(newCommands)
        return this
    }

    fun hitBox(hitBox: AbsoluteRect): RenderElementBuilder {
        this.hitBox = hitBox
        return this
    }

    fun eventId(id: EventId): RenderElementBuilder {
        this.eventId = id
        return this
    }

    fun trackId(id: TrackId): RenderElementBuilder {
        this.trackId = id
        return this
    }

    fun measureNumber(number: Int): RenderElementBuilder {
        this.measureNumber = number
        return this
    }

    fun systemIndex(index: Int): RenderElementBuilder {
        this.systemIndex = index
        return this
    }

    fun staffIndex(index: Int): RenderElementBuilder {
        this.staffIndex = index
        return this
    }

    fun selected(selected: Boolean): RenderElementBuilder {
        this.selected = selected
        return this
    }

    fun highlighted(highlighted: Boolean): RenderElementBuilder {
        this.highlighted = highlighted
        return this
    }

    fun metadata(key: String, value: String): RenderElementBuilder {
        metadata[key] = value
        return this
    }

    fun relativeHitBox(box: RelativeRect): RenderElementBuilder {
        this.relativeHitBox = box
        return this
    }

    fun build(): RenderElement {
        // Calculate hit box from commands if not explicitly set
        val calculatedHitBox = hitBox ?: calculateBounds()

        return RenderElement(
            id = id,
            type = type,
            commands = commands.toList(),
            hitBox = calculatedHitBox,
            eventId = eventId,
            trackId = trackId,
            measureNumber = measureNumber,
            systemIndex = systemIndex,
            staffIndex = staffIndex,
            selected = selected,
            highlighted = highlighted,
            metadata = metadata.toMap(),
            relativeHitBox = relativeHitBox
        )
    }

    private fun calculateBounds(): AbsoluteRect {
        if (commands.isEmpty()) {
            return AbsoluteRect(AbsolutePoint.ZERO, Pixels.ZERO, Pixels.ZERO)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (command in commands) {
            val bounds = command.bounds
            minX = minOf(minX, bounds.origin.x.value)
            minY = minOf(minY, bounds.origin.y.value)
            maxX = maxOf(maxX, bounds.origin.x.value + bounds.width.value)
            maxY = maxOf(maxY, bounds.origin.y.value + bounds.height.value)
        }

        return AbsoluteRect(
            origin = AbsolutePoint(Pixels(minX), Pixels(minY)),
            width = Pixels(maxX - minX),
            height = Pixels(maxY - minY)
        )
    }
}

/**
 * Helper function to create a RenderElement builder.
 */
fun renderElement(id: RenderElementId, type: RenderElementType): RenderElementBuilder =
    RenderElementBuilder(id, type)
