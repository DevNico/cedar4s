---
sidebar_label: Effect Types
title: Effect Type Integration
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Effect Type Integration

cedar4s is effect-polymorphic - all APIs are generic over an effect type `F[_]`. This
allows you to use `Future`, cats-effect `IO`, ZIO, or any custom effect type.

## Overview

All core types in cedar4s are generic over an effect type:

```scala
trait CedarSession[F[_]]
trait EntityStore[F[_]]
trait CedarEngine[F[_]]
trait EntityFetcher[F[_], E, Id]
```

cedar4s provides three type classes for effect capabilities:

- **`Sync[F]`** - For synchronous/blocking operations (wraps cedar-java calls)
- **`Concurrent[F]`** - For parallel operations (batch entity loading)
- **`FlatMap[F]`** - For deferred authorization checks (`.on(id)` syntax)

## Using with Future

The simplest option. cedar4s includes built-in instances for `scala.concurrent.Future`.

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global

// Import capability instances for Future
import cedar4s.capability.instances.{futureSync, futureMonadError}
import cedar4s.auth.FlatMap

// Provide FlatMap for deferred checks
given FlatMap[Future] = FlatMap.futureInstance

// Import generated types
import example.myapp.cedar.MyApp
import example.myapp.cedar.MyApp.*

// Create CedarEngine
val engine: CedarEngine[Future] = CedarEngine.fromResources[Future](
  policiesPath = "policies",
  policyFiles = Seq("myapp.cedar")
)

// Create EntityStore
val store: EntityStore[Future] = EntityStore.builder[Future]()
  .register[Entities.User, String](new UserFetcher())
  .register[Entities.Document, String](new DocumentFetcher())
  .build()

// Runtime + session
val runtime = CedarRuntime[Future](engine, store, CedarRuntime.resolverFrom(buildPrincipal))
given CedarSession[Future] = runtime.session(currentUser)

val result: Future[Unit] = MyApp.Document.Read("folder-1", "doc-1").require
val deferred: Future[Unit] = MyApp.Document.Read.on("doc-1").require
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global

// Import capability instances for Future
import cedar4s.capability.instances.{futureSync, futureMonadError}
import cedar4s.auth.FlatMap

// Provide FlatMap for deferred checks
implicit val flatMapFuture: FlatMap[Future] = FlatMap.futureInstance

// Import generated types
import example.myapp.cedar.MyApp
import example.myapp.cedar.MyApp._

// Create CedarEngine
val engine: CedarEngine[Future] = CedarEngine.fromResources[Future](
  policiesPath = "policies",
  policyFiles = Seq("myapp.cedar")
)

// Create EntityStore
val store: EntityStore[Future] = EntityStore.builder[Future]()
  .register[Entities.User, String](new UserFetcher())
  .register[Entities.Document, String](new DocumentFetcher())
  .build()

// Runtime + session
val runtime = CedarRuntime[Future](engine, store, CedarRuntime.resolverFrom(buildPrincipal))
implicit val session: CedarSession[Future] = runtime.session(currentUser)

val result: Future[Unit] = MyApp.Document.Read("folder-1", "doc-1").require
val deferred: Future[Unit] = MyApp.Document.Read.on("doc-1").require
```

</TabItem>
</Tabs>

## Type Class Hierarchy

cedar4s defines minimal type classes to avoid external dependencies.

### Sync[F]

Required for wrapping blocking cedar-java calls:

```scala
trait Sync[F[_]] extends MonadError[F] {
  /** Defer a side-effecting computation */
  def delay[A](thunk: => A): F[A]
  
  /** Execute a blocking operation */
  def blocking[A](thunk: => A): F[A]
  
  /** Suspend an already-constructed effect */
  def defer[A](fa: => F[A]): F[A]
}
```

### Concurrent[F]

Required for parallel entity loading and batch operations:

```scala
trait Concurrent[F[_]] extends Sync[F] {
  trait Fiber[A] {
    def join: F[A]
    def cancel: F[Unit]
  }
  
  def start[A](fa: F[A]): F[Fiber[A]]
  def parSequence[A](fas: List[F[A]]): F[List[A]]
  def parTraverse[A, B](as: List[A])(f: A => F[B]): F[List[B]]
}
```

### FlatMap[F]

Required for deferred authorization checks:

```scala
trait FlatMap[F[_]] {
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]
  def pure[A](a: A): F[A]
  def liftFuture[A](future: scala.concurrent.Future[A]): F[A]
}
```

## Bridging to cats-effect

If your application uses cats-effect, create bridge instances:

<Tabs groupId="scala-version">
<TabItem value="scala3" label="Scala 3" default>

```scala
import cats.effect.Sync as CatsSync
import cats.effect.Concurrent as CatsConcurrent

