package com.narareminder.store

import org.springframework.data.jpa.repository.JpaRepository

interface NotifiedNoticeRepository : JpaRepository<NotifiedNotice, NotifiedNoticeId>
