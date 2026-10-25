package com.narareminder.filter

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/filters")
class FilterController(private val filterService: FilterService) {

    @GetMapping
    fun get(): FilterResponse = filterService.view().toResponse()

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@RequestBody request: AddTermRequest): TermResponse =
        filterService.addTerm(request.type, request.value).toResponse()

    @PutMapping("/terms/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: UpdateTermRequest): TermResponse =
        filterService.updateTerm(id, request.value).toResponse()

    @DeleteMapping("/terms/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = filterService.deleteTerm(id)

    @PutMapping("/settings")
    fun updateSettings(@RequestBody request: UpdateSettingRequest): FilterResponse {
        filterService.updateSetting(request.categories, request.priceMin, request.priceMax)
        return filterService.view().toResponse()
    }
}
