package com.narareminder.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByEmail(email: String): AppUser?
    fun findAllByStatus(status: UserStatus): List<AppUser>

    @Query("select u from AppUser u where u.isAdmin = true")
    fun findAdmins(): List<AppUser>

    // 병렬 로그인 시도가 실패 횟수를 덮어쓰지 못하게 행을 잠그고 읽는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.email = :email")
    fun findByEmailForUpdate(@Param("email") email: String): AppUser?
}
