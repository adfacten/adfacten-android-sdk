package com.adfacten.ads.internal

/** The cross-platform framework (Flutter, React Native, …) the SDK runs under, reported to the ad server. */
internal class WrapperInfo(val name: String, val version: String) {
    /** ` Wrapper/<name>-<version>`, appended to the User-Agent. */
    val userAgentSuffix: String get() = " Wrapper/$name-$version"

    companion object {
        val NAMES = setOf("flutter", "react_native", "unity", "other")
        private val VERSION = Regex("[0-9A-Za-z.+-]{1,20}")

        /** Unknown names become `other`; a version that is empty, too long or has other characters becomes `unknown`. */
        fun of(name: String, version: String): WrapperInfo {
            val n = name.trim().lowercase().takeIf { it in NAMES } ?: "other"
            val v = version.trim().takeIf { VERSION.matches(it) } ?: "unknown"
            return WrapperInfo(n, v)
        }
    }
}
