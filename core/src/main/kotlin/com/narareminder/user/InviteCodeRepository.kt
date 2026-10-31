package com.narareminder.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface InviteCodeRepository : JpaRepository<InviteCode, Long> {
    fun findByCode(code: String): InviteCode?
    fun findAllByOrderByIdDesc(): List<InviteCode>

    // 동시에 확인 링크를 눌러도 사용 한도를 넘지 않도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InviteCode i where i.id = :id")
    fun findForUpdate(id: Long): InviteCode?
}
