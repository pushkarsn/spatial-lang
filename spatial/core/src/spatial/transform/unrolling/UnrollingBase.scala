package spatial.transform.unrolling

import argon.core._
import argon.transform.ForwardTransformer
import spatial.aliases._
import spatial.metadata._
import spatial.nodes._
import spatial.utils._
import org.virtualized.SourceContext
import spatial.aliases

trait UnrollingBase extends ForwardTransformer {
  /**
    * Valid bits - tracks all valid bits associated with the current scope to handle edge cases
    * e.g. cases where parallelization is not an even divider of counter max
    */
  var validBits: Seq[Exp[Bit]] = Nil
  def withValids[T](valids: Seq[Exp[Bit]])(blk: => T): T = {
    val prevValids = validBits
    validBits = valids
    val result = blk
    validBits = prevValids
    result
  }

  // Single global valid - should only be used in inner pipes - creates AND tree
  def globalValid: () => Exp[Bit] = () => {
    if (validBits.isEmpty) Bit.const(true)
    else spatial.lang.Math.reduceTree(validBits){(a,b) => Bit.and(a,b) }
  }

  // Sequence of valid bits associated with current unrolling scope
  def globalValids: Seq[Exp[Bit]] = if (validBits.nonEmpty) validBits else Seq(Bit.const(true))

  /**
    * Unroll numbers - gives the unroll index of each pre-unrolled (prior to transformer) index
    * Used to determine which duplicate a particular memory access should be associated with
    */
  var unrollNum: Map[Exp[Index], Int] = Map.empty
  def withUnrollNums[A](ind: Seq[(Exp[Index], Int)])(blk: => A): A = {
    val prevUnroll = unrollNum
    unrollNum ++= ind
    val result = blk
    unrollNum = prevUnroll
    result
  }

  /**
    * Memory duplicate substitution rules - gives a mapping from a pre-unrolled memory
    * and dispatch index to an unrolled memory instance
    */
  var memories: Map[(Exp[_], Int), Exp[_]] = Map.empty
  def withMemories[A](mems: Seq[((Exp[_],Int), Exp[_])])(blk: => A): A = {
    val prevMems = memories
    memories ++= mems
    val result = blk
    memories = prevMems
    result
  }

  /**
    * Clone functions - used to add extra rules (primarily for metadata) during unrolling
    * Applied directly after mirroring
    */
  var cloneFuncs: List[Exp[_] => Unit] = Nil
  def duringClone[T](func: Exp[_] => Unit)(blk: => T)(implicit ctx: SrcCtx): T = {
    val prevCloneFuncs = cloneFuncs
    cloneFuncs = cloneFuncs :+ func   // Innermost is executed last

    val result = blk
    cloneFuncs = prevCloneFuncs

    result
  }
  def inReduction[T](isInner: Boolean)(blk: => T): T = {
    duringClone{e => if (spatialConfig.enablePIR && !isInner) reduceType(e) = None }{ blk }
  }
  def inCycle[T](reduceTp: Option[ReduceFunction])(blk: => T): T = {
    duringClone{e => if (spatialConfig.enablePIR) reduceType(e) = reduceTp }{ blk }
  }


  var inHwScope: Boolean = false

  def unroll(stm: Stm, lanes: Unroller): List[Exp[_]] = stm match {
    case TP(lhs, rhs) => unroll(lhs, rhs, lanes)(lhs.ctx)
    case TTP(lhs,rhs) => throw new Exception("TTP not supported in Spatial!")
  }
  def unroll[T](lhs: Sym[T], rhs: Op[T], lanes: Unroller)(implicit ctx: SrcCtx): List[Exp[_]] = {
    logs(s"Duplicating $lhs = $rhs")
    lanes.duplicate(lhs, rhs)
  }

  override def transform[A:Type](lhs: Sym[A], rhs: Op[A])(implicit ctx: SrcCtx): Exp[A] = rhs match {
    case e:Hwblock =>
      inHwScope = true
      val lhs2 = super.transform(lhs,rhs)
      inHwScope = false
      lhs2
    case _ => super.transform(lhs, rhs)
  }

  override def mirror(lhs: Seq[Sym[_]], rhs: Def): Seq[Exp[_]] = rhs match {
    case op: Op[_] => Seq(cloneOp(lhs.head.asInstanceOf[Sym[Any]], op.asInstanceOf[Op[Any]]))
    case _ => super.mirror(lhs, rhs)
  }

