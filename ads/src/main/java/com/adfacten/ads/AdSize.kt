package com.adfacten.ads

/** A banner size in dp. Only the standard sizes below are served. */
public class AdSize private constructor(public val width: Int, public val height: Int) {
    internal val wire: String get() = "${width}x$height"
    override fun toString(): String = wire
    override fun equals(other: Any?): Boolean = other is AdSize && other.width == width && other.height == height
    override fun hashCode(): Int = width * 31 + height

    public companion object {
        @JvmField public val BANNER: AdSize = AdSize(320, 50)
        @JvmField public val LARGE_BANNER: AdSize = AdSize(320, 100)
        @JvmField public val MEDIUM_RECTANGLE: AdSize = AdSize(300, 250)
        /** Tablets only. */
        @JvmField public val LEADERBOARD: AdSize = AdSize(728, 90)

        internal fun fromAttr(index: Int): AdSize = when (index) {
            1 -> LARGE_BANNER
            2 -> MEDIUM_RECTANGLE
            3 -> LEADERBOARD
            else -> BANNER
        }
    }
}
