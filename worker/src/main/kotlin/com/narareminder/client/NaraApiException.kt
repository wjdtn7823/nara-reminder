package com.narareminder.client

class NaraApiException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