  def cloneOp[A](lhs: Sym[A], rhs: Op[A]): Exp[A] = {
    def cloneOrMirror(lhs: Sym[A], rhs: Op[A])(implicit mA: Type[A], ctx: SrcCtx): Exp[A] = (lhs match {
      case Def(op: EnabledControlNode)  => op.mirrorAndEnable(this, globalValids)
      case Def(op: EnabledPrimitive[_]) => op.mirrorAndEnable(this, globalValid)
      case _ => rhs.mirrorNode(f).head
    }).asInstanceOf[Exp[A]]

    dbgs(c"Cloning $lhs = $rhs")
    //strMeta(lhs)

    val (lhs2, isNew) = transferMetadataIfNew(lhs){ cloneOrMirror(lhs, rhs)(mtyp(lhs.tp), lhs.ctx) }

    if (isNew) cloneFuncs.foreach{func => func(lhs2) }
    dbgs(c"Created ${str(lhs2)}")
    //strMeta(lhs2)

    /*if (cloneFuncs.nonEmpty) {
      dbgs(c"Cloning $lhs = $rhs")
      metadata.get(lhs).foreach{m => dbgs(c" - ${m._1}: ${m._2}") }
      dbgs(c"Created ${str(lhs2)}")
      metadata.get(lhs2).foreach{m => dbgs(c" - ${m._1}: ${m._2}") }
    }*/

    lhs2
  }


  /**
    * Helper objects for unrolling
    * Tracks multiple substitution contexts in 'contexts' array
    **/
  trait Unroller {
    type MemContext = ((Exp[_],Int), Exp[_])

    def inds: Seq[Bound[Index]]
    def Ps: Seq[Int]

    def P: Int = Ps.product
    def N: Int = Ps.length
    def size: Int = P
    def prods: List[Int] = List.tabulate(N){i => Ps.slice(i+1,N).product }
    def parAddr(p: Int): List[Int] = List.tabulate(N){d => (p / prods(d)) % Ps(d) }

    def contexts: Array[ Map[Exp[Any],Exp[Any]] ]

    private var __memContexts: Option[Array[Seq[MemContext]]] = None
    def memContexts: Array[Seq[MemContext]] = {
      if (__memContexts.isEmpty) { __memContexts = Some(Array.fill(P)(Nil)) }
      __memContexts.get
    }

    private var __valids: Option[ Seq[Seq[Exp[Bit]]] ] = None
    protected def createLaneValids(): Seq[Seq[Exp[Bit]]]

    final def valids: Seq[Seq[Exp[Bit]]] = __valids.getOrElse{
      val vlds = createLaneValids()
      __valids = Some(vlds)
      vlds
    }

    def inLanes[A](lns: Seq[Int])(block: Int => A): Seq[A] = lns.map{ln => inLane(ln)(block(ln)) }

    def inLane[A](i: Int)(block: => A): A = {
      val save = subst
      val addr = parAddr(i)
      withMemories(memContexts(i)) {
        withUnrollNums(inds.zip(addr)) {
          withSubstRules(contexts(i)) {
            withValids(valids(i)) {
              val result = block
              // Retain only the substitutions added within this scope
              contexts(i) ++= subst.filterNot(save contains _._1)
              result
            }
          }
        }
      }
    }

    def map[A](block: Int => A): List[A] = List.tabulate(P){p => inLane(p){ block(p) } }

    def foreach(block: Int => Unit) { map(block) }

    // --- Each unrolling rule should do at least one of three things:

    // 1. Split a given vector as the substitution for the single original symbol
    def duplicate[A](s: Sym[A], d: Op[A]): List[Exp[_]] = map{_ =>
      val s2 = cloneOp(s, d)
      register(s -> s2)
      s2
    }
    // 2. Make later stages depend on the given substitution across all lanes
    // NOTE: This assumes that the node has no meaningful return value (i.e. all are Pipeline or Unit)
    // Bad things can happen here if you're not careful!
    def split[T:Type](orig: Sym[T], vec: Exp[Vector[_]])(implicit ctx: SrcCtx): List[Exp[T]] = map{p =>
      val element = Vector.select[T](vec.asInstanceOf[Exp[Vector[T]]], p)
      register(orig -> element)
      element
    }
    def splitLanes[T:Type](lns: List[Int])(orig: Exp[_], vec: Exp[Vector[T]])(implicit ctx: SrcCtx): List[Exp[T]] = {
      lns.zipWithIndex.map{case (ln,i) =>
        inLane(ln){
          val element = Vector.select[T](vec.asInstanceOf[Exp[Vector[T]]], i)
          register(orig -> element)
          element
        }
      }
    }

