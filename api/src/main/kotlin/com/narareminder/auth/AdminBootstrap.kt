package com.narareminder.auth

import com.narareminder.config.AppProperties
import com.narareminder.user.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

// 첫 관리자는 ADMIN_EMAIL 로 비밀번호 없이 만들고, 비밀번호 재설정으로 첫 비밀번호를 정한다
@Component
class AdminBootstrap(
    private val props: AppProperties,
    private val userRepository: AppUserRepository,
    private val accountService: AccountService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val email = props.adminEmail.trim().lowercase()
        if (email.isEmpty()) {
            log.warn("ADMIN_EMAIL 이 비어 있어 관리자 계정을 확인하지 않습니다")
            return
        }
        val user = userRepository.findByEmail(email) ?: accountService.create(email, null, admin = true)
        user.isAdmin = true
        userRepository.save(user)
    }
}
