package com.narareminder.auth

import com.narareminder.user.AppUser
import com.narareminder.user.AppUserRepository
import com.narareminder.watch.UserFilter
import com.narareminder.watch.UserFilterRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class AccountService(
    private val userRepository: AppUserRepository,
    private val userFilterRepository: UserFilterRepository,
    private val clock: Clock,
) {
    // 새 계정은 공통 조건 기본값(제외 없음·전체 업무구분·가격 제한 없음)과 함께 만든다
    @Transactional
    fun create(email: String, passwordHash: String?, admin: Boolean): AppUser {
        val user = userRepository.save(AppUser(email, passwordHash, admin, clock.instant()))
        userFilterRepository.save(UserFilter(requireNotNull(user.id)))
        return user
    }
}
