package cta.app.services

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.Base64
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import java.io.ByteArrayOutputStream
import java.util.Properties
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class MailService {
    @Value("\${gmail.client-id}")
    lateinit var clientId: String
    @Value("\${gmail.client-secret}")
    lateinit var clientSecret: String
    @Value("\${gmail.refresh-token}")
    lateinit var refreshToken: String
    @Value("\${gmail.address}")
    lateinit var address: String
    @Value("\${gmail.enabled}")
    var emailEnabled: Boolean = false
    @Value("\${gmail.bcc-address}")
    lateinit var bcc_address: String

    val gmail: Gmail by lazy {
        val jsonFactory = GsonFactory.getDefaultInstance()
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val credential = GoogleCredential.Builder()
            .setJsonFactory(jsonFactory)
            .setTransport(transport)
            .setClientSecrets(clientId, clientSecret)
            .build()
            .setRefreshToken(refreshToken)
        logger.info("MailService startup. Enabled: $emailEnabled FromAddress: $address")
        credential.refreshToken()
        Gmail.Builder(transport, jsonFactory, credential).setApplicationName("Community Techaid").build()
    }

    fun sendMessage(message: MimeMessage): Message = sendMessage(createMessageWithEmail(message))    

    fun sendMessage(message: Message): Message = gmail.users().messages().send(address, message).execute()
}

fun createEmail(
    to: String,
    from: String,
    subject: String,
    bodyText: String,
    mimeType: String = "plain",
    charset: String? = null
): MimeMessage {
    val props = Properties()
    val session = Session.getDefaultInstance(props, null)
    val email = MimeMessage(session)
    email.setFrom(InternetAddress(from))
    email.addRecipient(
        jakarta.mail.Message.RecipientType.TO,
        InternetAddress(to)
    )
    email.subject = subject
    email.setText(bodyText, charset, mimeType)
    return email
}

fun createMessageWithEmail(emailContent: MimeMessage): Message {
    val buffer = ByteArrayOutputStream()
    emailContent.writeTo(buffer)
    val bytes = buffer.toByteArray()
    val encodedEmail = Base64.encodeBase64URLSafeString(bytes)
    val message = Message()
    message.raw = encodedEmail
    return message
}
