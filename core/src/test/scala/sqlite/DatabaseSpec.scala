package sqlite

import org.scalatest.flatspec.AnyFlatSpec

object TestTypes {
  import Types._

  case class Warehouse(id: IntField, name: StringField, capacity: DoubleField) extends Model
  object Warehouse {
    def apply(id: Int, name: String, capacity: Double) =
      new Warehouse(id = IntField(id), name = StringField(name), capacity = DoubleField(capacity))
  }
}

class DatabaseSpec extends AnyFlatSpec {
  import Types._
  import TestTypes._
  import ManagedUtil._

  "The Database app" should "create a table successfully" in {
    val d = Database("jdbc:sqlite::memory:")
    val c = (for {
      conn <- d.getConnection()
      createTableSql = """
        CREATE TABLE IF NOT EXISTS warehouses (
          id integer PRIMARY KEY,
          name text NOT NULL,
          capacity real);
        """
      _ <- conn.executeUpdate(createTableSql)
    } yield ())

    assert(c == Right(()))
  }

  it should "insert a warehouse and fetch it successfully" in {
    val d = Database("jdbc:sqlite::memory:")
    val w = Warehouse(1, "First", 1.0)
    val res = for {
      conn <- d.getConnection()
      createTableSql = """
        CREATE TABLE IF NOT EXISTS warehouses (
          id integer PRIMARY KEY,
          name text NOT NULL,
          capacity real);
        """
      _ <- conn.executeUpdate(createTableSql)
      insertWarehouseSQL = """
      INSERT INTO warehouses(id, name, capacity) VALUES (?, ?, ?)
      """
      _ <- conn.executeUpdate(insertWarehouseSQL, w)
      querySQL = """
      SELECT * FROM warehouses;
      """
      w <- conn.executeQuery[Warehouse](querySQL)
      _ <- conn.close()
    } yield w

    assert(res == Right(List(Warehouse(1, "First", 1.0))))
  }
}
