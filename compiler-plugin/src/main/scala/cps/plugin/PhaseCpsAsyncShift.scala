package cps.plugin

import dotty.tools.dotc.*
import dotty.tools.dotc.core.Types.TypeRef
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.util.SrcPos
import core.{ Names, Types, * }
import core.Names.*
import core.Contexts.*
import core.Constants.*
import core.Annotations.*
import core.Decorators.*
import core.Symbols.*
import core.Types.*
import ast.tpd.*
import transform.{ Erasure, Inlining, Pickler, PruneErasedDefs }
import plugins.*

//TODO: merge with phaseSelect
class PhaseCpsAsyncShift(selectedNodes: SelectedNodes, shiftedSymbols: ShiftedSymbols)
    extends PluginPhase {

  override val phaseName = PhaseCpsAsyncShift.name

  // strange -
  override def allowsImplicitSearch = true
  override val runsAfter            = Set(PhaseCps.name)
  override val runsBefore           = Set(Erasure.name, PhaseCpsAsyncReplace.name)
  private var srcPos: SrcPos = null

  // override def run(using Context): Unit = {
  // TODO:
  //  check what async-shift needed in the current compilation unit
  //   -- generate one in the special class
  //   -- update the global cache, setting as key - signature of function or method, value - async tree
  // }

  /**
   * replaces symbols by transformed values from shiftedSymbols
   * @param tree
   * @param Context
   * @return
   */
  override def transformApply(tree: tpd.Apply)(using Context): Tree =
    // if (tree.symbol == "FutureAsyncMonad")
    // println(s"cpsAsyncShift::transformApply Symbol ${tree.symbol}")
    // println(s"cpsAsyncShift::transformApply ${tree.show}")
    // println(s"cpsAsyncShift::transformApply Info ${tree}")
    super.transformApply(tree)

  override def transformTemplate(tree: Template)(using Context): Tree =
    try transformTemplateInternal(tree)
    catch
      case ex: CpsTransformException =>
        report.error(ex.message, ex.pos)
        throw ex

  /**
   * looks for annotated functions, changes them and add to shiftedSymbols
   * @param tree
   * @param Context
   * @return
   */
  def transformTemplateInternal(tree: Template)(using Context): Tree = {
    println(s"cpsAsyncShift::transformTemplate: ${tree.symbol.name}, ${tree.tpe.show}")
    val annotationClass = Symbols.requiredClass("cps.plugin.annotation.makeCPS")
    var newMethods      = List.empty[DefDef]
    for (
      bodyTree <- tree.body
      if bodyTree.symbol.is(Flags.Method) /*&& generateCps */
    )
      bodyTree match
        case fun: DefDef
            if (!fun.symbol.isAnonymousFunction &&
              !fun.symbol.denot.getAnnotation(annotationClass).isEmpty) =>
          srcPos = fun.srcPos
          checkApplicableForMakeCPS(fun) match
            case Left(err) =>
              throw CpsTransformException(err, srcPos)
            case Right(_) => ()
          // create PolyType for a new Symbol info
          println(s"passedChecks::${fun.symbol.name}")
          val newSymbolInfo = generateNewFuncType(fun)
          val newFunName    = (fun.symbol.name.debugString + "$cps").toTermName
          val newFunSymbol  =
            Symbols.newSymbol(
              fun.symbol.owner,
              newFunName,
              fun.symbol.flags | Flags.Synthetic,
              newSymbolInfo
            )
          println(s"passedSymbolCreation::${fun.symbol.name}")
          // create new rhs
          val funcParams    = getHighOrderArgs(fun)
          println(s"passedBodyTransformation::${fun.symbol.name}")
          val newMethod     =
            DefDef(
              newFunSymbol,
              newParamss => {
                println(s"newParamsDefDef=$newParamss")
                val newTypeParams   = newParamss.head
                val newNormalParams = newParamss.tail.head
                // create new func body
                val transformedRhs  =
                  transformFunсBody(fun.rhs, funcParams, newTypeParams, newNormalParams)
                // create new paramss
                TransformUtil
                  .substParams(
                    transformedRhs,
                    filterParamsValDef(fun.paramss),
                    newParamss.flatten
                  )
                  .changeOwner(fun.symbol, newFunSymbol)
              }
            )
          shiftedSymbols.addAsyncShift(fun.symbol, newMethod)
          newMethods = newMethod :: newMethods
        case _ => ()

    val retval = if (newMethods.isEmpty) {
      super.transformTemplate(tree)
    } else {
      cpy.Template(tree)(body = tree.body ++ newMethods)
    }
    retval
  }

  def generateNewFuncType(f: DefDef)(using Context): Type =
    val args = f.paramss
    val typeArgs:         List[TypeDef]  = filterParamsTypeDef(args)
    val normalArgs:       List[ValDef]   = filterParamsValDef(args)
    val typeParamNames:   List[TypeName] = typeArgs.map(_.name)
    val normalParamNames: List[TermName] = normalArgs.map(_.name)
    // create new return type with type params
    PolyType(List("F".toTypeName, "C".toTypeName) ++ typeParamNames)(
      // bounds for the type parameters
      pt => {
        // F[_]
        val hkTypeLambda = HKTypeLambda.any(1)
        // C <: CpsMonadContext[F]
        val appliedType  = AppliedType(
          Symbols
            .requiredClassRef("cps.CpsMonadContext"),
          List(pt.newParamRef(0))
        )
        List(
          TypeBounds(defn.NothingType, hkTypeLambda),
          TypeBounds(defn.NothingType, appliedType)
        ) ++ typeArgs.map(_.rhs.tpe.asInstanceOf[TypeBounds])
      },
      pt => {
        val mtParamTypes = List(
          AppliedType(
            TypeRef(Symbols.requiredClassRef("cps.CpsMonad"), "Aux".toTermName),
            List(pt.newParamRef(0), pt.newParamRef(1))
          )
        ) ++ normalArgs.map(_.tpt.tpe.widen)
        val mtReturnType = pt.newParamRef(0).appliedTo(f.symbol.info.widen)
        MethodType("am".toTermName :: normalParamNames)(
          _ => mtParamTypes,
          _ => mtReturnType
        )
      }
    )

  // Hight-level description of generation of async-shifted version of function
  //  val typeParams = if (haveTypeParams) paramss.head else Nil
  //  val normalArgs = if (haveTypeParams) args.tail else args
  //  Apply(
  //    TypeApply(
  //          ref(Symbols.requiredMetod("cps.cpsAsyncApply")),
  //          List[
  //            task - print and look.
  //            TypeTree(F[_],TypeParam,),
  //          ]
  //    ),
  //      Task:  add parameter to already existing function.
  //    List(
  //      ValDef(am, TypeTree(,,,)),
  //      ValDef(f, TypeTree(AppliedType(defn.ContextFunctionSymbol,))
  //    )
  //  )
  //   example:
  //   map[A,B](collection:List[A])(f: A => B): List[B] =  doSomething
  //   map$cps[F[_],C <: CpsMonadContext[F],A,B](am: CpsMonad.Aux[F,C])(collection:List[A])(f: A => B): F[List[B]] = {
  //      cpsAsyncApply[F,List[B],C](am,
  //           mt = ContextMethodType(....)
  //           Lambda(mt, tss => transformedBody(f) )
  //      )
  //   }
  //
  //   add type-params
  //      new typr of DefDef:  if its PolyType, - copy PolyType and add type parameter
  //                           MethodType - create PolyType with type-params and MethodType

  // original
  // class Functor[F[_]] {
  //    def map[A, B](f: A => B): F[A] => F[B]
  // }
  // expanded
  // class Functor[F <: Lambda1] {
  //    def map[A, B](f: A => B): F { type $hkArg$0 = A } # Apply  =>  F { type $hkArg$0 = B } # Apply
  // }

  //   transformedBody(f) = {
  //      Apply(f, ...)  =>  await[[F,result-typr-of-apply,F]](f, ....)
  //      or throw unimplemented instead await
  //

  //  DefDef( ....   rhs = cpsAsyncShift ....  )

  /**
   * transform rhs of the annotated function
   * @param tree
   * @param Context
   * @return
   */
  def transformFunсBody(
    tree:          Tree,
    funcParams:    List[ValDef],
    newTypeParams: List[Tree],
    newParams:     List[Tree]
  )(using Context): Tree =
    // val finalResType = tree.tpe.finalResultType
    // if isFunc(finalResType) then transformInnerFunction(tree)
    // else
    val typeParamF = newTypeParams.head
    val typeParamC = newTypeParams.tail.head
    val paramAm    = newParams.head
    val methodName = "apply".toTermName
    val mtt        =
      ContextualMethodType(List("C".toTermName))(
        mt => List(typeParamC.tpe),
        mt => tree.tpe.widen
      )
    // f: C ?=> T
    val lambda     = Lambda(
      mtt,
      // get context
      contextParams => {
        val mapper = new TreeMap() {
          override def transform(tree: Tree)(using Context): Tree =
            tree match
              case Apply(TypeApply(Select(f @ Ident(fname), methodName), targs), args)
                  if (funcParams.exists(_.symbol == f.symbol)) =>
                insertAwait(tree, newParams, contextParams.head)
              case Apply(Select(f, methodName), args)
                  if (funcParams.exists(_.symbol == f.symbol)) =>
                insertAwait(tree, newParams, contextParams.head)
              case f: Ident if (funcParams.exists(_.symbol == f.symbol)) =>
                throw new CpsTransformException("Function is not invoked", srcPos)
              case _ => super.transform(tree)
        }
        val body   = mapper.transform(tree)
        body
      }
    )
    // create cpsAsyncApply from awaited function
    Apply(
      TypeApply(
        ref(Symbols.requiredMethod("cps.plugin.cpsAsyncApply")),
        List(TypeTree(typeParamF.tpe), TypeTree(tree.tpe.widen), TypeTree(typeParamC.tpe))
      ),
      List(paramAm, lambda)
    )

  def insertAwait(tree: Tree, typeParams: List[Tree], monadCtx: Tree)(using
    Context
  ): Tree =
    val tF = typeParams.head
    Apply(
      Apply(
        TypeApply(
          // F[_],T, F[_]
          ref(Symbols.requiredMethod("cps.await")),
          List(tF, TypeTree(tree.tpe.widen), tF)
        ),
        List(tree)
      ),
      List(
        // using ctx: CpsMonadContext[F], conversion: CpsMonadConversion[F,G]
        monadCtx,
        TypeApply(
          ref(Symbols.requiredMethod("cps.CpsMonadConversion.identityConversion")),
          List(tF)
        )
      )
    )

  /**
   * transform a function which is returned from the high-order annotated
   * function
   */
  // def transformInnerFunction(tree: Tree)(using Context): Tree =
  //   tree match
  //     // TODO: create a check for inline function
  //     case Block((innerFunc: DefDef) :: Nil, expr) => // unpack inner function
  //       Block(List(transformInnerFunction(innerFunc)), expr)
  //     case t: DefDef => // create a transformed copy of original inner function
  //       val rhs            = t.rhs
  //       val transformedRHS = transformInnerFunction(rhs)
  //       cpy.DefDef(t)(t.name, t.paramss, t.tpt, transformedRHS)
  //     case Block(stats, expr: Apply) => // transform inner function
  //       val newExpr = transformFunсBody(expr, List())
  //       Block(stats, newExpr)

  def checkApplicableForMakeCPS(tree: DefDef)(using Context): Either[String, Unit] =
    // check ValDef input params
    if !isHightOrderByArg(tree) then
      Left(
        "Object annotated with cps.plugin.annotation.makeCPS has to be a high-order function"
      )
    else // check the return type
    if isFunc(tree.tpe.finalResultType) then
      Left("Unsupported type of function. The return type must not be a function")
    else Right(())

  def getHighOrderArgs(tree: DefDef)(using Context): List[ValDef] =
    val valDefs: List[ValDef] = filterParamsValDef(tree.paramss)
    val funcParams = valDefs.filter(p => isFunc(p.tpt.tpe))
    funcParams

  def isHightOrderByArg(tree: DefDef)(using Context): Boolean =
    // check ValDef input params
    val funcParams = getHighOrderArgs(tree)
    funcParams.nonEmpty

  def filterParamsTypeDef(params: List[ParamClause]): List[TypeDef] =
    val ps = params.flatten[ValDef | TypeDef]
    ps.collect { case v: TypeDef => v }

  def filterParamsValDef(params: List[ParamClause]): List[ValDef] =
    val ps = params.flatten[ValDef | TypeDef]
    ps.collect { case v: ValDef => v }

  def isFunc(t: Type)(using Context): Boolean =
    val retval = t match
      case _: AppliedType
          if (defn.isFunctionType(t) ||
            defn.isContextFunctionType(t)) =>
        true
      case _: MethodType => true
      case _: PolyType => true
      case _ => false
    retval

}

object PhaseCpsAsyncShift {
  val name: String = "rssh.cpsAsyncShift"
}
