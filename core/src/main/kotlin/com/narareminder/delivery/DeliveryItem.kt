package com.narareminder.delivery

import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Embeddable
data class DeliveryItemId(
    val deliveryId: Long,
    val watchItemId: Long,
) : Serializable

// 발송 대기 공고가 어떤 항목에 걸렸는지. 발송 시점 계산과 상세 화면에 쓴다
@Entity
@Table(name = "delivery_item")
class DeliveryItem(
    @EmbeddedId
    val id: DeliveryItemId,
)
