package com.mecon.renderer.geometry

import com.mecon.renderer.smufl.EngravingDefaults
import kotlinx.serialization.Serializable

/**
 * A line segment in relative coordinates.
 */
@Serializable
data class RelativeLine(
    val start: RelativePoint,
    val end: RelativePoint,
    val thickness: StaffSpace = EngravingDefaults.BRAVURA.staffLineThickness
) {
    /**
     * Length of the line.
     */
    val length: StaffSpace get() = start.distanceTo(end)

    /**
     * Midpoint of the line.
     */
    val midpoint: RelativePoint
        get() = RelativePoint(
            x = StaffSpace((start.x.value + end.x.value) / 2),
            y = StaffSpace((start.y.value + end.y.value) / 2)
        )

    /**
     * Convert to absolute coordinates.
     */
    fun toAbsolute(scale: ScaleFactor, origin: AbsolutePoint = AbsolutePoint.ZERO): AbsoluteLine =
        AbsoluteLine(
            start = start.toAbsolute(scale, origin),
            end = end.toAbsolute(scale, origin),
            thickness = scale.toPixels(thickness)
        )

    companion object {
        /**
         * Create a horizontal line.
         */
        fun horizontal(y: StaffSpace, startX: StaffSpace, endX: StaffSpace, thickness: StaffSpace = EngravingDefaults.BRAVURA.staffLineThickness) =
            RelativeLine(
                start = RelativePoint(startX, y),
                end = RelativePoint(endX, y),
                thickness = thickness
            )

        /**
         * Create a vertical line.
         */
        fun vertical(x: StaffSpace, startY: StaffSpace, endY: StaffSpace, thickness: StaffSpace = EngravingDefaults.BRAVURA.stemThickness) =
            RelativeLine(
                start = RelativePoint(x, startY),
                end = RelativePoint(x, endY),
                thickness = thickness
            )
    }
}

/**
 * A line segment in absolute coordinates.
 */
@Serializable
data class AbsoluteLine(
    val start: AbsolutePoint,
    val end: AbsolutePoint,
    val thickness: Pixels
)

/**
 * An axis-aligned rectangle in relative coordinates.
 */
@Serializable
data class RelativeRect(
    val origin: RelativePoint,  // Top-left corner
    val width: StaffSpace,
    val height: StaffSpace
) {
    /** Center point */
    val center: RelativePoint
        get() = RelativePoint(
            origin.x + width / 2f,
            origin.y + height / 2f
        )

    /** Bottom-right corner */
    val bottomRight: RelativePoint
        get() = RelativePoint(origin.x + width, origin.y + height)

    /** Top-right corner */
    val topRight: RelativePoint
        get() = RelativePoint(origin.x + width, origin.y)

    /** Bottom-left corner */
    val bottomLeft: RelativePoint
        get() = RelativePoint(origin.x, origin.y + height)

    /** Left edge X coordinate */
    val left: StaffSpace get() = origin.x

    /** Right edge X coordinate */
    val right: StaffSpace get() = origin.x + width

    /** Top edge Y coordinate */
    val top: StaffSpace get() = origin.y

    /** Bottom edge Y coordinate */
    val bottom: StaffSpace get() = origin.y + height

    /**
     * Check if a point is inside this rectangle.
     */
    operator fun contains(point: RelativePoint): Boolean =
        point.x >= origin.x && point.x <= origin.x + width &&
        point.y >= origin.y && point.y <= origin.y + height

    /**
     * Check if this rectangle overlaps another.
     */
    fun overlaps(other: RelativeRect): Boolean =
        left < other.right && right > other.left &&
        top < other.bottom && bottom > other.top

    /**
     * Expand the rectangle by the given amount on all sides.
     */
    fun expand(amount: StaffSpace): RelativeRect = RelativeRect(
        origin = RelativePoint(origin.x - amount, origin.y - amount),
        width = width + amount * 2,
        height = height + amount * 2
    )

    /**
     * Convert to absolute coordinates.
     */
    fun toAbsolute(scale: ScaleFactor, layoutOrigin: AbsolutePoint = AbsolutePoint.ZERO): AbsoluteRect =
        AbsoluteRect(
            origin = origin.toAbsolute(scale, layoutOrigin),
            width = scale.toPixels(width),
            height = scale.toPixels(height)
        )

    companion object {
        /**
         * Create a rectangle from center point and dimensions.
         */
        fun fromCenter(center: RelativePoint, width: StaffSpace, height: StaffSpace): RelativeRect =
            RelativeRect(
                origin = RelativePoint(center.x - width / 2f, center.y - height / 2f),
                width = width,
                height = height
            )

        /**
         * Create a rectangle containing multiple points.
         */
        fun boundingBox(points: List<RelativePoint>): RelativeRect? {
            if (points.isEmpty()) return null
            var minX = points[0].x
            var maxX = points[0].x
            var minY = points[0].y
            var maxY = points[0].y
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            return RelativeRect(
                origin = RelativePoint(minX, minY),
                width = maxX - minX,
                height = maxY - minY
            )
        }
    }
}

/**
 * An axis-aligned rectangle in absolute coordinates.
 */
@Serializable
data class AbsoluteRect(
    val origin: AbsolutePoint,
    val width: Pixels,
    val height: Pixels
) {
    val center: AbsolutePoint
        get() = AbsolutePoint(
            origin.x + width / 2f,
            origin.y + height / 2f
        )

    val bottomRight: AbsolutePoint
        get() = AbsolutePoint(origin.x + width, origin.y + height)

    operator fun contains(point: AbsolutePoint): Boolean =
        point.x.value >= origin.x.value && point.x.value <= origin.x.value + width.value &&
        point.y.value >= origin.y.value && point.y.value <= origin.y.value + height.value
}
