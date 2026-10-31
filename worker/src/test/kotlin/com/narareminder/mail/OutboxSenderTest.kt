package com.narareminder.mail

import com.narareminder.PostgresTestConfig
import com.narareminder.alert.DiscordNotifier
import com.narareminder.support.KST
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Clock
import java.time.Instant

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig::class)
@ActiveProfiles("test")
class OutboxSenderTest @Autowired constructor(private val outbox: OutboxMailRepository) {

    private val now = Instant.parse("2026-10-31T05:00:00Z")
    private val mailNotifier = mockk<MailNotifier>(relaxed = true)
    private val discordNotifier = mockk<DiscordNotifier>(relaxed = true)
    private val mailQuota = mockk<MailQuota>(relaxed = true)
    private val sender = OutboxSender(outbox, mailNotifier, discordNotifier, mailQuota, Clock.fixed(now, KST))

    @Test
    fun `대기 중인 인증 메일을 보내고 SENT 로 바꾼다`() {
        val mail = outbox.save(OutboxMail("a@example.com", "이메일 확인", "<p>링크</p>", now))

        sender.send()

        verify { mailNotifier.sendHtml("a@example.com", "이메일 확인", "<p>링크</p>") }
        val saved = outbox.findById(requireNotNull(mail.id)).orElseThrow()
        assertThat(saved.status).isEqualTo(OutboxStatus.SENT)
        assertThat(saved.sentAt).isEqualTo(now)
        verify { mailQuota.check(now) }
    }

    @Test
    fun `3번 실패하면 FAILED 로 바꾸고 디스코드로 알린다`() {
        val mail = outbox.save(OutboxMail("a@example.com", "이메일 확인", "<p>링크</p>", now))
        every { mailNotifier.sendHtml(any(), any(), any()) } throws IllegalStateException("SMTP 오류")

        repeat(3) { sender.send() }

        assertThat(outbox.findById(requireNotNull(mail.id)).orElseThrow().status).isEqualTo(OutboxStatus.FAILED)
        verify(exactly = 1) { discordNotifier.send(match { it.contains("a@example.com") }) }
        verify(exactly = 0) { mailQuota.check(any()) }
    }
}
