package cps.monads

import cps._
import scala.concurrent._
import scala.concurrent.duration._
import scala.quoted._
import scala.util._
import scala.util.control._


/**
 * Default CpsMonad implementation for `Future`
 **/
given FutureAsyncMonad(using ExecutionContext): CpsSchedulingMonad[Future] with CpsMonadInstanceContext[Future] with

   type F[+T] = Future[T]

   override type WF[T] = F[T]

   def pure[T](t:T):Future[T] = Future.successful(t)

   def map[A,B](fa:F[A])(f: A=>B):F[B] =
        fa.map(f)

   def flatMap[A,B](fa:F[A])(f: A=>F[B]):F[B] =
        fa.flatMap(f)

   def error[A](e: Throwable): F[A] =
        Future.failed(e)
   
   override def mapTry[A,B](fa:F[A])(f: Try[A]=>B): F[B] =
        fa.transform{ v => Success(f(v)) }

   def flatMapTry[A,B](fa:F[A])(f: Try[A]=>F[B]): F[B] =
        fa.transformWith{ v => f(v) }


   override def restore[A](fa: F[A])(fx:Throwable => F[A]): F[A] =
        fa.recoverWith{ case ex => fx(ex) }

   def adoptCallbackStyle[A](source: (Try[A]=>Unit) => Unit): F[A] =
        val p = Promise[A]
        source(p.complete(_))
        p.future

   def spawn[A](op: Context ?=> F[A]): F[A] =
        val p = Promise[A]
        summon[ExecutionContext].execute{ 
          () => 
              try
                p.completeWith(op(using this)) 
              catch
                case NonFatal(ex) =>
                  p.complete(Failure(ex))
        }
        p.future

   /**
   * returns failed Future, because cancellation is not supported.
   */     
   def tryCancel[A](op: Future[A]): Future[Unit] =
        Future failed new UnsupportedOperationException("FutureAsyncMonad.tryCancel is unsupported")

   /**
    * join two computations in such way, that they will execute concurrently.
    *  Since Future computation is already spawned, we can not spawn waiting for results here
    **/
   override def concurrently[A,B](fa:F[A], fb:F[B]): F[Either[(Try[A],Future[B]),(Future[A],Try[B])]] =
       val p = Promise[Either[(Try[A],Future[B]),(Future[A],Try[B])]]
       fa.onComplete{ ra =>
          p.tryComplete(Success(Left((ra,fb))))
       }
       fb.onComplete{ rb =>
          p.tryComplete(Success(Right((fa,rb))))
       }
       p.future

  
   def executionContext = summon[ExecutionContext]



given futureDiscard: cps.automaticColoring.WarnValueDiscard[Future] with {}


given futureMemoization: CpsMonadMemoization.Default[Future] with {}



given fromFutureConversion[G[_],T](using ExecutionContext, CpsAsyncMonad[G]): CpsMonadConversion[Future,G] with

  def apply[T](ft:Future[T]): G[T] =
    summon[CpsAsyncMonad[G]].adoptCallbackStyle(listener => ft.onComplete(listener) )
                                         

given toFutureConversion[F[_], T](using ExecutionContext, CpsSchedulingMonad[F]): CpsMonadConversion[F,Future] with

  def apply[T](ft:F[T]): Future[T] =
    val p = Promise[T]()
    val u = summon[CpsSchedulingMonad[F]].restore(
                        summon[CpsMonad[F]].map(ft)( x => p.success(x) )
                 )(ex => summon[CpsMonad[F]].pure(p.failure(ex)) )
    summon[CpsSchedulingMonad[F]].spawn(u)
    p.future

