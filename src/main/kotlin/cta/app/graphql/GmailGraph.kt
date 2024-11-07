package cta.app.graphql

import com.google.api.services.gmail.model.Draft
import com.google.api.services.gmail.model.Label
import com.google.api.services.gmail.model.ListThreadsResponse
import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartBody
import com.google.api.services.gmail.model.MessagePartHeader
import com.google.api.services.gmail.model.Thread
import cta.app.services.EmailFilter
import cta.app.services.EmailPage
import cta.app.services.MailService
import cta.app.services.createEmail
import jakarta.mail.internet.MimeMessage
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated

@PreAuthorize("hasAnyAuthority('read:emails')")
@Controller
class GmailGraph(
    private val mailService: MailService
) {

    @QueryMapping
    fun emails(@Argument filter: EmailFilter, @Argument id: String?): EmailPage {
        return mailService.emails(filter, id ?: mailService.address)
    }

    @QueryMapping
    fun email(@Argument id: String): Message {
        return mailService.gmail.users().messages().get(mailService.address, id).execute()
    }

    @QueryMapping
    fun thread(@Argument id: String): Thread {
        return mailService.gmail.users().threads().get(mailService.address, id).execute()
    }

    @QueryMapping
    fun emailThreads(@Argument filter: EmailFilter): ListThreadsResponse {
        return mailService.threads(filter)
    }

    @QueryMapping
    fun emailLabels(@Argument ids: List<String>?): List<Label> {
        return if (ids.isNullOrEmpty()) {
            mailService.gmail.users().labels().list(mailService.address).execute().labels.map {
                mailService.gmail.users().labels().get(mailService.address, it.id).execute()
            }
        } else {
            ids.map {
                mailService.gmail.users().labels().get(mailService.address, it).execute()
            }
        }
    }
}

@Controller
@Validated
@PreAuthorize("hasAnyAuthority('write:emails')")
class GmailMutations(
    private val mailService: MailService
) {
    @MutationMapping
    fun sendEmail(@Valid data: EmailInput): Message {
        return mailService.sendMessage(data.toMessage(mailService.address))
    }

    @MutationMapping
    fun replyEmail(id: String, @Valid data: EmailInput): Message {
        val message = mailService.replyTo(id, data.toMessage(mailService.address))
        return mailService.sendMessage(message)
    }

    @MutationMapping
    fun draftEmail(@Valid data: EmailInput): Draft {
        return mailService.createDraft(data.toMessage(mailService.address))
    }

    @MutationMapping
    fun replyDraft(id: String, @Valid data: EmailInput): Draft {
        val message = mailService.replyTo(id, data.toMessage(mailService.address))
        return mailService.createDraft(message)
    }

    @MutationMapping
    fun updateDraft(id: String, @Valid data: EmailInput): Draft {
        return mailService.updateDraft(id, data.toMessage(mailService.address))
    }

    @MutationMapping
    fun deleteDraft(id: String): Boolean {
        mailService.deleteDraft(id)
        return true
    }

    @MutationMapping
    fun sendDraft(id: String, @Valid data: EmailInput? = null): Message {
        return mailService.sendDraft(id, data?.toMessage(mailService.address))
    }
}

@Controller
class MessagePartBodyResolver(
    private val mailService: MailService
) {
    @QueryMapping
    fun decodedData(@Argument part: MessagePartBody): String? {
        return part.decodeData()?.let { it.toString(charset("UTF-8")) }
    }
}

@Controller
class MessagePartResolver(
    private val mailService: MailService
) {
    @QueryMapping
    fun headers(@Argument part: MessagePart, @Argument keys: List<String>?): List<MessagePartHeader> {
        keys ?: return part.headers
        return (part.headers ?: listOf<MessagePartHeader>()).filter { keys.contains(it.name) }
    }

    @QueryMapping
    fun content(@Argument part: MessagePart, @Argument mimeType: String): MessagePart? {
        (part.parts ?: listOf<MessagePart>()).forEach {
            mimeType(mimeType, it)?.let { p ->
                return p
            }
        }
        return null
    }

    private fun mimeType(mimeType: String, part: MessagePart?): MessagePart? {
        part ?: return null
        if (part.mimeType == mimeType && part.body?.data != null) {
            return part
        }
        if (part.parts != null) {
            part.parts.forEach {
                mimeType(mimeType, it)?.let { p ->
                    return p
                }
            }
        }
        return null
    }
}

@Controller
class ThreadResolver(
    private val mailService: MailService
)  {
    @QueryMapping
    fun messages(@Argument thread: Thread): List<Message> {
        return if (thread.messages.isNullOrEmpty()) {
            mailService.gmail.users().Threads().get(mailService.address, thread.id).execute().messages
        } else {
            thread.messages
        }
    }
}

data class EmailInput(
    val subject: String,
    @get:NotBlank
    val to: String,
    val body: String,
    val mimeType: String = "plain"
) {
    fun toMessage(from: String): MimeMessage {
        return createEmail(
            to = to,
            from = from,
            subject = subject,
            bodyText = body,
            mimeType = mimeType,
            charset = "UTF-8"
        )
    }
}
