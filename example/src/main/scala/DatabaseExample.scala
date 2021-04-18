package sqlite

import sqlite.Database
import sqlite.ManagedUtil._
import sqlite.Types._

import scala.util.chaining._

object DatabaseExample extends App {
  case class Name(name: StringField) extends Model

  object Name {
    def apply(name: String): Name =
      new Name(StringField(name))
  }

  case class Warehouse(
      id: IntField,
      name: ModelField[Name],
      capacity: DoubleField,
      numItems: OptionalField[IntField]
  ) extends Model

  object Warehouse {
    def apply(
        id: Int,
        name: Name,
        capacity: Double,
        numItems: Option[Int]
    ): Warehouse =
      new Warehouse(
        IntField(id),
        ModelField(name),
        DoubleField(capacity),
        OptionalField(numItems.map(IntField))
     )
  }

  val d = Database("jdbc:sqlite:database.db")
  val w = Warehouse(1, Name("First"), 1.0, Some(1))
  val c = (for {
    conn <- d.getConnection().tap(println)
    dropTableSql = """
    DROP TABLE IF EXISTS warehouses;
    """
    createTableSql = """
    CREATE TABLE IF NOT EXISTS warehouses (
      id integer PRIMARY KEY,
      name text NOT NULL,
      capacity real,
      numItems integer);
    """

    _ <- conn.executeUpdate(dropTableSql)
    _ <- conn.executeUpdate(createTableSql)
    insertWarehouseSQL = """
    INSERT INTO warehouses(id, name, capacity, numItems) VALUES (?, ?, ?, ?)
    """
    deleteWarehousesSQL = """
    DELETE FROM warehouses;
    """
    _ <- conn.executeUpdate(deleteWarehousesSQL)
    _ <- Right("Deleted").tap(println)
    _ <- conn.executeUpdate(insertWarehouseSQL, w)
    _ <- Right("Inserted").tap(println)
    _ <-
      conn
        .executeUpdate(
          insertWarehouseSQL,
          Warehouse(
            id = 2,
            name = Name("Second"),
            capacity = 2.0,
            numItems = Some(2)
          )
        )
    _ <- Right("Inserted").tap(println)
    querySQL = """
    SELECT * FROM warehouses WHERE id=?;
    """
    _ <- conn.executeQuery[Warehouse](querySQL, 2).tap(println)
    _ <- conn.close().tap(println)
  } yield ())

  println(s"c = $c")
}
