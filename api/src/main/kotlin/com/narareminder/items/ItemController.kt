package com.narareminder.items

import com.narareminder.security.AppPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/items")
class ItemController(private val itemService: ItemService) {

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AppPrincipal): List<ItemResponse> = itemService.list(principal.userId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal principal: AppPrincipal, @RequestBody body: CreateItemRequest): ItemResponse =
        itemService.create(principal.userId, body)

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long): ItemDetailResponse =
        itemService.detail(principal.userId, id)

    @PatchMapping("/{id}")
    fun update(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long, @RequestBody body: UpdateItemRequest): ItemResponse =
        itemService.update(principal.userId, id, body)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: AppPrincipal, @PathVariable id: Long) {
        itemService.delete(principal.userId, id)
    }
}
