package ru.titovtima.songsserver.model

import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import ru.titovtima.songsserver.HOST
import ru.titovtima.songsserver.httpClient
import ru.titovtima.songsserver.plugins.ActionToken

@Serializable
class Email(val recipients: List<String>, val subject: String, val contentType: String, val data: String,
            val sender: String = EmailsSender) {
    companion object {
        val SendEmailRequestAddress = System.getenv("EMAIL_SERVICE_URL") ?: throw error("Env EMAIL_SERVICE_URL not defined")
        val EmailsSender = System.getenv("EMAIL_SENDER") ?: throw error("Env EMAIL_SENDER not defined")
        val EmailServiceToken = System.getenv("EMAIL_SERVICE_TOKEN") ?: throw error("Env EMAIL_SERVICE_TOKEN not defined")

        fun passwordRecoveryEmail(user: User): Email? {
            if (user.email == null) return null
            val token = ActionToken.createToken(user.id, 1) ?: return null
            return Email(listOf(user.email), "Восстановление пароля", "text/html",
                "Здравствуйте, ${user.username}. <br/>" +
                "Для изменения пароля на сайте <a href=\"$HOST\">$HOST</a> перейдите по " +
                "<a href=\"https://$HOST/reset_password/${user.id}/$token\">ссылке</a>")
        }
    }

    suspend fun send() {
        httpClient.post(SendEmailRequestAddress) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Token $EmailServiceToken")
            setBody(this@Email)
        }
    }
}
