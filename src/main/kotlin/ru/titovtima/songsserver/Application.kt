package ru.titovtima.songsserver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import ru.titovtima.songsserver.model.MutexByString
import ru.titovtima.songsserver.plugins.configureRouting
import ru.titovtima.songsserver.plugins.configureSecurity
import ru.titovtima.songsserver.plugins.configureSerialization
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.sql.Connection
import java.sql.DriverManager
import kotlin.concurrent.thread

fun main() {
    cleaningCacheThread()
    val serverHost = System.getenv("SERVER_HOST") ?: "127.0.0.1"
    embeddedServer(Netty, port = 2403, host = serverHost, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureSecurity()
    configureSerialization()
    configureRouting()
}

val dbConnection: Connection = run {
    val dbHost = System.getenv("POSTGRES_HOST") ?: "localhost"
    val dbPort = System.getenv("POSTGRES_PORT") ?: "5432"
    val dbName = System.getenv("POSTGRES_DB") ?: "songsserver_db"
    val dbUser = System.getenv("POSTGRES_USER") ?: "songsserver_user"
    val dbSchema = System.getenv("POSTGRES_SCHEMA")
    val jdbcUrl = if (dbSchema != null) "jdbc:postgresql://$dbHost:$dbPort/$dbName?currentSchema=$dbSchema"
        else "jdbc:postgresql://$dbHost:$dbPort/$dbName"
    DriverManager.getConnection(jdbcUrl, dbUser, System.getenv("POSTGRES_PASSWORD"))
}
val dbLock = Mutex()

fun cleaningCacheThread() {
    thread {
        runBlocking {
            while (true) {
                cleanOldCache()
                delay(1000 * 60 * 60)
            }
        }
    }
}

suspend fun cleanOldCache() {
    println("cleaning cache")
    val now = System.currentTimeMillis()
    val cacheDir = File(System.getenv("CACHE_PATH"))
    cacheDir.walk().forEach { file ->
        if (file.isFile) {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            if (now - attrs.lastAccessTime().toMillis() > 1000 * 60 * 60) {
                MutexByString.withLock(file.name) { file.delete() }
            }
        }
    }
}

val HOST = System.getenv("HOST") ?: throw error("Env HOST not defined")
val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
}
