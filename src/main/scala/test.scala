import cats.state._
import cats.{Unapply, Monad, Id, ~>}
import cats.syntax.FlatMapOps
import vtoro.tagless.Term
import language.higherKinds
import scala.concurrent.{Future, ExecutionContext}

object test extends App {
  import vtoro.tagless._
  import language.higherKinds
  import Interpreter.and
  import cats.syntax.flatMap._
  import cats._
  import cats.state._
  import vtoro.tagless.autoEmbed._
  import scala.concurrent.{Await, Future}
  import scala.concurrent.duration.Duration
  import cats.std.future._
  import java.util.concurrent.Executor
  implicit val ec : ExecutionContext = ExecutionContext.fromExecutor( new Executor {
    def execute(command: Runnable): Unit = command.run()
  })

  trait Console[M[_]] {
    def getLine : M[String]
    def putLine( s: String ) : M[Unit]
  }

  def getLine = Term[Console].mkTerm( _.getLine )
  def putLine( s : String ) = Term[Console].mkTerm( _.putLine( s ) )

  val prg1 = for {
    _ <- putLine( "What is your Name?" )
    name <- getLine
    _ <- putLine( s"Hello $name" )
  } yield name

  object TestConsole extends Console[Id] {
    def getLine : Id[String] = "test"
    def putLine( s : String ) = println(s)
  }

  val consoleTest = Interpreter( TestConsole )
  prg1.run( consoleTest )

  object idToEval extends (Id ~> Eval) {
    override def apply[A](fa: Id[A]): Eval[A] = Eval.later{ fa }
  }
  val evalPrg1 = prg1.run( consoleTest andThen idToEval )
  evalPrg1.value


  trait KVStore[M[_]] {
    def put( key: String, value: String ) : M[Unit]
    def get( key: String ) : M[Option[String]]
  }

  def put( key: String, value: String ) = Term[KVStore].mkTerm( _.put( key, value ) )
  def get( key: String ) = Term[KVStore].mkTerm( _.get( key ) )

  type CKV[X[_]] = (Console and KVStore)#pair[X]

  val prg2 : Term[CKV,(String,Boolean)] = for {
    user <- prg1.as[CKV]
    maybePwd <- get( user ).as[CKV]
    authenticated <-
    (maybePwd.fold
      ( putLine("You need to create an account!") >> Term.pure[Console,Boolean](false) )
      ( password => putLine("Password:") >> getLine.map( _ == password ) )
      ).as[CKV]
  } yield (user, authenticated)

  type KVState[X] = State[Map[String,String],X]

  object TestKVStore extends KVStore[KVState] {
    def put( key: String, value: String ) : KVState[Unit] = State.modify { s => s + (key -> value) }
    def get( key: String ) : KVState[Option[String]] = State.get.map( _.get(key) )
  }

  val testKV = Interpreter( TestKVStore )

  object idToKVState extends (Id ~> KVState) {
    def apply[A](fa: Id[A]): KVState[A] = State.pure(fa)
  }

  val testCKV : Interpreter[CKV,KVState] = (consoleTest andThen idToKVState) and testKV

  prg2.run( testCKV ).run( Map( "user" -> "password" ) ).value
  prg2.run( testCKV ).run( Map( "test" -> "test" ) ).value

  trait Logging[M[_]] {
    def debug( s:String ) : M[Unit]
    def warning( s: String ) : M[Unit]
  }

  def debug( s: String ) = Term[Logging].mkTerm( _.debug(s) )
  def warning( s: String ) = Term[Logging].mkTerm( _.warning(s) )

  type LCKV[X[_]] = (Logging and CKV)#pair[X]

  val prg3 = for {
    userTuple <- prg2.as[LCKV]
    _ <- if( userTuple._2 )
           debug(s"User: '${userTuple._1}' successfully authenticated!").as[LCKV]
         else
           warning(s"User: '${userTuple._1}' was not authenticated!").as[LCKV]
  } yield userTuple


  class ConsoleLogging[M[_] : Monad](f: Interpreter[Console,M] ) extends Logging[M] {
    def debug(s: String): M[Unit] = f( _.putLine(s"[DEBUG] $s"))
    def warning(s: String): M[Unit] = f( _.putLine(s"[WARNING] $s"))
  }
  val consoleToLogging = Embed[Logging,Console].toAlgebra(c => implicit m => new ConsoleLogging( c ) )

  val testLCKV = consoleToLogging( consoleTest andThen idToKVState ) and testCKV

  prg3.run( testLCKV ).run( Map( "user" -> "password" ) ).value
  prg3.run( testLCKV ).run( Map( "test" -> "test" ) ).value

  val LCKVtoCKV = Embed[LCKV,CKV].toInterpreter(iCKV => implicit m => consoleToLogging(iCKV.init.left andThen iCKV.nt) and[CKV] iCKV )


  val prg3ckv : Term[CKV,(String,Boolean)] = prg3.as[CKV]( LCKVtoCKV )

  prg3ckv.run( testCKV ).run( Map( "test" -> "test" ) ).value


  trait Parallel[M[_]] {
    def par[X]( l: List[M[X]] ) : M[List[X]]
  }

  def par[F[_[_]],X]( lt: List[Term[(Parallel and F)#fin,X]] ) : Term[(Parallel and F)#fin,List[X]] =
    Term[Parallel].mkFinal[F,List[X]](
      ParAlg =>
      ParAndF =>
      implicit monad =>
        ParAlg.par( lt.map( _.run( ParAndF ) ) )
    )


  type PCKV[M[_]] = (Parallel and CKV)#fin[M]
  def parPCKV[X](l: List[Term[PCKV,X]] ) : Term[PCKV,List[X]] = par[CKV,X]( l )

  type FKVState[A] = StateT[Future,Map[String,String],A]
  implicit val FKVStateMonad : Monad[FKVState] = StateT.stateTMonadState[Future,Map[String,String]]

  object futureParallel extends Parallel[FKVState] {
    def par[X](l: List[FKVState[X]]): FKVState[List[X]] =
      StateT.applyF( Future { state => Future.sequence( l.map( _.run(state ).map(_._2) ) ).map( (state,_) ) })
  }
  object stateToParallel extends ( KVState ~> FKVState) {
    def apply[A](fa: KVState[A]): FKVState[A] = StateT.apply( s => Future{ fa.run(s).value } )
  }

  val futurePCKVInterpreter = Final( futureParallel ) and (testCKV andThen stateToParallel)

  val prg4 = parPCKV( prg3ckv.as[PCKV] :: prg3ckv.as[PCKV] :: Nil )

  val result = Await.result( prg4.run( futurePCKVInterpreter ).run( Map("test" -> "test") ), Duration.Inf )

  object futureToId extends (FKVState ~> KVState) {
    def apply[A](fa: FKVState[A]): KVState[A] =
      State.apply( s => Await.result( fa.run(s), Duration.Inf ) )

  }

  val idPCKVInterpreter = futurePCKVInterpreter andThen futureToId

  val result2 = prg4.run( idPCKVInterpreter ).run( Map("test" -> "test") ).value

  println( result2 )

}
