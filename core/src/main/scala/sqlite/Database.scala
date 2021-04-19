package sqlite

import scala.util.Try
import scala.util.chaining._
import scala.reflect.{ClassTag, classTag}
import scala.reflect.runtime.universe._
import scala.annotation.tailrec

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.ResultSet

object Types {
  type SQLEither[T] = Either[List[SQLException], T]
  type SQLEitherL[T] = Either[List[SQLException], List[T]]

  trait DBField
  case class IntField(value: Int) extends DBField
  case class StringField(value: String) extends DBField
  case class BooleanField(value: Boolean) extends DBField
  case class DoubleField(value: Double) extends DBField
  case class OptionalField[T <: DBField](value: Option[T]) extends DBField
  case class ModelField[T <: Model](value: T) extends DBField

  trait Model <: Product
}

import Types._

object TryUtil {
  implicit class TryOps[T](value: Try[T]) {
    import EitherUtil._

    def toSQLException: SQLEither[T] =
      value
        .fold(ex => Left(List(new SQLException(ex.getMessage))), Right(_))
        .toNonNullEither(List(new SQLException("Expected a value, got null")))

    def toSQLException[U](f: T => U): SQLEither[U] =
      value
        .fold(ex => Left(List(new SQLException(ex.getMessage))), Right(_))
        .toNonNullEither(List(new SQLException("Expected a value, got null")), f)
  }
}

object EitherUtil {
  implicit class EitherOps[E, T](value: Either[E, T]) {
    def leftMap[U](f: E => U): Either[U, T] =
      value match {
        case Left(err) => Left(f(err))
        case Right(v)  => Right(v)
      }

    def bimap[U, V](f: E => U, g: T => V): Either[U, V] =
      value match {
        case Left(err) => Left(f(err))
        case Right(v)  => Right(g(v))
      }

    def toNonNullEither(err: E): Either[E, T] = value.flatMap(s => s match {
      case null => Left(err)
      case v => Right(v)
    })

    def toNonNullEither[U](err: E, f: T => U): Either[E, U] = value.flatMap(s => s match {
      case null => Left(err)
      case v => Right(f(v))
    })
  }

  implicit class EitherListOps[E, T](eithers: List[Either[E, T]]) {
    def sequence: Either[E, List[T]] =
      eithers.foldRight[Either[E, List[T]]](Right(List()))((e, acc) =>
        e match {
          case Left(l)  => return Left(l)
          case Right(l) => acc.map(ll => l :: ll)
        }
      )
  }
}

object ManagedUtil {
  implicit class EitherStatementOps[E, T](
      value: SQLEither[T]
  ) {
    import EitherUtil._
    import TryUtil._

    def managed(stmt: Statement): SQLEither[T] =
      value.leftMap(ex => {
        println("Closing statement due to error...")
        Try(stmt.close()).toSQLException.fold(ex1 => ex ++ ex1, _ => ex)
      })

    def managed(conn: Connection): SQLEither[T] =
      value.leftMap(ex => {
        println("Closing connection due to error...")
        Try(conn.close()).toSQLException.fold(ex1 => ex ++ ex1, _ => ex)
      })
  }
}

