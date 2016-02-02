package vtoro.tagless

import language.higherKinds
import cats.{Unapply, ~>, Monad}
import Interpreter.and

/* Term is basically a function F[M] => M[X] forall M */
trait Term[F[_[_]],X] { self =>
  def run[M[_] : Monad]( interpreter: Interpreter[F,M] ) : M[X]
  def flatMap[A]( f : X => Term[F,A] ) : Term[F,A] = new Term[F,A] {
    def run[M[_] : Monad]( interpreter: Interpreter[F, M] ): M[A] =
      Monad[M].flatMap( self.run( interpreter ) ){ x => f(x).run( interpreter ) }
  }
  def map[A]( f: X => A ) = flatMap( f andThen { a => Term.pure[F,A](a) }  )
  def as[G[_[_]]](implicit E: Embed[F,G] ) : Term[G,X] = new Term[G,X] {
    def run[M[_] : Monad](interpreter: Interpreter[G, M]): M[X] = self.run( E( interpreter ) )
  }

  /* Convenience methods: these are not actually needed but intelliJ fails to infer implicit flatMapOps for Terms, resulting in ugly squigglies*/
  def >>[Y]( t: Term[F,Y]) : Term[F,Y] = self.flatMap( ignore => t )
}

object Term {
  def pure[F[_[_]],X]( x:X ) = new Term[F,X] {
    def run[M[_] : Monad](interpreter: Interpreter[F, M]): M[X] = Monad[M].pure( x )
  }
  implicit def monad[F[_[_]]] : Monad[Term[F,?]] = new Monad[Term[F,?]] {
    def flatMap[A, B](fa: Term[F,A])(f: (A) => Term[F,B]): Term[F,B] = fa.flatMap( f )
    def pure[A](x: A): Term[F,A] = Term.pure[F,A]( x )
  }
  def apply[F[_[_]]] : TermBuilder[F] = new TermBuilder[F] {}

  /* Needs rank-3 unapply to get monad ops for Term */
  type Aux2Rank3Right[TC[_[_]], MA, F[_[_[_]],_], AA[_[_]], B] = Unapply[TC, MA] {
    type M[X] = F[AA,X]
    type A = B
  }
  implicit def unapply2rank3right[TC[_[_]], F[_[_[_]],_], AA[_[_]], B](implicit tc: TC[F[AA,?]]): Aux2Rank3Right[TC,F[AA,B], F, AA, B] = new Unapply[TC, F[AA,B]] {
    type M[X] = F[AA, X]
    type A = B
    def TC: TC[F[AA, ?]] = tc
    def subst: F[AA, B] => M[A] = identity
  }

}

sealed trait TermBuilder[F[_[_]]] {
  type X[_] //forall X
  def apply[A]( f : F[X]=>X[A] ) : Term[F,A] = new Term[F,A] {
    def run[M[_] : Monad](interpreter: Interpreter[F, M]): M[A] =
      /* casts X to interpreter.G, should be safe since X[_] is existential and TermBuilder is sealed */
      interpreter.apply( f.asInstanceOf[F[interpreter.G] => interpreter.G[A]] )
  }
  def mkTerm[A]( f : F[X]=>X[A] ) : Term[F,A] = apply(f)

  /* Final is basically a function from (F and G)#fin[M] => M[X] */
  def mkFinal[G[_[_]],A]( f: F[X] => Interpreter[(F and G)#fin,X] => Monad[X] => X[A] ) = new Term[(F and G)#fin,A] {
    def run[M[_] : Monad](interpreter: Interpreter[and[F, G]#fin, M]): M[A] = {
      interpreter.nt(
        /* casts X to interpreter.G */
        f.asInstanceOf[F[interpreter.G] => Interpreter[(F and G)#fin, interpreter.G] => Monad[interpreter.G] => interpreter.G[A]]
          (interpreter.init.left)
          (Final(interpreter.init.left)(interpreter.init.monad) and interpreter.init.right)
          (interpreter.init.monad)
      )
    }
  }
}



