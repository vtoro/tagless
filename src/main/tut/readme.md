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
```tut:silent
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

```tut:book
trait Console[M[_]] {
  def getLine : M[String]
  def putLine( s: String ) : M[Unit]
}
```

We define two methods in the algebra, one to get a line from the console returning ```M[String]``` and one to put a line to the console returning ```M[Unit]```.

### Terms

To create a program from this algebra we need Terms, to create a term we use ```Term[Algebra].mkTerm( f )``` where f is a 
function from ```Algebra[M] => M[X]``` forall ```M```. That is it takes any concretization of the abstract algebra and returns a 
value wrapped in what ever was chosen as the specified context(```M```). This returns a ```Term[Algebra,X]``` where X is specified by the return type of f.
  
For the console algebra we can define two helper functions to create terms like this:

```tut:book
def getLine = Term[Console].mkTerm( _.getLine )
def putLine( s : String ) = Term[Console].mkTerm( _.putLine( s ) )
```

### Composing a program

Term has(/is) a Monad, so you can build a program using for comprehension:

```tut:book
val prg1 = for {
  _ <- putLine( "What is your Name?" )
  name <- getLine
  _ <- putLine( s"Hello $name" )
} yield name 
```

Notice that the program is also just a Term.

### Making an interpreter

All interepreters need a concretization of the abstract algebra.

```tut:book
object TestConsole extends Console[Id] {
  def getLine : Id[String] = "test"
  def putLine( s : String ) = println(s)
}
val consoleTest = Interpreter( TestConsole )
```

here we're defining an interpretation of the algebra into the context ```Id``` which is just ```Id[A] = A```, that is it has no special context.
 
### Running the program

To run the program we simply call run and supply the interpreter.

```tut:book
prg1.run( consoleTest )
```

notice that the return type is ```Id[String]```. So we're not so much running a program then interpreting/compiling it into another context. Here that context happens to be such
that side-effect will be instantly evaluated.

We can also map that ```Id``` into a ```Eval```, using a natural transformation and binding that transformation into the Interpreter with ```andThen```

```tut:book
object idToEval extends (Id ~> Eval) {
  override def apply[A](fa: Id[A]): Eval[A] = Eval.later{ fa }
}
val evalPrg1 = prg1.run( consoleTest andThen idToEval )
```

Notice that it did execute the first action of the program before halting the Eval, this is because we need to evaluate the first Id value to get the first Eval value.
But after we have the first Eval value, it then use flatMap to bind the actions together, and it halts until we call .value on it

```tut:book
evalPrg1.value
```


### Pairing algebras

Lets say we want to have a program that uses a key store as well as a console.

```tut:book

trait KVStore[M[_]] {
  def put( key: String, value: String ) : M[Unit]
  def get( key: String ) : M[Option[String]]
}

def put( key: String, value: String ) = Term[KVStore].mkTerm( _.put( key, value ) )
def get( key: String ) = Term[KVStore].mkTerm( _.get( key ) )
```

To combine two algebras we define an algebra/interpreter pair that combines both algebras. And two embeddings, from ```Console``` to ```CKV``` and from ```KVStore``` to ```CKV```-

```tut:book
type CKV[X[_]] = (Console and KVStore)#pair[X]
val ConsoleToCKV : Embed[Console,CKV] = Embed.left
val KVStoreToCKV : Embed[KVStore,CKV] = Embed.right
```

To turn a Term of algebra ```A``` into a Term of another bigger/encompassing algebra ```B``` we use ```Term[A,X].as[B](Embed[A,B]) : Term[B,X]```.

```tut:book
val prg2 : Term[CKV,(String,Boolean)] = for {
  user <- prg1.as( ConsoleToCKV )
  maybePwd <- get( user ).as( KVStoreToCKV )
  authenticated <-
    (maybePwd.fold
      ( putLine("You need to create an account!") >> Term.pure[Console,Boolean](false) )
      ( password => putLine("Password:") >> getLine.map( _ == password ) )
    ).as( ConsoleToCKV )
} yield (user, authenticated)
```

As before lets define an interpreter for our KVStore, for this we'll use State

```tut:book
type KVState[X] = State[Map[String,String],X]

object TestKVStore extends KVStore[KVState] {
  def put( key: String, value: String ) : KVState[Unit] = State.modify { s => s + (key -> value) }
  def get( key: String ) : KVState[Option[String]] = State.get.map( _.get(key) )
}

