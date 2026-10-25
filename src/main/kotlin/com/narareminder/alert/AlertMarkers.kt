package com.narareminder.alert

import org.slf4j.Marker
import org.slf4j.MarkerFactory

object AlertMarkers {
    // 배치 장애 알림(연속 실패 임계치)이 따로 알리는 로그. ERROR 로그 디스코드 전송에서 제외한다
    val HANDLED_BY_JOB_ALERT: Marker = MarkerFactory.getMarker("HANDLED_BY_JOB_ALERT")
}
