package com.narareminder.auth

import com.narareminder.config.AppProperties
import com.narareminder.mail.OutboxMail
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AuthMails(private val props: AppProperties) {

    fun verify(to: String, token: String, now: Instant) = OutboxMail(
        to, "[나라장터 알리미] 이메일 확인",
        html("가입을 마치려면 아래 링크를 눌러 이메일을 확인하세요. 링크는 24시간 동안 유효합니다.", link("/verify", token), "이메일 확인"),
        now,
    )

    fun reset(to: String, token: String, now: Instant) = OutboxMail(
        to, "[나라장터 알리미] 비밀번호 재설정",
        html("아래 링크에서 새 비밀번호를 정하세요. 링크는 30분 동안 유효하고 한 번만 쓸 수 있습니다.", link("/reset", token), "비밀번호 재설정"),
        now,
    )

    private fun link(path: String, token: String) = "${props.baseUrl.trimEnd('/')}$path?token=$token"

    private fun html(message: String, url: String, label: String) =
        """<p>$message</p><p><a href="$url">$label</a></p><p>요청하지 않으셨다면 이 메일을 무시하세요.</p>"""
}
