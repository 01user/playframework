package play.api.libs.concurrent

import play.core._
import akka.dispatch.Future

sealed trait PromiseValue[+A] {
  def isDefined = this match { case Waiting => false; case _ => true }
}

trait NotWaiting[+A] extends PromiseValue[A] {
  /**
   * Return the value or the promise, throw it if it held an exception
   */
  def get: A = this match {
    case Thrown(e) => throw e
    case Redeemed(a) => a
  }
}
case class Thrown(e: scala.Throwable) extends NotWaiting[Nothing]
case class Redeemed[+A](a: A) extends NotWaiting[A]
case object Waiting extends PromiseValue[Nothing]

trait Promise[A] {

  def onRedeem(k: A => Unit): Unit

  def extend[B](k: Function1[Promise[A], B]): Promise[B]

  def value: NotWaiting[A]

  def filter(p: A => Boolean): Promise[A]

  def map[B](f: A => B): Promise[B]

  def flatMap[B](f: A => Promise[B]): Promise[B]
}

trait Redeemable[A] {
  def redeem(a: => A): Unit
}

/**
 * a promise impelemantation based on Akka's Future
 */
class AkkaPromise[A](future: Future[A]) extends Promise[A] {

  /**
   * call back hook
   */
  def onRedeem(k: A => Unit) {
    future.onComplete { _.value.get.fold(Thrown(_), k) }
  }

  /*
   * extend @param k 
   */
  def extend[B](k: Function1[Promise[A], B]): Promise[B] =
    new AkkaPromise[B](future.map(p => k(this)))

  /*
   * it's time to retrieve the future value
   */
  def value: NotWaiting[A] = try {
    Redeemed(future.get)
  } catch { case e => Thrown(e) }

  /*
   * filtering akka based future and rewrapping the result in an AkkaPromise
   */
  def filter(p: A => Boolean): Promise[A] =
    new AkkaPromise[A](future.filter(p.asInstanceOf[(Any => Boolean)]).asInstanceOf[Future[A]])

  /*
   * mapping @param f function to AkkaPromise 
   *
   */
  def map[B](f: A => B): Promise[B] = new AkkaPromise[B](future.map(f))

  /**
   * provides a means to flatten Akka based promises
   */
  def flatMap[B](f: A => Promise[B]): Promise[B] =
    new AkkaPromise[B](future.map(f).map(_.value match { case r: Redeemed[_] => r.a }))

}

class STMPromise[A] extends Promise[A] with Redeemable[A] {
  import scala.concurrent.stm._

  val actions: Ref[List[Promise[A] => Unit]] = Ref(List())
  var redeemed: Ref[PromiseValue[A]] = Ref(Waiting)

  def extend[B](k: Function1[Promise[A], B]): Promise[B] = {
    val result = new STMPromise[B]()
    onRedeem(_ => result.redeem(k(this)))
    result
  }

  def filter(p: A => Boolean): Promise[A] = {
    val result = new STMPromise[A]()
    onRedeem(a => if (p(a)) result.redeem(a))
    result
  }

  def collect[B](p: PartialFunction[A, B]) = {
    val result = new STMPromise[B]()
    onRedeem(a => p.lift(a).foreach(result.redeem(_)))
    result
  }

  def onRedeem(k: A => Unit): Unit = {
    addAction(p => p.value match { case Redeemed(a) => k(a); case _ => })

  }

  private def addAction(k: Promise[A] => Unit): Unit = {
    if (redeemed.single().isDefined) {
      k(this)
    } else {
      val ok: Boolean = atomic { implicit txn =>
        if (!redeemed().isDefined) { actions() = actions() :+ k; true }
        else false
      }
      if (!ok) invoke(this, k)
    }
  }

  def value: NotWaiting[A] = atomic { implicit txn =>
    if (redeemed() != Waiting) redeemed().asInstanceOf[NotWaiting[A]]
    else retry
  }

  private def invoke[T](a: T, k: T => Unit) = PromiseInvoker.invoker ! Invoke(a, k)

  def redeem(body: => A): Unit = {
    val result = scala.util.control.Exception.allCatch[A].either(body)
    atomic { implicit txn =>
      if (redeemed().isDefined) error("already redeemed")
      redeemed() = result.fold(Thrown(_), Redeemed(_))
    }
    actions.single.swap(List()).foreach(invoke(this, _))
  }

  def map[B](f: A => B): Promise[B] = {
    val result = new STMPromise[B]()
    this.addAction(p => p.value match {
      case Redeemed(a) => result.redeem(f(a))
      case Thrown(e) => result.redeem(throw e)
    })
    result
  }

  def flatMap[B](f: A => Promise[B]) = {
    val result = new STMPromise[B]()
    this.addAction(p => p.value match {
      case Redeemed(a) =>
        f(a).extend(ip => ip.value match {
          case Redeemed(a) => result.redeem(a)
          case Thrown(e) => result.redeem(throw e)

        })
      case Thrown(e) => result.redeem(throw e)
    })
    result
  }
}

object PurePromise {

  def apply[A](a: A): Promise[A] = new Promise[A] {

    private def neverRedeemed[A]: Promise[A] = new Promise[A] {
      def onRedeem(k: A => Unit): Unit = ()

      def extend[B](k: Function1[Promise[A], B]): Promise[B] = neverRedeemed[B]

      def value: NotWaiting[A] = scala.sys.error("not redeemed")

      def filter(p: A => Boolean): Promise[A] = this

      def map[B](f: A => B): Promise[B] = neverRedeemed[B]

      def flatMap[B](f: A => Promise[B]): Promise[B] = neverRedeemed[B]

    }

    def onRedeem(k: A => Unit): Unit = k(a)

    def value = Redeemed(a)

    def redeem(a: A) = error("Already redeemed")

    def extend[B](f: (Promise[A] => B)): Promise[B] = {
      apply(f(this))
    }

    def filter(p: A => Boolean) = if (p(a)) this else neverRedeemed[A]

    def map[B](f: A => B): Promise[B] = PurePromise[B](f(a))

    def flatMap[B](f: A => Promise[B]): Promise[B] = f(a)
  }
}

object Promise {
  def pure[A](a: A) = PurePromise(a)
  def apply[A](): Promise[A] with Redeemable[A] = new STMPromise[A]()
}

