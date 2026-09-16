package dev.simplecalendar.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Where settings come from: real environment variables first, then a `.env` file in the working
 * directory, so `./gradlew run` reads the same values docker compose passes in.
 *
 * A blank value counts as absent. That is what lets compose list a variable as `${NAME:-}` without
 * an unset one turning into a broken empty setting.
 */
class Env(private val lookup: (String) -> String?) {

    fun string(name: String): String? = lookup(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** For a setting that only makes sense alongside another: [name] is needed [because]. */
    fun required(name: String, because: String): String =
        string(name) ?: error("$because, but $name is missing")

    fun double(name: String): Double? = string(name)?.let { raw ->
        raw.toDoubleOrNull() ?: error("$name must be a number, got '$raw'")
    }

    /** A comma-separated list; spaces around items are ignored, empty items dropped. */
    fun list(name: String): List<String> =
        string(name)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    companion object {
        fun system(dotEnvPath: Path = Path(".env")): Env {
            val dotEnv = loadDotEnv(dotEnvPath)
            return Env { name -> System.getenv(name) ?: dotEnv[name] }
        }

        fun of(values: Map<String, String>): Env = Env(values::get)

        /** Minimal `KEY=value` reader: comments, blank lines and surrounding quotes, nothing more. */
        private fun loadDotEnv(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return Files.readAllLines(path, Charsets.UTF_8)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = line.take(separator).trim()
                    val value = line.substring(separator + 1).trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    key to value
                }
                .toMap()
        }
    }
}
