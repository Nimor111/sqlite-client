---
layout: docs
title: Intro
permalink: docs/
---

# Guide

- [Guide](#guide)
  - [Installation](#installation)
  - [Main concepts](#main-concepts)
  - [Usage](#usage)
    - [Create a DB Connection](#create-a-db-connection)
    - [Create a table](#create-a-table)
    - [Insert data](#insert-data)
    - [Query data](#query-data)
      - [Without params](#without-params)
      - [With params](#with-params)


## Installation

With sbt:
```scala
libraryDependencies += "TBA"
```

With ammonite:
```scala
import $ivy.`TBA`
```

Imports

```scala
import sqlite._
```

## Main concepts

The library functions aim to be compatible with the JDBC interface.

Tables are represented by case classes, which extend
the `Model` trait.

There are several types of supported fields, which extend the `DBField` trait.
They are: `IntField`, `StringField`, `BooleanField`, `DoubleField`, `OptionalField` and `ModelField` for foreign
key relations.

The library makes use of runtime reflection right now, with the aim to transition to macros and compile time
reflection in the future.

The SQL is written by the user, and the library executes it in a typesafe way, using abstractions like `Try` and
`Either` for error handling.

## Usage

```scala mdoc:silent
import sqlite._
import sqlite.Types._
```

### Create a DB connection

```scala mdoc
val d = Database("jdbc:sqlite:database.db")

// SQLEither is a type alias for Either[List[SQLException], DBConnection]
val conn = d.getConnection()
```

### Create a table

```scala mdoc
for {
  conn <- d.getConnection()
  dropTableSql = """
    DROP TABLE IF EXISTS warehouses;
  """
  createTableSql = """
    CREATE TABLE IF NOT EXISTS warehouses (
      id integer PRIMARY KEY,
      name text NOT NULL,
      capacity real NOT NULL,
      numItems integer);
  """

  _ <- conn.executeUpdate(dropTableSql)
  _ <- conn.executeUpdate(createTableSql)
  _ <- conn.close()
} yield ()
```

### Insert data

**Note**: Inner classes for tables are not currently supported. The table classes should not be nested in other classes.
This is due to limitations in Scala reflection. This should be addressed with the switch to compile time reflection.

```scala mdoc
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

// Easier creation
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

val w = Warehouse(1, Name("First"), 1.0, Some(1))

for {
  conn <- d.getConnection()

  insertWarehouseSQL = """
  INSERT INTO warehouses(id, name, capacity, numItems) VALUES (?, ?, ?, ?)
  """

  _ <- conn.executeUpdate(insertWarehouseSQL, w)
  _ <- conn.close()
} yield ()
```

## Query data

### Without params

```scala
for {
  conn <- d.getConnection()

  querySQL = """
    SELECT * FROM warehouses;
  """

  w <- conn.executeQuery[Warehouse](querySQL)
  _ <- conn.close()
} yield w
```

### With params

```scala
for {
  conn <- d.getConnection()

  querySQL = """
    SELECT * FROM warehouses WHERE id=?;
  """

  w <- conn.executeQuery[Warehouse](querySQL, 1)
  _ <- conn.close()
} yield w
```
