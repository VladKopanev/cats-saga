package com.vladkopanev.cats.saga.example.model

class OrderSagaError(message: String) extends RuntimeException(s"Saga failed with message: $message")
