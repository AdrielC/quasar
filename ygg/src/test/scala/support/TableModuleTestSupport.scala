package ygg.tests

import scalaz._, Scalaz._
import ygg._, common._, data._, json._, table._
import TableModule._
import TestSupport._

abstract class TableQspec extends quasar.Qspec with TableModuleTestSupport {
  import SampleData._
  import trans._

  type ToSelf[A] = A => A
  type ASD       = Arbitrary[SampleData]

  class TableCommuteTest(f: Seq[JValue] => Seq[JValue], g: Table => Table) extends CommuteTest[Seq[JValue], Table] {
    def transformR(x: Seq[JValue])  = f(x)
    def transformS(x: Table)        = g(x)
    def rToS(x: Seq[JValue]): Table = fromJson(x)
    def sToR(x: Table): Seq[JValue] = toJson(x).copoint
  }

  implicit class SampleDataOps(private val sd: SampleData) {
    def fieldHead = sd.schema.get._2.head._1.head.get
    def fieldHeadName: String = fieldHead match {
      case JPathField(s) => s
      case _             => abort("non-field reached")
    }
    def fieldHeadIndex: Int = fieldHead match {
      case JPathIndex(s) => s
      case _             => abort("non-index reached")
    }
  }

  case class TableTestFun(table: Table, fun: Table => Table, expected: Seq[JValue]) {
    def check(): MatchResult[Seq[JValue]] = (toJson(fun(table)).copoint: Seq[JValue]) must_=== expected
  }
  case class TableTest(table: Table, spec: TransSpec1, expected: Seq[JValue]) {
    def check(): MatchResult[Seq[JValue]] = (toJson(table transform spec).copoint: Seq[JValue]) must_=== expected
  }
  case class TableProp(f: SampleData => TableTest) {
    def check()(implicit z: ASD): Prop = prop((sd: SampleData) => f(sd).check())
  }

  /** Verify a scalacheck property that for any SampleData generated by the implicit ASD,
   *  generating a table and transforming it with `spec` produces a copoint which is equal
   *  to the result of the function `expect` applied to the original data.
   */
  def checkSpec(spec: TransSpec1)(expect: ToSelf[Seq[JValue]])(implicit z: ASD): Prop =
    TableProp(sd => TableTest(fromSample(sd), spec, expect(sd.data))).check()

  def checkCommutes(f: Seq[JValue] => Seq[JValue], g: Table => Table, gen: Gen[Seq[JValue]]): Prop = {
    implicit val za: Arbitrary[Seq[JValue]] = Arbitrary(gen)
    implicit val ze: Eq[Seq[JValue]]        = Eq.equal((x, y) => (x corresponds y)(_ == _))
    implicit val zs: Show[Seq[JValue]]      = Show.shows(_.toString)

    new TableCommuteTest(f, g).checkR()
  }

  def checkSpecDefault(spec: TransSpec1)(expect: ToSelf[Seq[JValue]]): Prop =
    checkSpec(spec)(expect)(defaultASD)

  def checkSpecData(spec: TransSpec1, data: Seq[JValue], expected: Seq[JValue]): Prop =
    TableTest(fromJson(data), spec, expected).check()

  def checkTableFun(fun: Table => Table, data: Seq[JValue], expected: Seq[JValue]): Prop = checkTableFun(fun, fromJson(data), expected)
  def checkTableFun(fun: Table => Table, table: Table, expected: Seq[JValue]): Prop      = TableTestFun(table, fun, expected).check()

  def checkSpecDataId(spec: TransSpec1, data: Seq[JValue]): Prop =
    checkSpecData(spec, data, data)

  protected def defaultASD: ASD                                                         = sample(schema)
  protected def select[A <: SourceType](qual: TransSpec[A], name: String): TransSpec[A] = DerefObjectStatic(qual, CPathField(name))
  protected def select(name: String): TransSpec1                                        = select(Fn.source, name)
}

abstract class ColumnarTableQspec extends TableQspec with ColumnarTableModuleTestSupport {
  import trans._

  class Table(slices: StreamT[Need, Slice], size: TableSize) extends ColumnarTable(slices, size) {
    import trans._

    def load(apiKey: APIKey, jtpe: JType)                                                                                           = ???
    def sort(sortKey: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean)                                                     = Need(this)
    def groupByN(groupKeys: Seq[TransSpec1], valueSpec: TransSpec1, sortOrder: DesiredSortOrder, unique: Boolean): Need[Seq[Table]] = ???

    // Deadlock
    // override def toString = toJson.value.mkString("TABLE{ ", ", ", "}")
  }

  trait TableCompanion extends ColumnarTableCompanion {
    def apply(slices: StreamT[Need, Slice], size: TableSize) = new Table(slices, size)
    def singleton(slice: Slice) = new Table(slice :: StreamT.empty[Need, Slice], ExactSize(1))
    def align(sourceLeft: Table, alignOnL: TransSpec1, sourceRight: Table, alignOnR: TransSpec1): Need[Table -> Table] = ???
  }

