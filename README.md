# CATS-SAGA
Purely Functional Transaction Management In Scala With Cats

# Disclaimer

This library was inspired by [goedverhaal](https://github.com/vectos/goedverhaal), but it's implementation
and semantics differs, it tries to be semantically consistent with [zio-saga](https://github.com/VladKopanev/zio-saga) 
and provide with flexible and powerful functions for building Sagas of different complexities.

# Motivation

This library addresses issues related to how it's hard to implement Saga pattern by yourself 

# For whom this library?

You want to apply Saga pattern to implement a transaction-like method and to do it in purely functional manner.
Moreover if you are using tagless final encoding this library is a perfect fit. 
If you are using [ZIO](https://github.com/zio/zio), then consider looking at [zio-saga](https://github.com/VladKopanev/zio-saga).
Although you can use this library with `ZIO` as well, [zio-saga](https://github.com/VladKopanev/zio-saga) is more
native to `ZIO`. You can think of `cats-saga` as a more generic version of `zio-saga` and of `zio-saga` as a specialized 
version of `cats-saga`.

# Example of usage:

Consider the following case, we have built our food delivery system in microservices fashion, so
we have `Order` service, `Payment` service, `LoyaltyProgram` service, etc. 
And now we need to implement a closing order method, that collects *payment*, assigns *loyalty* points 
and closes the *order*. This method should run transactionally so if e.g. *closing order* fails we will 
rollback the state for user and *refund payments*, *cancel loyalty points*.

Applying Saga pattern we need a compensating action for each call to particular microservice, those 
actions needs to be run for each completed request in case some of the requests fails.

![Order Saga Flow](./images/diagrams/Order%20Saga%20Flow.jpeg)

Let's think for a moment about how we could implement this pattern without any specific libraries.

The naive implementation could look like this:

```
def orderSaga(): IO[Unit] = {
  for {
    _ <- collectPayments(2d, 2) handleErrorWith (_ => refundPayments(2d, 2))
    _ <- assignLoyaltyPoints(1d, 1) handleErrorWith (_ => cancelLoyaltyPoints(1d, 1))
    _ <- closeOrder(1) handleErrorWith (_ => reopenOrder(1))
  } yield ()  
}
```

Looks pretty simple and straightforward, `handleErrorWith` function tries to recover the original request if it fails.
We have covered every request with a compensating action. But what if last request fails? We know for sure that corresponding 
compensation `reopenOrder` will be executed, but when other compensations would be run? Right, they would not be triggered, 
because the error would not be propagated higher, thus not triggering compensating actions. That is not what we want, we want 
full rollback logic to be triggered in Saga, whatever error occurred.
 
Second try, this time let's somehow trigger all compensating actions.
  
```
def orderSaga: IO[Unit] = {
  collectPayments(2d, 2).flatMap { _ =>
    assignLoyaltyPoints(1d, 1).flatMap { _ =>
      closeOrder(1) handleErrorWith(e => reopenOrder(1) *> IO.raiseError(e))
    } handleErrorWith (e => cancelLoyaltyPoints(1d, 1)  *> IO.raiseError(e))
  } handleErrorWith(e => refundPayments(2d, 2) *> IO.raiseError(e))  
}
```

This works, we trigger all rollback actions by failing after each. 
But the implementation itself looks awful, we lost expressiveness in the call-back hell, imagine 15 saga steps implemented in such manner.

You can solve this problems in different ways, but you will encounter a number of difficulties, and your code still would 
look pretty much the same as we did in our last try. 

Achieve a generic solution is not that simple, so you will end up
repeating the same boilerplate code from service to service.

`cats-saga` tries to address this concerns and provide you with simple syntax to compose your Sagas.

With `cats-saga` we could do it like so:

```
def orderSaga(): IO[Unit] = {
  import com.vladkopanev.cats.saga.Saga._
    
  (for {
    _ <- collectPayments(2d, 2) compensate refundPayments(2d, 2)
    _ <- assignLoyaltyPoints(1d, 1) compensate cancelLoyaltyPoints(1d, 1)
    _ <- closeOrder(1) compensate reopenOrder(1)
  } yield ()).transact
}
```

`compensate` pairs request IO with compensating action IO and returns a new `Saga` object which then you can compose
 with other `Sagas`.
To materialize `Saga` object to `IO` when it's complete it is required to use `transact` method.

Because `Saga` is effect polymorphic you could use whatever effect type you want in tagless final style:

```
def orderSaga[F[_]: Concurrent](): F[Unit] = {
  import com.vladkopanev.cats.saga.Saga._
    
  (for {
    _ <- collectPayments(2d, 2) compensate refundPayments(2d, 2)
    _ <- assignLoyaltyPoints(1d, 1) compensate cancelLoyaltyPoints(1d, 1)
    _ <- closeOrder(1) compensate reopenOrder(1)
  } yield ()).transact
}
```

As you can see with `cats-saga` the process of building your Sagas is greatly simplified comparably to ad-hoc solutions. 
`cats-sagas` are composable, boilerplate-free and intuitively understandable for people that aware of Saga pattern.
This library lets you compose transaction steps both in sequence and in parallel, 
this feature gives you more powerful control over transaction execution.

# Additional capabilities

### Parallel execution
Saga pattern does not limit transactional requests to run only in sequence.
Because of that `cats-sagas` contains methods for parallel execution of requests. 

```
    val flight          = bookFlight compensate cancelFlight
    val hotel           = bookHotel compensate cancelHotel
    val bookingSaga     = flight zipPar hotel
```