package com.narareminder.job

import com.narareminder.notice.NoticeSource

enum class JobName(val label: String, val source: NoticeSource?) {
    BID_POLLING("입찰공고 수집", NoticeSource.BID),
    PRE_SPEC_DAILY("사전규격 수집", NoticeSource.PRE_SPEC),
    DELIVERY("메일 발송", null),
}
