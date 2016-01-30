package vtoro.tagless

import language.higherKinds
import cats.Monad
import vtoro.tagless.Interpreter.and

trait testAutoEmbed {
  import autoEmbed._
  type A[_[_]]
  type B[_[_]]
  type C[_[_]]
  type D[_[_]]
  type E[_[_]]
  type F[_[_]]

  type BA[X[_]] = (B and A)#pair[X]
  type CBA[X[_]] = (C and BA)#pair[X]
  type DCBA[X[_]] = (D and CBA)#fin[X]
  type EDCBA[X[_]] = (E and DCBA)#pair[X]
  type FEDCBA[X[_]] = (F and EDCBA)#pair[X]

  type FCBA[X[_]] = (F and CBA)

  type M[_]
  implicitly[UnapplyPair[DCBA]]
  implicitly[Embed[CBA,CBA]]
  implicitly[Embed[A,BA]]
  implicitly[Embed[B,BA]]
  implicitly[Embed[B,CBA]]
  implicitly[Embed[A,CBA]]
  implicitly[Embed[BA,CBA]]
  implicitly[Embed[C,CBA]]
  implicitly[Embed[D,DCBA]]
  implicitly[Embed[C,DCBA]]
  implicitly[Embed[CBA,DCBA]]
  implicitly[Embed[BA,DCBA]]
//  implicitly[Embed[DCBA,FEDCBA]] //TODO: fails on final pairs

  //Negative cases
  /*
  implicitly[Embed[CBA,BA]]
  implicitly[Embed[D,CBA]]
  implicitly[Embed[FCBA,FEDCBA]]
  */
}

object autoEmbed {

  import language.implicitConversions
  implicit def autoEmbedTerm[A1[_[_]],A2[_[_]],X]( t: Term[A1,X])( implicit E: Embed[A1,A2] ) : Term[A2,X] = t.as[A2]( E )

  //Begin outrageous typefoolery

  //https://gist.github.com/milessabin/cadd73b7756fe4097ca0
  case class SingletonOf[T, U](value: U, f: U => T) {
    def get : T = f(value)
  }
  object SingletonOf {
    implicit def mkSingletonOf[T <: AnyRef](implicit t: T): SingletonOf[T, t.type] = SingletonOf(t,identity)
  }

  implicit def embedRefl[F[_[_]]] : Embed[F,F]= new Embed[F,F] {
    def apply[M[_] : Monad](f: Interpreter[F, M]): Interpreter[F, M] = f
  }
  implicit def embedUnapplyRight[F[_[_]],G[_[_]],P <: {type Left[_[_]];type Right[_[_]]}]( implicit sPair: SingletonOf[UnapplyPair[G],P], E : Embed[F,P#Right] ) : Embed[F,G] = new Embed[F,G] {
    val unapplyG : UnapplyPair[G] = sPair.get
    val embedGRight = E.asInstanceOf[Embed[F,unapplyG.Right]]
    def apply[M[_] : Monad](f: Interpreter[G, M]): Interpreter[F, M] = embedGRight( unapplyG( f.init ).right andThen f.nt )
  }

  implicit def embedUnapplyLeft[F[_[_]],G[_[_]],P <: {type Left[_[_]];type Right[_[_]]}]( implicit sPair: SingletonOf[UnapplyPair[G],P], E : Embed[F,P#Left] ) : Embed[F,G] = new Embed[F,G] {
    val unapplyG : UnapplyPair[G] = sPair.get
    val embedGLeft = E.asInstanceOf[Embed[F,unapplyG.Left]]
    def apply[M[_] : Monad](f: Interpreter[G, M]): Interpreter[F, M] = embedGLeft( unapplyG( f.init ).left andThen f.nt )
  }
}

trait UnapplyPair[-G[_[_]]] {
  type Left[_[_]]
  type Right[_[_]]
  type Pair[M[_]] = (Left and Right)#pair[M]
  def apply[M[_]]( g: G[M] ) : Pair[M]
}
object UnapplyPair {
  //Can unapply pair shapes 6 layers deep
  //TODO: shapeless

  class pair1Aux[F[_[_]], G[_[_]]] extends UnapplyPair[(F and G)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = G[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  class pair2Aux[F[_[_]], G[_[_]], H[_[_]]] extends UnapplyPair[(F and (G and H)#pair)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = (G and H)#pair[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  class pair3Aux[F[_[_]], G[_[_]], H[_[_]], I[_[_]]] extends UnapplyPair[(F and (G and (H and I)#pair)#pair)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = (G and (H and I)#pair)#pair[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  class pair4Aux[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]]] extends UnapplyPair[(F and (G and (H and (I and J)#pair)#pair)#pair)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = (G and (H and (I and J)#pair)#pair)#pair[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  class pair5Aux[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]], K[_[_]]] extends UnapplyPair[(F and (G and (H and (I and (J and K)#pair)#pair)#pair)#pair)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = (G and (H and (I and (J and K)#pair)#pair)#pair)#pair[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  class pair6Aux[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]], K[_[_]], L[_[_]]] extends UnapplyPair[(F and (G and (H and (I and (J and (K and L)#pair)#pair)#pair)#pair)#pair)#pair] {
    type Left[M[_]] = F[M]
    type Right[M[_]] = (G and (H and (I and (J and (K and L)#pair)#pair)#pair)#pair)#pair[M]
    def apply[M[_]]( g: Pair[M] ) : Pair[M] = g
  }

  implicit def pair1[F[_[_]], G[_[_]]]: pair1Aux[F, G] = new pair1Aux[F, G]
  implicit def pair2[F[_[_]], G[_[_]], H[_[_]]]: pair2Aux[F, G, H] = new pair2Aux[F, G, H]
  implicit def pair3[F[_[_]], G[_[_]], H[_[_]], I[_[_]]]: pair3Aux[F, G, H, I] = new pair3Aux[F, G, H, I]
  implicit def pair4[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]]]: pair4Aux[F, G, H, I, J] = new pair4Aux[F, G, H, I, J]
  implicit def pair5[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]], K[_[_]]]: pair5Aux[F, G, H, I, J, K] = new pair5Aux[F, G, H, I, J, K]
  implicit def pair6[F[_[_]], G[_[_]], H[_[_]], I[_[_]], J[_[_]], K[_[_]], L[_[_]]]: pair6Aux[F, G, H, I, J, K, L] = new pair6Aux[F, G, H, I, J, K, L]

  type Aux[P[_[_]],L[_[_]],R[_[_]]] = UnapplyPair[P] { type Left[M[_]] = L[M] ; type Right[M[_]] = R[M] }
}

