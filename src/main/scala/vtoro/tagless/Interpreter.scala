package vtoro.tagless

import language.higherKinds
import cats.{Monad, ~>}

/**
  * Interpreter consists of F[G[_] and G[_] ~> M[_], where G is existential
  * yielding something like F[M] to which you can apply natural transformations
  * it looks a lot like a higher-order co-yoneda
   */
trait Interpreter[F[_[_]],M[_]] { self =>
  import Interpreter.and
  type G[_]
  def init : F[G]
  def nt : G ~> M
  def andThen[H[_]]( n : M ~> H ) : Interpreter[F,H] =
    InterpreterNT[F,G,H]( init, nt andThen n )
  def and[H[_[_]]]( i: Interpreter[H,M] ) : Interpreter[(F and H)#pair,M] =
    InterpreterInit[InterpreterPair[F,H,?[_]],M]( InterpreterPair(self, i ) )
  def apply[A]( f: F[G] => G[A] ) : M[A] = nt( f( init ) )
}
/* An interpreter without a natural transformation */
case class InterpreterInit[F[_[_]],M[_]]( init: F[M] ) extends Interpreter[F,M] {
  type G[X] = M[X]
  val nt = new (M ~> M) {
    def apply[A](fa: M[A]): M[A] = fa
  }
}
/* An interepreter with a natural transformation */
case class InterpreterNT[F[_[_]],G0[_],M[_]]( init: F[G0], nt: G0 ~> M) extends Interpreter[F,M] {
  type G[X] = G0[X]
}

/* An interpreter pair is just a pair of interpreters*/
case class InterpreterPair[F[_[_]],G[_[_]],M[_]]( left: Interpreter[F,M], right: Interpreter[G,M] )

/* Final pairs are otherwise like InterpreterPairs, but the left side must not have natural transformations */
case class FinalPair[F[_[_]],G[_[_]],M[_]]( left: F[M], right: Interpreter[G,M], monad:Monad[M])
case class Final[F[_[_]],M[_] : Monad]( fm: F[M] ) {
  def and[G[_[_]]]( interpreter: Interpreter[G,M] ) : Interpreter[FinalPair[F,G,?[_]],M] =
    Interpreter[FinalPair[F,G,?[_]],M]( FinalPair( fm, interpreter, implicitly[Monad[M]]) )
}

object Interpreter {
  def apply[F[_[_]],M[_]]( fm : F[M] ) : Interpreter[F,M] = InterpreterInit[F,M]( fm )
  def apply[F[_[_]],H[_],M[_]]( fm : F[H], nt: H ~> M ) : Interpreter[F,M] = InterpreterNT( fm, nt )
  type and[A[_[_]],B[_[_]]] = ({
    type pair[X[_]] = InterpreterPair[A,B,X]
    type fin[X[_]] = FinalPair[A,B,X]
  })
}