case class DBConnection(conn: Connection) {
  import TryUtil._
  import EitherUtil._
  import ManagedUtil._
  import scala.reflect.runtime.universe.{typeOf, TypeTag, runtimeMirror, Type}

  def close(): SQLEither[Unit] =
    Try(conn.close()).toSQLException

  def executeQuery[T <: Model: TypeTag](
      sql: String,
      params: Any*
  ): SQLEitherL[T] =
    (for {
      stmt <- Try(conn.prepareStatement(sql)).toSQLException
      _ <- setParameters(stmt, 1, params.toList)
      resultSet <- Try(stmt.executeQuery()).toSQLException.managed(stmt)
      data <- getData[T](resultSet).managed(stmt)
    } yield data).managed(conn)

  // FIXME: Shouldn't be Any here, some strong typing is warranted
  private def setParameters(
      stmt: PreparedStatement,
      fieldNum: Int,
      params: List[Any]
  ): SQLEither[Unit] = {
    @tailrec
    def go(
        stmt: PreparedStatement,
        idx: Int,
        params: List[Any]
    ): SQLEither[Unit] = {
      params match {
        case Nil => Right(())
        case head :: tail =>
          head match {
            case t: Int =>
              Try(stmt.setInt(idx, t)).toSQLException
            case d: Double =>
              Try(stmt.setDouble(idx, d)).toSQLException
            case s: String =>
              Try(stmt.setString(idx, s)).toSQLException
            case b: Boolean =>
              Try(stmt.setBoolean(idx, b)).toSQLException
            case _ =>
              Left(
                List(
                  new SQLException(
                    "Only Int, Double, String, Boolean supported for query params."
                  )
                )
              )
          }

          go(stmt, idx + 1, tail)
      }
    }

    go(stmt, 1, params)
  }

  private def getData[T: TypeTag](
      resultSet: ResultSet
  ): SQLEitherL[T] = {
    @tailrec
    def go(
        ts: List[T],
        resultSet: ResultSet
    ): SQLEitherL[T] =
      resultSet.next match {
        case false => Right(ts)
        case true =>
          getFieldDataValues(resultSet) match {
            case Right(v) => go(ts :+ v, resultSet)
            case Left(ex) => Left(ex)
          }
      }

    go(Nil, resultSet)
  }

  private def getFieldDataValues[T: TypeTag](
      resultSet: ResultSet
  ): SQLEither[T] = {
    // Get them in the same order as declared in the case class
    val fieldNames = typeOf[T].members.sorted.collect {
      case m: MethodSymbol if m.isCaseAccessor => m.name.toString
    }

    // Get them in the same order as declared in the case class
    val fieldTypes =
      typeOf[T].members.sorted.filter(!_.isMethod).map(_.typeSignature)

    extractResultSetValues(fieldTypes, resultSet, fieldNames)
      .fold(Left(_), buildInstance[T](_: _*))
  }

  private def extractResultSetValues(
      fieldTypes: List[Type],
      resultSet: ResultSet,
      fieldNames: List[String]
  ): SQLEitherL[DBField] =
    fieldTypes
      .foldLeft[List[Either[List[SQLException], DBField]]](Nil)((acc, tpe) =>
        acc :+ extractField(
          tpe,
          resultSet,
          fieldNames(findIndex(fieldTypes, tpe))
        )
      )
      .partition(_.isLeft) match {
      case (Nil, acc) =>
        Right(acc.foldLeft(List[DBField]().empty) {
          case (acc, Right(v)) => acc :+ v
          case (acc, _)        => acc
        })
      case (lefts, _) =>
        Left(lefts.foldLeft(List[SQLException]().empty) {
          case (acc, Left(ex)) => acc ++ ex
          case (acc, _)        => acc
        })
    }

  private def extractField(
      tpe: Type,
      resultSet: ResultSet,
      fieldName: String
  ): SQLEither[DBField] = {
    tpe match {
      case t if t =:= typeOf[IntField] =>
        Try(
          resultSet.getInt(fieldName)
        ).map(IntField).toSQLException
      case s if s =:= typeOf[StringField] =>
        Try(
          resultSet.getString(fieldName)
        ).map(StringField).toSQLException
      case b if b =:= typeOf[BooleanField] =>
        Try(
          resultSet.getBoolean(fieldName)
        ).map(BooleanField).toSQLException
      case d if d =:= typeOf[DoubleField] =>
        Try(
          resultSet.getDouble(fieldName)
        ).map(DoubleField).toSQLException
      case o if o.typeSymbol == typeOf[OptionalField[_]].typeSymbol =>
        extractOption(o, resultSet, fieldName).map(OptionalField(_))
      case cls if cls.typeSymbol == typeOf[ModelField[_]].typeSymbol =>
        extractCaseClass(cls, resultSet).map(ModelField(_))
      case _ =>
        Left(
          List(
            new SQLException(
              "Only Int, String, Boolean, Double, Option and Model fields are supported."
            )
          )
        )
    }
  }

  private def extractOption(
      option: Type,
      resultSet: ResultSet,
      fieldName: String
  ) = Right(extractField(option.typeArgs.head, resultSet, fieldName).toOption)

  private def extractCaseClass(cls: Type, resultSet: ResultSet) = {
    val currentMirror = scala.reflect.runtime.currentMirror
    val classSymbol = currentMirror.staticClass(cls.typeArgs.head.toString)
    val constructorSymbol = classSymbol.primaryConstructor.asMethod
    val fieldTypes =
      classSymbol.toType.members.sorted.filter(!_.isMethod).map(_.typeSignature)
    val fieldNames = classSymbol.toType.members.sorted.collect {
      case m: MethodSymbol if m.isCaseAccessor => m.name.toString
    }
    val classMirror = currentMirror.reflectClass(classSymbol)
    val instance = classMirror.reflectConstructor(constructorSymbol)

    // list of fields, same as above
    (fieldTypes
      .zip(fieldNames)
      .map { case (tpe, name) =>
        extractField(tpe, resultSet, name)
      })
      .sequence
      .flatMap(fieldValues =>
        Try(instance.apply(fieldValues: _*).asInstanceOf[Model]).toSQLException
      )
  }

  private def buildInstance[T: TypeTag](values: DBField*): SQLEither[T] = {
    val m = runtimeMirror(getClass.getClassLoader)
    val cls = typeOf[T].typeSymbol.asClass
    val cm = m.reflectClass(cls)

    val ctor = typeOf[T].decls.filter(_.isConstructor).map(_.asMethod).head
    val ctorm = cm.reflectConstructor(ctor)

    val data = ctorm(values: _*)

    Try(data.asInstanceOf[T]).toSQLException
  }

  def executeUpdate(sql: String): SQLEither[Unit] =
    (for {
      stmt <- Try(conn.createStatement()).toSQLException
      _ <- Try(stmt.execute(sql)).toSQLException.managed(stmt)
      _ <- Try(stmt.close()).toSQLException
    } yield ()).managed(conn)

  def executeUpdate(
      sql: String,
      data: Model
  ): SQLEither[Unit] =
    (for {
      preparedStmt <- Try(conn.prepareStatement(sql)).toSQLException
      _ <- setFieldDataValues(data, preparedStmt).managed(preparedStmt)
      _ <-
        Try(preparedStmt.executeUpdate())
          .map(_ => ())
          .toSQLException
          .managed(preparedStmt)
      _ <- Try(preparedStmt.close()).toSQLException
    } yield ()).managed(conn)

  private def setFieldDataValues(
      data: Model,
      stmt: PreparedStatement,
      startingIdx: Int = 0
  ): SQLEither[Unit] = {
    for {
      fields <- Try(
        data.productIterator.toList.asInstanceOf[List[DBField]]
      ).toSQLException
      _ <- setFieldValues(stmt, fields, startingIdx)
    } yield ()
  }

  private def setFieldValues(
      preparedStmt: PreparedStatement,
      fieldValues: List[DBField],
      startingIdx: Int = 0
  ): SQLEither[Unit] =
    fieldValues
      .map(fv =>
        setField(preparedStmt, startingIdx + findIndex(fieldValues, fv) + 1, fv)
      )
      .filter(_.isLeft) match {
      case Nil => Right(())
      case l =>
        Left(l.foldLeft(List[SQLException]().empty) {
          case (acc, Left(ex)) => acc ++ ex
          case (acc, _)        => acc
        })
    }

  private def setField(
      preparedStmt: PreparedStatement,
      fieldNum: Int,
      fieldValue: DBField
  ): SQLEither[Unit] =
    fieldValue match {
      case t: IntField =>
        Try(
          preparedStmt.setInt(fieldNum, t.value)
        ).toSQLException
      case s: StringField =>
        Try(
          preparedStmt.setString(fieldNum, s.value)
        ).toSQLException
      case b: BooleanField =>
        Try(
          preparedStmt.setBoolean(fieldNum, b.value)
        ).toSQLException
      case d: DoubleField =>
        Try(
          preparedStmt.setDouble(fieldNum, d.value)
        ).toSQLException
      case o: OptionalField[_] =>
        o.value match {
          case Some(s) => setField(preparedStmt, fieldNum, s)
          case None    => Right(())
        }
      case n: ModelField[_] =>
        setFieldDataValues(n.value, preparedStmt, startingIdx = fieldNum - 1)
      case _ =>
        Left(
          List(
            new SQLException(
              "Only Int, String, Boolean, Double, Option and Model fields are supported."
            )
          )
        )
    }

  private def isCaseClass[T: TypeTag]: Boolean = {
    val tpe = typeOf[T]

    tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isCaseClass
  }

  private def findIndex[T](l: List[T], element: T): Int =
    l.zipWithIndex.find(_._1.equals(element)).map(_._2).getOrElse(-1)
}

case class Database(url: String) {
  import TryUtil._

  def getConnection(): SQLEither[DBConnection] =
    Try(DriverManager.getConnection(url)).toSQLException(DBConnection.apply)
}

case object Database {
  def apply(url: String): Database = {
    Class.forName("org.sqlite.JDBC");

    new Database(url)
  }
}