val testKV = Interpreter( TestKVStore )
```

We now have an interpreter from ```Console``` to ```Id``` and from ```KVStore``` to ```KVState```
We can also make a pair out of two interpreters with ``` and ```, but they must be defined for the same target type constructor
We can now use a natural transformation to go from ```Id``` to ```KVState```, and after that we can do the pairing.

```tut:book
object IdToKVState extends (Id ~> KVState) {
  def apply[A](fa: Id[A]): KVState[A] = State.pure(fa)
}

val testCKV : Interpreter[CKV,KVState] = (consoleTest andThen IdToKVState) and testKV
```

and now were ready to run our program

```tut:book
prg2.run( testCKV ).run( Map( "user" -> "password" ) ).value
prg2.run( testCKV ).run( Map( "test" -> "test" ) ).value

```


### Embedding algebras

Lets now add logging as well

```tut:book
trait Logging[M[_]] {
  def debug( s:String ) : M[Unit]
  def warning( s: String ) : M[Unit]
}

def debug( s: String ) = Term[Logging].mkTerm( _.debug(s) )
def warning( s: String ) = Term[Logging].mkTerm( _.warning(s) )

type LCKV[X[_]] = (Logging and CKV)#pair[X]
val CKVtoLCKV : Embed[CKV,LCKV] = Embed.right[Logging,CKV]
val LoggingToLCKV : Embed[Logging,LCKV] = Embed.left[Logging,CKV]

val prg3 = for {
  userTuple <- prg2.as( CKVtoLCKV )
  _ <- if( userTuple._2 )
         debug(s"User: '${userTuple._1}' successfully authenticated!").as( LoggingToLCKV )
       else
         warning(s"User: '${userTuple._1}' was not authenticated!").as( LoggingToLCKV )
} yield userTuple
```

but we want to do the logging to the console, and we specifically want to use the same console algebra as we used above

to do this we can define an embedding of ```Logging``` into ```Console```, first we define a new class that ```extends Logging[M]``` 
and takes a parameter of an interpreter of ```Console```, we then proxy the logging calls to the console interpreter, adding a log-level prefix.


```tut:book
class ConsoleLogging[M[_] : Monad](f: Interpreter[Console,M] ) extends Logging[M] {
  def debug(s: String): M[Unit] = f( _.putLine(s"[DEBUG] $s"))
  def warning(s: String): M[Unit] = f( _.putLine(s"[WARNING] $s"))
}
```

We then turn it into an instance of ```Embed[Logging,Console]``` for this we can EmbedBuilder, and either  
toAlgebra which takes an ```Interpreter[A,M] => Monad[M] => B[M]``` and returns an ```Embed[A,B]``` or  
toInterpreter which takes an ```Interpreter[A,M] => Monad[M] => Interpreter[B,M]``` and returns an ```Embed[A,B]```  

```tut:book
val consoleToLogging = Embed[Logging,Console].toAlgebra(c => implicit m => new ConsoleLogging( c ) )
```

we can now define an interpreter for ```LCKV```, and use it to run our new program
 
```tut:book
val testLCKV = consoleToLogging( consoleTest andThen IdToKVState ) and testCKV

prg3.run( testLCKV ).run( Map( "user" -> "password" ) ).value
prg3.run( testLCKV ).run( Map( "test" -> "test" ) ).value
```

we can also get rid of the Logging aspect of ```LCKV``` algebra, we already defined how we go from ```Logging``` to ```Console``` 
now we just need to go from ```LCKV``` to ```CKV```. 

To do that we define how we can take an Intepreter of ```CKV``` named and provide an interpreter of ```LCKV```.   
We start by taking the init of ```CKV``` which we know is a pair of ```Console``` and ```KeyStore```, we take the left interpreter, which is the ```Console``` interpreter, 
we then compose it with whatever natural transformations the ```CKV``` interpreter had, we use our previously defined ```logToConsole``` to transform that 
```Console``` interpreter into a ```Logging``` interpreter, and we then pair it up with the same ```CKV``` interpreter. 

  
```tut:book
val LCKVtoCKV : Embed[LCKV,CKV] = Embed[LCKV,CKV].toInterpreter(iCKV => implicit m => consoleToLogging(iCKV.init.left andThen iCKV.nt) and[CKV] iCKV )
```
 
we can now take any ```Term[LCKV,_]``` and embed it in just ```CKV```, and run it with just a ```CKV``` interpreter
 
```tut:book
val prg3ckv = prg3.as( LCKVtoCKV )

