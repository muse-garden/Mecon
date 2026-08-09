package com.mecon.renderer.geometry

import kotlinx.serialization.Serializable

/**
 * Path segment types.
 */
@Serializable
sealed interface RelativePathSegment {
    @Serializable
    data class MoveTo(val point: RelativePoint) : RelativePathSegment

    @Serializable
    data class LineTo(val point: RelativePoint) : RelativePathSegment

    @Serializable
    data class QuadTo(val control: RelativePoint, val end: RelativePoint) : RelativePathSegment

    @Serializable
    data class CubicTo(val control1: RelativePoint, val control2: RelativePoint, val end: RelativePoint) : RelativePathSegment

    @Serializable
    data object Close : RelativePathSegment
}

/**
 * A general path composed of segments.
 * Used for complex shapes like noteheads, clefs, etc.
 */
@Serializable
data class RelativePath(
    val segments: List<RelativePathSegment>
) {
    /**
     * Convert to absolute coordinates.
     */
    fun toAbsolute(scale: ScaleFactor, origin: AbsolutePoint = AbsolutePoint.ZERO): AbsolutePath =
        AbsolutePath(
            segments = segments.map { segment ->
                when (segment) {
                    is RelativePathSegment.MoveTo -> AbsolutePathSegment.MoveTo(segment.point.toAbsolute(scale, origin))
                    is RelativePathSegment.LineTo -> AbsolutePathSegment.LineTo(segment.point.toAbsolute(scale, origin))
                    is RelativePathSegment.QuadTo -> AbsolutePathSegment.QuadTo(
                        segment.control.toAbsolute(scale, origin),
                        segment.end.toAbsolute(scale, origin)
                    )
                    is RelativePathSegment.CubicTo -> AbsolutePathSegment.CubicTo(
                        segment.control1.toAbsolute(scale, origin),
                        segment.control2.toAbsolute(scale, origin),
                        segment.end.toAbsolute(scale, origin)
                    )
                    is RelativePathSegment.Close -> AbsolutePathSegment.Close
                }
            }
        )

    /**
     * Translate the path by offset.
     */
    fun translate(offset: RelativePoint): RelativePath = RelativePath(
        segments = segments.map { segment ->
            when (segment) {
                is RelativePathSegment.MoveTo -> RelativePathSegment.MoveTo(segment.point + offset)
                is RelativePathSegment.LineTo -> RelativePathSegment.LineTo(segment.point + offset)
                is RelativePathSegment.QuadTo -> RelativePathSegment.QuadTo(
                    segment.control + offset,
                    segment.end + offset
                )
                is RelativePathSegment.CubicTo -> RelativePathSegment.CubicTo(
                    segment.control1 + offset,
                    segment.control2 + offset,
                    segment.end + offset
                )
                is RelativePathSegment.Close -> RelativePathSegment.Close
            }
        }
    )

    companion object {
        /**
         * Create a simple rectangle path.
         */
        fun rectangle(rect: RelativeRect): RelativePath = RelativePath(
            listOf(
                RelativePathSegment.MoveTo(rect.origin),
                RelativePathSegment.LineTo(rect.topRight),
                RelativePathSegment.LineTo(rect.bottomRight),
                RelativePathSegment.LineTo(rect.bottomLeft),
                RelativePathSegment.Close
            )
        )

        /**
         * Create an ellipse path (approximation using bezier curves).
         */
        fun ellipse(center: RelativePoint, radiusX: StaffSpace, radiusY: StaffSpace): RelativePath {
            // Bezier approximation constant for circle/ellipse
            val k = 0.5522847498f

            val kx = radiusX * k
            val ky = radiusY * k

            return RelativePath(listOf(
                RelativePathSegment.MoveTo(center.offset(radiusX, StaffSpace.ZERO)),
                RelativePathSegment.CubicTo(
                    center.offset(radiusX, -ky),
                    center.offset(kx, -radiusY),
                    center.offset(StaffSpace.ZERO, -radiusY)
                ),
                RelativePathSegment.CubicTo(
                    center.offset(-kx, -radiusY),
                    center.offset(-radiusX, -ky),
                    center.offset(-radiusX, StaffSpace.ZERO)
                ),
                RelativePathSegment.CubicTo(
                    center.offset(-radiusX, ky),
                    center.offset(-kx, radiusY),
                    center.offset(StaffSpace.ZERO, radiusY)
                ),
                RelativePathSegment.CubicTo(
                    center.offset(kx, radiusY),
                    center.offset(radiusX, ky),
                    center.offset(radiusX, StaffSpace.ZERO)
                ),
                RelativePathSegment.Close
            ))
        }
    }
}

/**
 * Absolute path segment types.
 */
@Serializable
sealed interface AbsolutePathSegment {
    @Serializable
    data class MoveTo(val point: AbsolutePoint) : AbsolutePathSegment

    @Serializable
    data class LineTo(val point: AbsolutePoint) : AbsolutePathSegment

    @Serializable
    data class QuadTo(val control: AbsolutePoint, val end: AbsolutePoint) : AbsolutePathSegment

    @Serializable
    data class CubicTo(val control1: AbsolutePoint, val control2: AbsolutePoint, val end: AbsolutePoint) : AbsolutePathSegment

    @Serializable
    data object Close : AbsolutePathSegment
}

/**
 * A general path in absolute coordinates.
 */
@Serializable
data class AbsolutePath(
    val segments: List<AbsolutePathSegment>
)
