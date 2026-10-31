package com.narareminder.watch

import org.springframework.data.jpa.repository.JpaRepository

interface UserFilterRepository : JpaRepository<UserFilter, Long>