  object Table extends TableCompanion

  def testConcat = {
    val data1: Stream[JValue] = Stream.fill(25)(json"""{ "a": 1, "b": "x", "c": null }""")
    val data2: Stream[JValue] = Stream.fill(35)(json"""[4, "foo", null, true]""")

    val table1   = fromSample(SampleData(data1), Some(10))
    val table2   = fromSample(SampleData(data2), Some(10))
    val results  = toJson(table1.concat(table2))
    val expected = data1 ++ data2

    results.copoint must_== expected
  }

  def streamToString(stream: StreamT[Need, CharBuffer]): String = {
    def loop(stream: StreamT[Need, CharBuffer], sb: StringBuilder): Need[String] =
      stream.uncons.flatMap {
        case None =>
          Need(sb.toString)
        case Some((cb, tail)) =>
          sb.append(cb)
          loop(tail, sb)
      }
    loop(stream, new StringBuilder).copoint
  }

  def testRenderJson(xs: JValue*) = {
    def minimizeItem(t: (String, JValue)) = minimize(t._2).map((t._1, _))
    def minimize(value: JValue): Option[JValue] = value match {
      case JUndefined       => None
      case JObject(fields)  => Some(JObject(fields.flatMap(minimizeItem)))
      case JArray(Seq())    => Some(jarray())
      case JArray(elements) => elements flatMap minimize match { case Seq() => None ; case xs => Some(JArray(xs)) }
      case v                => Some(v)
    }

    val table     = fromJson(xs.toVector)
    val expected  = JArray(xs.toVector)
    val arrayM    = table.renderJson("[", ",", "]").foldLeft("")(_ + _.toString).map(JParser.parseUnsafe)
    val minimized = minimize(expected) getOrElse jarray()

    arrayM.copoint mustEqual minimized
  }

  def sanitize(s: String): String = s.toArray.map(c => if (c < ' ') ' ' else c).mkString("")
}

trait TableModuleTestSupport extends TableModule {
  def lookupF1(namespace: List[String], name: String): CF1 = {
    val lib = Map[String, CF1](
      "negate"         -> cf.math.Negate,
      "coerceToDouble" -> cf.CoerceToDouble,
      "true" -> CF1("testing::true") { _ =>
        Some(Column.const(true))
      }
    )

    lib(name)
  }

  def lookupF2(namespace: List[String], name: String): CF2 = {
    val lib = Map[String, CF2](
      "add" -> cf.math.Add,
      "mod" -> cf.math.Mod,
      "eq"  -> cf.std.Eq
    )
    lib(name)
  }

  def lookupScanner(namespace: List[String], name: String): Scanner = {
    val lib = Map[String, Scanner](
      "sum" -> new Scanner {
        type A = BigDecimal
        val init = BigDecimal(0)
        def scan(a: BigDecimal, cols: Map[ColumnRef, Column], range: Range): (A, Map[ColumnRef, Column]) = {
          val identityPath = cols collect { case c @ (ColumnRef.id(_), _) => c }
          val prioritized = identityPath.values filter {
            case (_: LongColumn | _: DoubleColumn | _: NumColumn) => true
            case _                                                => false
          }

          val mask = Bits.filteredRange(range.start, range.end) { i =>
            prioritized exists { _ isDefinedAt i }
          }

          val (a2, arr) = mask.toList.foldLeft((a, new Array[BigDecimal](range.end))) {
            case ((acc, arr), i) => {
              val col = prioritized find { _ isDefinedAt i }

              val acc2 = col map {
                case lc: LongColumn   => acc + lc(i)
                case dc: DoubleColumn => acc + dc(i)
                case nc: NumColumn    => acc + nc(i)
                case _                => abort("unreachable")
              }

              acc2 foreach { arr(i) = _ }

              (acc2 getOrElse acc, arr)
            }
          }

          (a2, Map(ColumnRef.id(CNum) -> ArrayNumColumn(mask, arr)))
        }
      }
    )

    lib(name)
  }

  def fromJson(data: Seq[JValue], maxBlockSize: Option[Int]): Table

  def toJson(dataset: Table): Need[Stream[JValue]]                         = dataset.toJson.map(_.toStream)
  def toJsonSeq(table: Table): Seq[JValue]                                 = toJson(table).copoint
  def fromJson(data: Seq[JValue]): Table                                   = fromJson(data, None)
  def fromJson(data: Seq[JValue], maxBlockSize: Int): Table                = fromJson(data, Some(maxBlockSize))
  def fromSample(sampleData: SampleData): Table                            = fromJson(sampleData.data, None)
  def fromSample(sampleData: SampleData, maxBlockSize: Option[Int]): Table = fromJson(sampleData.data, maxBlockSize)
}
