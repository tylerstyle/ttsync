package de.tergelius.ttsync.mapping

import java.security.MessageDigest

/**
 * Content hash of an [EventSpec] (DESIGN.md section 4, "Hash"). The engine writes a row only when
 * this hash differs from `mapping.spec_hash`. Bumping [EventMapper.VERSION] changes every hash, so
 * every row is rewritten once.
 *
 * SHA-256 over the mapper version, the uuid and the sorted [EventSpec.columns]. Each value is
 * length-prefixed and null is distinct from the empty string, so different specs cannot collide by
 * concatenation. Result: 64 lowercase hex characters. Never the empty string, which marks an adopted
 * row whose content is unknown (`Mapping.ADOPTED_HASH`).
 */
object SpecHash {
  fun of(spec: EventSpec, mapperVersion: Int = EventMapper.VERSION): String {
    val canonical = buildString {
      append("ttsync-spec\n")
      append("mapper_version=").append(mapperVersion).append('\n')
      append("uuid=").append(spec.uuid.length).append(':').append(spec.uuid).append('\n')
      for ((name, value) in spec.columns().toSortedMap()) {
        append(name).append('=')
        if (value == null) append('-') else append(value.length).append(':').append(value)
        append('\n')
      }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }
}
