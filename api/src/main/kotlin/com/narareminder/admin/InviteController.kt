package com.narareminder.admin

import com.narareminder.security.AppPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/invites")
class InviteController(private val inviteService: InviteService) {

    @GetMapping
    fun list(): List<InviteResponse> = inviteService.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: CreateInviteRequest): InviteResponse =
        inviteService.create(principal.userId, body)

    @PostMapping("/{id}/revoke")
    fun revoke(@PathVariable id: Long): InviteResponse = inviteService.revoke(id)
}
