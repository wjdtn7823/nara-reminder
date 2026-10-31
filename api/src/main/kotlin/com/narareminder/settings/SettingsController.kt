package com.narareminder.settings

import com.narareminder.security.AppPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/settings")
class SettingsController(private val settingsService: SettingsService) {

    @GetMapping
    fun get(@AuthenticationPrincipal principal: AppPrincipal): SettingsResponse = settingsService.get(principal.userId)

    @PutMapping
    fun update(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: SettingsRequest): SettingsResponse =
        settingsService.update(principal.userId, body)
}