prg3ckv.run( testCKV ).run( Map( "test" -> "test" ) ).value
```

### Algebras that reference other algebras

Given a parallel algebra:

```tut:book
trait Parallel[M[_]] {
  def par[X]( l: List[M[X]] ) : M[List[X]]
}
```

now this algebra wouldn't be very interesting if the terms in the List that its working with don't come from other algebras, luckily theres a way to do just that

For this we use ```mkFinal```, which has a signature like this: 

```scala
Term[A].mkFinal[F,X]( A[M]=> Interpreter[(A and F)#fin,M] => Monad[M] => M[X] ) : Term[(A and F)#fin,X]
```

it takes an algebra ```A```that will reference another algebra ```F``` and to produce Terms you can use the original algebra(```A```), 
interpreter of the combined algebra(```(A and F)#fin```), and a monad for the target ```M```

```tut:book
def par[F[_[_]],X]( lt: List[Term[(Parallel and F)#fin,X]] ) : Term[(Parallel and F)#fin,List[X]] =
  Term[Parallel].mkFinal[F,List[X]](
    ParAlg =>
    ParAndF =>
    implicit monad =>
      ParAlg.par( lt.map( _.run( ParAndF ) ) )
  )
```

note that what this returns is a term defined for a different kind of a pair then the previous ```#pair```, its a ```FinalPair``` denoted by the suffix ```#fin```, 
which means that the left hand side, here ```Parallel```, cannot be mapped with a natural transformation. This is because the target type constructor 
of the right side of the pair needs to be something that can be fed as an input to the left sides algebra, but both sides still need to evaluate to the same target, 
leaving the only option to the left side to be just the plain algebra, without natural transformations. 

we can now proceed to create some auxiliary definitions, like our target monads type: ```FKVState```
```tut:book
type PCKV[M[_]] = (Parallel and CKV)#fin[M]
val CKVtoPCKV : Embed[CKV,PCKV] = Embed.finRight[Parallel,CKV]
type FKVState[A] = StateT[Future,Map[String,String],A]
implicit val FKVStateMonad : Monad[FKVState] = StateT.stateTMonadState[Future,Map[String,String]]
def parPCKV[X](l: List[Term[PCKV,X]] ) : Term[PCKV,List[X]] = par[CKV,X]( l )
```

we need to line up our types so we need
an interpreter```Parallel[FKVState]```, and natural tranformation from ```KVState``` to ```FKVState```
 

```tut:book
object futureParallel extends Parallel[FKVState] {
  def par[X](l: List[FKVState[X]]): FKVState[List[X]] =
    StateT.applyF( Future { state => Future.sequence( l.map( _.run(state ).map(_._2) ) ).map( (state,_) ) })
}

object stateToParallel extends ( KVState ~> FKVState) {
  def apply[A](fa: KVState[A]): FKVState[A] = StateT.apply( s => Future{ fa.run(s).value } )
}
```

and then we can create our ```PCKV``` interpreter, for this we use an auxiliary ```Final```-builder
```tut:book
val futurePCKVInterpreter = Final( futureParallel ) and (testCKV andThen stateToParallel)
```

a simple program in ```PCKV```

```tut:book
  val prg4 = parPCKV( prg3ckv.as(CKVtoPCKV) :: prg3ckv.as(CKVtoPCKV) :: Nil )
```

running it, and then waiting for the result

```tut:book
Await.result( prg4.run( futurePCKVInterpreter ).run( Map("test" -> "test") ), Duration.Inf )
```

even though ```PCKV``` is final, we can still add further natural transformations to the results of ```PCKV```, like from ```FKVState``` back to ```KVState``` via ```Await.result```

```tut:book
object futureToId extends (FKVState ~> KVState) {
  def apply[A](fa: FKVState[A]): KVState[A] =
    State.apply( s => Await.result( fa.run(s), Duration.Inf ) )
}

val idPCKVInterpreter = futurePCKVInterpreter andThen futureToId

prg4.run( idPCKVInterpreter ).run( Map("test" -> "test") ).value
```
