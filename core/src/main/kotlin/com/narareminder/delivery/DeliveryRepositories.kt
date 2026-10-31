package com.narareminder.delivery

import org.springframework.data.jpa.repository.JpaRepository

interface DeliveryRepository : JpaRepository<Delivery, Long> {
    fun findAllByStatus(status: DeliveryStatus): List<Delivery>
}

interface DeliveryItemRepository : JpaRepository<DeliveryItem, DeliveryItemId> {
    fun findAllByIdDeliveryIdIn(deliveryIds: Collection<Long>): List<DeliveryItem>
}
