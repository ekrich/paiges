package org.typelevel.paiges

import org.scalacheck.Shrink.shrink
import org.scalacheck.{ Arbitrary, Cogen, Gen, Shrink }

object Generators {
  import Doc.text

  val asciiString: Gen[String] =
    for {
      n <- Gen.choose(1, 10)
      cs <- Gen.listOfN(n, Gen.choose(32.toChar, 126.toChar))
    } yield cs.mkString

  val generalString: Gen[String] =
    implicitly[Arbitrary[String]].arbitrary

  val doc0Gen: Gen[Doc] = Gen.frequency(
    (1, Doc.empty),
    (1, Doc.space),
    (1, Doc.line),
    (1, Doc.lineBreak),
    (1, Doc.lineOrSpace),
    (1, Doc.lineOrEmpty),
    (10, asciiString.map(text(_))),
    (10, generalString.map(text(_))),
    (3, asciiString.map(Doc.split(_))),
    (3, generalString.map(Doc.split(_))),
    (3, generalString.map(Doc.paragraph(_)))
    )

  val combinators: Gen[(Doc, Doc) => Doc] =
    Gen.oneOf(
    { (a: Doc, b: Doc) => a + b },
    { (a: Doc, b: Doc) => a space b },
    { (a: Doc, b: Doc) => a / b },
    { (a: Doc, b: Doc) => a lineOrSpace b })

  val unary: Gen[Doc => Doc] =
    Gen.oneOf(
      Gen.const({ d: Doc => d.grouped }),
      Gen.const({ d: Doc => d.aligned }),
      Gen.const({ d: Doc => Doc.lineOr(d) }),
      Gen.choose(0, 40).map { i => { d: Doc => d.nested(i) } })

  def folds(genDoc: Gen[Doc], withFill: Boolean): Gen[(List[Doc] => Doc)] = {
    val gfill = genDoc.map { sep =>
      { ds: List[Doc] => Doc.fill(sep, ds.take(8)) }
    }

    Gen.frequency(
      (1, genDoc.map { sep =>
        { ds: List[Doc] => Doc.intercalate(sep, ds) }
      }),
      (1, Gen.const({ ds: List[Doc] => Doc.spread(ds) })),
      (1, Gen.const({ ds: List[Doc] => Doc.stack(ds) })),
      (if (withFill) 1 else 0, gfill)
    )
  }

  def leftAssoc(max: Int): Gen[Doc] = for {
    n <- Gen.choose(1, max)
    start <- genDoc
    front <- Gen.listOfN(n, genDoc)
  } yield front.foldLeft(start)(Doc.Concat)

  def fill(max: Int): Gen[Doc] = for {
    n <- Gen.choose(1, max)
    m <- Gen.choose(1, max)
    k <- Gen.choose(1, max)
    l <- Gen.listOfN(n, leftAssoc(m))
    sep <- leftAssoc(k)
  } yield Doc.fill(sep, l)

  val maxDepth = 7

  def genTree(depth: Int, withFill: Boolean): Gen[Doc] = {
    val recur = Gen.lzy(genTree(depth - 1, withFill))
    val ugen = for {
      u <- unary
      d <- genTree(depth - 1, withFill)
    } yield u(d)

    val cgen = for {
      c <- combinators
      d0 <- genTree(depth - 1, withFill)
      d1 <- genTree(depth - 1, withFill)
    } yield c(d0, d1)

    val fgen = for {
      fold <- folds(recur, withFill)
      num <- Gen.choose(0, 20)
      ds <- Gen.listOfN(num, recur)
    } yield fold(ds)

    if (depth <= 0) doc0Gen
    else if (depth >= maxDepth - 1) {
      Gen.frequency(
        // bias to simple stuff
        (6, doc0Gen),
        (1, ugen),
        (1, recur.map(Doc.defer(_))),
        (2, cgen),
        (1, fgen))
    } else {
      // bias to simple stuff
      Gen.frequency(
        (6, doc0Gen),
        (1, ugen),
        (2, cgen))
    }
  }

  val genDoc: Gen[Doc] =
    Gen.choose(0, 7).flatMap(genTree(_, withFill = true))

  val genDocNoFill: Gen[Doc] =
    Gen.choose(0, 7).flatMap(genTree(_, withFill = false))

  implicit val arbDoc: Arbitrary[Doc] =
    Arbitrary(genDoc)

  implicit val cogenDoc: Cogen[Doc] =
    Cogen[Int].contramap((d: Doc) => d.hashCode)

  private def isUnion(d: Doc): Boolean = d match {
    case Doc.Union(_, _) => true
    case _ => false
  }

  // Unions generated by `fill` are poorly behaved.  Some tests only
  // pass with Unions generated by `grouped`.
  implicit val genGroupedUnion: Gen[Doc.Union] =
    genDocNoFill.map(_.grouped).filter(isUnion).map(_.asInstanceOf[Doc.Union])

  implicit val genUnion: Gen[Doc.Union] =
    Gen.oneOf(genDoc.map(_.grouped), fill(10)).filter(isUnion).map(_.asInstanceOf[Doc.Union])

  implicit val arbUnion: Arbitrary[Doc.Union] = Arbitrary(genUnion)

  implicit val shrinkDoc: Shrink[Doc] = {
    import Doc._
    def interleave[A](xs: Stream[A], ys: Stream[A]): Stream[A] =
      if (xs.isEmpty) ys
      else if (ys.isEmpty) xs
      else xs.head #:: ys.head #:: interleave(xs.tail, ys.tail)
    def combine[A](a: A)(f: A => A)(implicit F: Shrink[A]): Stream[A] = {
      val sa = shrink(a)
      a #:: interleave(sa, sa.map(f))
    }
    def combine2[A](a: A, b: A)(f: (A, A) => A)(implicit F: Shrink[A]): Stream[A] = {
      val (sa, sb) = (shrink(a), shrink(b))
      a #:: b #:: interleave(interleave(sa, sb), sa.flatMap(x => sb.map(y => f(x, y))))
    }
    Shrink {
      case FlatAlt(_, b) => b #:: shrinkDoc.shrink(b)
      case Union(a, b) => combine2(a, b)(Union)
      case Concat(a, b) => combine2(a, b)(_ + _)
      case Text(s) => shrink(s).map(text)
      case Nest(i, d) => interleave(shrink(d), combine(d)(_.nested(i)))
      case Align(d) => interleave(shrink(d), combine(d)(_.aligned))
      case Line | Empty => Stream.empty
      case d@LazyDoc(_) => d.evaluated #:: shrink(d.evaluated)
    }
  }
}
