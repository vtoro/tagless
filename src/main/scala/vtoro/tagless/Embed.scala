package vtoro.tagless

import language.higherKinds
import cats.Monad
import Interpreter.and

trait Embed[F[_[_]],G[_[_]]] {
  def apply[M[_] : Monad]( f: Interpreter[G,M] ) : Interpreter[F,M]
}

object Embed {
  def embedLeft[F[_[_]],G[_[_]]] : Embed[F,(F and G)#pair] = new Embed[F,(F and G)#pair] {
    def apply[M[_] : Monad](f: Interpreter[(F and G)#pair,M]): Interpreter[F,M] = f.init.left andThen f.nt
  }
  def embedRight[F[_[_]],G[_[_]]] : Embed[F,(G and F)#pair] = new Embed[F,(G and F)#pair] {
    def apply[M[_] : Monad](f: Interpreter[(G and F)#pair,M]): Interpreter[F,M] = f.init.right andThen f.nt
  }
  def embedFinalRight[F[_[_]],G[_[_]]] : Embed[F,(G and F)#fin] = new Embed[F,(G and F)#fin] {
    def apply[M[_] : Monad](f: Interpreter[(G and F)#fin, M]): Interpreter[F, M] = f.init.right andThen f.nt
  }

  def apply[F[_[_]],G[_[_]]] : EmbedBuilder[F,G] = new EmbedBuilder[F,G] {}
}

sealed trait EmbedBuilder[F[_[_]],G[_[_]]] {
  type X[_] // forall X

  def toAlgebra(f: Interpreter[G,X] => Monad[X] => F[X] ) : Embed[F,G] = new Embed[F,G] {
    def apply[M[_] : Monad](i: Interpreter[G, M]): Interpreter[F, M] = Interpreter( f.asInstanceOf[ Interpreter[G,M]=>Monad[M]=>F[M] ]( i )( implicitly[Monad[M]]) )
  }
  def toInterpreter(f: Interpreter[G,X] => Monad[X] => Interpreter[F,X] ) : Embed[F,G] = new Embed[F,G] {
    def apply[M[_] : Monad](i: Interpreter[G, M]): Interpreter[F, M] = f.asInstanceOf[ Interpreter[G,M]=>Monad[M]=>Interpreter[F,M] ]( i )( implicitly[Monad[M]] )
  }
}