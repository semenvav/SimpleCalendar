package dev.simplecalendar

import dev.simplecalendar.api.installRoutes
import dev.simplecalendar.config.AppConfig
import dev.simplecalendar.net.OutgoingProxy
import dev.simplecalendar.plugins.installPlugins
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("dev.simplecalendar.Main")
    val config = AppConfig.fromEnv()

    // Before anything sends a request: every HTTP client in the application consults it.
    OutgoingProxy.install(config.proxy)

    log.info("Starting SimpleCalendar on {}:{}", config.host, config.port)
    log.info("Data directory: {}", config.dataDir)
    log.info("Time zone: {}", config.timeZone)
    log.info("Outgoing proxy: {}", config.proxy)
    if (config.caldav == null) {
        log.warn("SC_CALDAV_URL is not set — the calendar will be empty until it is configured")
    } else {
        log.info(
            "CalDAV source: {} (user {}, {})",
            config.caldav.baseUrl, config.caldav.username, OutgoingProxy.describeRoute(config.caldav.baseUrl),
        )
    }
    log.info("Frontend: {}", config.staticDir ?: "not bundled (expecting the Vite dev server)")

    val components = AppComponents(config)
    components.start()

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(components)
    }.start(wait = true)
}

fun Application.module(components: AppComponents) {
    installPlugins(components.config)
    installRoutes(components)

    // Stops the sync loop and closes the database on SIGTERM, which is how Docker stops us.
    monitor.subscribe(ApplicationStopped) { components.close() }
}
