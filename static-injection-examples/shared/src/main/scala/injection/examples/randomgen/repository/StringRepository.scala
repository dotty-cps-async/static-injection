package injection.examples.randomgen.repository

import cats.effect.Sync

import scala.io.Source

class StringRepository[F[_] : Sync](string: String) extends Repository[F, String] {

  override val all: F[List[String]] = Sync[F].pure(Source.fromString(string).getLines.toList)

  override val count: F[Long] = Sync[F].map(all)(_.size)
}
