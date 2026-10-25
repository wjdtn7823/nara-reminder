package com.narareminder.job

import com.narareminder.notice.NoticeSource

enum class JobName(val source: NoticeSource) {
    BID_POLLING(NoticeSource.BID),
    PRE_SPEC_DAILY(NoticeSource.PRE_SPEC),
}
