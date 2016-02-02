import cats.{Monad, Id, ~>}
import vtoro.tagless.Term
import cats.data.State
import language.higherKinds
import vtoro.tagless._
import language.higherKinds
import Interpreter.and
import CKV._

trait Console[M[_]] {
  def getLine : M[String]
  def putLine( s: String ) : M[Unit]
}
class ConsoleSyntax[G[_[_]]]( asG : Embed[Console,G] ) {
  def getLine = Term[Console].mkTerm( _.getLine ).as( asG )
  def putLine( s : String ) = Term[Console].mkTerm( _.putLine( s ) ).as( asG )
}

object TestConsole extends Console[Id] {
  def getLine : Id[String] = "test"
  def putLine( s : String ) = println(s)
}

trait KVStore[M[_]] {
  def put( key: String, value: String ) : M[Unit]
  def get( key: String ) : M[Option[String]]
}

class KVStoreSyntax[G[_[_]]]( asG: Embed[KVStore,G] ) {
  def put(key: String, value: String) = Term[KVStore].mkTerm(_.put(key, value)).as( asG )
  def get(key: String) = Term[KVStore].mkTerm(_.get(key)).as( asG )
}

object TestKVStore extends KVStore[KVState] {
  def put( key: String, value: String ) : KVState[Unit] = State.modify { s => s + (key -> value) }
  def get( key: String ) : KVState[Option[String]] = State.get.map( _.get(key) )
}

object IdToKVState extends (Id ~> KVState) {
  def apply[A](fa: Id[A]): KVState[A] = State.pure(fa)
}

trait Logging[M[_]] {
  def debug( s:String ) : M[Unit]
  def warning( s: String ) : M[Unit]
}

class LoggingSyntax[G[_[_]]]( asG: Embed[Logging,G] ) {
  def debug( s: String ) = Term[Logging].mkTerm( _.debug(s) ).as( asG )
  def warning( s: String ) = Term[Logging].mkTerm( _.warning(s) ).as( asG )
}

class ConsoleLogging[M[_] : Monad](f: Interpreter[Console,M] ) extends Logging[M] {
  def debug(s: String): M[Unit] = f( _.putLine(s"[DEBUG] $s"))
  def warning(s: String): M[Unit] = f( _.putLine(s"[WARNING] $s"))
}

object CKV {
  type CKV[X[_]] = (Console and KVStore)#pair[X]
  type KVState[X] = State[Map[String,String],X]
  val ConsoleToCKV : Embed[Console,CKV] = Embed.left
  val KVStoreToCKV : Embed[KVStore,CKV] = Embed.right
  val LoggingToCKV : Embed[Logging,CKV] = Embed[Logging,CKV].toAlgebra(iCKV => implicit m => new ConsoleLogging(iCKV.init.left andThen iCKV.nt) )
  val consoleSyntax = new ConsoleSyntax[CKV](ConsoleToCKV)
  val kvStoreSyntax = new KVStoreSyntax[CKV](KVStoreToCKV)
  val loggingSyntax = new LoggingSyntax[CKV](LoggingToCKV)

  val KVStateInterpreter = Interpreter( TestConsole, IdToKVState ) and Interpreter( TestKVStore )
  def pure[X]( x:X ) = Term.pure[CKV,X]( x )
}

object test extends App {
  import consoleSyntax._
  import kvStoreSyntax._
  import loggingSyntax._

  val prg1 = for {
    _ <- putLine( "What is your Name?" )
    name <- getLine
    _ <- putLine( s"Hello $name" )
  } yield name

  val prg2 = for {
    user <- prg1
    maybePwd <- get( user )
    authenticated <-
      (maybePwd.fold
        ( putLine("You need to create an account!") >> CKV.pure(false) )
        ( password => putLine("Password:") >> getLine.map( _ == password ) )
      )
  } yield (user, authenticated)

  val prg3 = for {
    userTuple <- prg2
    _ <- if( userTuple._2 )
           debug(s"User: '${userTuple._1}' successfully authenticated!")
         else
           warning(s"User: '${userTuple._1}' was not authenticated!")
  } yield userTuple


  println("----\nResult is: " + prg3.run( CKV.KVStateInterpreter ).run( Map( "test" -> "test" ) ).value )

}
