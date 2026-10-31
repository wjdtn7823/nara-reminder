package com.narareminder.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByEmail(email: String): AppUser?
    fun findAllByStatus(status: UserStatus): List<AppUser>

    @Query("select u from AppUser u where u.isAdmin = true")
    fun findAdmins(): List<AppUser>
}
