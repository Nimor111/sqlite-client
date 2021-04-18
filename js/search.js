// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [    
    {
      "title": "Intro",
      "url": "/sqlite-client/docs/",
      "content": "Guide Guide Installation Main concepts Usage Create a DB Connection Create a table Insert data Query data Without params With params Installation With sbt: libraryDependencies += \"TBA\" With ammonite: import $ivy.`TBA` Imports import sqlite._ Main concepts The library functions aim to be compatible with the JDBC interface. Tables are represented by case classes, which extend the Model trait. There are several types of supported fields, which extend the DBField trait. They are: IntField, StringField, BooleanField, DoubleField, OptionalField and ModelField for foreign key relations. The library makes use of runtime reflection right now, with the aim to transition to macros and compile time reflection in the future. The SQL is written by the user, and the library executes it in a typesafe way, using abstractions like Try and Either for error handling. Usage import sqlite._ import sqlite.Types._ Create a DB connection val d = Database(\"jdbc:sqlite:database.db\") // d: Database = Database(url = \"jdbc:sqlite:database.db\") // SQLEither is a type alias for Either[List[SQLException], DBConnection] val conn = d.getConnection() // conn: SQLEither[DBConnection] = Right( // value = DBConnection(conn = org.sqlite.jdbc4.JDBC4Connection@2d160bd6) // ) Create a table for { conn &lt;- d.getConnection() dropTableSql = \"\"\" DROP TABLE IF EXISTS warehouses; \"\"\" createTableSql = \"\"\" CREATE TABLE IF NOT EXISTS warehouses ( id integer PRIMARY KEY, name text NOT NULL, capacity real NOT NULL, numItems integer); \"\"\" _ &lt;- conn.executeUpdate(dropTableSql) _ &lt;- conn.executeUpdate(createTableSql) _ &lt;- conn.close() } yield () // res0: Either[List[java.sql.SQLException], Unit] = Right(value = ()) Insert data Note: Inner classes for tables are not currently supported. The table classes should not be nested in other classes. This is due to limitations in Scala reflection. This should be addressed with the switch to compile time reflection. case class Name(name: StringField) extends Model object Name { def apply(name: String): Name = new Name(StringField(name)) } case class Warehouse( id: IntField, name: ModelField[Name], capacity: DoubleField, numItems: OptionalField[IntField] ) extends Model // Easier creation object Warehouse { def apply( id: Int, name: Name, capacity: Double, numItems: Option[Int] ): Warehouse = new Warehouse( IntField(id), ModelField(name), DoubleField(capacity), OptionalField(numItems.map(IntField)) ) } val w = Warehouse(1, Name(\"First\"), 1.0, Some(1)) // w: Warehouse = Warehouse( // id = IntField(value = 1), // name = ModelField(value = Name(name = StringField(value = \"First\"))), // capacity = DoubleField(value = 1.0), // numItems = OptionalField(value = Some(value = IntField(value = 1))) // ) for { conn &lt;- d.getConnection() insertWarehouseSQL = \"\"\" INSERT INTO warehouses(id, name, capacity, numItems) VALUES (?, ?, ?, ?) \"\"\" _ &lt;- conn.executeUpdate(insertWarehouseSQL, w) _ &lt;- conn.close() } yield () // res1: Either[List[java.sql.SQLException], Unit] = Right(value = ()) Query data Without params for { conn &lt;- d.getConnection() querySQL = \"\"\" SELECT * FROM warehouses; \"\"\" w &lt;- conn.executeQuery[Warehouse](querySQL) _ &lt;- conn.close() } yield w With params for { conn &lt;- d.getConnection() querySQL = \"\"\" SELECT * FROM warehouses WHERE id=?; \"\"\" w &lt;- conn.executeQuery[Warehouse](querySQL, 1) _ &lt;- conn.close() } yield w"
    } ,        
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