    // 3. Create an unrolled mapping of symbol (orig -> unrolled) for each lane
    def unify[T](orig: Exp[T], unrolled: Exp[T]): List[Exp[T]] = {
      foreach{p => register(orig -> unrolled) }
      List(unrolled)
    }
    def unifyLanes[T](lns: Seq[Int])(orig: Exp[T], unrolled: Exp[T]): List[Exp[T]] = {
      inLanes(lns){p => register(orig -> unrolled) }
      List(unrolled)
    }

    def unifyUnsafe[A,B](orig: Exp[A], unrolled: Exp[B]): List[Exp[B]] = {
      foreach{p => registerUnsafe(orig, unrolled) }
      List(unrolled)
    }

    def duplicateMem(mem: Exp[_])(blk: Int => Seq[(Exp[_],Int)]): Unit = foreach{p =>
      val duplicates = blk(p)
      memContexts(p) ++= duplicates.map{case (mem2,d) => (mem,d) -> mem2 }
    }

    // Same symbol for all lanes
    def isCommon(e: Exp[_]): Boolean = contexts.map{p => f(e)}.forall{e2 => e2 == f(e)}
  }


  case class PartialUnroller(cchain: Exp[CounterChain], inds: Seq[Bound[Index]], isInnerLoop: Boolean) extends Unroller {
    // HACK: Don't unroll inner loops for CGRA generation
    val Ps: Seq[Int] = if (isInnerLoop && spatialConfig.enablePIR) inds.map{_ => 1}
                       else parFactorsOf(cchain).map{case Exact(c) => c.toInt }

    val fs: Seq[Boolean] = countersOf(cchain).map(isForever)

    val indices: Seq[Seq[Bound[Index]]]   = Ps.map{p => List.fill(p){ fresh[Index] }}
    val indexValids: Seq[Seq[Bound[Bit]]] = Ps.map{p => List.fill(p){ fresh[Bit] }}

    // Valid bits corresponding to each lane
    protected def createLaneValids(): Seq[Seq[Exp[Bit]]] = List.tabulate(P){p =>
      val laneIdxValids = indexValids.zip(parAddr(p)).map{case (vec,i) => vec(i)}
      laneIdxValids ++ validBits
    }

    // Substitution for each duplication "lane"
    val contexts = Array.tabulate(P){p =>
      val inds2 = indices.zip(parAddr(p)).map{case (vec, i) => vec(i) }
      Map.empty[Exp[Any],Exp[Any]] ++ inds.zip(inds2)
    }
  }



  case class FullUnroller(cchain: Exp[CounterChain], inds: Seq[Bound[Index]], isInnerLoop: Boolean) extends Unroller {
    val Ps: Seq[Int] = parFactorsOf(cchain).map{case Exact(c) => c.toInt }

    val indices: Seq[Seq[Const[Index]]] = countersOf(cchain).map{
      case Def(CounterNew(Exact(start),_,Exact(step),Exact(par))) =>
        List.tabulate(par.toInt){i => FixPt.int32s(BigDecimal(start + step*i)) }
    }
    val indexValids: Seq[Seq[Const[Bit]]] = indices.zip(countersOf(cchain)).map{
      case (is, Def(CounterNew(_,Exact(end),_,_))) =>
        is.map{case Exact(i) => Bit.const(i < end) }
    }

    protected def createLaneValids(): Seq[Seq[Exp[Bit]]] = List.tabulate(P){p =>
      val laneIdxValids = indexValids.zip(parAddr(p)).map{case (vec,i) => vec(i) }
      laneIdxValids ++ validBits
    }

    val contexts: Array[Map[Exp[Any], Exp[Any]]] = Array.tabulate(P){p =>
      val inds2 = indices.zip(parAddr(p)).map{case (vec, i) => vec(i) }
      Map.empty[Exp[Any],Exp[Any]] ++ inds.zip(inds2)
    }
  }

  case class UnitUnroller(isInnerLoop: Boolean) extends Unroller {
    val Ps: Seq[Int] = Seq(1)
    val inds: Seq[Bound[Index]] = Nil
    val indices: Seq[Seq[Const[Index]]] = Seq(Nil)
    val indexValids: Seq[Seq[Const[Bit]]] = Seq(Nil)
    protected def createLaneValids(): Seq[Seq[Exp[Bit]]] = Seq(Nil)
    val contexts: Array[Map[Exp[Any], Exp[Any]]] = Array.tabulate(1){_ => Map.empty[Exp[Any],Exp[Any]] }
  }

}