given syncFromCats[F[_]](using S: CatsSync[F]): cedar4s.capability.Sync[F] with {
  def pure[A](a: A): F[A] = S.pure(a)
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = S.flatMap(fa)(f)
  def raiseError[A](e: Throwable): F[A] = S.raiseError(e)
  def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = S.handleErrorWith(fa)(f)
  def delay[A](thunk: => A): F[A] = S.delay(thunk)
  def blocking[A](thunk: => A): F[A] = S.blocking(thunk)
}

given concurrentFromCats[F[_]](using C: CatsConcurrent[F]): cedar4s.capability.Concurrent[F] with {
  def start[A](fa: F[A]): F[Fiber[A]] = C.start(fa).map(f =>
    new Fiber[A] {
      def cancel: F[Unit] = f.cancel
      def join: F[A] = f.joinWithNever
    }
  )
  // ... other methods
}
```

</TabItem>
<TabItem value="scala2" label="Scala 2">

```scala
import cats.effect.{Sync => CatsSync}
import cats.effect.{Concurrent => CatsConcurrent}

implicit def syncFromCats[F[_]](implicit S: CatsSync[F]): cedar4s.capability.Sync[F] =
  new cedar4s.capability.Sync[F] {
    def pure[A](a: A): F[A] = S.pure(a)
    def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = S.flatMap(fa)(f)
    def raiseError[A](e: Throwable): F[A] = S.raiseError(e)
    def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] = S.handleErrorWith(fa)(f)
    def delay[A](thunk: => A): F[A] = S.delay(thunk)
    def blocking[A](thunk: => A): F[A] = S.blocking(thunk)
  }

implicit def concurrentFromCats[F[_]](implicit C: CatsConcurrent[F]): cedar4s.capability.Concurrent[F] =
  new cedar4s.capability.Concurrent[F] {
    def start[A](fa: F[A]): F[Fiber[A]] = C.start(fa).map(f =>
      new Fiber[A] {
        def cancel: F[Unit] = f.cancel
        def join: F[A] = f.joinWithNever
      }
    )
    // ... other methods
  }
```

</TabItem>
</Tabs>

## Custom Effect Types

Implement the capability traits for custom effect types:

```scala
case class MyEffect[+A](run: () => A)

given cedar4s.capability.Sync[MyEffect] = 
  cedar4s.capability.Sync.instance[MyEffect](
    new cedar4s.capability.Sync.Builder[MyEffect] {
      def pure[A](a: A): MyEffect[A] = MyEffect(() => a)
      def flatMap[A, B](fa: MyEffect[A])(f: A => MyEffect[B]): MyEffect[B] =
        MyEffect(() => f(fa.run()).run())
      def raiseError[A](e: Throwable): MyEffect[A] = MyEffect(() => throw e)
      def handleErrorWith[A](fa: MyEffect[A])(f: Throwable => MyEffect[A]): MyEffect[A] =
        MyEffect(() => try fa.run() catch { case e: Throwable => f(e).run() })
      def delay[A](thunk: => A): MyEffect[A] = MyEffect(() => thunk)
      def blocking[A](thunk: => A): MyEffect[A] = MyEffect(() => thunk)
    }
  )

// Now use with cedar4s
val engine: CedarEngine[MyEffect] = CedarEngine.fromResources[MyEffect](
  policiesPath = "policies",
  policyFiles = Seq("myapp.cedar")
)
```

## Built-in Future Instances

cedar4s includes complete instances for `scala.concurrent.Future`:

```scala
package cedar4s.capability.instances

implicit def futureMonadError(implicit ec: ExecutionContext): MonadError[Future]
implicit def futureSync(implicit ec: ExecutionContext): Sync[Future]
implicit def futureConcurrent(implicit ec: ExecutionContext): Concurrent[Future]
```

All instances require an `ExecutionContext` in scope.

## Performance Considerations

### Blocking Operations

cedar4s wraps cedar-java calls with `Sync[F].blocking`:

```scala
def authorize(request: CedarRequest, entities: CedarEntities): F[CedarDecision] =
  F.blocking {
    cedarAuthorizationEngine.isAuthorized(...)
  }
```

Effect types can optimize this:

- **cats-effect IO**: Shifts to blocking thread pool
- **ZIO**: Uses blocking pool via `ZIO.attemptBlocking`
- **Future**: Executes on provided `ExecutionContext`

### Parallel Entity Loading

`Concurrent[F].parTraverse` enables parallel entity fetching:

```
Sequential: 10 entities × 5ms = 50ms
Parallel:   10 entities = ~5ms
```

