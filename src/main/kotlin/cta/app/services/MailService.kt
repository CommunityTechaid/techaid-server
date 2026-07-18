package cta.app.services

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.UserCredentials
import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties

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
    lateinit var bccAddress: String

    val gmail: Gmail by lazy {
        val jsonFactory = GsonFactory.getDefaultInstance()
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val credentials =
            UserCredentials
                .newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build()
        logger.info("MailService startup. Enabled: $emailEnabled FromAddress: $address")
        Gmail.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials)).setApplicationName("Community Techaid").build()
    }

    fun sendMessage(message: MimeMessage): Message = sendMessage(createMessageWithEmail(message))

    fun sendMessage(message: Message): Message =
        gmail
            .users()
            .messages()
            .send(address, message)
            .execute()
}

/** An optional file to attach to an email built with [createEmail]. */
data class EmailAttachment(
    val filename: String,
    val content: String,
    val contentType: String,
)

fun createEmail(
    to: String,
    from: String,
    subject: String,
    bodyText: String,
    mimeType: String = "plain",
    charset: String? = null,
    attachment: EmailAttachment? = null,
): MimeMessage {
    val props = Properties()
    val session = Session.getDefaultInstance(props, null)
    val email = MimeMessage(session)
    email.setFrom(InternetAddress(from))
    email.addRecipient(
        jakarta.mail.Message.RecipientType.TO,
        InternetAddress(to),
    )
    email.subject = subject

    if (attachment == null) {
        email.setText(bodyText, charset, mimeType)
    } else {
        val bodyPart = MimeBodyPart()
        bodyPart.setText(bodyText, charset, mimeType)

        val attachmentPart = MimeBodyPart()
        attachmentPart.dataHandler = DataHandler(ByteArrayDataSource(attachment.content, attachment.contentType))
        attachmentPart.fileName = attachment.filename
        attachmentPart.disposition = jakarta.mail.Part.ATTACHMENT

        val multipart = MimeMultipart()
        multipart.addBodyPart(bodyPart)
        multipart.addBodyPart(attachmentPart)
        email.setContent(multipart)
    }

    return email
}

fun createMessageWithEmail(emailContent: MimeMessage): Message {
    val buffer = ByteArrayOutputStream()
    emailContent.writeTo(buffer)
    val bytes = buffer.toByteArray()
    val encodedEmail = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val message = Message()
    message.raw = encodedEmail
    return message
}
