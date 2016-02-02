# Tagless

**Tagless is a project to lift tagless monads into an enterprise grade reactive design pattern.**

Compared to tagful Free Monads:

Pros:

* No GADT woes
* No Squigglies in IntelliJ

Cons:

* Entirely untested
* It might turn out not to be a good idea

The idea came from this blog post: https://pchiusano.github.io/2014-05-20/scala-gadts.html.

## Example

See: [test.scala](https://github.com/vtoro/tagless/blob/master/src/main/scala/test.scala)

# Tutorial

Imports:
```scala
import language.higherKinds
import vtoro.tagless._
import vtoro.tagless.Interpreter.and
import cats._
import cats.data._
import cats.syntax.flatMap._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import cats.std.future._
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executor
implicit val ec : ExecutionContext = ExecutionContext.fromExecutor( new Executor {
  def execute(command: Runnable): Unit = command.run()
})
```

### Making an algebra

An algebra is just a trait of kind ```_[_[_]]```, that is, a trait parameterized with a type constructor.
The type constructor will finally be replaced with some Monad when it comes time to run a program made out of 
Terms from this algebra.

For example, a simple console algebra:

```scala
trait Console[M[_]] {
  def getLine : M[String]
  def putLine( s: String ) : M[Unit]
}
// defined trait Console
```

We define two methods in the algebra, one to get a line from the console returning ```M[String]``` and one to put a line to the console returning ```M[Unit]```.

### Terms

To create a program from this algebra we need Terms, to create a term we use ```Term[Algebra].mkTerm( f )``` where f is a 
function from ```Algebra[M] => M[X]``` forall ```M```. That is it takes any concretization of the abstract algebra and returns a 
value wrapped in what ever was chosen as the specified context(```M```). This returns a ```Term[Algebra,X]``` where X is specified by the return type of f.
  
For the console algebra we can define two helper functions to create terms like this:

```scala
def getLine = Term[Console].mkTerm( _.getLine )
// getLine: vtoro.tagless.Term[Console,String]

def putLine( s : String ) = Term[Console].mkTerm( _.putLine( s ) )
// putLine: (s: String)vtoro.tagless.Term[Console,Unit]
```

### Composing a program

Term has(/is) a Monad, so you can build a program using for comprehension:

```scala
val prg1 = for {
  _ <- putLine( "What is your Name?" )
  name <- getLine
  _ <- putLine( s"Hello $name" )
} yield name 
// prg1: vtoro.tagless.Term[Console,String] = vtoro.tagless.Term$$anon$3@4c3e390f
```

Notice that the program is also just a Term.

### Making an interpreter

All interepreters need a concretization of the abstract algebra.

```scala
object TestConsole extends Console[Id] {
  def getLine : Id[String] = "test"
  def putLine( s : String ) = println(s)
}
// defined object TestConsole

val consoleTest = Interpreter( TestConsole )
// consoleTest: vtoro.tagless.Interpreter[Console,cats.Id] = InterpreterInit(TestConsole$@785d80fa)
```

here we're defining an interpretation of the algebra into the context ```Id``` which is just ```Id[A] = A```, that is it has no special context.
 
### Running the program

To run the program we simply call run and supply the interpreter.

```scala
prg1.run( consoleTest )
// What is your Name?
// Hello test
// res0: cats.Id[String] = test
```

notice that the return type is ```Id[String]```. So we're not so much running a program then interpreting/compiling it into another context. Here that context happens to be such
that side-effect will be instantly evaluated.

We can also map that ```Id``` into a ```Eval```, using a natural transformation and binding that transformation into the Interpreter with ```andThen```

```scala
object idToEval extends (Id ~> Eval) {
  override def apply[A](fa: Id[A]): Eval[A] = Eval.later{ fa }
}
// defined object idToEval

val evalPrg1 = prg1.run( consoleTest andThen idToEval )
// What is your Name?
// evalPrg1: cats.Eval[String] = cats.Eval$$anon$8@284a8abb
```

Notice that it did execute the first action of the program before halting the Eval, this is because we need to evaluate the first Id value to get the first Eval value.
But after we have the first Eval value, it then use flatMap to bind the actions together, and it halts until we call .value on it

```scala
evalPrg1.value
// Hello test
// res1: String = test
```


### Pairing algebras

Lets say we want to have a program that uses a key store as well as a console.

```scala
trait KVStore[M[_]] {
  def put( key: String, value: String ) : M[Unit]
  def get( key: String ) : M[Option[String]]
}
// defined trait KVStore

def put( key: String, value: String ) = Term[KVStore].mkTerm( _.put( key, value ) )
// put: (key: String, value: String)vtoro.tagless.Term[KVStore,Unit]

def get( key: String ) = Term[KVStore].mkTerm( _.get( key ) )
// get: (key: String)vtoro.tagless.Term[KVStore,Option[String]]
```

To combine two algebras we define an algebra/interpreter pair that combines both algebras. And two embeddings, from ```Console``` to ```CKV``` and from ```KVStore``` to ```CKV```-

```scala
type CKV[X[_]] = (Console and KVStore)#pair[X]
// defined type alias CKV

val ConsoleToCKV : Embed[Console,CKV] = Embed.left
// ConsoleToCKV: vtoro.tagless.Embed[Console,CKV] = vtoro.tagless.Embed$$anon$1@44d9d993

val KVStoreToCKV : Embed[KVStore,CKV] = Embed.right
// KVStoreToCKV: vtoro.tagless.Embed[KVStore,CKV] = vtoro.tagless.Embed$$anon$2@6a062115
```

To turn a Term of algebra ```A``` into a Term of another bigger/encompassing algebra ```B``` we use ```Term[A,X].as[B](Embed[A,B]) : Term[B,X]```.

```scala
val prg2 : Term[CKV,(String,Boolean)] = for {
  user <- prg1.as( ConsoleToCKV )
  maybePwd <- get( user ).as( KVStoreToCKV )
  authenticated <-
    (maybePwd.fold
      ( putLine("You need to create an account!") >> Term.pure[Console,Boolean](false) )
      ( password => putLine("Password:") >> getLine.map( _ == password ) )
    ).as( ConsoleToCKV )
} yield (user, authenticated)
// prg2: vtoro.tagless.Term[CKV,(String, Boolean)] = vtoro.tagless.Term$$anon$3@4efeaa99
```

As before lets define an interpreter for our KVStore, for this we'll use State

```scala
type KVState[X] = State[Map[String,String],X]
// defined type alias KVState

object TestKVStore extends KVStore[KVState] {
  def put( key: String, value: String ) : KVState[Unit] = State.modify { s => s + (key -> value) }
  def get( key: String ) : KVState[Option[String]] = State.get.map( _.get(key) )
}
// defined object TestKVStore

val testKV = Interpreter( TestKVStore )
// testKV: vtoro.tagless.Interpreter[KVStore,KVState] = InterpreterInit(TestKVStore$@3bc7c4ee)
```

We now have an interpreter from ```Console``` to ```Id``` and from ```KVStore``` to ```KVState```
We can also make a pair out of two interpreters with ``` and ```, but they must be defined for the same target type constructor
We can now use a natural transformation to go from ```Id``` to ```KVState```, and after that we can do the pairing.

```scala
object IdToKVState extends (Id ~> KVState) {
  def apply[A](fa: Id[A]): KVState[A] = State.pure(fa)
}
// defined object IdToKVState

val testCKV : Interpreter[CKV,KVState] = (consoleTest andThen IdToKVState) and testKV
// testCKV: vtoro.tagless.Interpreter[CKV,KVState] = InterpreterInit(InterpreterPair(InterpreterNT(TestConsole$@785d80fa,cats.arrow.NaturalTransformation$$anon$1@1a57fafe),InterpreterInit(TestKVStore$@3bc7c4ee)))
```

and now were ready to run our program

```scala
prg2.run( testCKV ).run( Map( "user" -> "password" ) ).value
// What is your Name?
// Hello test
// You need to create an account!
// res2: (Map[String,String], (String, Boolean)) = (Map(user -> password),(test,false))

prg2.run( testCKV ).run( Map( "test" -> "test" ) ).value
// What is your Name?
// Hello test
// Password:
// res3: (Map[String,String], (String, Boolean)) = (Map(test -> test),(test,true))
```


### Embedding algebras

Lets now add logging as well

```scala
trait Logging[M[_]] {
  def debug( s:String ) : M[Unit]
  def warning( s: String ) : M[Unit]
}
// defined trait Logging

def debug( s: String ) = Term[Logging].mkTerm( _.debug(s) )
// debug: (s: String)vtoro.tagless.Term[Logging,Unit]

def warning( s: String ) = Term[Logging].mkTerm( _.warning(s) )
// warning: (s: String)vtoro.tagless.Term[Logging,Unit]

type LCKV[X[_]] = (Logging and CKV)#pair[X]
// defined type alias LCKV

val CKVtoLCKV : Embed[CKV,LCKV] = Embed.right[Logging,CKV]
// CKVtoLCKV: vtoro.tagless.Embed[CKV,LCKV] = vtoro.tagless.Embed$$anon$2@74f566e0

val LoggingToLCKV : Embed[Logging,LCKV] = Embed.left[Logging,CKV]
// LoggingToLCKV: vtoro.tagless.Embed[Logging,LCKV] = vtoro.tagless.Embed$$anon$1@3ec9696

val prg3 = for {
  userTuple <- prg2.as( CKVtoLCKV )
  _ <- if( userTuple._2 )
         debug(s"User: '${userTuple._1}' successfully authenticated!").as( LoggingToLCKV )
       else
         warning(s"User: '${userTuple._1}' was not authenticated!").as( LoggingToLCKV )
} yield userTuple
// prg3: vtoro.tagless.Term[LCKV,(String, Boolean)] = vtoro.tagless.Term$$anon$3@1f2621d8
```

but we want to do the logging to the console, and we specifically want to use the same console algebra as we used above

to do this we can define an embedding of ```Logging``` into ```Console```, first we define a new class that ```extends Logging[M]``` 
and takes a parameter of an interpreter of ```Console```, we then proxy the logging calls to the console interpreter, adding a log-level prefix.


```scala
class ConsoleLogging[M[_] : Monad](f: Interpreter[Console,M] ) extends Logging[M] {
  def debug(s: String): M[Unit] = f( _.putLine(s"[DEBUG] $s"))
  def warning(s: String): M[Unit] = f( _.putLine(s"[WARNING] $s"))
}
// defined class ConsoleLogging
```

We then turn it into an instance of ```Embed[Logging,Console]``` for this we can EmbedBuilder, and either  
toAlgebra which takes an ```Interpreter[A,M] => Monad[M] => B[M]``` and returns an ```Embed[A,B]``` or  
toInterpreter which takes an ```Interpreter[A,M] => Monad[M] => Interpreter[B,M]``` and returns an ```Embed[A,B]```  

```scala
val consoleToLogging = Embed[Logging,Console].toAlgebra(c => implicit m => new ConsoleLogging( c ) )
// consoleToLogging: vtoro.tagless.Embed[Logging,Console] = vtoro.tagless.EmbedBuilder$$anon$6@446cdc3f
```

we can now define an interpreter for ```LCKV```, and use it to run our new program
 
```scala
val testLCKV = consoleToLogging( consoleTest andThen IdToKVState ) and testCKV
// testLCKV: vtoro.tagless.Interpreter[[X[_]]vtoro.tagless.InterpreterPair[Logging,CKV,X],KVState] = InterpreterInit(InterpreterPair(InterpreterInit(ConsoleLogging@16b17b75),InterpreterInit(InterpreterPair(InterpreterNT(TestConsole$@785d80fa,cats.arrow.NaturalTransformation$$anon$1@1a57fafe),InterpreterInit(TestKVStore$@3bc7c4ee)))))

prg3.run( testLCKV ).run( Map( "user" -> "password" ) ).value
// What is your Name?
// Hello test
// You need to create an account!
// [WARNING] User: 'test' was not authenticated!
// res4: (Map[String,String], (String, Boolean)) = (Map(user -> password),(test,false))

prg3.run( testLCKV ).run( Map( "test" -> "test" ) ).value
// What is your Name?
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// res5: (Map[String,String], (String, Boolean)) = (Map(test -> test),(test,true))
```

we can also get rid of the Logging aspect of ```LCKV``` algebra, we already defined how we go from ```Logging``` to ```Console``` 
now we just need to go from ```LCKV``` to ```CKV```. 

To do that we define how we can take an Intepreter of ```CKV``` named and provide an interpreter of ```LCKV```.   
We start by taking the init of ```CKV``` which we know is a pair of ```Console``` and ```KeyStore```, we take the left interpreter, which is the ```Console``` interpreter, 
we then compose it with whatever natural transformations the ```CKV``` interpreter had, we use our previously defined ```logToConsole``` to transform that 
```Console``` interpreter into a ```Logging``` interpreter, and we then pair it up with the same ```CKV``` interpreter. 

  
```scala
val LCKVtoCKV : Embed[LCKV,CKV] = Embed[LCKV,CKV].toInterpreter(iCKV => implicit m => consoleToLogging(iCKV.init.left andThen iCKV.nt) and[CKV] iCKV )
// LCKVtoCKV: vtoro.tagless.Embed[LCKV,CKV] = vtoro.tagless.EmbedBuilder$$anon$7@cbaa398
```
 
we can now take any ```Term[LCKV,_]``` and embed it in just ```CKV```, and run it with just a ```CKV``` interpreter
 
```scala
val prg3ckv = prg3.as( LCKVtoCKV )
// prg3ckv: vtoro.tagless.Term[CKV,(String, Boolean)] = vtoro.tagless.Term$$anon$4@2fedcd07

prg3ckv.run( testCKV ).run( Map( "test" -> "test" ) ).value
// What is your Name?
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// res6: (Map[String,String], (String, Boolean)) = (Map(test -> test),(test,true))
```

### Algebras that reference other algebras

Given a parallel algebra:

```scala
trait Parallel[M[_]] {
  def par[X]( l: List[M[X]] ) : M[List[X]]
}
// defined trait Parallel
```

now this algebra wouldn't be very interesting if the terms in the List that its working with don't come from other algebras, luckily theres a way to do just that

For this we use ```mkFinal```, which has a signature like this: 

```scala
Term[A].mkFinal[F,X]( A[M]=> Interpreter[(A and F)#fin,M] => Monad[M] => M[X] ) : Term[(A and F)#fin,X]
```

it takes an algebra ```A```that will reference another algebra ```F``` and to produce Terms you can use the original algebra(```A```), 
interpreter of the combined algebra(```(A and F)#fin```), and a monad for the target ```M```

```scala
def par[F[_[_]],X]( lt: List[Term[(Parallel and F)#fin,X]] ) : Term[(Parallel and F)#fin,List[X]] =
  Term[Parallel].mkFinal[F,List[X]](
    ParAlg =>
    ParAndF =>
    implicit monad =>
      ParAlg.par( lt.map( _.run( ParAndF ) ) )
  )
// par: [F[_[_]], X](lt: List[vtoro.tagless.Term[[X[_]]vtoro.tagless.FinalPair[Parallel,F,X],X]])vtoro.tagless.Term[[X[_]]vtoro.tagless.FinalPair[Parallel,F,X],List[X]]
```

note that what this returns is a term defined for a different kind of a pair then the previous ```#pair```, its a ```FinalPair``` denoted by the suffix ```#fin```, 
which means that the left hand side, here ```Parallel```, cannot be mapped with a natural transformation. This is because the target type constructor 
of the right side of the pair needs to be something that can be fed as an input to the left sides algebra, but both sides still need to evaluate to the same target, 
leaving the only option to the left side to be just the plain algebra, without natural transformations. 

we can now proceed to create some auxiliary definitions, like our target monads type: ```FKVState```
```scala
type PCKV[M[_]] = (Parallel and CKV)#fin[M]
// defined type alias PCKV

val CKVtoPCKV : Embed[CKV,PCKV] = Embed.finRight[Parallel,CKV]
// CKVtoPCKV: vtoro.tagless.Embed[CKV,PCKV] = vtoro.tagless.Embed$$anon$3@13600b15

type FKVState[A] = StateT[Future,Map[String,String],A]
// defined type alias FKVState

implicit val FKVStateMonad : Monad[FKVState] = StateT.stateTMonadState[Future,Map[String,String]]
// FKVStateMonad: cats.Monad[FKVState] = cats.data.StateTInstances$$anon$1@34f8718d

def parPCKV[X](l: List[Term[PCKV,X]] ) : Term[PCKV,List[X]] = par[CKV,X]( l )
// parPCKV: [X](l: List[vtoro.tagless.Term[PCKV,X]])vtoro.tagless.Term[PCKV,List[X]]
```

we need to line up our types so we need
an interpreter```Parallel[FKVState]```, and natural tranformation from ```KVState``` to ```FKVState```
 

```scala
object futureParallel extends Parallel[FKVState] {
  def par[X](l: List[FKVState[X]]): FKVState[List[X]] =
    StateT.applyF( Future { state => Future.sequence( l.map( _.run(state ).map(_._2) ) ).map( (state,_) ) })
}
// defined object futureParallel

object stateToParallel extends ( KVState ~> FKVState) {
  def apply[A](fa: KVState[A]): FKVState[A] = StateT.apply( s => Future{ fa.run(s).value } )
}
// defined object stateToParallel
```

and then we can create our ```PCKV``` interpreter, for this we use an auxiliary ```Final```-builder
```scala
val futurePCKVInterpreter = Final( futureParallel ) and (testCKV andThen stateToParallel)
// futurePCKVInterpreter: vtoro.tagless.Interpreter[[γ[_$2]]vtoro.tagless.FinalPair[Parallel,CKV,γ],FKVState] = InterpreterInit(FinalPair(futureParallel$@740dca7d,InterpreterNT(InterpreterPair(InterpreterNT(TestConsole$@785d80fa,cats.arrow.NaturalTransformation$$anon$1@1a57fafe),InterpreterInit(TestKVStore$@3bc7c4ee)),cats.arrow.NaturalTransformation$$anon$1@3594380b),cats.data.StateTInstances$$anon$1@34f8718d))
```

a simple program in ```PCKV```

```scala
  val prg4 = parPCKV( prg3ckv.as(CKVtoPCKV) :: prg3ckv.as(CKVtoPCKV) :: Nil )
// prg4: vtoro.tagless.Term[PCKV,List[(String, Boolean)]] = vtoro.tagless.TermBuilder$$anon$8@705736a4
```

running it, and then waiting for the result

```scala
Await.result( prg4.run( futurePCKVInterpreter ).run( Map("test" -> "test") ), Duration.Inf )
// What is your Name?
// What is your Name?
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// res7: (Map[String,String], List[(String, Boolean)]) = (Map(test -> test),List((test,true), (test,true)))
```

even though ```PCKV``` is final, we can still add further natural transformations to the results of ```PCKV```, like from ```FKVState``` back to ```KVState``` via ```Await.result```

```scala
object futureToId extends (FKVState ~> KVState) {
  def apply[A](fa: FKVState[A]): KVState[A] =
    State.apply( s => Await.result( fa.run(s), Duration.Inf ) )
}
// defined object futureToId

val idPCKVInterpreter = futurePCKVInterpreter andThen futureToId
// idPCKVInterpreter: vtoro.tagless.Interpreter[[γ[_$2]]vtoro.tagless.FinalPair[Parallel,CKV,γ],KVState] = InterpreterNT(FinalPair(futureParallel$@740dca7d,InterpreterNT(InterpreterPair(InterpreterNT(TestConsole$@785d80fa,cats.arrow.NaturalTransformation$$anon$1@1a57fafe),InterpreterInit(TestKVStore$@3bc7c4ee)),cats.arrow.NaturalTransformation$$anon$1@3594380b),cats.data.StateTInstances$$anon$1@34f8718d),cats.arrow.NaturalTransformation$$anon$1@43de9d27)

prg4.run( idPCKVInterpreter ).run( Map("test" -> "test") ).value
// What is your Name?
// What is your Name?
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// Hello test
// Password:
// [DEBUG] User: 'test' successfully authenticated!
// res8: (Map[String,String], List[(String, Boolean)]) = (Map(test -> test),List((test,true), (test,true)))
```
